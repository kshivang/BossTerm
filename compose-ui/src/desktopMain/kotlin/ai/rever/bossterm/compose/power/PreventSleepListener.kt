package ai.rever.bossterm.compose.power

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.terminal.model.CommandStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holds an OS wake-lock while a foreground command runs past the configured
 * threshold (Phase 7). Registered per session as an OSC-133 [CommandStateListener].
 *
 * On command start, schedules a delayed acquire; if the command is still running
 * after `preventSleepThresholdSeconds`, the wake-lock is taken. On command finish
 * the pending job is cancelled and any held lock released. No-op (and never
 * acquires) when `preventSleepDuringCommands` is off.
 */
class PreventSleepListener(
    private val scope: CoroutineScope,
) : CommandStateListener {

    private val preventSleep = PreventSleepFactory.create()
    private var job: Job? = null

    private fun enabled(): Boolean =
        SettingsManager.instance.settings.value.preventSleepDuringCommands

    private fun thresholdMs(): Long =
        SettingsManager.instance.settings.value.preventSleepThresholdSeconds.toLong().coerceAtLeast(1L) * 1000L

    override fun onCommandStarted() {
        if (!enabled()) return
        job?.cancel()
        job = scope.launch {
            delay(thresholdMs())
            if (enabled()) preventSleep.acquire("BossTerm: long-running command")
        }
    }

    override fun onCommandFinished(exitCode: Int) {
        job?.cancel()
        job = null
        preventSleep.release()
    }

    /**
     * Release any held wake-lock and cancel pending acquisition. Must be called
     * when the session is disposed — otherwise a tab closed mid-command (after the
     * lock was acquired, before OSC 133;D) would orphan the caffeinate/systemd
     * process and keep the machine awake.
     */
    fun dispose() {
        job?.cancel()
        job = null
        preventSleep.release()
    }
}
