package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.settings.SettingsTheme
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** Material controls must use the same opaque foreground palette as BossTerm's glass surfaces. */
@Composable
internal fun RemoteFilesTheme(content: @Composable () -> Unit) {
    val palette = BossUiTheme.current
    val defaults = if (palette.isDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = defaults.copy(
        primary = SettingsTheme.AccentTextColor,
        onPrimary = SettingsTheme.TextOnAccent,
        primaryContainer = palette.signalWash,
        onPrimaryContainer = SettingsTheme.TextPrimary,
        secondary = SettingsTheme.AccentTextColor,
        onSecondary = SettingsTheme.TextOnAccent,
        background = SettingsTheme.BackgroundColor,
        onBackground = SettingsTheme.TextPrimary,
        surface = SettingsTheme.SurfaceColor,
        onSurface = SettingsTheme.TextPrimary,
        surfaceVariant = SettingsTheme.SurfaceColor,
        onSurfaceVariant = SettingsTheme.TextSecondary,
        outline = SettingsTheme.TextMuted,
        outlineVariant = SettingsTheme.BorderColor,
        error = SettingsTheme.Danger,
        surfaceTint = SettingsTheme.SurfaceColor,
    ), content = content)
}
