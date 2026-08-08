package ai.rever.bossterm.compose.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MS = 1_000_000L

/**
 * The arithmetic behind a blink that keeps its phase when the UI thread is busy.
 *
 * Extracted from the composable for the same reason as `foldHistoryAppends`: the edges are
 * ordinary assertions rather than something needing a Compose harness.
 */
class BlinkClockTest {

    @Test
    fun visibilityAlternatesEveryPeriod() {
        val period = 500 * MS
        assertTrue(blinkVisibleAt(0, period), "a fresh phase starts visible")
        assertTrue(blinkVisibleAt(499 * MS, period))
        assertTrue(!blinkVisibleAt(500 * MS, period), "the first edge hides it")
        assertTrue(!blinkVisibleAt(999 * MS, period))
        assertTrue(blinkVisibleAt(1000 * MS, period))
        assertTrue(!blinkVisibleAt(1500 * MS, period))
    }

    @Test
    fun anUnsetPeriodIsAlwaysVisible() {
        assertTrue(blinkVisibleAt(12_345, periodNanos = 0))
        assertTrue(blinkVisibleAt(12_345, periodNanos = -1))
    }

    @Test
    fun negativeElapsedIsVisibleRatherThanACrash() {
        // A clock that appears to run backwards must not produce a negative modulus.
        assertTrue(blinkVisibleAt(-1, 500 * MS))
        assertEquals(500 * MS, nanosToNextBlinkEdge(-1, 500 * MS))
    }

    @Test
    fun theNextEdgeIsNeverZeroAndNeverBeyondOnePeriod() {
        val period = 500 * MS
        for (elapsed in longArrayOf(0, 1, 250 * MS, 499 * MS, 500 * MS, 501 * MS, 10_000 * MS)) {
            val next = nanosToNextBlinkEdge(elapsed, period)
            assertTrue(next in 1..period, "elapsed=$elapsed produced $next")
        }
        assertEquals(period, nanosToNextBlinkEdge(0, period), "exact multiples sleep a full period")
        assertEquals(period, nanosToNextBlinkEdge(period, period))
    }

    @Test
    fun theSleepRoundsUpSoAWakeNeverLandsBeforeItsEdge() {
        // Rounding down would wake a fraction of a millisecond early, recompute the same phase
        // and immediately re-sleep - a spin at the boundary.
        assertEquals(1L, millisToNextBlinkEdge(499 * MS + 999_999L, 500 * MS))
        assertEquals(500L, millisToNextBlinkEdge(0, 500 * MS))
        assertEquals(1L, millisToNextBlinkEdge(499 * MS, 500 * MS))
    }

    /**
     * The characterisation test that names the bug.
     *
     * Both models are driven by the same sequence of late wakes. The old shape restarted its
     * delay from wherever it happened to resume, so lateness accumulated; the absolute-edge
     * shape re-derives the phase from the epoch, so each edge is late by that wake's delay only
     * and never by the sum of everything before it.
     */
    @Test
    fun theOldFixedDelayLoopDriftsAndTheAbsoluteEdgeLoopDoesNot() {
        val period = 500L
        // Deterministic pseudo-random lateness in 0..400ms; a fixed seed keeps the test stable.
        var seed = 12345L
        val lateness = LongArray(20) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            seed % 401
        }

        // Old: next wake is scheduled `period` after the previous RESUMPTION.
        var oldNow = 0L
        val oldEdges = lateness.map { late -> oldNow += period + late; oldNow }

        // New: next wake targets the next absolute edge past the epoch.
        var newNow = 0L
        val newEdges = lateness.map { late ->
            newNow += (period - newNow % period) + late
            newNow
        }

        val idealLast = 20 * period
        assertTrue(
            oldEdges.last() > idealLast + 3_000,
            "the fixed-delay loop should have drifted seconds past ${idealLast}ms, " +
                "was ${oldEdges.last()}ms",
        )
        newEdges.forEachIndexed { i, edge ->
            val ideal = (i + 1) * period
            assertTrue(
                edge - ideal == lateness[i],
                "edge $i should be late by exactly its own wake (${lateness[i]}ms), " +
                    "was ${edge - ideal}ms past ${ideal}ms",
            )
        }
    }
}
