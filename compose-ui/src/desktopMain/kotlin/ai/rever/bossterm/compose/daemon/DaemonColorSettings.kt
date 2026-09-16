package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings

/**
 * Colors for the daemon's OSC replies. The GUI persists themes in a separate process, so the
 * daemon's in-memory settings flow cannot supply these. Read on a color query (not every frame
 * or input byte), retaining the last valid palette if the file is temporarily unreadable.
 */
class DaemonColorSettings(private val manager: SettingsManager) {
    private var lastValid = manager.settings.value

    @Synchronized
    fun current(): TerminalSettings {
        manager.readFromDisk()?.let { lastValid = it }
        return lastValid
    }
}
