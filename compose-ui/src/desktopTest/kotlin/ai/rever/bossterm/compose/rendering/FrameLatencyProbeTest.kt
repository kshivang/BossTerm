package ai.rever.bossterm.compose.rendering

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The probe exists to decide whether a latency change helped. A histogram that reports
 * plausible-but-wrong percentiles would make every later measurement worthless while
 * looking fine, so the bucketing is pinned here rather than trusted.
 */
class FrameLatencyProbeTest {

    private var wasEnabled = false

    @BeforeTest
    fun setUp() {
        wasEnabled = FrameLatencyProbe.enabled
        FrameLatencyProbe.enabled = true
        FrameLatencyProbe.reset()
    }

    @AfterTest
    fun tearDown() {
        FrameLatencyProbe.reset()
        FrameLatencyProbe.enabled = wasEnabled
    }

    @Test
    fun bucketsRoundTripWithinStatedError() {
        // Four sub-buckets per octave puts the worst case at 1/8 of the value. A bucket
        // that silently widened would show up as an under-reported p99, which is exactly
        // the number a latency claim rests on.
        val h = FrameLatencyProbe.Histogram()
        var v = 1L
        while (v < 4_000_000L) {
            h.reset()
            h.record(v)
            val reported = h.quantile(0.5)
            assertTrue(
                reported <= v && v - reported <= v / 8 + 1,
                "value $v reported as $reported, outside the 12.5% bucket bound"
            )
            v = v * 3 / 2 + 1
        }
    }

    @Test
    fun quantilesTrackAKnownDistribution() {
        val h = FrameLatencyProbe.Histogram()
        // 990 samples at 1000, 10 at 100000. p50 and p95 must land on the body, p99 on it
        // too (the 10 outliers are the top 1%, so p99 is the last body sample), and only
        // max should see the tail. Getting this backwards is the classic percentile bug.
        repeat(990) { h.record(1_000) }
        repeat(10) { h.record(100_000) }

        assertEquals(1000L, h.count())
        assertWithinBucket(1_000, h.quantile(0.50))
        assertWithinBucket(1_000, h.quantile(0.95))
        assertWithinBucket(1_000, h.quantile(0.99))
        assertWithinBucket(100_000, h.quantile(1.0))
    }

    @Test
    fun emptyHistogramReportsNothingRatherThanZeroLatency() {
        val h = FrameLatencyProbe.Histogram()
        assertEquals(0L, h.count())
        assertEquals(0L, h.quantile(0.5))
        assertTrue(h.jsonMillis().contains("\"n\": 0"), h.jsonMillis())
        // A reader must be able to tell "no samples" from "instant", or an unexercised
        // build reads as a win.
        assertTrue(!h.jsonMillis().contains("p50"), h.jsonMillis())
    }

    @Test
    fun frameKeepsTheEarliestPendingArrival() {
        // A paint draws the whole buffer, so it renders every chunk that arrived before it.
        // The latency that matters is the one the OLDEST un-drawn chunk experienced; a
        // later arrival overwriting it would under-report exactly when output is bursty.
        val now = System.nanoTime()
        FrameLatencyProbe.markArrival(now - 50_000_000L) // 50 ms ago
        FrameLatencyProbe.markArrival(now - 1_000_000L)  // 1 ms ago, must not win

        val start = FrameLatencyProbe.beginFrame()
        FrameLatencyProbe.endFrame(start)

        assertEquals(1L, FrameLatencyProbe.byteToPaint.count())
        val p50 = FrameLatencyProbe.byteToPaint.quantile(0.5)
        assertTrue(p50 >= 40_000, "expected roughly 50ms in microseconds, got $p50")
    }

    @Test
    fun aFrameWithNoNewDataIsNotCountedAsLatency() {
        // Blink, resize and scroll all repaint without any byte having arrived. Recording
        // those would flood the histogram with fabricated near-zero samples and drag every
        // percentile down.
        val start = FrameLatencyProbe.beginFrame()
        FrameLatencyProbe.endFrame(start)

        assertEquals(0L, FrameLatencyProbe.byteToPaint.count())
        assertEquals(1L, FrameLatencyProbe.paintCost.count())
    }

    @Test
    fun drawCallsAreCountedPerFrameNotCumulatively() {
        repeat(3) {
            val start = FrameLatencyProbe.beginFrame()
            repeat(7) { FrameLatencyProbe.countDrawCall() }
            FrameLatencyProbe.endFrame(start)
        }
        assertEquals(3L, FrameLatencyProbe.drawCallsPerFrame.count())
        assertEquals(7L, FrameLatencyProbe.drawCallsPerFrame.quantile(0.5))
    }

    @Test
    fun disabledProbeRecordsNothing() {
        FrameLatencyProbe.enabled = false
        FrameLatencyProbe.markArrival(System.nanoTime() - 10_000_000L)
        val start = FrameLatencyProbe.beginFrame()
        FrameLatencyProbe.countDrawCall()
        FrameLatencyProbe.endFrame(start)

        assertEquals(0L, FrameLatencyProbe.byteToPaint.count())
        assertEquals(0L, FrameLatencyProbe.paintCost.count())
    }

    private fun assertWithinBucket(expected: Long, actual: Long) {
        assertTrue(
            actual <= expected && expected - actual <= expected / 8 + 1,
            "expected ~$expected, got $actual"
        )
    }
}
