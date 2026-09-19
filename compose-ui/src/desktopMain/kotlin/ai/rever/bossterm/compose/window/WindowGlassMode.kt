package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.TerminalSettings
import androidx.compose.runtime.staticCompositionLocalOf

/** Persisted independently of terminal opacity and background-image blur. */
enum class WindowGlassMode(val setting: String, val label: String) {
    OFF("off", "Off"),
    BARS("bars", "Sidebar"),
    TERMINAL("terminal", "Terminal"),
    WINDOW("window", "Both");

    val includesSidebar: Boolean get() = this == BARS || this == WINDOW
    val includesTerminal: Boolean get() = this == TERMINAL || this == WINDOW

    fun terminalOpacity(configuredOpacity: Float): Float =
        if (this == BARS) 1f else configuredOpacity.coerceIn(0f, 1f)

    companion object {
        fun fromSetting(value: String): WindowGlassMode = entries.firstOrNull { it.setting == value } ?: OFF
    }
}

/** Only the standalone window sets this; embedded terminals retain their host's appearance. */
val LocalWindowGlassMode = staticCompositionLocalOf { WindowGlassMode.OFF }

/** Theme tint above the native material; separate from the terminal's background opacity. */
val LocalWindowGlassTint = staticCompositionLocalOf { 0.24f }

/** Glass is an opt-in theme identity, never a global effect applied to other themes. */
val TerminalSettings.isLiquidGlassTheme: Boolean
    get() = activeThemeId == "liquid-glass-light" || activeThemeId == "liquid-glass-dark"

val TerminalSettings.effectiveWindowGlassMode: WindowGlassMode
    get() = if (isLiquidGlassTheme) WindowGlassMode.fromSetting(windowGlassMode) else WindowGlassMode.OFF

val TerminalSettings.effectiveBackgroundOpacity: Float
    get() = if (isLiquidGlassTheme) effectiveWindowGlassMode.terminalOpacity(windowGlassOpacity)
        else backgroundOpacity

/** Opacity of standalone window chrome, shared by title actions and update banners. */
val LocalWindowChromeOpacity = staticCompositionLocalOf { 1f }

/** Saved glass opacity must never turn an unsupported/failed backdrop into an unreadable window. */
fun TerminalSettings.surfaceOpacity(nativeGlassInstalled: Boolean): Float =
    if (isLiquidGlassTheme && !nativeGlassInstalled) 1f else effectiveBackgroundOpacity.coerceIn(0f, 1f)
