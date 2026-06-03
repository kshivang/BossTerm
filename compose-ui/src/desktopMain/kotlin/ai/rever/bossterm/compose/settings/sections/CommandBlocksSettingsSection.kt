package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.toSettingsHex
import ai.rever.bossterm.compose.settings.components.*

/**
 * Command Blocks settings: per-command gutter bars, colors, and scrollbar markers.
 *
 * Everything here is gated by [TerminalSettings.commandBlocksEnabled], which
 * defaults to false — so an untouched terminal behaves exactly as before.
 */
@Composable
fun CommandBlocksSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "Command Blocks") {
            SettingsToggle(
                label = "Enable Command Blocks",
                checked = settings.commandBlocksEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(commandBlocksEnabled = it)) },
                description = "Group each command's output and show a colored gutter bar by exit code (requires shell integration)"
            )

            SettingsSlider(
                label = "Gutter Width",
                value = settings.commandBlockGutterWidth,
                onValueChange = { onSettingsChange(settings.copy(commandBlockGutterWidth = it)) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 1f..8f,
                steps = 7,
                valueDisplay = { "${it.toInt()} px" },
                enabled = settings.commandBlocksEnabled
            )

            SettingsToggle(
                label = "Show Scrollbar Markers",
                checked = settings.commandBlockShowScrollbarMarkers,
                onCheckedChange = { onSettingsChange(settings.copy(commandBlockShowScrollbarMarkers = it)) },
                description = "Mark each command's start position in the scrollbar",
                enabled = settings.commandBlocksEnabled
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = "Colors") {
            ColorSetting(
                label = "Success",
                color = settings.commandBlockSuccessColorValue,
                onColorChange = { onSettingsChange(settings.copy(commandBlockSuccessColor = it.toSettingsHex())) },
                description = "Exit code 0",
                enabled = settings.commandBlocksEnabled
            )

            ColorSetting(
                label = "Error",
                color = settings.commandBlockErrorColorValue,
                onColorChange = { onSettingsChange(settings.copy(commandBlockErrorColor = it.toSettingsHex())) },
                description = "Non-zero exit code",
                enabled = settings.commandBlocksEnabled
            )

            ColorSetting(
                label = "Running",
                color = settings.commandBlockRunningColorValue,
                onColorChange = { onSettingsChange(settings.copy(commandBlockRunningColor = it.toSettingsHex())) },
                description = "Command still in progress",
                enabled = settings.commandBlocksEnabled
            )
        }
    }
}
