package ai.rever.bossterm.compose.settings

import ai.rever.bossterm.compose.window.LocalAuxiliaryGlassOpacity
import ai.rever.bossterm.compose.window.LocalAuxiliaryGlassTint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Only surface fills become transparent. Text, icons and control accents stay opaque. */
object DialogTheme {
    val BackgroundColor: Color
        @Composable get() = SettingsTheme.BackgroundColor.copy(alpha = LocalAuxiliaryGlassOpacity.current)
    val SurfaceColor: Color
        @Composable get() = SettingsTheme.SurfaceColor.copy(alpha = LocalAuxiliaryGlassTint.current)
}
