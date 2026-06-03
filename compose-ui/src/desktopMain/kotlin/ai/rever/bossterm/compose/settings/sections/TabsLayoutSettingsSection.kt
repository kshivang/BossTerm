package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.*

/**
 * Tabs & layout settings (Phase 5). Defaults preserve today's layout
 * (top tab bar, no per-split tabs).
 */
@Composable
fun TabsLayoutSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "Tab Bar") {
            SettingsDropdown(
                label = "Position",
                options = listOf("top", "left"),
                selectedOption = settings.tabBarPosition,
                onOptionSelected = { onSettingsChange(settings.copy(tabBarPosition = it)) },
                description = "Place the tab bar across the top or down the left side"
            )
        }

        SettingsSection(title = "Per-Split Tabs") {
            SettingsToggle(
                label = "Enable Per-Split Tabs",
                checked = settings.enablePerSplitTabs,
                onCheckedChange = { onSettingsChange(settings.copy(enablePerSplitTabs = it)) },
                description = "Allow multiple sessions inside a single split pane with their own tab strip"
            )
            SettingsSlider(
                label = "Per-Split Tab Height",
                value = settings.perSplitTabBarHeight,
                onValueChange = { onSettingsChange(settings.copy(perSplitTabBarHeight = it)) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 20f..40f,
                steps = 20,
                valueDisplay = { "${it.toInt()} px" },
                enabled = settings.enablePerSplitTabs
            )
        }
    }
}
