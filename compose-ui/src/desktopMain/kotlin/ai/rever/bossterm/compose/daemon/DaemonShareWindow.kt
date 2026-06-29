package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.settings.components.SettingsSection
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

private val Track = Color(0xFF252526)
private val Danger = Color(0xFFE57373)
private val Success = Color(0xFF4CAF50)

/**
 * Daemon-hosted session-sharing window (Phase 2). The thin-client analogue of
 * [ai.rever.bossterm.compose.share.ShareWindow]: the daemon owns the actual share server, so this
 * window only observes [DaemonShareClient.state] and steers it (start/stop/approve/remote-mode) over
 * the attach socket. Styled like ShareWindow (SettingsTheme + [SettingsSection]).
 *
 * Picks the share to display by the chosen scope: SESSION (the focused daemon [focusedSessionId]) or
 * ALL; falls back to the first active share. When no share matches the chosen scope, shows a Start
 * button that asks the daemon to begin one.
 */
@Composable
fun DaemonShareWindow(
    focusedSessionId: String?,
    onDismiss: () -> Unit,
    focusTick: Int = 0,
) {
    val clipboard = LocalClipboardManager.current
    val shareState by DaemonShareClient.state.collectAsState()

    // Scope the user is managing. Default to a single focused session when one is available, else
    // every session — matches how the user most likely arrived (a tab's Share button vs window menu).
    var scope by remember {
        mutableStateOf(
            if (focusedSessionId != null) DaemonAttachProtocol.ShareScopeKind.SESSION
            else DaemonAttachProtocol.ShareScopeKind.ALL
        )
    }

    // The share matching the chosen scope/session; fall back to the first active share so an
    // existing share is always visible even if the scope toggle hasn't been touched.
    val share = remember(shareState, scope, focusedSessionId) {
        shareState.shares.firstOrNull { s ->
            when (scope) {
                DaemonAttachProtocol.ShareScopeKind.SESSION ->
                    s.scope == DaemonAttachProtocol.ShareScopeKind.SESSION && s.sessionId == focusedSessionId
                else -> s.scope == DaemonAttachProtocol.ShareScopeKind.ALL
            }
        } ?: shareState.shares.firstOrNull()
    }
    // Approvals waiting on the currently-shown share (the daemon may host several at once).
    val pending = remember(shareState, share) {
        share?.let { sh -> shareState.pending.filter { it.token == sh.token } } ?: emptyList()
    }

    Window(
        onCloseRequest = onDismiss,
        title = "BossTerm — Share",
        resizable = false,
        state = rememberWindowState(size = DpSize(600.dp, 680.dp))
    ) {
        // Raise an already-open window when the Share button is clicked again (focusTick changes).
        LaunchedEffect(focusTick) {
            window.toFront()
            window.requestFocus()
        }
        Surface(color = BackgroundColor, modifier = Modifier.fillMaxSize()) {
          Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                Text("Share session", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "This share keeps running in the background after you close BossTerm. " +
                        "Open a link on another device to watch live; the control link also lets it type.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(20.dp))

                ScopeSection(scope, focusedSessionId) { scope = it }
                Spacer(Modifier.height(20.dp))

                if (share == null) {
                    StartSection(scope, focusedSessionId)
                } else {
                    ShareDetails(share, pending, clipboard)
                }
            }
            // Pinned footer — always visible without scrolling.
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (share != null) {
                    TextButton(
                        onClick = { DaemonShareClient.stopShare(share.token) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Danger)
                    ) { Text("Stop sharing") }
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
                ) { Text("Close") }
            }
          }
        }
    }
}

