package ai.rever.bossterm.compose.power

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils

/**
 * Abstraction over OS-level "keep awake" / power-management inhibition.
 *
 * While a [PreventSleep] is held (between [acquire] and [release]) the system is
 * asked not to enter idle sleep. This is useful while long-running terminal
 * commands are executing so the machine does not doze off mid-task.
 *
 * Implementations spawn a child process (e.g. `caffeinate` on macOS or
 * `systemd-inhibit` on Linux) rather than linking against native libraries, so
 * no JNA dependency is required.
 */
interface PreventSleep {
    /**
     * Begin inhibiting system sleep.
     *
     * Idempotent: calling [acquire] again while already holding the inhibitor is
     * a no-op (the original [reason] continues to apply).
     *
     * @param reason human-readable description of why sleep is being prevented.
     */
    fun acquire(reason: String)

    /**
     * Release a previously acquired inhibitor, allowing the system to sleep
     * again. Safe to call when nothing is held.
     */
    fun release()
}

/**
 * Creates the appropriate [PreventSleep] implementation for the current OS.
 *
 * - macOS  -> [MacOsPreventSleep] (spawns `caffeinate -i`)
 * - Linux  -> [LinuxPreventSleep] (spawns `systemd-inhibit ... sleep infinity`)
 * - other  -> [NoOpPreventSleep]
 */
object PreventSleepFactory {
    /** Returns a platform-appropriate [PreventSleep]; never null. */
    fun create(): PreventSleep = when {
        ShellCustomizationUtils.isMacOS() -> MacOsPreventSleep()
        ShellCustomizationUtils.isLinux() -> LinuxPreventSleep()
        else -> NoOpPreventSleep()
    }
}

/**
 * macOS implementation that holds a `caffeinate -i` process for the duration of
 * the inhibition. `-i` prevents the system from idle sleeping.
 *
 * Thread-safe and idempotent: all state transitions are guarded by an intrinsic
 * lock and repeated [acquire] calls reuse the existing process.
 */
internal class MacOsPreventSleep : PreventSleep {
    private val lock = Any()
    private var process: Process? = null

    override fun acquire(reason: String) {
        synchronized(lock) {
            // Already holding a live inhibitor -> nothing to do.
            if (process?.isAlive == true) return
            process = runCatching {
                ProcessBuilder("caffeinate", "-i")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull()
        }
    }

    override fun release() {
        synchronized(lock) {
            process?.destroy()
            process = null
        }
    }
}

/**
 * Linux implementation that holds a `systemd-inhibit` process which blocks idle,
 * sleep, and lid-switch handling for the lifetime of an inner `sleep infinity`.
 *
 * If `systemd-inhibit` is not present on the system the process start is caught
 * and the implementation degrades to a graceful no-op.
 *
 * Thread-safe and idempotent.
 */
internal class LinuxPreventSleep : PreventSleep {
    private val lock = Any()
    private var process: Process? = null

    override fun acquire(reason: String) {
        synchronized(lock) {
            // Already holding a live inhibitor -> nothing to do.
            if (process?.isAlive == true) return
            process = runCatching {
                ProcessBuilder(
                    "systemd-inhibit",
                    "--what=idle:sleep:handle-lid-switch",
                    "--why=$reason",
                    "--mode=block",
                    "sleep",
                    "infinity",
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull()
        }
    }

    override fun release() {
        synchronized(lock) {
            process?.destroyForcibly()
            process = null
        }
    }
}

/**
 * Fallback implementation for platforms without a supported inhibition
 * mechanism. All operations are intentionally empty.
 */
internal class NoOpPreventSleep : PreventSleep {
    override fun acquire(reason: String) { /* no-op */ }
    override fun release() { /* no-op */ }
}
