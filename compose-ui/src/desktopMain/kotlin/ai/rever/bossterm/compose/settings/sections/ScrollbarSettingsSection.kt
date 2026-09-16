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
 * Scrollbar settings section: appearance and search markers.
 */
@Composable
fun ScrollbarSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Appearance Settings
        SettingsSection(title = "Appearance") {
            SettingsToggle(
                label = "Show Scrollbar",
                checked = settings.showScrollbar,
                onCheckedChange = { onSettingsChange(settings.copy(showScrollbar = it)) },
                description = "Display scrollbar on the right side"
            )

            SettingsToggle(
                label = "Show scrollbar gutter",
                checked = settings.showScrollbarGutter,
                onCheckedChange = { onSettingsChange(settings.copy(showScrollbarGutter = it)) },
                description = "Show the background behind the scrollbar thumb",
                enabled = settings.showScrollbar
            )

            SettingsToggle(
                label = "Always Visible",
                checked = settings.scrollbarAlwaysVisible,
                onCheckedChange = { onSettingsChange(settings.copy(scrollbarAlwaysVisible = it)) },
                description = "Reserve space for the scrollbar. When off, it overlays text while scrolling or hovered.",
                enabled = settings.showScrollbar
            )

            SettingsSlider(
                label = "Scrollbar Width",
                value = settings.scrollbarWidth,
                onValueChange = { onSettingsChange(settings.copy(scrollbarWidth = it)) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 6f..20f,
                steps = 13,
                valueDisplay = { "${it.toInt()} px" },
                enabled = settings.showScrollbar
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = "Terminal spacing") {
            SettingsToggle(
                label = "Right edge gap",
                checked = settings.terminalRightGapEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(terminalRightGapEnabled = it)) },
                description = "Leave extra space after terminal text, before the scrollbar or window edge"
            )
            SettingsSlider(
                label = "Gap width",
                value = settings.terminalRightGap,
                onValueChange = { onSettingsChange(settings.copy(terminalRightGap = it)) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 0f..32f,
                steps = 31,
                valueDisplay = { "${it.toInt()} dp" },
                enabled = settings.terminalRightGapEnabled
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Colors
        SettingsSection(title = "Colors") {
            ColorSetting(
                label = "Gutter Color",
                color = settings.scrollbarColorValue,
                onColorChange = { onSettingsChange(settings.copy(scrollbarColor = it.toSettingsHex())) },
                description = "Background behind the scrollbar thumb",
                enabled = settings.showScrollbar && settings.showScrollbarGutter
            )

            ColorSetting(
                label = "Thumb Color",
                color = settings.scrollbarThumbColorValue,
                onColorChange = { onSettingsChange(settings.copy(scrollbarThumbColor = it.toSettingsHex())) },
                description = "Scrollbar handle",
                enabled = settings.showScrollbar
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Search Markers
        SettingsSection(title = "Search Markers") {
            SettingsToggle(
                label = "Show Search Markers",
                checked = settings.showSearchMarkersInScrollbar,
                onCheckedChange = { onSettingsChange(settings.copy(showSearchMarkersInScrollbar = it)) },
                description = "Highlight search matches in scrollbar",
                enabled = settings.showScrollbar
            )

            ColorSetting(
                label = "Marker Color",
                color = settings.searchMarkerColorValue,
                onColorChange = { onSettingsChange(settings.copy(searchMarkerColor = it.toSettingsHex())) },
                description = "Search match indicator",
                enabled = settings.showScrollbar && settings.showSearchMarkersInScrollbar
            )

            ColorSetting(
                label = "Current Match Color",
                color = settings.currentSearchMarkerColorValue,
                onColorChange = { onSettingsChange(settings.copy(currentSearchMarkerColor = it.toSettingsHex())) },
                description = "Current match indicator",
                enabled = settings.showScrollbar && settings.showSearchMarkersInScrollbar
            )
        }
    }
}
