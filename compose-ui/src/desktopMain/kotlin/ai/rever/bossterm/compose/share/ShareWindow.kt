package ai.rever.bossterm.compose.share

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

/**
 * Session-sharing window (issue #276) — a top-level OS window styled like
 * [ai.rever.bossterm.compose.settings.SettingsWindow] (SettingsTheme colors +
 * SettingsSection headers). Sections: Scope (Tab/Window toggle), QR (View/Control
 * toggle + code), Links (view/control), and Stop/Close actions.
 */
@Composable
fun ShareWindow(
    info: SessionShareManager.ShareInfo,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onScopeChange: (ShareScope) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val isWindow = info.scope == ShareScope.WINDOW
    val loopbackOnly = info.url.contains("://127.0.0.1") || info.url.contains("://localhost")
    var controlQr by remember { mutableStateOf(false) }
    val qrUrl = if (controlQr) info.controlUrl else info.url
    val qr = remember(qrUrl) { qrImageBitmap(qrUrl) }

    Window(
        onCloseRequest = onDismiss,
        title = if (isWindow) "BossTerm — Share Window" else "BossTerm — Share Tab",
        resizable = false,
        state = rememberWindowState(size = DpSize(460.dp, 720.dp))
    ) {
        Surface(color = BackgroundColor, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                Text("Share session", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open a link on another device to watch live; the control link also lets it type.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(20.dp))

                SettingsSection("Scope") {
                    SegToggle("Tab", "Window", rightSelected = isWindow) { win ->
                        onScopeChange(if (win) ShareScope.WINDOW else ShareScope.TAB)
                    }
                    Text(
                        if (isWindow) "Sharing all tabs in this window — switchable in the viewer, with splits."
                        else "Sharing this tab and its splits.",
                        color = TextMuted, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(20.dp))

                SettingsSection("QR code") {
                    SegToggle("View", "Control", rightSelected = controlQr) { controlQr = it }
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = if (controlQr) "Control link QR" else "View link QR",
                            modifier = Modifier.size(210.dp).align(Alignment.CenterHorizontally)
                                .background(Color.White).padding(10.dp)
                        )
                    }
                    Text(
                        if (controlQr) "QR encodes the Control link — scanning grants typing access."
                        else "QR encodes the View link (read-only).",
                        color = if (controlQr) Danger else TextMuted, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(20.dp))

                SettingsSection("Links") {
                    LinkRow("View (read-only)", info.url, clipboard)
                    LinkRow("Control (can type)", info.controlUrl, clipboard)
                    Text(
                        if (loopbackOnly)
                            "Reachable only on this machine. Set the bind scope to LAN in Settings → Session Sharing, or enable Tailscale, to reach other devices."
                        else
                            "Reachable from devices that can route to this host. For the public internet, use Tailscale Funnel or a TLS tunnel.",
                        color = TextMuted, fontSize = 11.sp
                    )
                    if (!info.secure) {
                        Text(
                            "⚠ Not encrypted — this link is plaintext. Use https (Tailscale Funnel / a TLS tunnel) beyond your trusted LAN.",
                            color = Danger, fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onStop, colors = ButtonDefaults.textButtonColors(contentColor = Danger)) {
                        Text("Stop sharing")
                    }
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/** Two-option segmented toggle in BossTerm's compact style (no Material min-size). */
@Composable
private fun SegToggle(left: String, right: String, rightSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Track)
            .border(1.dp, BorderColor, RoundedCornerShape(6.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Seg(left, !rightSelected) { onSelect(false) }
        Seg(right, rightSelected) { onSelect(true) }
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

/** Render [text] as a QR code into a Compose [ImageBitmap], or null on failure. */
private fun qrImageBitmap(text: String, size: Int = 256): ImageBitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, if (matrix[x, y]) black else white)
    image.toComposeImageBitmap()
}.getOrNull()
