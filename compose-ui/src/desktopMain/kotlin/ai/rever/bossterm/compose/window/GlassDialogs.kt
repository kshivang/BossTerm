package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.SettingsTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties

/** Modal cards must not inherit the terminal's transparency or default Material palette. */
@Composable
private fun DialogChrome(content: @Composable () -> Unit) {
    val palette = ai.rever.bossterm.compose.settings.theme.BossUiTheme.current
    val colors3 = (if (palette.isDark) androidx.compose.material3.darkColorScheme()
        else androidx.compose.material3.lightColorScheme()).copy(
        primary = SettingsTheme.AccentColor, onPrimary = SettingsTheme.TextOnAccent,
        secondary = SettingsTheme.AccentColor, onSecondary = SettingsTheme.TextOnAccent,
        surface = SettingsTheme.SurfaceColor.copy(alpha = 1f), onSurface = SettingsTheme.TextPrimary,
        onSurfaceVariant = SettingsTheme.TextSecondary,
        background = SettingsTheme.BackgroundColor.copy(alpha = 1f), onBackground = SettingsTheme.TextPrimary,
        outline = SettingsTheme.BorderColor, error = SettingsTheme.Danger,
    )
    val typography3 = androidx.compose.material3.MaterialTheme.typography.copy(
        headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    )
    val colors2 = (if (palette.isDark) androidx.compose.material.darkColors()
        else androidx.compose.material.lightColors()).copy(
        primary = SettingsTheme.AccentColor, onPrimary = SettingsTheme.TextOnAccent,
        secondary = SettingsTheme.AccentColor, onSecondary = SettingsTheme.TextOnAccent,
        surface = SettingsTheme.SurfaceColor.copy(alpha = 1f), onSurface = SettingsTheme.TextPrimary,
        background = SettingsTheme.BackgroundColor.copy(alpha = 1f), onBackground = SettingsTheme.TextPrimary,
        error = SettingsTheme.Danger,
    )
    val typography2 = androidx.compose.material.MaterialTheme.typography.copy(
        h6 = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        body1 = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
        body2 = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
        button = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
    androidx.compose.material.MaterialTheme(colors = colors2, typography = typography2,
        shapes = androidx.compose.material.MaterialTheme.shapes.copy(small = RoundedCornerShape(8.dp))) {
        androidx.compose.material3.MaterialTheme(colorScheme = colors3, typography = typography3,
            shapes = androidx.compose.material3.MaterialTheme.shapes.copy(
                small = RoundedCornerShape(8.dp),
            ), content = content)
    }
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
    shape: Shape = RoundedCornerShape(16.dp),
    backgroundColor: Color = SettingsTheme.BackgroundColor,
    contentColor: Color = SettingsTheme.TextPrimary,
    properties: DialogProperties = DialogProperties()
) {
    DialogChrome {
        androidx.compose.material.AlertDialog(
            onDismissRequest = onDismissRequest, confirmButton = confirmButton,
            modifier = modifier, dismissButton = dismissButton, title = title,
            text = text,
            shape = shape, backgroundColor = backgroundColor.copy(alpha = 1f), contentColor = contentColor,
            properties = properties
        )
    }
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
) {
    DialogChrome {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest, confirmButton = confirmButton,
            modifier = modifier, dismissButton = dismissButton, title = title,
            text = text,
            containerColor = containerColor.copy(alpha = 1f),
            shape = RoundedCornerShape(16.dp),
            titleContentColor = SettingsTheme.TextPrimary,
            textContentColor = SettingsTheme.TextSecondary,
            tonalElevation = 0.dp,
            properties = properties
        )
    }
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
