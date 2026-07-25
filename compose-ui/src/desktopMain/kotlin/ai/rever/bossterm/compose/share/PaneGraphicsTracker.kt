package ai.rever.bossterm.compose.share

import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.image.ImageDataCache
import ai.rever.bossterm.terminal.model.image.ImageFormat
import ai.rever.bossterm.terminal.model.image.TerminalImage
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import java.lang.ref.WeakReference
import java.util.Base64

internal const val PANE_GRAPHICS_CAPABILITY = "paneGraphicsV1"
internal const val MAX_WEB_VIEWER_SCROLLBACK_LINES = 100_000
internal const val MAX_WEB_IMAGE_RASTER_BYTES = 8 * 1024 * 1024
internal const val MAX_WEB_PANE_RASTER_BYTES = 16L * 1024 * 1024

internal fun webViewerScrollbackLines(textBuffer: TerminalTextBuffer): Int =
    textBuffer.maxHistoryLinesCount.coerceIn(0, MAX_WEB_VIEWER_SCROLLBACK_LINES)

/** A graphics delta plus whether xterm's text/cursor state needs an authoritative re-anchor. */
internal data class PaneGraphicsUpdate(
    val message: ServerMessage.PaneGraphics,
    val requiresTextSnapshot: Boolean,
)

/**
 * Converts BossTerm's authoritative image cache + ImageCell grid into revisioned browser frames.
 *
 * Placements are complete on every update, while raster bytes are delta-compressed against the
 * preceding revision. A new/recovering viewer uses [fullMessage] to receive every referenced image.
 */
