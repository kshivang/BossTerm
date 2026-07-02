package ai.rever.bossterm.compose.settings

import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import androidx.compose.ui.graphics.Color

/**
 * Shared UI chrome for the settings panel and dialogs.
 *
 * Every value follows the active terminal theme via [BossUiTheme]; reads are
 * snapshot-backed, so composables restyle live when the theme changes.
 */
object SettingsTheme {
    val SurfaceColor: Color get() = BossUiTheme.current.raised
    val BackgroundColor: Color get() = BossUiTheme.current.panel
    val AccentColor: Color get() = BossUiTheme.current.signal
    val BorderColor: Color get() = BossUiTheme.current.line
    val TextPrimary: Color get() = BossUiTheme.current.chalk
    val TextSecondary: Color get() = BossUiTheme.current.mist
    val TextMuted: Color get() = BossUiTheme.current.muted

    /** Content color for accent-filled controls (buttons, badges). */
    val TextOnAccent: Color get() = BossUiTheme.current.onSignal

    // Semantic status colors
    val Danger: Color get() = BossUiTheme.current.alert
    val Success: Color get() = BossUiTheme.current.ok
    val Warning: Color get() = BossUiTheme.current.warn
    val Info: Color get() = BossUiTheme.current.data
}
