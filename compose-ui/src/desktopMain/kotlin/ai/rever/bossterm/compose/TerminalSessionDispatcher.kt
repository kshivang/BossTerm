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
 * the shared pools. 256 permits ≈ 85 concurrent sessions; threads are
 * created lazily, so idle cost is zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
val TerminalSessionDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(256, "bossterm-session")
