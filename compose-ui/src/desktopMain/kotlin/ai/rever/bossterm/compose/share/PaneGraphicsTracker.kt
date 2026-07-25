package ai.rever.bossterm.compose.share

import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.image.ImageDataCache
import ai.rever.bossterm.terminal.model.image.ImageFormat
import ai.rever.bossterm.terminal.model.image.TerminalImage
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import java.security.MessageDigest
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

    /** Capture and return a delta only when visible image data or placement changed. */
    @Synchronized
    fun pollUpdate(): PaneGraphicsUpdate? {
        val captured = capture()
        if (captured.cells == previousCells && sameImageObjects(captured.images, previousImages)) return null

        val changed = captured.images.filter { (id, image) -> previousImages[id] !== image }
        val removed = previousImages.keys.filter { it !in captured.images }.map(Long::toString)
        val requiresTextSnapshot = changed.isNotEmpty() ||
            (captured.cells.isNotEmpty() && !isUniformRowShift(previousCells, captured.cells))
        previousCells = captured.cells
        previousImages = captured.images
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
        }
        return message(captured, full = true, images = captured.images.values.toList(), removed = emptyList())
    }

    @Synchronized
    fun hasVisibleGraphics(): Boolean = previousCells.isNotEmpty()

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
        images = images.map(::sharedImage),
        removedImageIds = removed,
        requiredImageIds = captured.images.keys.map(Long::toString),
        cells = captured.cells,
    )

    private fun sharedImage(image: TerminalImage): SharedTerminalImage = SharedTerminalImage(
        id = image.id.toString(),
        mimeType = when (image.format) {
            ImageFormat.PNG -> "image/png"
            ImageFormat.JPEG -> "image/jpeg"
            ImageFormat.GIF -> "image/gif"
            ImageFormat.BMP -> "image/bmp"
            ImageFormat.WEBP -> "image/webp"
            ImageFormat.UNKNOWN -> error("unknown image formats are filtered before browser sharing")
        },
        data = Base64.getEncoder().encodeToString(image.data),
        contentHash = hashes.getOrPut(image) {
            MessageDigest.getInstance("SHA-256").digest(image.data)
                .joinToString("") { "%02x".format(it) }
        },
    )

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

/**
 * Stateful prefix detector because PTY reads may split `ESC P` / `ESC _ G` across chunks.
 * It only arms expensive graphics snapshots for Sixel, Kitty, iTerm2 images, or Kitty's
 * Unicode placeholder — ordinary ANSI output does not trigger a full-buffer scan.
 */
internal class GraphicsSequenceDetector {
    private var tail = ""

    @Synchronized
    fun inspect(chunk: String): Boolean {
        val candidate = tail + chunk
        val detected = SIXEL.containsMatchIn(candidate) ||
            candidate.contains("\u001b_G") ||
            candidate.contains("\u009fG") ||
            candidate.contains("\u001b]1337;File") ||
            candidate.contains("\u001b]1337;MultipartFile") ||
            candidate.contains(KITTY_PLACEHOLDER)
        tail = if (detected) {
            ""
        } else {
            candidate.takeLast(96).let { suffix ->
                if (suffix.firstOrNull()?.isLowSurrogate() == true) suffix.drop(1) else suffix
            }
        }
        return detected
    }

    private companion object {
        val SIXEL = Regex("(?:\\u001bP|\\u0090)[0-9;]{0,64}q")
        val KITTY_PLACEHOLDER = String(Character.toChars(0x10EEEE))
    }
}

/** Per-connection, per-pane throttle for expensive full graphics resyncs. */
internal class GraphicsResyncLimiter(
    private val minimumIntervalNanos: Long = 500_000_000L,
) {
    private val lastByPane = HashMap<String, Long>()

    @Synchronized
    fun tryAcquire(paneId: String, nowNanos: Long = System.nanoTime()): Boolean {
        val previous = lastByPane[paneId]
        if (previous != null && nowNanos - previous < minimumIntervalNanos) return false
        lastByPane[paneId] = nowNanos
        return true
    }
}