internal class PaneGraphicsTracker(
    private val paneId: String,
    private val textBuffer: TerminalTextBuffer,
    private val imageDataCache: ImageDataCache,
    private val scrollbackLines: Int = webViewerScrollbackLines(textBuffer),
) {
    private var revision = 0L
    private var previousCells: List<SharedImageCellRun> = emptyList()
    private var previousImages: Map<Long, ImageFingerprint> = emptyMap()
    private val fingerprintCache = HashMap<Long, CachedFingerprint>()
    private var previousImageCellRevision = textBuffer.imageCellRevision
    private var previousImageCacheRevision = imageDataCache.contentRevision
    private var previousViewerTrimCount = viewerTrimCount()

    /** Capture and return a delta only when visible image data or placement changed. */
    @Synchronized
    fun pollUpdate(): PaneGraphicsUpdate? {
        val cellRevision = textBuffer.imageCellRevision
        val cacheRevision = imageDataCache.contentRevision
        val viewerTrimCount = viewerTrimCount()
        if (cellRevision == previousImageCellRevision && cacheRevision == previousImageCacheRevision) {
            val trimmedRows = (viewerTrimCount - previousViewerTrimCount).coerceAtLeast(0L)
            if (trimmedRows == 0L) return null
            previousViewerTrimCount = viewerTrimCount
            return shiftForHistoryTrim(trimmedRows)
        }

        val captured = capture()
        if (captured.cells == previousCells && captured.fingerprints == previousImages) {
            previousImageCellRevision = cellRevision
            previousImageCacheRevision = cacheRevision
            previousViewerTrimCount = viewerTrimCount
            return null
        }

        val changed = captured.images.filter { (id, _) ->
            captured.fingerprints[id] != previousImages[id]
        }
        val removed = previousImages.keys.filter { it !in captured.images }.map(Long::toString)
        val requiresTextSnapshot = captured.cells != previousCells &&
            captured.cells.isNotEmpty() &&
            !isUniformRowShift(previousCells, captured.cells)
        previousCells = captured.cells
        previousImages = captured.fingerprints
        previousImageCellRevision = cellRevision
        previousImageCacheRevision = cacheRevision
        previousViewerTrimCount = viewerTrimCount
        revision++
        return PaneGraphicsUpdate(
            message = message(captured, full = false, images = changed.values.toList(), removed = removed),
            requiresTextSnapshot = requiresTextSnapshot,
        )
    }

    /**
     * Full current state for initial paint or an explicit browser resync.
     *
     * [commit] advances the shared delta baseline. MirrorShare uses `false` for a full frame sent
     * to only one viewer, so other viewers do not observe an artificial revision gap.
     * Consequently, a private full frame's revision may describe the shared baseline rather than
     * its newer complete placements; `full=true` makes those placements authoritative regardless.
     */
    @Synchronized
    fun fullMessage(commit: Boolean = true): ServerMessage.PaneGraphics {
        val captured = capture()
        if (commit) {
            if (captured.cells != previousCells || captured.fingerprints != previousImages) {
                revision++
            }
            previousCells = captured.cells
            previousImages = captured.fingerprints
            previousImageCellRevision = textBuffer.imageCellRevision
            previousImageCacheRevision = imageDataCache.contentRevision
            previousViewerTrimCount = viewerTrimCount()
        }
        return message(captured, full = true, images = captured.images.values.toList(), removed = emptyList())
    }

    @Synchronized
    fun hasVisibleGraphics(): Boolean = previousCells.isNotEmpty()

    /** Heap-oriented wire estimate used before accepting an explicit full-payload resync. */
    @Synchronized
    fun estimatedWireBytes(): Long =
        previousImages.values.sumOf { fingerprint ->
            ((fingerprint.rasterBytes + 2) / 3 * 4) * 2
        } + GRAPHICS_JSON_OVERHEAD_BYTES

    private fun capture(): Captured {
        val snapshot = textBuffer.createIncrementalSnapshot()
        // Browsers cannot decode UNKNOWN/application-octet-stream rasters. Excluding them here
        // prevents shipping bytes that can only become a permanent missing-image placeholder.
        val cachedImages = imageDataCache.snapshotImages().filterValues {
            it.format != ImageFormat.UNKNOWN && it.data.size <= MAX_WEB_IMAGE_RASTER_BYTES
        }
        val allCells = cellRuns(snapshot, cachedImages.keys, scrollbackLines)
        val selectedIds = LinkedHashSet<Long>()
        var selectedBytes = 0L
        for (run in allCells) {
            val id = run.imageId.toLong()
            if (id in selectedIds) continue
            val image = cachedImages[id] ?: continue
            if (selectedBytes + image.data.size > MAX_WEB_PANE_RASTER_BYTES) continue
            selectedIds.add(id)
            selectedBytes += image.data.size
        }
        val cells = allCells.filter { it.imageId.toLong() in selectedIds }
        val images = cachedImages.filterKeys { it in selectedIds }
        val fingerprints = images.mapValues { (_, image) -> fingerprint(image) }
        fingerprintCache.keys.retainAll(images.keys)
        return Captured(
            cells = cells,
            images = images,
            fingerprints = fingerprints,
        )
    }

    private fun message(
        captured: Captured,
        full: Boolean,
        images: List<TerminalImage>,
        removed: List<String>,
    ): ServerMessage.PaneGraphics = ServerMessage.PaneGraphics(
        paneId = paneId,
        revision = revision,
        full = full,
        images = images.mapNotNull { image ->
            sharedImage(image, captured.fingerprints.getValue(image.id))
        },
        removedImageIds = removed,
        requiredImageIds = captured.fingerprints.keys.map(Long::toString),
        cells = captured.cells,
        historyLines = textBuffer.historyLinesCount.coerceAtMost(scrollbackLines),
    )

    private fun sharedImage(
        image: TerminalImage,
        fingerprint: ImageFingerprint,
    ): SharedTerminalImage? {
        val mimeType = when (image.format) {
            ImageFormat.PNG -> "image/png"
            ImageFormat.JPEG -> "image/jpeg"
            ImageFormat.GIF -> "image/gif"
            ImageFormat.BMP -> "image/bmp"
            ImageFormat.WEBP -> "image/webp"
            ImageFormat.UNKNOWN -> return null
        }
        return SharedTerminalImage(
            id = image.id.toString(),
            mimeType = mimeType,
            data = SharedRasterBase64Cache.encode(fingerprint.contentHash, image.data),
            contentHash = fingerprint.contentHash,
        )
    }

    /**
     * Stable across tracker/connection lifetimes, unlike a local sequence number. The weak cache
     * avoids hashing an unchanged raster on every capture without retaining evicted byte arrays.
     */
    private fun fingerprint(image: TerminalImage): ImageFingerprint {
        fingerprintCache[image.id]?.let { cached ->
            if (cached.image.get() === image) return cached.fingerprint
        }
        var hash = FNV64_OFFSET xor image.format.ordinal.toLong()
        for (byte in image.data) {
            hash = (hash xor (byte.toLong() and 0xffL)) * FNV64_PRIME
        }
        val fingerprint = ImageFingerprint(
            contentHash = "${image.data.size.toString(36)}-${java.lang.Long.toUnsignedString(hash, 36)}",
            rasterBytes = image.data.size.toLong(),
        )
        fingerprintCache[image.id] = CachedFingerprint(WeakReference(image), fingerprint)
        return fingerprint
    }

    private fun shiftForHistoryTrim(trimmedRows: Long): PaneGraphicsUpdate {
        val delta = trimmedRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val shiftedCells = previousCells.asSequence()
            .filter { it.row >= delta }
            .map { it.copy(row = it.row - delta) }
            .toList()
        val retainedIds = shiftedCells.asSequence().map { it.imageId.toLong() }.toSet()
        val shiftedImages = previousImages.filterKeys { it in retainedIds }
        val removed = previousImages.keys.filter { it !in shiftedImages }.map(Long::toString)
        previousCells = shiftedCells
        previousImages = shiftedImages
        fingerprintCache.keys.retainAll(retainedIds)
        revision++
        val captured = Captured(shiftedCells, emptyMap(), shiftedImages)
        return PaneGraphicsUpdate(
            message = message(captured, full = false, images = emptyList(), removed = removed),
            requiresTextSnapshot = false,
        )
    }

    /**
     * Counts both real host evictions and rows hidden by the stricter browser history cap. Their
     * sum advances once for every oldest row xterm trims, even while the larger host history grows.
     */
    private fun viewerTrimCount(): Long =
        textBuffer.imageCellHistoryTrimCount +
            (textBuffer.historyLinesCount - scrollbackLines).coerceAtLeast(0).toLong()

    private fun isUniformRowShift(
        before: List<SharedImageCellRun>,
        after: List<SharedImageCellRun>,
    ): Boolean {
        if (before.isEmpty() || before.size != after.size) return false
        val delta = after.first().row - before.first().row
        return before.indices.all { index ->
            val a = before[index]
            val b = after[index]
            b.row - a.row == delta &&
                a.imageId == b.imageId &&
                a.col == b.col &&
                a.cellX == b.cellX &&
                a.cellY == b.cellY &&
                a.length == b.length &&
                a.totalCellsX == b.totalCellsX &&
                a.totalCellsY == b.totalCellsY
        }
    }

    private data class Captured(
        val cells: List<SharedImageCellRun>,
        val images: Map<Long, TerminalImage>,
        val fingerprints: Map<Long, ImageFingerprint>,
    )

    private data class ImageFingerprint(
        val contentHash: String,
        val rasterBytes: Long,
    )

    private data class CachedFingerprint(
        val image: WeakReference<TerminalImage>,
        val fingerprint: ImageFingerprint,
    )

    internal companion object {
        private const val FNV64_OFFSET = -3750763034362895579L
        private const val FNV64_PRIME = 1099511628211L
        private const val GRAPHICS_JSON_OVERHEAD_BYTES = 64L * 1024

        /**
         * Compress adjacent cells from the same image row. Missing/evicted image ids are omitted:
         * a browser must never retain a placement whose raster bytes the host no longer owns.
         */
        fun cellRuns(
            snapshot: VersionedBufferSnapshot,
            availableImageIds: Set<Long>,
            historyLimit: Int = Int.MAX_VALUE,
        ): List<SharedImageCellRun> {
            val result = ArrayList<SharedImageCellRun>()
            val retainedHistoryLines = snapshot.historyLinesCount.coerceAtMost(historyLimit)
            for (row in -retainedHistoryLines until snapshot.height) {
                val cells = snapshot.getLine(row).getAllImageCells()
                if (cells.isEmpty()) continue
                val orderedCells = if (cells.size > 1) cells.toSortedMap() else cells
                var runImageId: Long? = null
                var runCol = 0
                var runCellX = 0
                var runCellY = 0
                var runLength = 0
                var runTotalCellsX = 0
                var runTotalCellsY = 0
                var previousCol = -2
                fun flushRun() {
                    val imageId = runImageId ?: return
                    result.add(
                        SharedImageCellRun(
                            imageId = imageId.toString(),
                            // Absolute buffer index is stable when a screen line first scrolls into
                            // history: historyCount grows while the line's relative row drops.
                            row = retainedHistoryLines + row,
                            col = runCol,
                            cellX = runCellX,
                            cellY = runCellY,
                            length = runLength,
                            totalCellsX = runTotalCellsX,
                            totalCellsY = runTotalCellsY,
                        )
                    )
                }
                for ((col, cell) in orderedCells) {
                    if (cell.imageId !in availableImageIds) continue
                    val canExtend = runImageId != null &&
                        runImageId == cell.imageId &&
                        runCellY == cell.cellY &&
                        runTotalCellsX == cell.totalCellsX &&
                        runTotalCellsY == cell.totalCellsY &&
                        col == previousCol + 1 &&
                        cell.cellX == runCellX + runLength
                    if (canExtend) {
                        runLength++
                    } else {
                        flushRun()
                        runImageId = cell.imageId
                        runCol = col
                        runCellX = cell.cellX
                        runCellY = cell.cellY
                        runLength = 1
                        runTotalCellsX = cell.totalCellsX
                        runTotalCellsY = cell.totalCellsY
                    }
                    previousCol = col
                }
                flushRun()
            }
            return result
        }
    }
}

