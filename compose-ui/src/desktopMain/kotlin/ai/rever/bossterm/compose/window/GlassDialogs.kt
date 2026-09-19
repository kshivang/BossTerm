package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.SettingsTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
private fun dialogColor(color: Color, installed: Boolean, minimumOpacity: Float = 0f): Color {
    val settings by SettingsManager.instance.settings.collectAsState()
    return if (installed && settings.isLiquidGlassTheme)
        color.copy(alpha = settings.windowGlassOpacity.coerceIn(minimumOpacity.coerceIn(0f, 1f), 1f)) else color.copy(alpha = 1f)
}

/** Preserve Material's sizing, focus, dismissal and keyboard behavior; style only its surface. */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = SettingsTheme.BackgroundColor,
    contentColor: Color = SettingsTheme.TextPrimary,
    properties: DialogProperties = DialogProperties()
) {
    var installed by remember { mutableStateOf(false) }
    androidx.compose.material.AlertDialog(
        onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        modifier = modifier, dismissButton = dismissButton, title = title,
        text = { InlineDialogGlassEffect { installed = it }; text?.invoke() },
        shape = shape, backgroundColor = dialogColor(backgroundColor, installed), contentColor = contentColor,
        properties = properties
    )
}

@Composable
fun GlassAlertDialog3(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = SettingsTheme.BackgroundColor,
    properties: DialogProperties = DialogProperties(),
    /** A readable surface floor for dense dialogs; foreground controls remain fully opaque. */
    minimumSurfaceOpacity: Float = 0f,
) {
    var installed by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        modifier = modifier, dismissButton = dismissButton, title = title,
        text = { InlineDialogGlassEffect { installed = it }; text?.invoke() },
        containerColor = dialogColor(containerColor, installed, minimumSurfaceOpacity), properties = properties
    )
}

@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
        InlineDialogGlassContent(content)
    }
}
