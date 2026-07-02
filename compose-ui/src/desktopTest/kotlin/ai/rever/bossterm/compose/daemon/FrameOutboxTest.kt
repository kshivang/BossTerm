package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two-lane send queue behind every attach connection: control frames (snapshots, lists,
 * lifecycle, resizes) are GUARANTEED, incremental output is best-effort char-bounded drop-oldest
 * (reporting each dropped session via [FrameOutbox.onOutputDropped] so the connection can heal it
 * with a re-snapshot), control always drains before output, queued same-session output coalesces
 * into one frame, and a control-lane overflow hard-closes an unrecoverable connection.
 */
class FrameOutboxTest {

    private fun ctrl(s: String) = FrameOutbox.Frame.Text(s)

    /** Flush everything buffered in [outbox] (which must already be closed) and return it. */
    private fun drainAll(outbox: FrameOutbox): List<FrameOutbox.Frame> = runBlocking {
        val got = mutableListOf<FrameOutbox.Frame>()
        withTimeout(5_000) { outbox.drainTo { got.add(it) } }
        got
    }

    @Test
    fun `control frames are never dropped and keep FIFO order`() = runBlocking {
        val outbox = FrameOutbox(controlCapacity = 4096)
        val got = ConcurrentLinkedQueue<String>()
        val drainer = launch(Dispatchers.IO) {
            outbox.drainTo { got.add((it as FrameOutbox.Frame.Text).text) }
        }
        val n = 2000
        repeat(n) { outbox.sendControl(ctrl("c$it")) }
        withTimeout(5_000) { while (got.size < n) delay(2) }
        outbox.close()
        drainer.join()
        assertEquals(n, got.size, "every control frame must be delivered")
        assertEquals((0 until n).map { "c$it" }, got.toList(), "control frames must keep FIFO order")
    }

    @Test
    fun `output is dropped oldest-first under back-pressure and each drop reports its session`() {
        // 10-char chunks, budget for 4 — with NO drainer running, drop-oldest evicts the first 16.
        val outbox = FrameOutbox(outputCapacityChars = 40, controlCapacity = 1024)
        val droppedSessions = ConcurrentLinkedQueue<String>()
        outbox.onOutputDropped = { droppedSessions.add(it) }
        // Distinct session per chunk so nothing coalesces and eviction order is observable.
        repeat(20) { outbox.sendOutput("s$it", "x".repeat(10)) }
        outbox.sendControl(ctrl("CTRL"))
        outbox.close()
        val got = drainAll(outbox)
        assertEquals(ctrl("CTRL"), got.first(), "the control frame must survive the output flood")
        assertEquals(
            (16 until 20).map { "s$it" },
            got.filterIsInstance<FrameOutbox.Frame.Output>().map { it.sessionId },
            "only the newest chunks within the char budget survive, in order",
        )
        assertEquals(
            (0 until 16).map { "s$it" },
            droppedSessions.toList(),
            "every evicted chunk must report its session for a healing re-snapshot",
        )
    }

    @Test
    fun `control always drains before queued output`() {
        val outbox = FrameOutbox()
        // Queue output first, then control, with no drainer; control must still be emitted first.
        outbox.sendOutput("sess", "out")
        outbox.sendControl(ctrl("ctrl"))
        outbox.close()
        assertEquals(
            listOf(ctrl("ctrl"), FrameOutbox.Frame.Output("sess", "out")),
            drainAll(outbox),
            "control frame must precede the output frame",
        )
    }

    @Test
    fun `queued same-session output coalesces into one frame without crossing sessions`() {
        val outbox = FrameOutbox()
        outbox.sendOutput("a", "1")
        outbox.sendOutput("a", "2")
        outbox.sendOutput("b", "3")
        outbox.sendOutput("a", "4")
        outbox.close()
        assertEquals(
            listOf(
                FrameOutbox.Frame.Output("a", "12"),
                FrameOutbox.Frame.Output("b", "3"),
                FrameOutbox.Frame.Output("a", "4"),
            ),
            drainAll(outbox),
            "adjacent same-session chunks merge; ordering across sessions is preserved",
        )
    }

    @Test
    fun `a control backlog does not starve output (fairness cap)`() {
        val outbox = FrameOutbox(controlCapacity = 4096)
        val controlCount = FrameOutbox.CONTROL_BURST * 3
        // Enqueue everything BEFORE draining, so the drainer faces a full control lane + pending
        // output. Distinct sessions keep the outputs as separate frames.
        repeat(controlCount) { outbox.sendControl(ctrl("c$it")) }
        repeat(5) { outbox.sendOutput("s$it", "o$it") }
        outbox.close()
        val got = drainAll(outbox)
        assertEquals(controlCount + 5, got.size)
        val firstOutputIdx = got.indexOfFirst { it is FrameOutbox.Frame.Output }
        assertTrue(
            firstOutputIdx in 0..FrameOutbox.CONTROL_BURST,
            "an output frame must appear within the first CONTROL_BURST (${FrameOutbox.CONTROL_BURST}) frames, was at $firstOutputIdx",
        )
    }

    @Test
    fun `close unblocks a drainer suspended on idle lanes`() = runBlocking {
        val outbox = FrameOutbox()
        val done = CompletableDeferred<Boolean>()
        val drainer = launch(Dispatchers.IO) { outbox.drainTo { }; done.complete(true) }
        delay(150) // let the drainer suspend on both empty lanes
        outbox.close()
        assertTrue(withTimeoutOrNull(2_000) { done.await() } == true, "close() must end the drainer")
        drainer.join()
    }

    @Test
    fun `output arriving while the drainer is idle wakes it`() = runBlocking {
        val outbox = FrameOutbox()
        val got = ConcurrentLinkedQueue<FrameOutbox.Frame>()
        val drainer = launch(Dispatchers.IO) { outbox.drainTo { got.add(it) } }
        delay(150) // let the drainer suspend on both empty lanes
        outbox.sendOutput("sess", "wake-up")
        withTimeout(5_000) { while (got.isEmpty()) delay(2) }
        outbox.close()
        drainer.join()
        assertEquals(listOf<FrameOutbox.Frame>(FrameOutbox.Frame.Output("sess", "wake-up")), got.toList())
    }

    @Test
    fun `control-lane overflow closes the outbox`() {
        val cap = 4
        val outbox = FrameOutbox(controlCapacity = cap)
        // No drainer: fill the control lane to capacity, then one more overflows → close().
        repeat(cap) { outbox.sendControl(ctrl("c$it")) }
        outbox.sendControl(ctrl("overflow")) // trySend fails → close()
        // Post-close sends are no-ops and a drainer flushes only the buffered frames, then returns.
        outbox.sendControl(ctrl("after-close"))
        outbox.sendOutput("sess", "after-close-out")
        assertEquals(
            (0 until cap).map { ctrl("c$it") },
            drainAll(outbox),
            "only the pre-overflow buffered frames survive",
        )
    }
}