/**
 * Reuses the expensive base64 String across viewers and resyncs. The access-ordered cache is
 * globally char-bounded (~64 MiB UTF-16) and keyed by the stable content fingerprint.
 */
private object SharedRasterBase64Cache {
    private const val MAX_CACHED_CHARS = 32 * 1024 * 1024
    private val values = object : LinkedHashMap<String, String>(16, 0.75f, true) {}
    private var cachedChars = 0

    @Synchronized
    fun encode(contentHash: String, data: ByteArray): String {
        values[contentHash]?.let { return it }
        val encoded = Base64.getEncoder().encodeToString(data)
        if (encoded.length > MAX_CACHED_CHARS) return encoded
        while (cachedChars + encoded.length > MAX_CACHED_CHARS && values.isNotEmpty()) {
            val iterator = values.entries.iterator()
            val oldest = iterator.next()
            cachedChars -= oldest.value.length
            iterator.remove()
        }
        values[contentHash] = encoded
        cachedChars += encoded.length
        return encoded
    }
}

internal data class FilteredGraphicsOutput(
    val output: String,
    val detectedGraphics: Boolean,
)

/**
 * Streaming filter for image-protocol payloads that xterm.js cannot render.
 *
 * Non-graphics viewers still receive the original PTY stream. Graphics-capable browsers receive
 * ordinary text/ANSI verbatim while Sixel DCS, Kitty APC, and iTerm2 image OSC payloads are
 * discarded as they arrive, so a multi-megabyte raster is not sent twice. Prefix state crosses PTY
 * chunk boundaries without retaining the discarded payload.
 */
