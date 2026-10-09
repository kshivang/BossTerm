package ai.rever.bossterm.app

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** GUI settings writes happen in another process; the daemon's in-memory StateFlow stays stale. */
internal fun daemonMcpSettingsChanges(readSettings: () -> TerminalSettings, pollIntervalMs: Long = 500L) = flow {
    require(pollIntervalMs > 0)
    while (true) {
        val settings = readSettings()
        emit(settings.mcpRunCommandPreferredShell to settings.disabledMcpTools.toSet())
        delay(pollIntervalMs)
    }
}.distinctUntilChanged()
