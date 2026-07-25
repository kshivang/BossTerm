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
    fun pollUpdate(): ServerMessage.PaneGraphics? {
        val captured = capture()
        if (captured.cells == previousCells && sameImageObjects(captured.images, previousImages)) return null

        val changed = captured.images.filter { (id, image) -> previousImages[id] !== image }
        val removed = previousImages.keys.filter { it !in captured.images }.map(Long::toString)
        previousCells = captured.cells
        previousImages = captured.images
        revision++
        return message(captured, full = false, images = changed.values.toList(), removed = removed)
    }

    /** Full current state for initial paint or an explicit browser resync. */
    @Synchronized
    fun fullMessage(): ServerMessage.PaneGraphics {
        val captured = capture()
        return message(captured, full = true, images = captured.images.values.toList(), removed = emptyList())
    }

    @Synchronized
    fun hasVisibleGraphics(): Boolean = previousCells.isNotEmpty()

    private fun capture(): Captured {
        val snapshot = textBuffer.createIncrementalSnapshot()
        val cachedImages = imageDataCache.snapshotImages()
        val cells = cellRuns(snapshot, cachedImages.keys)
        val referenced = cells.asSequence().map { it.imageId.toLong() }.toSet()
        return Captured(
            cells = cells,
            images = cachedImages.filterKeys { it in referenced },
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
            ImageFormat.UNKNOWN -> "application/octet-stream"
        },
        data = Base64.getEncoder().encodeToString(image.data),
        contentHash = hashes.getOrPut(image) {
            MessageDigest.getInstance("SHA-256").digest(image.data)
                .joinToString("") { "%02x".format(it) }
        },
    )

    private fun sameImageObjects(a: Map<Long, TerminalImage>, b: Map<Long, TerminalImage>): Boolean =
        a.size == b.size && a.all { (id, image) -> b[id] === image }

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
                val cells = snapshot.getLine(row).getAllImageCells().toSortedMap()
                var run: SharedImageCellRun? = null
                var previousCol = -2
                for ((col, cell) in cells) {
                    if (cell.imageId !in availableImageIds) continue
                    val canExtend = run != null &&
                        run.imageId == cell.imageId.toString() &&
                        run.cellY == cell.cellY &&
                        run.totalCellsX == cell.totalCellsX &&
                        run.totalCellsY == cell.totalCellsY &&
                        col == previousCol + 1 &&
                        cell.cellX == run.cellX + run.length
                    if (canExtend) {
                        run = run!!.copy(length = run.length + 1)
                        result[result.lastIndex] = run
                    } else {
                        run = SharedImageCellRun(
                            imageId = cell.imageId.toString(),
                            row = row,
                            col = col,
                            cellX = cell.cellX,
                            cellY = cell.cellY,
                            length = 1,
                            totalCellsX = cell.totalCellsX,
                            totalCellsY = cell.totalCellsY,
                        )
                        result.add(run)
                    }
                    previousCol = col
                }
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
        tail = candidate.takeLast(96)
        return SIXEL.containsMatchIn(candidate) ||
            candidate.contains("\u001b_G") ||
            candidate.contains("\u009fG") ||
            candidate.contains("\u001b]1337;File") ||
            candidate.contains("\u001b]1337;MultipartFile") ||
            candidate.contains(KITTY_PLACEHOLDER)
    }

    private companion object {
        val SIXEL = Regex("(?:\\u001bP|\\u0090)[^q]{0,64}q")
        val KITTY_PLACEHOLDER = String(Character.toChars(0x10EEEE))
    }
}