internal class GraphicsOutputFilter(
    private val maxDiscardChars: Int = MAX_GRAPHICS_CONTROL_CHARS,
) {
    private enum class Mode { TEXT, ESCAPE, SIXEL_PREFIX, KITTY_PREFIX, ITERM_PREFIX, DISCARD_ST, DISCARD_OSC }

    private var mode = Mode.TEXT
    private val prefix = StringBuilder()
    private var discardEscape = false
    private var discardChars = 0
    private var stripLeadingKittyMarks = false

    @Synchronized
    fun filter(chunk: String): FilteredGraphicsOutput {
        // Ordinary PTY output overwhelmingly contains no graphics introducer. Avoid the
        // per-character state machine (and its allocation) on that hot path.
        if (mode == Mode.TEXT && !stripLeadingKittyMarks && !requiresFiltering(chunk)) {
            return FilteredGraphicsOutput(chunk, detectedGraphics = false)
        }
        val stripped = stripKittyUnicodePlacements(chunk)
        var detected = stripped.second
        val input = stripped.first
        val output = StringBuilder(input.length)

        for (char in input) {
            when (mode) {
                Mode.TEXT -> when (char) {
                    ESC -> {
                        prefix.clear()
                        prefix.append(char)
                        mode = Mode.ESCAPE
                    }
                    DCS -> beginPrefix(char, Mode.SIXEL_PREFIX)
                    APC -> beginPrefix(char, Mode.KITTY_PREFIX)
                    OSC -> beginPrefix(char, Mode.ITERM_PREFIX)
                    else -> output.append(char)
                }

                Mode.ESCAPE -> when (char) {
                    ESC -> {
                        // Preserve the first unrelated ESC while keeping the second as a possible
                        // fragmented graphics introducer (`ESC ESC P q ...`).
                        output.append(prefix)
                        prefix.clear()
                        prefix.append(char)
                    }
                    'P' -> {
                        prefix.append(char)
                        mode = Mode.SIXEL_PREFIX
                    }
                    '_' -> {
                        prefix.append(char)
                        mode = Mode.KITTY_PREFIX
                    }
                    ']' -> {
                        prefix.append(char)
                        mode = Mode.ITERM_PREFIX
                    }
                    else -> {
                        output.append(prefix).append(char)
                        prefix.clear()
                        mode = Mode.TEXT
                    }
                }

                Mode.SIXEL_PREFIX -> {
                    prefix.append(char)
                    val parameters = prefix.substring(if (prefix[0] == ESC) 2 else 1)
                    when {
                        char == 'q' && parameters.dropLast(1).all { it.isDigit() || it == ';' } -> {
                            detected = true
                            beginDiscard(Mode.DISCARD_ST)
                        }
                        parameters.length > MAX_SIXEL_PARAMETERS ||
                            parameters.any { it != 'q' && !it.isDigit() && it != ';' } -> flushPrefix(output)
                    }
                }

                Mode.KITTY_PREFIX -> {
                    prefix.append(char)
                    if (char == 'G') {
                        detected = true
                        // BossEmulator's APC reader counts the Kitty `G` as the first body char.
                        beginDiscard(Mode.DISCARD_ST, alreadyConsumedChars = 1)
                    } else {
                        flushPrefix(output)
                    }
                }

                Mode.ITERM_PREFIX -> {
                    prefix.append(char)
                    val value = prefix.substring(if (prefix[0] == ESC) 2 else 1)
                    when {
                        ITERM_IMAGE_PREFIXES.any { it == value } -> {
                            detected = true
                            beginDiscard(Mode.DISCARD_OSC)
                        }
                        ITERM_IMAGE_PREFIXES.none { it.startsWith(value) } -> flushPrefix(output)
                    }
                }

                Mode.DISCARD_ST -> discard(char, acceptsBell = false)
                Mode.DISCARD_OSC -> discard(char, acceptsBell = true)
            }
        }
        return FilteredGraphicsOutput(output.toString(), detected)
    }

    private fun requiresFiltering(chunk: String): Boolean {
        var index = 0
        while (index < chunk.length) {
            when (chunk[index]) {
                ESC, DCS, OSC, APC -> return true
                KITTY_PLACEHOLDER[0] -> {
                    if (chunk.startsWith(KITTY_PLACEHOLDER, index)) return true
                }
            }
            index++
        }
        return false
    }

    /**
     * Kitty's Unicode placement placeholder is followed by combining marks that encode row/column.
     * xterm cannot render that protocol, so remove the marks with the placeholder, including when
     * the placeholder and its marks are split across PTY chunks.
     */
    private fun stripKittyUnicodePlacements(chunk: String): Pair<String, Boolean> {
        var index = 0
        var modified = false
        var detected = false
        val output = StringBuilder(chunk.length)
        if (stripLeadingKittyMarks) {
            if (chunk.isEmpty()) return chunk to false
            while (index < chunk.length) {
                val codePoint = chunk.codePointAt(index)
                if (!isCombiningMark(codePoint)) break
                index += Character.charCount(codePoint)
                modified = true
            }
            stripLeadingKittyMarks = false
            if (index == chunk.length) stripLeadingKittyMarks = true
        }
        while (index < chunk.length) {
            val placeholder = chunk.indexOf(KITTY_PLACEHOLDER, index)
            if (placeholder < 0) {
                output.append(chunk, index, chunk.length)
                break
            }
            modified = true
            detected = true
            output.append(chunk, index, placeholder).append(' ')
            index = placeholder + KITTY_PLACEHOLDER.length
            while (index < chunk.length) {
                val codePoint = chunk.codePointAt(index)
                if (!isCombiningMark(codePoint)) break
                index += Character.charCount(codePoint)
            }
            if (index == chunk.length) stripLeadingKittyMarks = true
        }
        return (if (modified) output.toString() else chunk) to detected
    }

    private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun beginPrefix(char: Char, nextMode: Mode) {
        prefix.clear()
        prefix.append(char)
        mode = nextMode
    }

    private fun beginDiscard(nextMode: Mode, alreadyConsumedChars: Int = 0) {
        prefix.clear()
        discardEscape = false
        discardChars = alreadyConsumedChars
        mode = nextMode
    }

    private fun flushPrefix(output: StringBuilder) {
        output.append(prefix)
        prefix.clear()
        mode = Mode.TEXT
    }

    private fun discard(char: Char, acceptsBell: Boolean) {
        if (char == ST || (acceptsBell && char == BEL)) {
            endDiscard()
            return
        }
        if (discardEscape && char == '\\') {
            endDiscard()
            return
        }
        if (discardEscape) {
            // BossEmulator consumes the byte after a stray ESC and aborts the control string.
            endDiscard()
            return
        }
        if (char == CAN || char == SUB) {
            endDiscard()
            return
        }
        if (char == ESC) {
            discardEscape = true
            return
        }
        discardChars++
        if (discardChars >= maxDiscardChars) endDiscard()
    }

    private fun endDiscard() {
        discardEscape = false
        discardChars = 0
        mode = Mode.TEXT
    }

    private companion object {
        const val ESC = '\u001b'
        const val BEL = '\u0007'
        const val CAN = '\u0018'
        const val SUB = '\u001a'
        const val DCS = '\u0090'
        const val OSC = '\u009d'
        const val APC = '\u009f'
        const val ST = '\u009c'
        const val MAX_SIXEL_PARAMETERS = 65
        // Mirrors RasterCodec.MAX_CONTROL_STRING_CHARS without exposing the decoder's internal API.
        const val MAX_GRAPHICS_CONTROL_CHARS = 70 * 1024 * 1024 + 64 * 1024
        val ITERM_IMAGE_PREFIXES = listOf("1337;File=", "1337;MultipartFile=")
        val KITTY_PLACEHOLDER = String(Character.toChars(0x10EEEE))
    }
}

