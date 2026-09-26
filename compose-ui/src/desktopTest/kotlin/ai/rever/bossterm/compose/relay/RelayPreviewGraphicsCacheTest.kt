package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.SharedTerminalImage
import kotlin.test.*

class RelayPreviewGraphicsCacheTest {
    private fun frame(pane: String, sequence: Long, data: String? = null) = ServerMessage.PaneGraphics(
        paneId = pane, revision = sequence, full = true, relaySequence = sequence,
        images = data?.let { listOf(SharedTerminalImage("image", "image/png", it, "hash")) }.orEmpty(),
    )

    @Test fun `only matching pane and sequence resolve and oldest captures are bounded`() {
        val cache = RelayPreviewGraphicsCache(maxPerPane = 2)
        cache.put(frame("a", 1)); cache.put(frame("a", 2)); cache.put(frame("b", 1))
        assertEquals(1L, cache.get("a", 1)?.relaySequence)
        assertNull(cache.get("b", 2))
        cache.put(frame("a", 3))
        assertNull(cache.get("a", 1))
        assertNotNull(cache.get("a", 2))
        assertNotNull(cache.get("b", 1))
        cache.clear()
        assertNull(cache.get("a", 3))
    }

    @Test fun `shared immutable rasters count once while distinct image allocations remain bounded`() {
        val cache = RelayPreviewGraphicsCache(maxBytes = 31_000)
        val data = "a".repeat(9_000)
        for (sequence in 1L..3L) cache.put(frame("a", sequence, data))
        assertNotNull(cache.get("a", 1))
        assertNotNull(cache.get("a", 3))
        cache.put(frame("a", 4, String(data.toCharArray())))
        assertNull(cache.get("a", 3))
        assertNotNull(cache.get("a", 4))
        cache.clear()
        cache.put(frame("a", 5, data))
        assertNotNull(cache.get("a", 5))
    }
}
