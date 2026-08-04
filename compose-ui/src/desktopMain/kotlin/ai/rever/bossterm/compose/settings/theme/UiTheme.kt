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
 * whatever theme the user picks. The two BOSS identities — BOSS Blueprint (the
 * default) and BOSS Operator — skip the derivation and map to their exact
 * design-system values; see [EXACT_TOKENS].
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
    val signalText: Color, // signal-colored TEXT/ICONS — held to the 4.5:1 text floor
    val onSignal: Color,   // content on signal-filled controls
    val data: Color,       // links / info
    val ok: Color,
    val warn: Color,
    val alert: Color,
) {
    val isDark: Boolean get() = ink.luminance() < 0.5f

    companion object {
        /**
         * Exact tokens for the bossconsole.ai identity, mirroring the host's
         * `BossBlueprintColorScheme` (`line2` is the host's `lineStrong`).
         *
         * `signal` is the site's `--blue`, which sits at 3.8:1 on `ink` — above
         * the WCAG floor for UI components, below a text floor. That is how the
         * site behaves: emphasis is a `signalWash` fill plus a 2.dp indicator,
         * never a hairline of `signal` alone. `onSignal` is white, not `ink`,
         * because `--blue` is too dark to carry ink-colored content.
         */
        val BOSS_BLUEPRINT = UiTheme(
            ink = Color(0xFF05070B),
            panel = Color(0xFF080B11),
            raised = Color(0xFF0E141E),
            line = Color(0xFF1C2432),
            line2 = Color(0xFF2E3B4F),
            chalk = Color(0xFFE7EDFA),
            mist = Color(0xFF9AA7BB),
            muted = Color(0xFF69768B),
            signal = Color(0xFF0F5BFF),
            signalDim = Color(0xFF0A45C4),
            signalWash = Color(0xFF0A1A3C),
            // --blue is 3.5:1 as a glyph; the site uses this lighter blue for
            // blue text on ink and so do we. 8.1:1 on the darkest surface.
            signalText = Color(0xFF88A9FF),
            onSignal = Color(0xFFFFFFFF),
            data = Color(0xFF88A9FF),
            ok = Color(0xFF2FD98A),
            warn = Color(0xFFF0B429),
            alert = Color(0xFFFF5D5D),
        )

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
            signalText = Color(0xFFF2A93B), // = signal; amber already clears the text floor
            onSignal = Color(0xFF0E1217),
            data = Color(0xFF56C7E0),
            ok = Color(0xFF6FD08C),
            warn = Color(0xFFF0B429),
            alert = Color(0xFFF2685F),
        )

        /**
         * The BOSS identities, whose chrome tokens are hand-authored rather than
         * derived.
         *
         * Keyed by theme id and *not* by [BuiltinThemes.DEFAULT_THEME_ID] — a map
         * means moving the default cannot silently push the theme that used to be
         * the default through [fromTheme]'s derivation and lose its authored
         * values.
         */
        private val EXACT_TOKENS: Map<String, UiTheme> =
            mapOf(
                BuiltinThemes.BOSS_BLUEPRINT.id to BOSS_BLUEPRINT,
                BuiltinThemes.BOSS_OPERATOR.id to BOSS_OPERATOR,
            )

        /**
         * Derive chrome tokens from a terminal theme.
         *
         * The surface ladder nudges the terminal floor toward the text color
         * using the same ratios the BOSS tokens sit at on ink, so any theme
         * (dark or light) yields a coherent panel/raised/border stack.
         */
        fun fromTheme(theme: Theme): UiTheme {
            EXACT_TOKENS[theme.id]?.let { return it }

            val bg = theme.backgroundColorValue
            val fg = theme.foregroundColor
            val signal = signalFor(theme, bg, fg)
            // Content on a signal fill: whichever of the theme's own floor/text
            // colors contrasts more with the fill.
            val onSignal = if (contrastRatio(signal, bg) >= contrastRatio(signal, fg)) bg else fg
            val panel = mix(bg, fg, 0.05f)
            val raised = mix(bg, fg, 0.09f)
            return UiTheme(
                ink = bg,
                panel = panel,
                raised = raised,
                line = mix(bg, fg, 0.16f),
                line2 = mix(bg, fg, 0.26f),
                chalk = fg,
                mist = mix(bg, fg, 0.62f),
                muted = mix(bg, fg, 0.40f),
                signal = signal,
                signalDim = mix(signal, Color.Black, 0.17f),
                signalWash = mix(bg, signal, 0.12f),
                // Derived themes get a signal pushed toward their own text color
                // until it clears the text floor, so accent-colored labels stay
                // readable on any imported theme rather than only on the BOSS two.
                signalText = legibleAsText(signal, listOf(bg, panel, raised), fg),
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
         * Nudge [signal] toward [fg] until it clears the 4.5:1 text floor against
         * **every** surface in [surfaces], giving up at the theme's own text color.
         *
         * A saturated accent that works as a fill is often too dark or too light to
         * read as a glyph; rather than pick a hue for every imported theme, we walk
         * the one axis the theme already tells us is legible on its own floor.
         *
         * Solved against the minimum over all surfaces rather than a surface picked
         * as "the worst": which one that is depends on whether the nudge moves the
         * accent lighter or darker, and guessing `raised` left solarized-dark at
         * 4.44:1 on `panel`.
         */
        private fun legibleAsText(signal: Color, surfaces: List<Color>, fg: Color): Color {
            fun worst(c: Color) = surfaces.minOf { contrastRatio(c, it) }
            if (worst(signal) >= 4.5f) return signal

            // Phase 1 — toward the theme's own text color, which keeps the result
            // inside the theme's palette.
            for (step in 1..20) {
                val candidate = mix(signal, fg, step / 20f)
                if (worst(candidate) >= 4.5f) return candidate
            }

            // Phase 2 — past fg, toward the luminance extreme. Needed because fg is
            // NOT guaranteed to clear the floor itself: solarized-dark's #839496 is
            // 4.44:1 against its own derived `panel`, so phase 1 alone cannot
            // converge there. Walking to an extreme always can, and mixing toward
            // white/black preserves hue, so the accent stays recognisable.
            val extreme = if (surfaces.first().luminance() < 0.5f) Color.White else Color.Black
            for (step in 1..20) {
                val candidate = mix(fg, extreme, step / 20f)
                if (worst(candidate) >= 4.5f) return candidate
            }
            return extreme
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
    // Seeded to the product default so chrome composed before ThemeManager's
    // loadActiveTheme() lands does not flash a different identity.
    private val state = mutableStateOf(UiTheme.fromTheme(BuiltinThemes.PRODUCT_DEFAULT))

    val current: UiTheme get() = state.value

    fun update(theme: Theme) {
        state.value = UiTheme.fromTheme(theme)
    }
}

/** For the AWT-rendered surfaces (right-click context menu). */
fun Color.toAwtColor(): java.awt.Color = java.awt.Color(red, green, blue, alpha)
