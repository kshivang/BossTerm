package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The conflated per-session Resize sampler behind [DaemonSessionBridge]: first request forwarded
 * promptly, a burst conflated to at most one send per interval, the FINAL grid always delivered,
 * value-equal requests deduped, and dropped keys silenced.
 */
class ResizeSamplerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private class Sent(val key: String, val cols: Int, val rows: Int)

    @Test
    fun `a burst conflates but always ends with the final grid`() = runBlocking {
        val sent = ConcurrentLinkedQueue<Sent>()
        val sampler = ResizeSampler(scope, minIntervalMs = 100) { k, c, r -> sent.add(Sent(k, c, r)) }
        val n = 60
        repeat(n) { sampler.request("s", cols = 80 + it, rows = 24) } // rapid drag-like burst
        // Wait for the burst to settle: the final grid must arrive despite heavy conflation.
        withTimeout(5_000) {
            while (sent.none { it.cols == 80 + n - 1 }) delay(10)
        }
        delay(250) // a couple more intervals: no further sends should trickle out
        val cols = sent.map { it.cols }
        assertEquals(80 + n - 1, cols.last(), "the final grid of the burst must always be delivered")
        assertTrue(cols.size < n / 2, "a rapid burst must be conflated, not forwarded 1:1 (sent ${cols.size}/$n)")
        assertEquals(cols, cols.sorted(), "forwarded grids must be monotonically the latest (never an older one)")
    }

    @Test
    fun `first request is forwarded without waiting an interval`() = runBlocking {
        val sent = ConcurrentLinkedQueue<Sent>()
        // A huge interval: if the first send waited for it, the timeout below would trip.
        val sampler = ResizeSampler(scope, minIntervalMs = 60_000) { k, c, r -> sent.add(Sent(k, c, r)) }
        sampler.request("s", 120, 40)
        withTimeout(2_000) { while (sent.isEmpty()) delay(5) }
        assertEquals(120 to 40, sent.first().let { it.cols to it.rows })
    }

    @Test
    fun `value-equal requests do not resend`() = runBlocking {
        val sent = ConcurrentLinkedQueue<Sent>()
        val sampler = ResizeSampler(scope, minIntervalMs = 20) { k, c, r -> sent.add(Sent(k, c, r)) }
        sampler.request("s", 100, 30)
        withTimeout(2_000) { while (sent.isEmpty()) delay(5) }
        repeat(5) { sampler.request("s", 100, 30) } // same grid again
        delay(200) // several intervals — nothing new should be forwarded
        assertEquals(1, sent.size, "a repeated identical grid must be deduped by the StateFlow")
    }

    @Test
    fun `keys are independent and drop cancels a pending conflated send`() = runBlocking {
        val sent = ConcurrentLinkedQueue<Sent>()
        // Large interval: after the first (immediate) send the collector parks in its conflation
        // delay, so a follow-up grid is pending — drop() must cancel it before the interval ends.
        val sampler = ResizeSampler(scope, minIntervalMs = 60_000) { k, c, r -> sent.add(Sent(k, c, r)) }
        sampler.request("a", 80, 24)
        sampler.request("b", 100, 40)
        withTimeout(2_000) { while (sent.map { it.key }.toSet() != setOf("a", "b")) delay(5) }
        sampler.request("a", 999, 99) // parked behind the conflation window
        sampler.drop("a")             // cancels the collector while the 999x99 grid is pending
        delay(150)
        assertEquals(listOf(80 to 24), sent.filter { it.key == "a" }.map { it.cols to it.rows },
            "a dropped key's pending grid must never be forwarded")
        assertEquals(listOf(100 to 40), sent.filter { it.key == "b" }.map { it.cols to it.rows },
            "other keys are unaffected by a drop")
    }
}
