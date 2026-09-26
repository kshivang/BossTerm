package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.image.ImageCell
import ai.rever.bossterm.terminal.model.image.ImageFormat
import ai.rever.bossterm.terminal.model.image.TerminalImage
import java.util.Base64
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/** Called only on the emulator thread after preceding remote text has been applied. */
internal class RemotePaneGraphics {
    private var revision = -1L

    /** False requests a full authenticated graphics resync; invalid deltas never partially apply. */
    fun apply(message: ServerMessage.PaneGraphics, terminal: BossTerminal, buffer: TerminalTextBuffer): Boolean {
        if (message.resyncRequired) return false
        if (!message.full && (revision < 0 || message.revision != revision + 1)) return false
        val cache = terminal.getImageDataCache()
        val decoded = runCatching {
            require(message.images.size <= 100 && message.cells.size <= 100_000)
            message.images.map { image ->
                require(image.data.length <= 12 * 1024 * 1024)
                val bytes = Base64.getDecoder().decode(image.data)
                val format = ImageFormat.detect(bytes)
                require(format in setOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.GIF, ImageFormat.WEBP, ImageFormat.BMP))
                // Inspect dimensions before the renderer can allocate a decoded raster.
                Data.makeFromBytes(bytes).use { encoded ->
                    Codec.makeFromData(encoded).use { codec ->
                        val width = codec.width; val height = codec.height
                        require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000)
                        TerminalImage(id = image.id.toLong(), data = bytes, format = format,
                            intrinsicWidth = width, intrinsicHeight = height)
                    }
                }
            }.also { require(it.sumOf { image -> image.data.size.toLong() } <= 50 * 1024 * 1024) }
        }.getOrNull() ?: return false
        val placements = runCatching {
            val required = message.requiredImageIds.map(String::toLong).toSet()
            val removed = message.removedImageIds.map(String::toLong).toSet()
            val available = (if (message.full) emptySet() else cache.snapshotImages().keys) - removed + decoded.map { it.id }
            require(required.all { it in available })
            val rows = mutableMapOf<Int, MutableMap<Int, ImageCell>>()
            var count = 0L
            for (run in message.cells) {
                val id = run.imageId.toLong()
                require(id in required && run.length > 0 && run.totalCellsX > 0 && run.totalCellsY > 0)
                require(run.cellX >= 0 && run.cellY in 0 until run.totalCellsY && run.cellX.toLong() + run.length <= run.totalCellsX)
                count += run.length; require(count <= 100_000)
                val row = run.row - message.historyLines.coerceAtLeast(0)
                if (row !in -buffer.historyLinesCount until buffer.height) continue
                for (offset in 0 until run.length) {
                    val col = run.col.toLong() + offset
                    if (col !in 0L until buffer.width.toLong()) continue
                    rows.getOrPut(row) { mutableMapOf() }[col.toInt()] = ImageCell(id, run.cellX + offset, run.cellY, run.totalCellsX, run.totalCellsY)
                }
            }
            Triple(rows, required, removed)
        }.getOrNull() ?: return false
        if (message.full) terminal.clearAllImages()
        placements.third.forEach { terminal.removeInlineImage(it) }
        decoded.forEach(cache::storeImage)
        if (placements.second.any { !cache.hasImage(it) }) return false
        buffer.replaceImageCells(placements.first)
        revision = message.revision
        return true
    }
}
