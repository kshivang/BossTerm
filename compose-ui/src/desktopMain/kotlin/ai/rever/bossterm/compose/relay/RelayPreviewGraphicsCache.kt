package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.ServerMessage
import java.util.IdentityHashMap

/** Recent full rasters requested only after a viewer receives the matching downsampled preview. */
internal class RelayPreviewGraphicsCache(
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxPerPane: Int = 32,
) {
    private data class Key(val pane: String, val sequence: Long)
    private val frames = LinkedHashMap<Key, ServerMessage.PaneGraphics>()
    // Snapshot captures reuse immutable encoded images. Charge shared strings once, not per capture.
    private val imageReferences = IdentityHashMap<String, Int>()
    private var bytes = 0L
    private fun metadata(message: ServerMessage.PaneGraphics) = 4096L + message.cells.size.toLong() * 128

    @Synchronized fun put(message: ServerMessage.PaneGraphics) {
        val sequence = message.relaySequence ?: return
        require(sequence >= 0 && message.full)
        val key = Key(message.paneId, sequence)
        remove(key)
        frames[key] = message
        bytes += metadata(message)
        for (image in message.images) {
            val references = imageReferences[image.data] ?: 0
            if (references == 0) bytes += image.data.length.toLong() * 2
            imageReferences[image.data] = references + 1
        }
        while (frames.keys.count { it.pane == message.paneId } > maxPerPane) {
            remove(frames.keys.first { it.pane == message.paneId })
        }
        while (bytes > maxBytes && frames.isNotEmpty()) remove(frames.keys.first())
    }

    @Synchronized fun get(pane: String, sequence: Long): ServerMessage.PaneGraphics? = frames[Key(pane, sequence)]
    @Synchronized fun clear() { frames.clear(); imageReferences.clear(); bytes = 0 }
    private fun remove(key: Key) {
        val message = frames.remove(key) ?: return
        bytes -= metadata(message)
        for (image in message.images) {
            val references = checkNotNull(imageReferences[image.data])
            if (references == 1) { imageReferences.remove(image.data); bytes -= image.data.length.toLong() * 2 }
            else imageReferences[image.data] = references - 1
        }
    }
}
