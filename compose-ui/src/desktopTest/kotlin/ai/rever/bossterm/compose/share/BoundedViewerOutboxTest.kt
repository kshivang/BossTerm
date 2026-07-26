package ai.rever.bossterm.compose.share

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedViewerOutboxTest {
    @Test
    fun `queue evicts oldest frames at its character bound`() = runBlocking {
        val outbox = BoundedViewerOutbox(capacityChars = 10, capacityFrames = 10)
        assertTrue(outbox.trySend("123456"))
        assertTrue(outbox.trySend("abcdef"))
        outbox.close()

        val drained = mutableListOf<String>()
        outbox.drainTo { drained.add(it) }

        assertEquals(listOf("abcdef"), drained)
    }

    /**
     * The control lane is guaranteed, not unbounded: a viewer that pumps voice traffic while not
     * reading its socket must cost itself the connection rather than the host's heap.
     */
    @Test
    fun `the control lane closes the connection past its bound`() = runBlocking {
        val outbox = BoundedViewerOutbox(
            capacityChars = 1024,
            capacityFrames = 1024,
            controlCapacityFrames = 3,
            controlCapacityChars = 1024,
        )
        assertTrue(outbox.sendControl("a"))
        assertTrue(outbox.sendControl("b"))
        assertTrue(outbox.sendControl("c"))
        assertFalse(outbox.sendControl("d"), "past the frame bound the lane refuses")

        // Refusing isn't enough — the stalled connection is closed, like FrameOutbox does.
        assertFalse(outbox.sendControl("e"))
        val drained = mutableListOf<String>()
        outbox.drainTo { drained.add(it) }
        assertEquals(listOf("a", "b", "c"), drained, "what was accepted is still delivered")
    }

    /** Control frames are preferred, but a burst of them must not starve pane output. */
    @Test
    fun `control traffic cannot starve pane output`() = runBlocking {
        val outbox = BoundedViewerOutbox(capacityChars = 1 shl 20, capacityFrames = 4096)
        repeat(BoundedViewerOutbox.CONTROL_BURST + 10) { outbox.sendControl("c$it") }
        outbox.trySend("output")
        outbox.close()

        val drained = mutableListOf<String>()
        outbox.drainTo { drained.add(it) }

        val outputAt = drained.indexOf("output")
        assertTrue(outputAt >= 0, "output must still be delivered")
        assertTrue(
            outputAt <= BoundedViewerOutbox.CONTROL_BURST,
            "output waited behind $outputAt control frames; the burst cap is " +
                "${BoundedViewerOutbox.CONTROL_BURST}",
        )
    }

    /**
     * The burst cap must not park the drain loop while control frames remain. The fairness test
     * above can't catch this because it closes the outbox first, so the empty pass returns instead
     * of waiting on `wake` — here the outbox stays OPEN, which is the real case.
     */
    @Test
    fun `a control backlog past the burst cap still drains without new sends`() = runBlocking {
        val outbox = BoundedViewerOutbox(capacityChars = 1 shl 20, capacityFrames = 4096)
        val queued = BoundedViewerOutbox.CONTROL_BURST * 3
        repeat(queued) { outbox.sendControl("c$it") }

        val drained = mutableListOf<String>()
        val drain = launch { outbox.drainTo { drained.add(it) } }
        // No further sends: everything already queued must come out on its own.
        withTimeout(5_000) {
            while (drained.size < queued) delay(10)
        }
        outbox.close()
        drain.join()
        assertEquals(queued, drained.size)
    }

    @Test
    fun `single frame larger than the bound is rejected`() {
        val outbox = BoundedViewerOutbox(capacityChars = 5, capacityFrames = 10)

        assertFalse(outbox.trySend("123456"))
    }

    @Test
    fun `recoverable frame never evicts queued pane output`() = runBlocking {
        val outbox = BoundedViewerOutbox(capacityChars = 10, capacityFrames = 10)
        assertTrue(outbox.trySend("output"))
        assertFalse(outbox.trySendWithoutEviction("graphic"))
        outbox.close()

        val drained = mutableListOf<String>()
        outbox.drainTo { drained.add(it) }

        assertEquals(listOf("output"), drained)
    }
}
