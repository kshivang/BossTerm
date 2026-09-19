package ai.rever.bossterm.compose.tabs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes

/** Shared by the native sidebar material and the Compose sidebar fallback. */
fun sidebarPanelColor(
    terminalBackground: Color,
    tintedGlass: Boolean,
    tint: Float,
    reduceTransparency: Boolean,
    increaseContrast: Boolean
): Color {
    val dark = terminalBackground.luminance() < 0.5f
    val theme = if (dark) BuiltinThemes.LIQUID_GLASS_DARK else BuiltinThemes.LIQUID_GLASS_LIGHT
    return if (!tintedGlass) {
        lerp(terminalBackground, theme.foregroundColor,
            if (increaseContrast) { if (dark) 0.16f else 0.12f }
            else if (dark) 0.10f else 0.06f).copy(alpha = 1f)
    } else theme.backgroundColorValue.copy(
        alpha = if (reduceTransparency || increaseContrast) 1f else tint.coerceIn(0f, 1f))
}

@androidx.compose.runtime.Composable
fun rememberSidebarPanelColor(terminalBackground: Color, tintedGlass: Boolean, tint: Float): Color {
    val preferences = ai.rever.bossterm.compose.window.rememberMacChromePreferences()
    return sidebarPanelColor(terminalBackground, tintedGlass, tint,
        preferences.reduceTransparency, preferences.increaseContrast)
}
