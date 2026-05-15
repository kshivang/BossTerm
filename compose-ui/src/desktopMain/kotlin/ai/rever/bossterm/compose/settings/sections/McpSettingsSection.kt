package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.SettingsNumberInput
import ai.rever.bossterm.compose.settings.components.SettingsSection
import ai.rever.bossterm.compose.settings.components.SettingsToggle
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted

/**
 * MCP server settings: toggle the in-process Model Context Protocol server
 * and pick a localhost port. Endpoint is always `http://127.0.0.1:<port>/mcp`
 * over SSE; the server only binds when enabled. Off by default so the port
 * isn't opened until the user opts in.
 */
@Composable
fun McpSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "MCP Server") {
            SettingsToggle(
                label = "Enable MCP Server",
                checked = settings.mcpEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(mcpEnabled = it)) },
                description = "Bind an in-process Model Context Protocol server on " +
                        "127.0.0.1 so external tools (e.g. AI clients) can read scrollback, " +
                        "search output, and drive your tabs. Toggling takes effect immediately."
            )

            SettingsNumberInput(
                label = "Port",
                value = settings.mcpPort,
                onValueChange = { onSettingsChange(settings.copy(mcpPort = it)) },
                range = 1..65535,
                description = "Localhost port the server listens on. Default 7676. " +
                        "Changing while enabled will rebind to the new port.",
                enabled = settings.mcpEnabled
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Inline endpoint + security note. Kept in the section instead of as
        // a toggle description so it can update with the port value.
        Text(
            text = "Endpoint when enabled: http://127.0.0.1:${settings.mcpPort}/mcp\n" +
                    "Server binds loopback only and rejects non-loopback Host headers (403). " +
                    "Tools include both read access (scrollback, search, last command) and " +
                    "write access (send_input, send_signal). Any process running as your " +
                    "user can reach the endpoint while it is enabled.",
            color = TextMuted,
            fontSize = 12.sp
        )
    }
}