/** Scope picker: every daemon session as tabs vs the single focused session. */
@Composable
private fun ScopeSection(scope: String, focusedSessionId: String?, onScopeChange: (String) -> Unit) {
    SettingsSection("Scope") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val canSession = focusedSessionId != null
            SegPicker(
                listOf("All sessions", "This session"),
                selectedIndex = if (scope == DaemonAttachProtocol.ShareScopeKind.SESSION) 1 else 0,
            ) { i ->
                // "This session" needs a focused session id; ignore the click when there isn't one.
                val target = if (i == 1) {
                    if (canSession) DaemonAttachProtocol.ShareScopeKind.SESSION else return@SegPicker
                } else DaemonAttachProtocol.ShareScopeKind.ALL
                onScopeChange(target)
            }
            Text(
                if (scope == DaemonAttachProtocol.ShareScopeKind.SESSION)
                    "Sharing just the focused session."
                else "Sharing every session — viewers switch between them as tabs.",
                color = TextMuted, fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Shown when no share matches the chosen scope: a button to ask the daemon to start one. */
@Composable
private fun StartSection(scope: String, focusedSessionId: String?) {
    SettingsSection("Not sharing yet") {
        Text(
            "No active share for this scope. Start one — it keeps serving the viewer even after you close BossTerm.",
            color = TextSecondary, fontSize = 12.sp
        )
        Button(
            onClick = {
                val sessionId = if (scope == DaemonAttachProtocol.ShareScopeKind.SESSION) focusedSessionId else null
                DaemonShareClient.startShare(scope, sessionId, null)
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
        ) { Text("Start sharing") }
    }
}

/** The QR + links + viewers + approvals + remote-access body for an active share. */
@Composable
private fun ShareDetails(
    share: DaemonAttachProtocol.ShareView,
    pending: List<DaemonAttachProtocol.PendingApproval>,
    clipboard: ClipboardManager,
) {
    // Devices waiting for approval surface first so the host acts on them.
    if (pending.isNotEmpty()) {
        SettingsSection("Pending requests") {
            pending.forEach { req -> PendingRow(share.token, req) }
        }
        Spacer(Modifier.height(20.dp))
    }

    var controlQr by remember { mutableStateOf(false) }
    val qrUrl = if (controlQr) share.controlUrl else share.url
    val qr = remember(qrUrl) { qrImageBitmap(qrUrl) }
    SettingsSection("QR code") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = if (controlQr) "Control link QR" else "View link QR",
                    modifier = Modifier.size(210.dp).background(Color.White).padding(10.dp)
                )
            }
            SegToggle("View", "Control", rightSelected = controlQr) { controlQr = it }
            Text(
                if (controlQr) "QR encodes the Control link — scanning grants typing access."
                else "QR encodes the View link (read-only).",
                color = if (controlQr) Danger else TextMuted, fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    Spacer(Modifier.height(20.dp))

    SettingsSection("Links") {
        share.e2eCode?.let { code ->
            Text(
                "🔒 End-to-end encrypted · code $code — the relay can't read this session. " +
                    "The same code shows on the viewer; matching codes confirm the key end-to-end.",
                color = AccentColor, fontSize = 11.sp
            )
        }
        LinkRow("View (read-only)", share.url, clipboard)
        LinkRow("Control (can type)", share.controlUrl, clipboard)
        if (!share.secure) {
            Text(
                "⚠ Not encrypted — this link is plaintext. Use https (Tailscale Funnel / a TLS tunnel) beyond your trusted LAN.",
                color = Danger, fontSize = 11.sp
            )
        }
        Text(
            "${share.viewers} viewer${if (share.viewers == 1) "" else "s"} connected.",
            color = TextMuted, fontSize = 11.sp
        )
    }
    Spacer(Modifier.height(20.dp))

    RemoteAccessSection(share)
}

/** One pending-viewer row: Approve (view), Approve (control), Deny. */
@Composable
private fun PendingRow(token: String, req: DaemonAttachProtocol.PendingApproval) {
    Surface(color = SurfaceColor, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(req.name ?: "A viewer", color = TextPrimary, fontSize = 12.sp)
                Text(
                    if (req.control) "Requesting control (can type)" else "Requesting view (read-only)",
                    color = if (req.control) Danger else TextMuted, fontSize = 11.sp
                )
            }
            TextButton(
                onClick = { DaemonShareClient.approve(token, req.clientId, false) },
                colors = ButtonDefaults.textButtonColors(contentColor = AccentColor)
            ) { Text("View", fontSize = 12.sp) }
            TextButton(
                onClick = { DaemonShareClient.approve(token, req.clientId, true) },
                colors = ButtonDefaults.textButtonColors(contentColor = AccentColor)
            ) { Text("Control", fontSize = 12.sp) }
            TextButton(
                onClick = { DaemonShareClient.deny(token, req.clientId) },
                colors = ButtonDefaults.textButtonColors(contentColor = Danger)
            ) { Text("Deny", fontSize = 12.sp) }
        }
    }
}

