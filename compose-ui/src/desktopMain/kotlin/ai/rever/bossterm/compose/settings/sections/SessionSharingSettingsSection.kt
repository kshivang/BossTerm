package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextOnAccent
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.Danger
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.relay.RelayConfig
import ai.rever.bossterm.compose.settings.components.*
import ai.rever.bossterm.compose.share.AccountSessionPublisher
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

import ai.rever.bossterm.compose.share.AccountTerminalPreferences
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

        SettingsSection(title = "Terminal relay") {
            val overrides = RelayConfig.overrides()
            val effective = RelayConfig.current(settings, overrides)
            SettingsToggle(
                label = "Use encrypted terminal relay",
                checked = RelayConfig.enabled(settings, overrides),
                enabled = overrides.enabled == null,
                onCheckedChange = { onSettingsChange(settings.copy(terminalRelayEnabled = it)) },
                description = "On by default. While signed in, publish terminal output once so multiple viewers " +
                        "can receive it. Turn off to use direct connections. Existing direct links remain supported."
            )
            if (overrides.present) {
                Text("Launch configuration overrides " +
                    listOfNotNull(if (overrides.enabled != null) "relay on/off" else null,
                        if (overrides.endpoint != null) "the relay endpoint" else null).joinToString(" and ") +
                    ". Effective relay: " + if (effective != null) "on" else if (RelayConfig.enabled(settings, overrides)) "invalid configuration" else "off",
                    color = TextMuted, fontSize = 11.sp)
            }
            var endpoint by remember(settings.terminalRelayUrl) { mutableStateOf(settings.terminalRelayUrl) }
            val validEndpoint = runCatching { RelayConfig(endpoint.trim()) }.isSuccess
            SettingsTextField(
                label = "Trusted relay endpoint (advanced)",
                value = if (overrides.endpoint == null) endpoint else runCatching { RelayConfig(overrides.endpoint).endpoint }.getOrDefault("Invalid endpoint override"),
                onValueChange = { endpoint = it },
                enabled = overrides.endpoint == null,
                placeholder = TerminalSettings.DEFAULT.terminalRelayUrl,
                description = "A trusted wss:// origin only, without a path, credentials, query, or fragment. " +
                        "Share links cannot redirect you to another relay."
            )
            if (overrides.endpoint == null && !validEndpoint) {
                Text("Enter a valid wss:// relay origin before saving.", color = Danger, fontSize = 11.sp)
            }
            Button(
                onClick = { onSettingsChange(settings.copy(terminalRelayUrl = endpoint.trim().trimEnd('/'))) },
                enabled = overrides.endpoint == null && validEndpoint && endpoint.trim().trimEnd('/') != settings.terminalRelayUrl,
                colors = ButtonDefaults.buttonColors(backgroundColor = AccentColor, contentColor = TextOnAccent,
                    disabledBackgroundColor = BorderColor, disabledContentColor = TextMuted),
            ) { Text("Save relay endpoint") }
            Text("Changes apply immediately to hosted shares. Relay viewers disconnect when you turn this off " +
                "or change the endpoint. Disconnect and reopen remote connections from the refreshed session list, " +
                "or copy a new share link (including for browsers).", color = TextMuted, fontSize = 11.sp)
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
            SettingsToggle(
                label = "Allow devices signed into my account without approval",
                checked = settings.autoApproveAccountSessions,
                onCheckedChange = { onSettingsChange(settings.copy(autoApproveAccountSessions = it)) },
                description = "Applies to encrypted connections opened through your account's session list. " +
                        "Turn off to require approval for new account devices, including on LAN. " +
                        "Existing connections and valid 24-hour device approvals remain active; " +
                        "copied guest links follow the device-approval setting above."
            )
            val account by ai.rever.bossterm.compose.share.AccountSessionSource.state.collectAsState()
            val signedIn = account is ai.rever.bossterm.compose.auth.BossAccountManager.AccountState.SignedIn
            val accountScope = rememberCoroutineScope()
            val uriHandler = LocalUriHandler.current
            var openingSettings by remember { mutableStateOf(false) }
            var settingsError by remember { mutableStateOf<String?>(null) }
            Button(
                enabled = signedIn && !openingSettings,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AccentColor,
                    contentColor = TextOnAccent,
                    disabledBackgroundColor = BorderColor,
                    disabledContentColor = TextMuted,
                ),
                modifier = Modifier.height(36.dp),
                onClick = {
                    openingSettings = true
                    settingsError = null
                    accountScope.launch {
                        try {
                            uriHandler.openUri(AccountTerminalPreferences.Default.settingsUrl())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            settingsError = "Unable to open account settings. Please try again."
                        } finally {
                            openingSettings = false
                        }
                    }
                }
            ) {
                Text(if (openingSettings) "Opening settings…" else "Account terminal settings", fontSize = 12.sp)
            }
            settingsError?.let { Text(it, color = Danger, fontSize = 12.sp) }
            SettingsToggle(
                label = "Publish live sessions to my BOSS account",
                checked = settings.publishSessionsToAccount,
                onCheckedChange = { onSettingsChange(settings.copy(publishSessionsToAccount = it)) },
                description = (if (signedIn) "" else "${ai.rever.bossterm.compose.share.AccountSessionSource.signInHint} to enable. ") +
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
                        "signed in and publishing, so the live-sessions page always shows this machine. " +
                        "Independent of Enable Session Sharing and of the tab Share/Stop button: those " +
                        "govern your own shares. Off = share tabs by hand as before."
            )
            SettingsToggle(
                label = "Attach my other devices' sessions automatically",
                checked = settings.autoConnectAccountSessions,
                onCheckedChange = { onSettingsChange(settings.copy(autoConnectAccountSessions = it)) },
                description = "Sessions shared by your other signed-in BossTerms appear here as remote tabs " +
                        "without opening Remote Sessions and pressing Connect. A session you disconnect by " +
                        "hand stays disconnected. Off = connect from the Remote Sessions window."
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
