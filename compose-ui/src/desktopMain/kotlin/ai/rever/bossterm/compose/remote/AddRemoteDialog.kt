package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.settings.components.SettingsSection
import ai.rever.bossterm.compose.settings.components.SettingsTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

private val Green = Color(0xFF4CAF50)
private val Amber = Color(0xFFE0A030)
private val Danger = Color(0xFFE57373)

/**
 * "Remote sessions" window — a top-level OS window styled like
 * [ai.rever.bossterm.compose.share.ShareWindow] (SettingsTheme colors + SettingsSection
 * headers, pinned footer). Paste another BossTerm's share link to mirror its tabs into
 * this window, and manage the live connections (status + view-only/control + disconnect).
 */
@Composable
fun AddRemoteDialog(manager: RemoteSessionManager, onDismiss: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var shareBack by remember { mutableStateOf(false) }
    val deviceName = remember {
        (System.getProperty("user.name")?.takeIf { it.isNotBlank() }?.let { "$it (BossTerm)" }) ?: "BossTerm"
    }
    fun connect() {
        val l = link.trim()
        if (l.isBlank()) return
        if (manager.connect(l, deviceName, shareBack) == null) {
            // connect() refuses our own share links — mirroring a session into itself loops.
            error = "That's this BossTerm's own share link — a session can't mirror itself."
        } else {
            link = ""
            error = null
        }
    }
    Window(
        onCloseRequest = onDismiss,
        title = "BossTerm — Remote Sessions",
        resizable = false,
        state = rememberWindowState(size = DpSize(560.dp, 520.dp))
    ) {
        // Raise the window if "Add remote" is clicked while it's already open.
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
        }
        Surface(color = BackgroundColor, modifier = Modifier.fillMaxSize()) {
          Column(modifier = Modifier.fillMaxSize()) {
            // Scrollable content fills the space above the pinned footer.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                Text("Remote sessions", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Paste a BossTerm share link to mirror its tabs here. They appear as tabs " +
                        "marked in cyan; with control granted you can type into them.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(20.dp))

                SettingsSection("Add remote") {
                    SettingsTextField(
                        label = "Share link",
                        value = link,
                        onValueChange = { link = it; error = null },
                        placeholder = "https://….trycloudflare.com/?t=…",
                    )
                    error?.let { Text(it, color = Danger, fontSize = 11.sp) }
                    // Two-way: once the host grants control, offer it this window's own share
                    // link so it mirrors our tabs back (starts a window share here if needed).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = shareBack,
                            onCheckedChange = { shareBack = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentColor, uncheckedColor = TextMuted)
                        )
                        Column {
                            Text("Two-way: also share my tabs with the host", color = TextPrimary, fontSize = 12.sp)
                            Text(
                                "Shares this window and offers the host the link once it grants control.",
                                color = TextMuted, fontSize = 11.sp
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { connect() },
                            enabled = link.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
                        ) { Text("Connect") }
                    }
                }

                if (manager.sessions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SettingsSection("Connected") {
                        manager.sessions.forEach { session ->
                            RemoteSessionRow(session, onDisconnect = { manager.disconnect(session) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // Pinned footer — always visible without scrolling.
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)) {
                    Text("Close")
                }
            }
          }
        }
    }
}

/**
 * Confirmation shown when a view-only action needs control (e.g. the sidebar's split/new-tab
 * icons on a view-only group): explains the state and asks before sending the host a control
 * request — the host then sees its usual approval toast.
 */
@Composable
fun RequestControlPrompt(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundColor,
        title = { Text("View-only session", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "You're viewing this session read-only — splits, new tabs, and typing need " +
                    "control. Ask for it? When the session is reached through another host, " +
                    "each host is asked in turn and its owner approves.",
                color = TextSecondary, fontSize = 12.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
            ) { Text("Request Control") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

/**
 * One-time offer when a remote mirror tab is first viewed and the host's grid doesn't render
 * 1:1 in this window (the native counterpart of the web viewer's "fit host to this phone?"
 * confirm): fit OUR window to the host's grid (purely local), or — control only — resize the
 * HOST's window to match ours instead.
 */
@Composable
fun RemoteFitPrompt(
    hostName: String,
    onFitMyWindow: () -> Unit,
    /** With control: resizes the host. View-only: the caller routes to a control request. */
    onFitHost: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundColor,
        title = { Text("Match window sizes?", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            // Both directions as stacked full-width buttons (a side-by-side row overflows
            // the dialog and can hide one option).
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "This tab mirrors $hostName — its terminal grid doesn't fit this window 1:1.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Button(
                    onClick = onFitMyWindow,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
                ) { Text("Fit my window to host") }
                Button(
                    onClick = onFitHost,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A), contentColor = TextPrimary)
                ) { Text("Fit host to my window") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Not now", color = TextSecondary) } },
    )
}

/**
 * Shown when a remote session drops after exhausting its reconnect budget (the native
 * counterpart of the web viewer's disconnect overlay): Reconnect retries with a fresh
 * budget; Disconnect removes the session + its mirror tabs; dismissing keeps the frozen
 * mirror without re-prompting for this failure.
 */
@Composable
fun RemoteDisconnectedDialog(
    name: String,
    message: String?,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundColor,
        title = { Text("Remote disconnected", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "Lost the connection to $name." +
                    (message?.let { "\n$it" } ?: "") +
                    "\nIts tabs stay frozen until it reconnects.",
                color = TextSecondary, fontSize = 12.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onReconnect,
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
            ) { Text("Reconnect") }
        },
        dismissButton = {
            TextButton(onClick = onDisconnect) { Text("Disconnect", color = Danger) }
        },
    )
}

@Composable
private fun RemoteSessionRow(session: RemoteSession, onDisconnect: () -> Unit) {
    val status by session.status.collectAsState()
    Surface(color = SurfaceColor, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.customName.value ?: session.hostName.value ?: hostOf(session.link),
                    color = TextPrimary, fontSize = 13.sp, maxLines = 1
                )
                Text(statusLabel(status), color = statusColor(status), fontSize = 11.sp, maxLines = 1)
            }
            val s = status
            if (s is RemoteStatus.Connected && !s.canControl) {
                TextButton(onClick = { session.requestControl() }) { Text("Request control", color = AccentColor) }
            }
            TextButton(onClick = onDisconnect) { Text("Disconnect", color = Danger) }
        }
    }
}

private fun statusLabel(s: RemoteStatus): String = when (s) {
    RemoteStatus.Connecting -> "connecting…"
    RemoteStatus.Pending -> "waiting for host approval…"
    is RemoteStatus.Connected -> if (s.canControl) "connected · control" else "connected · view only"
    is RemoteStatus.Denied -> "denied" + (s.reason?.let { ": $it" } ?: "")
    is RemoteStatus.Failed -> "disconnected: ${s.message}"
    RemoteStatus.Closed -> "closed"
}

private fun statusColor(s: RemoteStatus): Color = when (s) {
    is RemoteStatus.Connected -> Green
    RemoteStatus.Pending, RemoteStatus.Connecting -> Amber
    is RemoteStatus.Denied, is RemoteStatus.Failed -> Danger
    RemoteStatus.Closed -> Color(0xFF888888)
}

private fun hostOf(link: String): String = runCatching {
    java.net.URI(link).host ?: link
}.getOrDefault(link)
