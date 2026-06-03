package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.*

/**
 * Command Palette settings. Gated by [TerminalSettings.commandPaletteEnabled];
 * when off, the Cmd/Ctrl+Shift+P hotkey is not intercepted.
 */
@Composable
fun CommandPaletteSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "Command Palette") {
            SettingsToggle(
                label = "Enable Command Palette",
                checked = settings.commandPaletteEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(commandPaletteEnabled = it)) },
                description = "Fuzzy-search and run any action or recent command (Cmd/Ctrl+Shift+P)"
            )
        }
    }
}
