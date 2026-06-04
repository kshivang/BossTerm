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
 * Session sharing / remote control (issue #276): a self-hosted web viewer that
 * mirrors a tab to another device's browser, with optional control and remote
 * reach via Tailscale. Everything here is gated by [TerminalSettings.sessionSharingEnabled];
 * the server only binds while a tab is actually shared.
 */
@Composable
fun SessionSharingSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSection(title = "Session Sharing") {
            SettingsToggle(
                label = "Enable Session Sharing",
                checked = settings.sessionSharingEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(sessionSharingEnabled = it)) },
                description = "Allow sharing a tab to a browser on another device. The server only " +
                        "binds while you actively share a tab (right-click a tab → Share Tab…)."
            )
            SettingsTextField(
                label = "Port",
                value = settings.sessionSharingPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { onSettingsChange(settings.copy(sessionSharingPort = it)) } },
                placeholder = "7677",
                description = "TCP port for the share server (falls back to the next free port if busy)."
            )
            SettingsDropdown(
                label = "Bind scope",
                options = listOf("loopback", "lan", "custom"),
                selectedOption = settings.sessionSharingBind,
                onOptionSelected = { onSettingsChange(settings.copy(sessionSharingBind = it)) },
                description = "lan (default) = reachable by devices on your network (e.g. your phone), " +
                        "URL is this machine's LAN IP; loopback = lock to this machine only; " +
                        "custom = bind a specific host. Share links are token-gated."
            )
            SettingsTextField(
                label = "Custom bind host",
                value = settings.sessionSharingBindHost,
                onValueChange = { onSettingsChange(settings.copy(sessionSharingBindHost = it)) },
                placeholder = "0.0.0.0",
                description = "Used only when bind scope is 'custom'."
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = "Remote Access (advanced)") {
            SettingsDropdown(
                label = "Tailscale",
                options = listOf("off", "serve", "funnel"),
                selectedOption = settings.shareTailscaleMode,
                onOptionSelected = { onSettingsChange(settings.copy(shareTailscaleMode = it)) },
                description = "Reach the share from anywhere via the Tailscale CLI (no port-forwarding): " +
                        "serve = your tailnet only; funnel = public internet (TLS). off = LAN/loopback only."
            )
            SettingsTextField(
                label = "Public URL override",
                value = settings.sessionSharingPublicUrl,
                onValueChange = { onSettingsChange(settings.copy(sessionSharingPublicUrl = it)) },
                placeholder = "https://term.example.com",
                description = "If you front the server with your own reverse proxy / cloudflared / SSH " +
                        "reverse tunnel, set the public base URL here (use https for internet access)."
            )
        }
    }
}
