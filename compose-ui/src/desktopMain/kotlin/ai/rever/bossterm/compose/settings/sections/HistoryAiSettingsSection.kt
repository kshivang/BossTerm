package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.*

/**
 * History search + AI command bar settings (Phase 4). Both default off; when off
 * Ctrl+R is not intercepted and no AI calls are made.
 */
@Composable
fun HistoryAiSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "History Search") {
            SettingsToggle(
                label = "Enable History Search",
                checked = settings.historySearchEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(historySearchEnabled = it)) },
                description = "Ctrl+R opens a fuzzy search over shell history and recent commands (ignored while a full-screen app is active)"
            )
        }

        SettingsSection(title = "AI Command Bar") {
            SettingsToggle(
                label = "Enable AI Command Bar",
                checked = settings.aiCommandBarEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(aiCommandBarEnabled = it)) },
                description = "When the history search query reads like natural language, ask the configured agent to suggest a command (inserted, never auto-run)",
                enabled = settings.historySearchEnabled
            )
        }
    }
}
