package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.*

/**
 * Phase 7 polish toggles: git branch indicator, shell vi-mode, shell
 * autosuggestions, and prevent-sleep. All default off.
 */
@Composable
fun ExtrasSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "Status") {
            SettingsToggle(
                label = "Git Branch Indicator",
                checked = settings.showGitBranchIndicator,
                onCheckedChange = { onSettingsChange(settings.copy(showGitBranchIndicator = it)) },
                description = "Show the active pane's git branch near the tab bar"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = "Shell") {
            SettingsToggle(
                label = "Vi Mode",
                checked = settings.shellViMode,
                onCheckedChange = { onSettingsChange(settings.copy(shellViMode = it)) },
                description = "Enable vi key bindings in the shell (injected via shell integration; takes effect in new shells)"
            )
            SettingsToggle(
                label = "Autosuggestions",
                checked = settings.shellAutosuggestions,
                onCheckedChange = { onSettingsChange(settings.copy(shellAutosuggestions = it)) },
                description = "Enable fish-style autosuggestions where the shell supports them (new shells)"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = "Power") {
            SettingsToggle(
                label = "Prevent Sleep During Long Commands",
                checked = settings.preventSleepDuringCommands,
                onCheckedChange = { onSettingsChange(settings.copy(preventSleepDuringCommands = it)) },
                description = "Hold a wake-lock while a foreground command runs past the threshold"
            )
            SettingsSlider(
                label = "Threshold",
                value = settings.preventSleepThresholdSeconds.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(preventSleepThresholdSeconds = it.toInt())) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 5f..300f,
                steps = 0,
                valueDisplay = { "${it.toInt()} s" },
                enabled = settings.preventSleepDuringCommands
            )
        }
    }
}
