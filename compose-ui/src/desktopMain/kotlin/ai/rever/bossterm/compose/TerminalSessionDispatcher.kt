package ai.rever.bossterm.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Dispatcher for per-session blocking terminal loops: the PTY reader, the
 * emulator-processing loop, and the process exit wait ([PlatformServices
 * .ProcessService.ProcessHandle.waitFor]). Each live session parks ~3 threads
 * in these loops for its entire lifetime.
 *
 * They must NOT run on the shared pools: `Dispatchers.Default` has only nCPU
 * permits and `Dispatchers.IO` 64, so enough open tabs pin both pools and
 * starve every other coroutine in the process — observed in the BOSS host at
 * ~20 sessions as frozen terminals, new tabs that never spawn a shell, and
 * Supabase Realtime heartbeat timeouts, while the rest of the app stayed up.
 *
 * A [limitedParallelism][CoroutineDispatcher.limitedParallelism] view of
 * `Dispatchers.IO` draws from its own permit budget (threads created for it
 * do not count against the global 64), so session loops can never exhaust
 * the shared pools. 256 accounted threads ≈ 85 concurrent sessions; threads
 * are created lazily, so idle cost is zero.
 *
 * The view's parallelism is [TerminalSessionSlots.MAX_THREADS] plus a little
 * headroom: [TerminalSessionSlots] accounting is advisory (a session's
 * reservation can be released slightly before its loops finish unwinding
 * during teardown), so short-lived work on the view must never depend on a
 * permit being free at exactly the accounted budget.
 */
@OptIn(ExperimentalCoroutinesApi::class)
val TerminalSessionDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(TerminalSessionSlots.MAX_THREADS + 4, "bossterm-session")

/**
 * Advisory accounting for [TerminalSessionDispatcher]'s permit budget.
 *
 * Session owners reserve their long-lived thread count up front and release it
 * when the session dies. When the budget is exhausted, a new session is REFUSED
 * outright (error state + a "close some terminals" dialog) instead of being
 * queued on the dispatcher — a queued session would silently never start,
 * which is exactly the failure mode this exists to make visible.
 *
 * Two rules for callers:
 * - [tryReserve] must run OUTSIDE [TerminalSessionDispatcher] (synchronously on
 *   the caller's thread, before dispatching): at exact saturation every view
 *   thread is parked in a live loop, so a coroutine dispatched just to check
 *   and report the refusal would itself queue forever.
 * - every successful [tryReserve] must be paired with exactly one [release],
 *   on ALL exit paths — including exceptions thrown between reserving and
 *   arming whatever completion hook normally releases.
 */
object TerminalSessionSlots {
    /** Permit budget of [TerminalSessionDispatcher]. */
    const val MAX_THREADS = 256

    /** Long-lived threads a full local session pins: PTY reader + emulator loop + waitFor. */
    const val THREADS_PER_SESSION = 3

    /** Message shown in the pane (and echoed by the capacity dialog) when a session is refused. */
    const val EXHAUSTED_MESSAGE =
        "No terminal threads available — close some terminal tabs or splits, then try again."

    private val used = java.util.concurrent.atomic.AtomicInteger(0)

    /** Currently reserved threads; UI may show this alongside [MAX_THREADS]. */
    val usedThreads: Int get() = used.get()

    /**
     * Reserve [n] session-loop threads. Returns false — reserving nothing — when
     * the budget can't fit them; the caller must not start its session loops.
     */
    fun tryReserve(n: Int = THREADS_PER_SESSION): Boolean {
        while (true) {
            val current = used.get()
            if (current + n > MAX_THREADS) return false
            if (used.compareAndSet(current, current + n)) return true
        }
    }

    /**
     * Return [n] previously reserved threads. Callers must pair this 1:1 with a
     * successful [tryReserve]; clamped at zero so a mis-paired release can't
     * corrupt the budget into admitting sessions the dispatcher can't run.
     */
    fun release(n: Int = THREADS_PER_SESSION) {
        used.updateAndGet { (it - n).coerceAtLeast(0) }
    }
}
