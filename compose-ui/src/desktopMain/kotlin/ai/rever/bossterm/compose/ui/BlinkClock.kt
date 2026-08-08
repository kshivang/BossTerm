package ai.rever.bossterm.compose.ui

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Whether a blinking element is visible [elapsedNanos] past its phase epoch.
 *
 * Visible for the first half-period of every cycle, so elapsed 0 starts visible.
 */
internal fun blinkVisibleAt(elapsedNanos: Long, periodNanos: Long): Boolean {
  if (periodNanos <= 0L) return true
  return (elapsedNanos.coerceAtLeast(0L) / periodNanos) % 2L == 0L
}

/** Nanos from [elapsedNanos] to the next phase edge. Always in `1..periodNanos`, never 0. */
internal fun nanosToNextBlinkEdge(elapsedNanos: Long, periodNanos: Long): Long {
  if (periodNanos <= 0L) return 0L
  return periodNanos - (elapsedNanos.coerceAtLeast(0L) % periodNanos)
}

/**
 * Whole milliseconds to sleep before the next edge, rounded UP.
 *
 * Rounding down would wake a fraction of a millisecond BEFORE the edge, recompute the same
 * phase, and immediately sleep again - a spin at the boundary. Up means every wake lands at or
 * after the edge it was aimed at.
 */
internal fun millisToNextBlinkEdge(elapsedNanos: Long, periodNanos: Long): Long =
  (nanosToNextBlinkEdge(elapsedNanos, periodNanos) + 999_999L) / 1_000_000L

/**
 * Drive a blink flag off an ABSOLUTE timeline.
 *
 * The shape this replaces was `while (isActive) { delay(period); flag = !flag }`. `delay`
 * measures from RESUMPTION, and these loops run in the composition's effect context - the AWT
 * event thread on Compose Desktop, shared with pointer handling, recomposition and drawing. So
 * under load the resumption lands X ms late, the toggle happens at `period + X`, and the NEXT
 * delay starts from there: the period stretches and the phase walks. X tracks scroll and output
 * activity, which is why the blink rate is visibly unstable while scrolling a busy TUI.
 *
 * Here the phase is a pure function of the monotonic clock, so a late wake produces the phase
 * that is CORRECT for the moment it woke, and the next sleep targets the next absolute edge
 * rather than `now + period`. A stall longer than a period simply skips the toggles it slept
 * through - real terminals do not replay missed blinks - and the `!= last` guard means a wake
 * landing inside the same half-period costs no invalidation at all.
 *
 * Still exactly two wakeups per period, and none while parked, so the zero-idle-repaint
 * guarantee that gates these loops on window focus and tab visibility is untouched. A frame-
 * clock formulation (`withFrameNanos` / `withInfiniteAnimationFrameMillis`) would instead
 * request a frame at display refresh rate forever, per visible tab, and break it.
 *
 * @param periodMs half-cycle length; `<= 0` means "no blink" and returns after showing visible.
 * @param nowNanos monotonic clock, injectable so the loop is testable on virtual time.
 * @param onVisible invoked only when the phase actually flips.
 */
internal suspend fun runBlinkPhase(
  periodMs: Int,
  nowNanos: () -> Long = System::nanoTime,
  onVisible: (Boolean) -> Unit,
) {
  onVisible(true)
  // The settings slider exposes 0 as "Off". Turning that into delay(0) in a tight loop pegged a
  // core instead of disabling the blink, so it stays an early return.
  if (periodMs <= 0) return
  val periodNanos = periodMs * 1_000_000L
  val epoch = nowNanos()
  var last = true
  while (currentCoroutineContext().isActive) {
    delay(millisToNextBlinkEdge(nowNanos() - epoch, periodNanos))
    val visible = blinkVisibleAt(nowNanos() - epoch, periodNanos)
    if (visible != last) {
      last = visible
      onVisible(visible)
    }
  }
}
