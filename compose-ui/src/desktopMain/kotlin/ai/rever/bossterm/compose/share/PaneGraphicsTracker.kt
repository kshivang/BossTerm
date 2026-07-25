package ai.rever.bossterm.compose.share

import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.image.ImageDataCache
import ai.rever.bossterm.terminal.model.image.ImageFormat
import ai.rever.bossterm.terminal.model.image.TerminalImage
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import java.util.Base64
import java.util.IdentityHashMap

internal const val PANE_GRAPHICS_CAPABILITY = "paneGraphicsV1"

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
) {
    private var revision = 0L
    private var previousCells: List<SharedImageCellRun> = emptyList()
    private var previousImages: Map<Long, TerminalImage> = emptyMap()
    private val hashes = IdentityHashMap<TerminalImage, String>()
    private var nextContentToken = 0L
    private var previousImageCellRevision = textBuffer.imageCellRevision
    private var previousImageCacheRevision = imageDataCache.contentRevision
    private var previousHistoryTrimCount = textBuffer.imageCellHistoryTrimCount

    /** Capture and return a delta only when visible image data or placement changed. */
    @Synchronized
    fun pollUpdate(): PaneGraphicsUpdate? {
        val cellRevision = textBuffer.imageCellRevision
        val cacheRevision = imageDataCache.contentRevision
        val historyTrimCount = textBuffer.imageCellHistoryTrimCount
        if (cellRevision == previousImageCellRevision && cacheRevision == previousImageCacheRevision) {
            val trimmedRows = (historyTrimCount - previousHistoryTrimCount).coerceAtLeast(0L)
            if (trimmedRows == 0L) return null
            previousHistoryTrimCount = historyTrimCount
            return shiftForHistoryTrim(trimmedRows)
        }

        val captured = capture()
        if (captured.cells == previousCells && sameImageObjects(captured.images, previousImages)) {
            previousImageCellRevision = cellRevision
            previousImageCacheRevision = cacheRevision
            previousHistoryTrimCount = historyTrimCount
            return null
        }

        val changed = captured.images.filter { (id, image) -> previousImages[id] !== image }
        val removed = previousImages.keys.filter { it !in captured.images }.map(Long::toString)
        val requiresTextSnapshot = captured.cells != previousCells &&
            captured.cells.isNotEmpty() &&
            !isUniformRowShift(previousCells, captured.cells)
        previousCells = captured.cells
        previousImages = captured.images
        previousImageCellRevision = cellRevision
        previousImageCacheRevision = cacheRevision
        previousHistoryTrimCount = historyTrimCount
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
     */
    @Synchronized
    fun fullMessage(commit: Boolean = true): ServerMessage.PaneGraphics {
        val captured = capture()
        if (commit) {
            if (captured.cells != previousCells || !sameImageObjects(captured.images, previousImages)) {
                revision++
            }
            previousCells = captured.cells
            previousImages = captured.images
            previousImageCellRevision = textBuffer.imageCellRevision
            previousImageCacheRevision = imageDataCache.contentRevision
            previousHistoryTrimCount = textBuffer.imageCellHistoryTrimCount
        }
        return message(captured, full = true, images = captured.images.values.toList(), removed = emptyList())
    }

    @Synchronized
    fun hasVisibleGraphics(): Boolean = previousCells.isNotEmpty()

    /** Cheap upper bound used before accepting an explicit full-payload resync. */
    @Synchronized
    fun estimatedRasterBytes(): Long = previousImages.values.sumOf { it.data.size.toLong() }

    private fun capture(): Captured {
        val snapshot = textBuffer.createIncrementalSnapshot()
        // Browsers cannot decode UNKNOWN/application-octet-stream rasters. Excluding them here
        // prevents shipping bytes that can only become a permanent missing-image placeholder.
        val cachedImages = imageDataCache.snapshotImages().filterValues { it.format != ImageFormat.UNKNOWN }
        val cells = cellRuns(snapshot, cachedImages.keys)
        val referenced = cells.asSequence().map { it.imageId.toLong() }.toSet()
        val images = cachedImages.filterKeys { it in referenced }
        // IdentityHashMap is intentional (a replaced image may reuse an id), but it must not pin
        // evicted TerminalImage objects and their ByteArrays for the lifetime of the share.
        hashes.keys.removeIf { cached -> images.values.none { it === cached } }
        return Captured(
            cells = cells,
            images = images,
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
        images = images.mapNotNull(::sharedImage),
        removedImageIds = removed,
        requiredImageIds = captured.images.keys.map(Long::toString),
        cells = captured.cells,
    )

    private fun sharedImage(image: TerminalImage): SharedTerminalImage? {
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
            data = Base64.getEncoder().encodeToString(image.data),
            contentHash = hashes.getOrPut(image) {
                (++nextContentToken).toString(36)
            },
        )
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
        hashes.keys.removeIf { cached -> shiftedImages.values.none { it === cached } }
        revision++
        val captured = Captured(shiftedCells, shiftedImages)
        return PaneGraphicsUpdate(
            message = message(captured, full = false, images = emptyList(), removed = removed),
            requiresTextSnapshot = false,
        )
    }

    private fun sameImageObjects(a: Map<Long, TerminalImage>, b: Map<Long, TerminalImage>): Boolean =
        a.size == b.size && a.all { (id, image) -> b[id] === image }

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
    )

    internal companion object {
        /**
         * Compress adjacent cells from the same image row. Missing/evicted image ids are omitted:
         * a browser must never retain a placement whose raster bytes the host no longer owns.
         */
        fun cellRuns(
            snapshot: VersionedBufferSnapshot,
            availableImageIds: Set<Long>,
        ): List<SharedImageCellRun> {
            val result = ArrayList<SharedImageCellRun>()
            for (row in -snapshot.historyLinesCount until snapshot.height) {
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
                            row = snapshot.historyLinesCount + row,
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
internal class GraphicsOutputFilter {
    private enum class Mode { TEXT, ESCAPE, SIXEL_PREFIX, KITTY_PREFIX, ITERM_PREFIX, DISCARD_ST, DISCARD_OSC }

    private var mode = Mode.TEXT
    private val prefix = StringBuilder()
    private var discardEscape = false

    @Synchronized
    fun filter(chunk: String): FilteredGraphicsOutput {
        var detected = chunk.contains(KITTY_PLACEHOLDER)
        val input = if (detected) chunk.replace(KITTY_PLACEHOLDER, " ") else chunk
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
                        beginDiscard(Mode.DISCARD_ST)
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

    private fun beginPrefix(char: Char, nextMode: Mode) {
        prefix.clear()
        prefix.append(char)
        mode = nextMode
    }

    private fun beginDiscard(nextMode: Mode) {
        prefix.clear()
        discardEscape = false
        mode = nextMode
    }

    private fun flushPrefix(output: StringBuilder) {
        output.append(prefix)
        prefix.clear()
        mode = Mode.TEXT
    }

    private fun discard(char: Char, acceptsBell: Boolean) {
        if (char == ST || (acceptsBell && char == BEL)) {
            discardEscape = false
            mode = Mode.TEXT
            return
        }
        if (discardEscape && char == '\\') {
            discardEscape = false
            mode = Mode.TEXT
            return
        }
        discardEscape = char == ESC
    }

    private companion object {
        const val ESC = '\u001b'
        const val BEL = '\u0007'
        const val DCS = '\u0090'
        const val OSC = '\u009d'
        const val APC = '\u009f'
        const val ST = '\u009c'
        const val MAX_SIXEL_PARAMETERS = 65
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
        if (nowNanos - active.windowStartNanos >= windowNanos) {
            active.windowStartNanos = nowNanos
            active.bytesInWindow = 0
        }
        if (estimatedBytes > maximumBytesPerWindow - active.bytesInWindow) return false
        active.lastNanos = nowNanos
        active.bytesInWindow += estimatedBytes
        entries[paneId] = active
        return true
    }

    @Synchronized
    fun remove(paneId: String) {
        entries.remove(paneId)
    }
}
