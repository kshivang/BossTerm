package ai.rever.bossterm.compose.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The blink loop on virtual time.
 *
 * The wall clock is injected separately from the scheduler on purpose: a test scheduler always
 * resumes a `delay` exactly on schedule, so the only way to model "the UI thread was blocked
 * and the resumption landed late" is to move the wall clock ahead of it. That skew is the whole
 * failure mode these tests exist for.
 */
class BlinkPhaseLoopTest {

    @Test
    fun edgesLandOnTheAbsoluteTimeline() = runTest {
        val seen = mutableListOf<Pair<Long, Boolean>>()
        backgroundScope.launch {
            runBlinkPhase(
                periodMs = 500,
                nowNanos = { testScheduler.currentTime * 1_000_000L },
            ) { seen += testScheduler.currentTime to it }
        }

        runCurrent()
        assertEquals(listOf(0L to true), seen, "the caret shows before the first edge")

        repeat(3) { advanceTimeBy(500); runCurrent() }
        assertEquals(
            listOf(0L to true, 500L to false, 1000L to true, 1500L to false),
            seen,
        )
    }

    @Test
    fun aStallLandingInTheSameHalfPeriodCostsNoInvalidation() = runTest {
        var wallMs = 0L
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch {
            runBlinkPhase(periodMs = 500, nowNanos = { wallMs * 1_000_000L }) { seen += it }
        }
        runCurrent()
        assertEquals(listOf(true), seen)

        // 1300ms of wall time passed while the dispatcher was blocked. 1300/500 = 2, an even
        // half-period, so the phase is still "visible" and nothing should be published.
        wallMs = 1300
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(true), seen, "a wake inside the same half-period must not invalidate")
    }

    @Test
    fun aLongStallSkipsTheMissedTogglesRatherThanReplayingThem() = runTest {
        var wallMs = 0L
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch {
            runBlinkPhase(periodMs = 500, nowNanos = { wallMs * 1_000_000L }) { seen += it }
        }
        runCurrent()

        // 1600ms in one jump: three edges were slept through. Real terminals do not replay
        // missed blinks - the caret simply shows the phase that is correct for now.
        wallMs = 1600
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(true, false), seen, "exactly one toggle, not a catch-up burst")
    }

    @Test
    fun aNonPositivePeriodShowsTheCaretAndStops() = runTest {
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch {
            runBlinkPhase(periodMs = 0, nowNanos = { testScheduler.currentTime * 1_000_000L }) {
                seen += it
            }
        }
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(true), seen, "'Off' must be off, not a delay(0) spin")
    }

    /**
     * The battery guard.
     *
     * An idle terminal is allowed exactly two wakeups per period and no more. This is what
     * fails if anyone re-formulates the loop on the frame clock, which would tick at display
     * refresh rate forever, per visible tab.
     */
    @Test
    fun tenSecondsCostsExactlyTwentyToggles() = runTest {
        var toggles = 0
        backgroundScope.launch {
            runBlinkPhase(
                periodMs = 500,
                nowNanos = { testScheduler.currentTime * 1_000_000L },
            ) { toggles++ }
        }
        runCurrent()
        toggles = 0 // discard the initial "visible"

        repeat(20) { advanceTimeBy(500); runCurrent() }
        assertEquals(20, toggles, "10s / 500ms = 20 edges, no extra wakeups")
    }
}
