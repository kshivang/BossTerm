package ai.rever.bossterm.compose.settings.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * UI chrome tokens for everything around the terminal surface — dialogs,
 * settings, wizards, menus. The Compose-side mirror of the CSS custom
 * properties in docs/design-system.html.
 *
 * Tokens are derived from the active terminal [Theme] so the chrome follows
 * whatever theme the user picks; the BOSS Operator theme maps to the exact
 * design-system values.
 */
data class UiTheme(
    // Surfaces
    val ink: Color,        // base floor — the terminal background
    val panel: Color,      // dialog / settings window surface
    val raised: Color,     // cards, menus, popovers
    val line: Color,       // hairline border / divider
    val line2: Color,      // stronger border
    // Text
    val chalk: Color,      // primary text
    val mist: Color,       // secondary text
    val muted: Color,      // tertiary / disabled
    // Signals
    val signal: Color,     // live / active / primary action
    val signalDim: Color,  // pressed / variant
    val signalWash: Color, // signal at home on ink (selected rows, chips)
    val onSignal: Color,   // content on signal-filled controls
    val data: Color,       // links / info
    val ok: Color,
    val warn: Color,
    val alert: Color,
) {
    val isDark: Boolean get() = ink.luminance() < 0.5f

    companion object {
        /** Exact tokens from docs/design-system.html `:root`. */
        val BOSS_OPERATOR = UiTheme(
            ink = Color(0xFF0E1217),
            panel = Color(0xFF161D26),
            raised = Color(0xFF1E2731),
            line = Color(0xFF2A3744),
            line2 = Color(0xFF3A4B5C),
            chalk = Color(0xFFE9EEF3),
            mist = Color(0xFF8593A3),
            muted = Color(0xFF5C6977),
            signal = Color(0xFFF2A93B),
            signalDim = Color(0xFFC98A2E),
            signalWash = Color(0xFF2A2113),
            onSignal = Color(0xFF0E1217),
            data = Color(0xFF56C7E0),
            ok = Color(0xFF6FD08C),
            warn = Color(0xFFF0B429),
            alert = Color(0xFFF2685F),
        )

        /**
         * Derive chrome tokens from a terminal theme.
         *
         * The surface ladder nudges the terminal floor toward the text color
         * using the same ratios the BOSS Operator tokens sit at on ink, so any
         * theme (dark or light) yields a coherent panel/raised/border stack.
         */
        fun fromTheme(theme: Theme): UiTheme {
            if (theme.id == BuiltinThemes.DEFAULT_THEME_ID) return BOSS_OPERATOR

            val bg = theme.backgroundColorValue
            val fg = theme.foregroundColor
            val signal = signalFor(theme, bg, fg)
            // Content on a signal fill: whichever of the theme's own floor/text
            // colors contrasts more with the fill.
            val onSignal = if (contrastRatio(signal, bg) >= contrastRatio(signal, fg)) bg else fg
            return UiTheme(
                ink = bg,
                panel = mix(bg, fg, 0.05f),
                raised = mix(bg, fg, 0.09f),
                line = mix(bg, fg, 0.16f),
                line2 = mix(bg, fg, 0.26f),
                chalk = fg,
                mist = mix(bg, fg, 0.62f),
                muted = mix(bg, fg, 0.40f),
                signal = signal,
                signalDim = mix(signal, Color.Black, 0.17f),
                signalWash = mix(bg, signal, 0.12f),
                onSignal = onSignal,
                data = theme.hyperlinkColor,
                ok = theme.getAnsiColor(2),
                warn = theme.getAnsiColor(3),
                alert = theme.getAnsiColor(1),
            )
        }

        /**
         * The signal is the cursor color — the theme's "live / now" color —
         * unless the cursor just repeats the foreground or background (common
         * in ports of classic themes), in which case ANSI yellow keeps the
         * accent in the amber family the design system is built around.
         */
        private fun signalFor(theme: Theme, bg: Color, fg: Color): Color {
            val cursor = theme.cursorColor
            return if (distance(cursor, fg) < 0.15f || distance(cursor, bg) < 0.15f) {
                theme.getAnsiColor(3)
            } else {
                cursor
            }
        }

        /**
         * Component-space (gamma sRGB) mix. The token ratios above are
         * calibrated against the design-system hex values in this space;
         * Compose's Oklab [androidx.compose.ui.graphics.lerp] collapses small
         * steps off pure black (lerp(black, white, 0.05) rounds back to black).
         */
        private fun mix(a: Color, b: Color, t: Float): Color = Color(
            red = a.red + (b.red - a.red) * t,
            green = a.green + (b.green - a.green) * t,
            blue = a.blue + (b.blue - a.blue) * t,
        )

        private fun contrastRatio(a: Color, b: Color): Float {
            val la = a.luminance() + 0.05f
            val lb = b.luminance() + 0.05f
            return maxOf(la, lb) / minOf(la, lb)
        }

        private fun distance(a: Color, b: Color): Float {
            val dr = a.red - b.red
            val dg = a.green - b.green
            val db = a.blue - b.blue
            return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
        }
    }
}

/**
 * Snapshot-backed holder for the [UiTheme] derived from the active terminal
 * theme. Reads inside composition subscribe automatically, so every dialog and
 * settings surface restyles live when the theme changes — across all windows.
 *
 * [ThemeManager] pushes updates whenever the active theme changes.
 */
object BossUiTheme {
    private val state = mutableStateOf(UiTheme.BOSS_OPERATOR)

    val current: UiTheme get() = state.value

    fun update(theme: Theme) {
        state.value = UiTheme.fromTheme(theme)
    }
}

/** For the AWT-rendered surfaces (right-click context menu). */
fun Color.toAwtColor(): java.awt.Color = java.awt.Color(red, green, blue, alpha)