/** Per-connection, per-pane throttle for expensive full graphics resyncs. */
internal class GraphicsResyncLimiter(
    private val minimumIntervalNanos: Long = 500_000_000L,
    private val maximumBytesPerWindow: Long = 64L * 1024 * 1024,
    private val windowNanos: Long = 60_000_000_000L,
) {
    private data class Entry(
        var lastNanos: Long,
        var windowStartNanos: Long,
        var bytesInWindow: Long,
    )

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun tryAcquire(
        paneId: String,
        estimatedBytes: Long = 0,
        nowNanos: Long = System.nanoTime(),
    ): Boolean {
        val entry = entries[paneId]
        if (entry != null && nowNanos - entry.lastNanos < minimumIntervalNanos) return false
        val active = entry ?: Entry(nowNanos, nowNanos, 0)
        val startsNewWindow = nowNanos - active.windowStartNanos >= windowNanos
        val admittedWindowBytes = if (startsNewWindow) 0 else active.bytesInWindow
        if (estimatedBytes > maximumBytesPerWindow - admittedWindowBytes) return false
        if (startsNewWindow) active.windowStartNanos = nowNanos
        active.lastNanos = nowNanos
        active.bytesInWindow = admittedWindowBytes + estimatedBytes
        entries[paneId] = active
        return true
    }

    @Synchronized
    fun remove(paneId: String) {
        entries.remove(paneId)
    }
}
