package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.material.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.concurrent.ConcurrentHashMap

private val symbolImages = ConcurrentHashMap<String, ImageBitmap>()

/** Load SF Symbols from the running OS; never bundle or redistribute Apple's artwork. */
@Composable
internal fun MacToolbarIcon(
    fallback: ImageVector,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
    symbol: String = when (fallback.name.substringAfterLast('.')) {
        "Share" -> "square.and.arrow.up"
        "Call" -> "phone"
        "Warning" -> "exclamationmark.triangle"
        "Settings" -> "gearshape"
        "Add" -> "plus"
        "MoreHoriz" -> "ellipsis"
        "VerticalSplit" -> "rectangle.split.2x1"
        "HorizontalSplit" -> "rectangle.split.1x2"
        "QrCode2" -> "qrcode"
        "Cloud" -> "desktopcomputer"
        "Hub" -> "network"
        else -> "sidebar.left"
    }
) {
    if (fallback === McpIcon) {
        Icon(fallback, contentDescription, modifier, tint)
        return
    }
    var bitmap by remember(symbol) { mutableStateOf(symbolImages[symbol]) }
    DisposableEffect(symbol) {
        var active = true
        if (bitmap == null && ShellCustomizationUtils.isMacOS()) {
            MacOSWindowGlass.loadSystemSymbol(symbol) { bytes ->
                val decoded = bytes?.let {
                    runCatching {
                        @Suppress("DEPRECATION")
                        it.inputStream().use { input -> loadImageBitmap(input) }
                    }.getOrNull()
                }
                if (decoded != null) {
                    val cached = symbolImages.putIfAbsent(symbol, decoded) ?: decoded
                    if (active) bitmap = cached
                }
            }
        }
        onDispose { active = false }
    }
    val loaded = bitmap
    if (loaded != null) Icon(loaded, contentDescription, modifier, tint)
    else Icon(fallback, contentDescription, modifier, tint)
}
