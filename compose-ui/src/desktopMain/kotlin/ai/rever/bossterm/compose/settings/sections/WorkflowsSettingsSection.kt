package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.*

/**
 * Workflows settings. Gated by [TerminalSettings.workflowsEnabled]; when off,
 * no workflows are loaded or shown in the palette.
 */
@Composable
fun WorkflowsSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "Workflows") {
            SettingsToggle(
                label = "Enable Workflows",
                checked = settings.workflowsEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(workflowsEnabled = it)) },
                description = "Saved parameterized commands from ~/.bossterm/workflows and project .warp/workflows, surfaced in the command palette"
            )
            SettingsToggle(
                label = "Run on Submit",
                checked = settings.workflowsAutoRun,
                onCheckedChange = { onSettingsChange(settings.copy(workflowsAutoRun = it)) },
                description = "Run the workflow command immediately instead of inserting it at the prompt for review",
                enabled = settings.workflowsEnabled
            )
        }
    }
}
