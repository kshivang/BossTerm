package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.UiTheme
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal data class MacChromePreferences(
    val dark: Boolean,
    val reduceTransparency: Boolean = false,
    val increaseContrast: Boolean = false
)

internal val LocalTitleBarTheme = compositionLocalOf { BossUiTheme.current }
internal val LocalTitleBarPreferences = compositionLocalOf { MacChromePreferences(true) }
internal val LocalTitleBarTrailing = compositionLocalOf { false }
internal val LocalCompactTitleBar = compositionLocalOf { false }
internal val LocalInTitleBar = compositionLocalOf { false }

@Composable
internal fun rememberMacChromePreferences(): MacChromePreferences {
    val fallback = BossUiTheme.current.isDark
    val preferences by produceState(MacChromePreferences(fallback)) {
        if (!ShellCustomizationUtils.isMacOS()) return@produceState
        // Lifecycle-bound: no process-wide timer survives a closed window. Read on
        // AppKit's main queue and publish on Swing's EDT; never call AppKit from Compose.
        while (true) {
            value = suspendCancellableCoroutine { continuation ->
                MacOSWindowGlass.readChromePreferences {
                    if (continuation.isActive) continuation.resume(it)
                }
            }
            delay(1500)
        }
    }
    return preferences
}

internal fun titleBarTheme(dark: Boolean): UiTheme = UiTheme.fromTheme(
    if (dark) BuiltinThemes.LIQUID_GLASS_DARK else BuiltinThemes.LIQUID_GLASS_LIGHT
)
