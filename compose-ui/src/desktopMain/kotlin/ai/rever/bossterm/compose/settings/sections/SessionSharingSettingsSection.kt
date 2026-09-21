package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.*
import ai.rever.bossterm.compose.share.AccountSessionPublisher
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

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
                label = "Remote access",
                options = listOf("off", "serve", "funnel", "cloudflare"),
                selectedOption = settings.shareTailscaleMode,
                onOptionSelected = { onSettingsChange(settings.copy(shareTailscaleMode = it)) },
                description = "Reach the share from other networks (no port-forwarding). " +
                        "serve = your Tailscale tailnet only; funnel = public via Tailscale (TLS); " +
                        "cloudflare = instant public link via cloudflared, no account (ephemeral URL); " +
                        "off = LAN/loopback only."
            )
            SettingsTextField(
                label = "Public URL override",
                value = settings.sessionSharingPublicUrl,
                onValueChange = { onSettingsChange(settings.copy(sessionSharingPublicUrl = it)) },
                placeholder = "https://term.example.com",
                description = "If you front the server with your own reverse proxy / cloudflared / SSH " +
                        "reverse tunnel, set the public base URL here (use https for internet access)."
            )
            SettingsDropdown(
                label = "Require device approval",
                options = listOf("off", "funnel", "all"),
                selectedOption = settings.sessionSharingApprovalScope,
                onOptionSelected = { onSettingsChange(settings.copy(sessionSharingApprovalScope = it)) },
                description = "Ask before a new device may view/control. funnel (default) = only for " +
                        "public reach (Funnel or a custom URL); all = every device incl. LAN; off = the " +
                        "link alone grants access. Approved devices get a 24h key so they aren't re-prompted."
            )
            val account by ai.rever.bossterm.compose.auth.BossAccountManager.state.collectAsState()
            val signedIn = account is ai.rever.bossterm.compose.auth.BossAccountManager.AccountState.SignedIn
            SettingsToggle(
                label = "Publish live sessions to my BOSS account",
                checked = settings.publishSessionsToAccount,
                onCheckedChange = { onSettingsChange(settings.copy(publishSessionsToAccount = it)) },
                description = (if (signedIn) "" else "Sign in (menu > Sign In) to enable. ") +
                        "Each active share is listed under your account so you can open it from any browser at " +
                        AccountSessionPublisher.LIVE_SESSIONS_PAGE + " after a magic-link sign-in. Only the link, " +
                        "device and session names leave this machine, never terminal content. Devices opening a " +
                        "session from that page are admitted without the approval prompt. Not yet applied to " +
                        "shares hosted by the background daemon."
            )
            SettingsToggle(
                label = "Share all windows automatically while signed in",
                checked = settings.autoShareToAccount,
                onCheckedChange = { onSettingsChange(settings.copy(autoShareToAccount = it)) },
                description = "Keeps one whole-app share running (over the Cloudflare tunnel) whenever you are " +
                        "signed in and publishing, so the live-sessions page always shows this machine. Turns " +
                        "session sharing on if it is off. Off = share tabs by hand as before."
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // statusLine here too: Settings is where someone goes to ask why the viewer shows no
        // Call BossTerm button, so it should answer that in place.
        BossCallingSection(
            settings = settings,
            onSettingsChange = onSettingsChange,
            statusLine = true,
        )
    }
}
