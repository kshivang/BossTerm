package ai.rever.bossterm.compose.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chunk ring is trimmed against a tracked counter rather than `ConcurrentLinkedQueue.size()`,
 * which is O(n) and was being called once per chunk that crossed the tty.
 *
 * A tracked counter can drift from the queue it shadows, and drift is silent and directional:
 * too high and the ring evicts live entries forever, too low and it grows without bound. Neither
 * shows up as an exception, and `read_debug_console` would just quietly return the wrong window
 * of history. So the invariant is pinned here rather than assumed.
 */
class DebugDataCollectorTrimTest {

    @Test
    fun ringStaysAtItsBoundUnderSustainedRecording() {
        val max = 16
        val collector = DebugDataCollector(tab = null, maxChunks = max, maxSnapshots = 4)

        repeat(max * 20) { collector.recordChunk("chunk-$it", ChunkSource.PTY_OUTPUT) }

        assertEquals(max, collector.getChunkCount(), "ring should sit exactly at its bound")
        assertEquals(max, collector.getDebugChunks().size, "counter and queue must agree")
    }

    @Test
    fun theRetainedWindowIsTheMostRecent() {
        // Evicting from the wrong end would keep the oldest history and silently drop
        // everything a caller actually wants.
        val max = 8
        val collector = DebugDataCollector(tab = null, maxChunks = max, maxSnapshots = 4)
        repeat(50) { collector.recordChunk("chunk-$it", ChunkSource.PTY_OUTPUT) }

        val kept = collector.getDebugChunks().map { String(it.data) }
        assertEquals((42..49).map { "chunk-$it" }, kept)
    }

    @Test
    fun clearResetsTheCounterAlongsideTheQueue() {
        // The failure this guards is nasty: a counter left high after a clear makes every
        // later record evict a live entry, so the ring never refills.
        val max = 8
        val collector = DebugDataCollector(tab = null, maxChunks = max, maxSnapshots = 4)
        repeat(40) { collector.recordChunk("before-$it", ChunkSource.PTY_OUTPUT) }
        collector.clear()
        assertEquals(0, collector.getChunkCount())

        repeat(5) { collector.recordChunk("after-$it", ChunkSource.PTY_OUTPUT) }
        assertEquals(5, collector.getChunkCount(), "ring must refill after a clear")
        assertEquals(5, collector.getDebugChunks().size)
    }

    @Test
    fun growingBelowTheBoundDoesNotEvict() {
        val collector = DebugDataCollector(tab = null, maxChunks = 100, maxSnapshots = 4)
        repeat(30) { collector.recordChunk("c-$it", ChunkSource.PTY_OUTPUT) }
        assertEquals(30, collector.getChunkCount())
        assertTrue(collector.getDebugChunks().first().data.concatToString() == "c-0")
    }
}