/** Remote-access mode picker (Off/Serve/Funnel/Cloudflare) + the daemon's live tunnel status. */
@Composable
private fun RemoteAccessSection(share: DaemonAttachProtocol.ShareView) {
    SettingsSection("Remote access") {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Track)
                .border(1.dp, BorderColor, RoundedCornerShape(6.dp)).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // The daemon mints a fresh link on a mode change, so just send the new mode — the next
            // ShareState push reflects it. Re-selecting the current mode is a no-op.
            Seg("Off", share.remoteMode == "off") { if (share.remoteMode != "off") DaemonShareClient.setRemoteMode(share.token, "off") }
            Seg("Serve", share.remoteMode == "serve") { if (share.remoteMode != "serve") DaemonShareClient.setRemoteMode(share.token, "serve") }
            Seg("Funnel", share.remoteMode == "funnel") { if (share.remoteMode != "funnel") DaemonShareClient.setRemoteMode(share.token, "funnel") }
            Seg("Cloudflare", share.remoteMode == "cloudflare") { if (share.remoteMode != "cloudflare") DaemonShareClient.setRemoteMode(share.token, "cloudflare") }
        }
        Text(
            when (share.remoteMode) {
                "serve" -> "Tailscale Serve = your tailnet only (the viewing device must be signed into your Tailscale)."
                "funnel" -> "Tailscale Funnel = public internet (anyone with the link; no Tailscale on their end)."
                "cloudflare" -> "Cloudflare = instant public link, no account, no config. Link changes each session (best-effort)."
                else -> "Off = LAN/loopback only. Pick a provider to reach other networks."
            },
            color = TextMuted, fontSize = 11.sp
        )
        Text(
            text = remoteStatusLabel(share),
            color = when (share.remoteStatus) {
                "active" -> Success
                "fellback" -> Danger
                else -> TextMuted
            },
            fontSize = 11.sp
        )
        // Mint a fresh public link in place — same share server, viewers, and E2E secret. The daemon
        // tears down + re-establishes the current provider (setRemoteMode with the same mode). Useful
        // when a Cloudflare quick tunnel goes stale (its URL changes); for Tailscale serve/funnel the
        // URL is stable, so this just re-establishes the mapping. Only shown while a provider is on.
        if (share.remoteMode != "off") {
            val refreshing = share.remoteStatus == "starting" || share.remoteStatus == "installing" ||
                share.remoteStatus == "verifying" || share.remoteStatus == "retrying"
            TextButton(
                onClick = { if (!refreshing) DaemonShareClient.setRemoteMode(share.token, share.remoteMode) },
                enabled = !refreshing,
                colors = ButtonDefaults.textButtonColors(contentColor = AccentColor),
            ) { Text(if (refreshing) "Refreshing…" else "↻ Refresh link", fontSize = 12.sp) }
        }
    }
}

/** Human-readable line for the daemon's remoteStatus string (mirrors ShareWindow's status text). */
private fun remoteStatusLabel(share: DaemonAttachProtocol.ShareView): String = when (share.remoteStatus) {
    "off" -> "LAN only"
    "starting" -> "${share.remoteMode} · starting…"
    "installing" -> "${share.remoteMode} · installing…"
    "verifying" -> "${share.remoteMode} · verifying link…"
    "retrying" -> "${share.remoteMode} · retrying (${share.remoteAttempt}/${share.remoteMaxAttempts})…"
    "active" -> "${share.remoteMode} · active"
    "fellback" -> "${share.remoteMode} · unreachable — using LAN link"
    else -> share.remoteStatus
}

/** Two-option segmented toggle in BossTerm's compact style (no Material min-size). */
@Composable
private fun SegToggle(left: String, right: String, rightSelected: Boolean, onSelect: (Boolean) -> Unit) {
    SegPicker(listOf(left, right), if (rightSelected) 1 else 0) { onSelect(it == 1) }
}

/** N-option segmented picker — same compact style as [SegToggle]. */
@Composable
private fun SegPicker(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Track)
            .border(1.dp, BorderColor, RoundedCornerShape(6.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            Seg(label, i == selectedIndex) { onSelect(i) }
        }
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (selected) AccentColor else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun LinkRow(label: String, url: String, clipboard: ClipboardManager) {
    Surface(color = SurfaceColor, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontSize = 11.sp, color = TextMuted)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(url, fontSize = 12.sp, maxLines = 1, color = TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(url)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentColor)
                ) { Text("Copy") }
            }
        }
    }
}

/** Render [text] as a crisp QR code into a Compose [ImageBitmap], or null on failure. */
private fun qrImageBitmap(text: String, target: Int = 512): ImageBitmap? = runCatching {
    // Asking ZXing for a fixed pixel size (e.g. 256) makes it scale the code by an integer module
    // multiple and center it — the leftover slack is baked in as a fat white border. Instead pass
    // size 1 so it emits a TIGHT 1px-per-module matrix (just the MARGIN=1 single-module quiet
    // zone, no slack), then upscale by an integer factor so module edges stay sharp.
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
    val n = matrix.width // module grid incl. the 1-module quiet zone each side (square)
    val scale = (target / n).coerceAtLeast(1)
    val px = n * scale
    val image = BufferedImage(px, px, BufferedImage.TYPE_INT_RGB)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (my in 0 until n) for (mx in 0 until n) {
        val color = if (matrix[mx, my]) black else white
        for (dy in 0 until scale) for (dx in 0 until scale) image.setRGB(mx * scale + dx, my * scale + dy, color)
    }
    image.toComposeImageBitmap()
}.getOrNull()
