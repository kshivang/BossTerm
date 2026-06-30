package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.daemon.DaemonClient
import ai.rever.bossterm.compose.daemon.DaemonProtocol
import ai.rever.bossterm.compose.daemon.LoginServiceManager
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.SettingsSection
import ai.rever.bossterm.compose.settings.components.SettingsToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session-daemon settings: opt into the tmux-style background daemon, install it as a login
 * service, and see/control its status. The daemon owns terminal sessions + MCP (+ later sharing)
 * so they survive the GUI closing. [TerminalSettings.daemonEnabled] is read at app startup, so
 * toggling it needs a restart — surfaced in the note.
 */
@Composable
fun DaemonSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSection(title = "Session Daemon") {
            SettingsToggle(
                label = "Run sessions in a background daemon",
                checked = settings.daemonEnabled,
                description = "Terminal sessions, MCP, and sharing live in a long-lived process that " +
                    "outlives this window. Enabling this also installs a start-at-login service (the " +
                    "toggle below turns that off). Takes effect after restarting BossTerm.",
                onCheckedChange = { on ->
                    if (on) {
                        // Enabling the daemon also schedules it at login by default (it's meant to be
                        // always-available) and installs the login service now. The separate toggle
                        // below can turn that back off without disabling the daemon. Persistence is the
                        // SettingsWindow debounce's job — a synchronous onSettingsSave here would save
                        // the pre-change pendingSettings (merge(old, old)).
                        onSettingsChange(settings.copy(daemonEnabled = true, startDaemonAtLogin = true))
                        scope.launch(Dispatchers.IO) { runCatching { LoginServiceManager.install() } }
                    } else {
                        // Disabling the daemon must also tear down the at-login service + clear its
                        // flag, otherwise a daemon keeps spawning at login that the GUI no longer
                        // connects to.
                        onSettingsChange(settings.copy(daemonEnabled = false, startDaemonAtLogin = false))
                        scope.launch(Dispatchers.IO) { runCatching { LoginServiceManager.uninstall() } }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            SettingsToggle(
                label = "Start daemon at login",
                checked = settings.startDaemonAtLogin,
                enabled = settings.daemonEnabled,
                description = "Install a per-OS login service so the daemon is always available — " +
                    "even before BossTerm is first opened, or after a reboot.",
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.copy(startDaemonAtLogin = enabled))
                    // Install/uninstall shells out — keep it off the UI thread.
                    scope.launch(Dispatchers.IO) {
                        val r = if (enabled) LoginServiceManager.install() else LoginServiceManager.uninstall()
                        withContext(Dispatchers.Main) {
                            status = r.fold(
                                onSuccess = { if (enabled) "Login service installed." else "Login service removed." },
                                onFailure = { "Login service ${if (enabled) "install" else "remove"} failed: ${it.message}" },
                            )
                        }
                    }
                },
            )
        }

        SettingsSection(title = "Status") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val text = readStatus()
                            withContext(Dispatchers.Main) { status = text; busy = false }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(backgroundColor = AccentColor, contentColor = Color.White),
                ) { Text("Refresh status", fontSize = 13.sp) }

                Button(
                    onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val client = DaemonClient()
                            val ep = client.ensureConnected(spawnIfAbsent = false)
                            val msg = if (ep == null) "No daemon running."
                            else (client.request(DaemonProtocol.SHUTDOWN, "{\"killSessions\":true}")?.let { "Daemon stopped." }
                                ?: "Quit request failed.")
                            withContext(Dispatchers.Main) { status = msg; busy = false }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6B2B2B), contentColor = Color.White),
                ) { Text("Quit daemon", fontSize = 13.sp) }
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(status, color = TextPrimary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The daemon never stops when you close the GUI — only via Quit daemon or OS logout.",
                color = TextMuted, fontSize = 11.sp,
            )
        }
    }
}

/** Connect (without spawning) and format the daemon's STATUS, or a "not running" note. */
private fun readStatus(): String {
    val client = DaemonClient()
    client.ensureConnected(spawnIfAbsent = false) ?: return "No daemon running (it starts when enabled and BossTerm launches)."
    val resp = client.request(DaemonProtocol.STATUS) ?: return "Daemon unreachable."
    val payload = resp.removePrefix("OK ").trim()
    val st = runCatching {
        DaemonProtocol.json.decodeFromString(DaemonProtocol.Status.serializer(), payload)
    }.getOrNull() ?: return "Daemon status unavailable."
    val uptimeS = st.uptimeMs / 1000
    return buildString {
        append("Running — pid ${st.pid}, v${st.version}\n")
        append("Sessions: ${st.sessionCount}\n")
        append("MCP port: ${st.mcpPort ?: "off"}   Attach port: ${st.attachPort ?: "off"}\n")
        append("Uptime: ${uptimeS}s")
    }
}
