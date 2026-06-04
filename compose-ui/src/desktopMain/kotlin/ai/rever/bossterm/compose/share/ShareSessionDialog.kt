package ai.rever.bossterm.compose.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

/**
 * Dialog shown after a tab starts sharing (issue #276). Shows a QR code + the
 * read-only view link (scan/open from another device) and the control link
 * (grants write access — share deliberately), a reach hint based on the URL,
 * and a Stop Sharing action.
 */
@Composable
fun ShareSessionDialog(
    info: SessionShareManager.ShareInfo,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val loopbackOnly = info.url.contains("://127.0.0.1") || info.url.contains("://localhost")
    val qr = remember(info.url) { qrImageBitmap(info.url) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sharing this tab") },
        text = {
            Column {
                Text("Scan or open this on another device to watch the session live:", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "Share link QR code",
                        modifier = Modifier
                            .size(200.dp)
                            .background(Color.White)
                            .padding(8.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                }
                LinkRow("View (read-only)", info.url, clipboard)
                Spacer(Modifier.height(8.dp))
                LinkRow("Control (can type)", info.controlUrl, clipboard)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (loopbackOnly)
                        "Reachable only on this machine. To open it from your phone, set the bind scope to LAN in Settings → Session Sharing, or turn on Tailscale."
                    else
                        "Reachable from devices that can route to this host. For the public internet, use Tailscale Funnel or a TLS tunnel.",
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onStop) { Text("Stop sharing") }
        }
    )
}

@Composable
private fun LinkRow(label: String, url: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Column {
        Text(label, fontSize = 11.sp, color = Color(0xFF888888))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(url, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("Copy") }
        }
    }
}

/** Render [text] as a QR code into a Compose [ImageBitmap], or null on failure. */
private fun qrImageBitmap(text: String, size: Int = 240): ImageBitmap? = runCatching {
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
