package ai.rever.bossterm.compose.share

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

private val BgColor = Color(0xFF1E1E1E)
private val PanelColor = Color(0xFF2B2B2B)
private val TextColor = Color(0xFFCCCCCC)
private val DimColor = Color(0xFF8A8A8A)
private val Accent = Color(0xFF4A90E2)
private val Danger = Color(0xFFE57373)

/**
 * Session-sharing window (issue #276) — a real top-level OS window, like
 * [ai.rever.bossterm.compose.settings.SettingsWindow], rather than an in-canvas
 * Compose dialog. Shows the QR code + view/control links + reach hint, themed to
 * match BossTerm. Render it conditionally: `info?.let { ShareWindow(it, …) }`.
 */
@Composable
fun ShareWindow(
    info: SessionShareManager.ShareInfo,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val loopbackOnly = info.url.contains("://127.0.0.1") || info.url.contains("://localhost")
    // Which link the QR encodes — toggled by the View/Control switch below it.
    var controlQr by remember { mutableStateOf(false) }
    val qrUrl = if (controlQr) info.controlUrl else info.url
    val qr = remember(qrUrl) { qrImageBitmap(qrUrl) }

    val isWindow = info.scope == ShareScope.WINDOW
    Window(
        onCloseRequest = onDismiss,
        title = if (isWindow) "BossTerm — Share Window" else "BossTerm — Share Tab",
        resizable = false,
        state = rememberWindowState(size = DpSize(440.dp, 660.dp))
    ) {
        Surface(color = BgColor, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    if (isWindow) "Sharing this window" else "Sharing this tab",
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                // Scope chip + what's included, so it's clear what the viewer will see.
                Surface(color = if (isWindow) Color(0xFF1E3A1E) else Color(0xFF233047),
                        shape = RoundedCornerShape(10.dp)) {
                    Text(
                        if (isWindow) "● Window — all tabs (switchable) + splits"
                        else "● Tab — this tab and its splits",
                        color = if (isWindow) Color(0xFFB9F6CA) else Color(0xFFAFCBFF),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Scan from another device. Toggle which link the QR encodes:",
                    color = TextColor, fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))

                // View / Control segmented toggle for the QR.
                QrModeToggle(
                    controlSelected = controlQr,
                    onSelect = { controlQr = it },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(12.dp))

                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = if (controlQr) "Control link QR code" else "View link QR code",
                        modifier = Modifier
                            .size(220.dp)
                            .align(Alignment.CenterHorizontally)
                            .background(Color.White)
                            .padding(10.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (controlQr) "QR: Control link — scanning grants typing access"
                               else "QR: View link — read-only",
                        color = if (controlQr) Danger else DimColor,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(16.dp))
                }

                LinkRow("View (read-only)", info.url, clipboard)
                Spacer(Modifier.height(10.dp))
                LinkRow("Control (can type)", info.controlUrl, clipboard)
                Spacer(Modifier.height(14.dp))

                Text(
                    if (loopbackOnly)
                        "Reachable only on this machine. To open it from your phone, set the bind scope to LAN in Settings → Session Sharing, or turn on Tailscale."
                    else
                        "Reachable from devices that can route to this host. For the public internet, use Tailscale Funnel or a TLS tunnel.",
                    color = DimColor, fontSize = 11.sp
                )
                if (!info.secure) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ Not encrypted — this link is plaintext over the network. Use https " +
                            "(Tailscale Funnel or a TLS tunnel) for anything beyond your trusted LAN.",
                        color = Danger, fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onStop,
                        colors = ButtonDefaults.textButtonColors(contentColor = Danger)
                    ) { Text("Stop sharing") }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
                    ) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, clipboard: ClipboardManager) {
    Surface(color = PanelColor, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontSize = 11.sp, color = DimColor)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(url, fontSize = 12.sp, maxLines = 1, color = Color(0xFFDDDDDD))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(url)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Accent)
                ) { Text("Copy") }
            }
        }
    }
}

/**
 * Segmented View/Control toggle controlling which link the QR encodes. Built with
 * [clickable] rather than a clickable [Surface] so it isn't forced to Material3's
 * 48dp minimum touch size — keeping the compact padding BossTerm uses elsewhere
 * (e.g. the search bar's toggles).
 */
@Composable
private fun QrModeToggle(controlSelected: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF252526))
            .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Seg("View", selected = !controlSelected) { onSelect(false) }
        Seg("Control", selected = controlSelected) { onSelect(true) }
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else Color(0xFF808080), fontSize = 12.sp)
    }
}

/** Render [text] as a QR code into a Compose [ImageBitmap], or null on failure. */
private fun qrImageBitmap(text: String, size: Int = 256): ImageBitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until size) {
        for (x in 0 until size) {
            image.setRGB(x, y, if (matrix[x, y]) black else white)
        }
    }
    image.toComposeImageBitmap()
}.getOrNull()
