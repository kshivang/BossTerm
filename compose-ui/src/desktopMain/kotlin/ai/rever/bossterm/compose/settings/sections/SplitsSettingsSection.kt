package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.*
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import ai.rever.bossterm.compose.settings.toSettingsHex

/**
 * Split pane settings section: behavior and appearance.
 */
@Composable
fun SplitsSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "Split Behavior") {
            SettingsSlider(
                label = "Default Split Ratio",
                value = settings.splitDefaultRatio,
                onValueChange = { onSettingsChange(settings.copy(splitDefaultRatio = it)) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 0.3f..0.7f,
                steps = 8,
                valueDisplay = { "${(it * 100).toInt()}%" },
                description = "Initial ratio for new splits (e.g., 50% = equal split)"
            )

            SettingsSlider(
                label = "Minimum Pane Size",
                value = settings.splitMinimumSize,
                onValueChange = { onSettingsChange(settings.copy(splitMinimumSize = it)) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 0.05f..0.4f,
                steps = 7,
                valueDisplay = { "${(it * 100).toInt()}%" },
                description = "Minimum size when resizing panes"
            )

            SettingsToggle(
                label = "Inherit Working Directory",
                checked = settings.splitInheritWorkingDirectory,
                onCheckedChange = { onSettingsChange(settings.copy(splitInheritWorkingDirectory = it)) },
                description = "New splits start in the same directory as parent"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = "Focus Indicator") {
            SettingsToggle(
                label = "Show Focus Border",
                checked = settings.splitFocusBorderEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(splitFocusBorderEnabled = it)) },
                description = "Highlight the focused pane with a colored border"
            )

            val themeFocusRing = BossUiTheme.current.focusRing
            val override = settings.splitFocusBorderColorOverride
            val followsTheme = override == null

            // Turning Match Theme off has to write *some* colour, and the stored one
            // is gone by then. Holding the last explicit choice for as long as the
            // window is open makes on -> off -> on lossless, which is the round trip
            // someone actually performs (flip it on, look at it, flip it back).
            // Deliberately not persisted: a colour nobody can see in the UI is worse
            // than re-picking one.
            var lastExplicit by remember { mutableStateOf(override) }
            if (override != null && override != lastExplicit) lastExplicit = override

            SettingsToggle(
                label = "Match Theme",
                checked = followsTheme,
                onCheckedChange = { follow ->
                    onSettingsChange(
                        settings.copy(
                            splitFocusBorderColor = when {
                                follow -> ""
                                // Falls back to what is on screen for someone who has
                                // never picked a colour, so the picker opens on what
                                // they were just looking at.
                                else -> (lastExplicit ?: themeFocusRing).toSettingsHex()
                            }
                        )
                    )
                },
                description = "Use the active theme's accent for the focus indicator",
                enabled = settings.splitFocusBorderEnabled
            )

            ColorSetting(
                label = "Focus Border Color",
                color = override ?: themeFocusRing,
                onColorChange = { onSettingsChange(settings.copy(splitFocusBorderColor = it.toSettingsHex())) },
                description = "Color of the focus indicator border",
                enabled = settings.splitFocusBorderEnabled && !followsTheme
            )
        }
    }
}
