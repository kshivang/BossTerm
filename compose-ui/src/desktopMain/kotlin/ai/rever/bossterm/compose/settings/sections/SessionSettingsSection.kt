package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.*

/**
 * Session-restore settings (Phase 6). Off by default → today's single-tab launch.
 */
@Composable
fun SessionSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "Session Restore") {
            SettingsToggle(
                label = "Restore Session on Launch",
                checked = settings.restoreSessionOnLaunch,
                onCheckedChange = { onSettingsChange(settings.copy(restoreSessionOnLaunch = it)) },
                description = "Reopen your tabs, split layout, and per-pane working directories when BossTerm starts"
            )
        }
    }
}
