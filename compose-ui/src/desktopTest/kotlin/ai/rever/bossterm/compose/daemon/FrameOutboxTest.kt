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
 * The two-lane send queue behind every attach/share connection: control frames (snapshots, lists,
 * lifecycle, resizes) are GUARANTEED, incremental output is best-effort DROP_OLDEST, control always
 * drains before output, and a control-lane overflow hard-closes an unrecoverable connection.
 */
class FrameOutboxTest {

    @Test
    fun `control frames are never dropped and keep FIFO order`() = runBlocking {
        // Small output lane, generous control lane — we push more control frames than the output cap.
        val outbox = FrameOutbox(outputCapacity = 8, controlCapacity = 4096)
        val got = ConcurrentLinkedQueue<String>()
        val drainer = launch(Dispatchers.IO) { outbox.drainTo { got.add(it) } }
        val n = 2000
        repeat(n) { outbox.sendControl("c$it") }
        withTimeout(5_000) { while (got.size < n) delay(2) }
        outbox.close()
        drainer.join()
        assertEquals(n, got.size, "every control frame must be delivered")
        assertEquals((0 until n).map { "c$it" }, got.toList(), "control frames must keep FIFO order")
    }

    @Test
    fun `output is dropped oldest-first under back-pressure while control survives`() = runBlocking {
        val cap = 4
        val outbox = FrameOutbox(outputCapacity = cap, controlCapacity = 1024)
        // Overfill the output lane with NO drainer running, so DROP_OLDEST evicts the oldest.
        repeat(20) { outbox.sendOutput("o$it") }
        outbox.sendControl("CTRL")
        val got = ConcurrentLinkedQueue<String>()
        val drainer = launch(Dispatchers.IO) { outbox.drainTo { got.add(it) } }
        withTimeout(5_000) { while (!got.contains("CTRL")) delay(2) }
        outbox.close()
        drainer.join()
        val list = got.toList()
        assertTrue(list.contains("CTRL"), "the control frame must survive the output flood")
        // Only the last `cap` outputs survive, in order (DROP_OLDEST keeps the most recent).
        assertEquals((16 until 20).map { "o$it" }, list.filter { it.startsWith("o") })
    }

    @Test
    fun `control always drains before queued output`() = runBlocking {
        val outbox = FrameOutbox(outputCapacity = 1024, controlCapacity = 1024)
        // Queue output first, then control, with no drainer; the drainer must still emit control first.
        outbox.sendOutput("out")
        outbox.sendControl("ctrl")
        val got = ConcurrentLinkedQueue<String>()
        val drainer = launch(Dispatchers.IO) { outbox.drainTo { got.add(it) } }
        withTimeout(5_000) { while (got.size < 2) delay(2) }
        outbox.close()
        drainer.join()
        assertEquals(listOf("ctrl", "out"), got.toList(), "control frame must precede the output frame")
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
    fun `control-lane overflow closes the outbox`() = runBlocking {
        val cap = 4
        val outbox = FrameOutbox(outputCapacity = 8, controlCapacity = cap)
        // No drainer: fill the control lane to capacity, then one more overflows → close().
        repeat(cap) { outbox.sendControl("c$it") }
        outbox.sendControl("overflow") // trySend fails → close()
        // Post-close sends are no-ops and a drainer flushes only the buffered frames, then returns.
        val got = ConcurrentLinkedQueue<String>()
        outbox.sendControl("after-close")
        outbox.sendOutput("after-close-out")
        withTimeout(2_000) { outbox.drainTo { got.add(it) } }
        val list = got.toList()
        assertEquals((0 until cap).map { "c$it" }, list, "only the pre-overflow buffered frames survive")
    }
}
