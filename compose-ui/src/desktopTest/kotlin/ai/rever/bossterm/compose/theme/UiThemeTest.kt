package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.SettingsTheme
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.UiTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locks the terminal-theme → UI-chrome bridge:
 *  1. ALL FOUR BOSS identities - the two dark and the two light - map to their
 *     exact design-system tokens, not a lerp approximation. One test each plus a
 *     sweep, because `UiTheme.EXACT_TOKENS` is keyed by theme id: when the default
 *     moved from boss-operator to boss-blueprint, keying off `DEFAULT_THEME_ID`
 *     would have silently dropped boss-operator into the derivation path, and a
 *     mistyped key does the same thing to a light identity today.
 *  2. Derivation for arbitrary themes yields a readable surface ladder and a
 *     signal distinct from the floor (including light themes).
 *  3. SettingsTheme stays wired to BossUiTheme, and BossUiTheme's own seed is the
 *     product default (chrome composed before ThemeManager loads must not flash a
 *     different identity).
 */
class UiThemeTest {

    @Test
    fun `boss-blueprint theme maps to exact design-system tokens`() {
        val ui = UiTheme.fromTheme(BuiltinThemes.BOSS_BLUEPRINT)
        assertEquals(Color(0xFF05070B), ui.ink)
        assertEquals(Color(0xFF080B11), ui.panel)
        assertEquals(Color(0xFF0E141E), ui.raised)
        assertEquals(Color(0xFF1C2432), ui.line)
        assertEquals(Color(0xFF2E3B4F), ui.line2)
        assertEquals(Color(0xFFE7EDFA), ui.chalk)
        assertEquals(Color(0xFF9AA7BB), ui.mist)
        assertEquals(Color(0xFF69768B), ui.muted)
        assertEquals(Color(0xFF0F5BFF), ui.signal, "the site's --blue")
        assertEquals(Color(0xFF0A1A3C), ui.signalWash)
        assertEquals(Color(0xFF88A9FF), ui.signalText, "--blue is only 3.5:1 as a glyph")
        assertEquals(Color(0xFFFFFFFF), ui.onSignal, "--blue is too dark to carry ink-colored content")
        assertEquals(Color(0xFF88A9FF), ui.data)
        assertEquals(Color(0xFFFF5D5D), ui.alert)
        assertTrue(ui.isDark)
    }

    @Test
    fun `boss-blueprint chrome mirrors the host BossBlueprintColorScheme`() {
        // The host's plugin-ui-core has no dependency edge to here, so the two
        // palettes can only be kept in step by hand. This is where that breaks
        // loudly instead of shipping a terminal whose dialogs are a shade off the
        // chrome around them.
        val ui = UiTheme.fromTheme(BuiltinThemes.BOSS_BLUEPRINT)
        val t = BuiltinThemes.BOSS_BLUEPRINT
        assertEquals(t.backgroundColorValue, ui.ink, "chrome floor must be the terminal floor")
        assertEquals(Color(0xFF05070B), ui.ink, "host BossBlueprintColorScheme.ink")
        assertEquals(Color(0xFF080B11), ui.panel, "host BossBlueprintColorScheme.panel")
        assertEquals(Color(0xFF2E3B4F), ui.line2, "host BossBlueprintColorScheme.lineStrong")
    }

    @Test
    fun `boss-operator theme still maps to exact design-system tokens`() {
        val ui = UiTheme.fromTheme(BuiltinThemes.BOSS_OPERATOR)
        assertEquals(Color(0xFF0E1217), ui.ink)
        assertEquals(Color(0xFF161D26), ui.panel)
        assertEquals(Color(0xFF1E2731), ui.raised)
        assertEquals(Color(0xFF2A3744), ui.line)
        assertEquals(Color(0xFFE9EEF3), ui.chalk)
        assertEquals(Color(0xFF8593A3), ui.mist)
        assertEquals(Color(0xFFF2A93B), ui.signal)
        assertEquals(Color(0xFF0E1217), ui.onSignal)
        assertEquals(Color(0xFF56C7E0), ui.data)
        assertEquals(Color(0xFFF2685F), ui.alert)
        assertTrue(ui.isDark)
    }

    /**
     * Asserts the ladder is STRICTLY MONOTONIC, not that it runs in a particular
     * direction.
     *
     * The previous version required light themes to step *darker* than their floor.
     * That was true of every theme it ever saw, because it was really asserting a
     * property of [UiTheme.fromTheme]'s derivation (`mix(bg, fg, t)` moves toward
     * the text, which on paper means darker) rather than a property of a good
     * surface ladder. The hand-authored light identities elevate the way light
     * design systems actually do - paper floor, white cards above it - and the old
     * assertion called that a bug.
     *
     * Direction is the theme author's call; collapse is the bug. A collapsed rung
     * is what erases every settings row, since `raised` is
     * `SettingsTheme.SurfaceColor` and those rows have no border of their own.
     */
    @Test
    fun `derived themes keep a readable surface ladder`() {
        for (theme in BuiltinThemes.ALL) {
            val ui = UiTheme.fromTheme(theme)
            val inkLum = ui.ink.luminance()
            val panelLum = ui.panel.luminance()
            val raisedLum = ui.raised.luminance()
            // Whichever way this theme's ladder runs, every rung must continue it.
            assertNotEquals(
                inkLum,
                panelLum,
                "${theme.id}: panel is indistinguishable from ink - the ladder collapsed",
            )
            val climbs = panelLum > inkLum
            assertTrue(
                if (climbs) raisedLum > panelLum else raisedLum < panelLum,
                "${theme.id}: ink -> panel ${if (climbs) "climbs" else "descends"} but " +
                    "panel -> raised does not follow (ink $inkLum, panel $panelLum, raised $raisedLum)",
            )
            // The signal must never dissolve into the floor or the text color.
            assertNotEquals(ui.ink, ui.signal, "${theme.id}: signal must differ from ink")
            assertNotEquals(ui.chalk, ui.signal, "${theme.id}: signal must differ from chalk")
            // Content on signal-filled controls must contrast with the fill.
            // 2.5 floor: the best of the theme's own floor/text colors; Solarized
            // Light's dark-yellow signal caps out just under 3.0 against its paper.
            val contrast = contrastRatio(ui.signal, ui.onSignal)
            assertTrue(contrast >= 2.5f, "${theme.id}: onSignal contrast $contrast on signal is too low")

            // The axis that regressed when Blueprint landed: `signal` is allowed to
            // sit at the 3:1 component floor, but anything drawn as a GLYPH uses
            // `signalText` and must clear 4.5:1 — on `raised`, the worst surface,
            // not just on the bare floor.
            for ((name, surface) in listOf("ink" to ui.ink, "panel" to ui.panel, "raised" to ui.raised)) {
                val text = contrastRatio(ui.signalText, surface)
                assertTrue(
                    text >= 4.5f,
                    "${theme.id}: signalText is $text on $name, below the 4.5:1 text floor",
                )
            }
        }
    }

    @Test
    fun `boss-blueprint-light maps to exact tokens and mirrors the host`() {
        val ui = UiTheme.fromTheme(BuiltinThemes.BOSS_BLUEPRINT_LIGHT)
        val t = BuiltinThemes.BOSS_BLUEPRINT_LIGHT
        assertEquals(t.backgroundColorValue, ui.ink, "chrome floor must be the terminal floor")

        // Values shared with the host's BossBlueprintLightColorScheme. plugin-ui-core
        // has no dependency edge to here, so hand-transcription is the only link and
        // this is where it breaks loudly.
        assertEquals(Color(0xFFF5F7FB), ui.ink, "host ink / site --paper")
        assertEquals(Color(0xFFDCE2EB), ui.line, "host line")
        assertEquals(Color(0xFFA8B2C2), ui.line2, "host lineStrong")
        assertEquals(Color(0xFF05070B), ui.chalk, "host textPrimary")
        assertEquals(Color(0xFF687081), ui.mist, "host textSecondary")
        assertEquals(Color(0xFF9AA3B2), ui.muted, "host textMuted")
        assertEquals(Color(0xFF0F5BFF), ui.signal, "host signal / --blue")
        assertEquals(Color(0xFF0A45C4), ui.signalDim, "host signalDim")
        assertEquals(Color(0xFFDCE7FF), ui.signalWash, "host signalWash / --blue-soft")
        assertEquals(Color(0xFF0F5BFF), ui.signalText, "--blue clears 4.9:1 on paper, so no separate glyph blue")
        assertEquals(Color(0xFFFFFFFF), ui.onSignal, "host onSignal")
        assertEquals(Color(0xFF0C3FBF), ui.data, "host data")
        assertEquals(Color(0xFF1E9E63), ui.ok, "host ok")
        assertEquals(Color(0xFFA8710A), ui.warn, "host warn")
        assertEquals(Color(0xFFD33B4A), ui.alert, "host alert")

        // The one deliberate divergence, asserted so it stays deliberate: the host
        // uses pure white for BOTH panel and raised. `raised` is the settings ROW
        // background and `panel` the window behind it, and those rows draw no border,
        // so copying the host would erase every row on a light theme.
        assertEquals(Color(0xFFFAFBFD), ui.panel, "the near-white rung the host omits")
        assertEquals(Color(0xFFFFFFFF), ui.raised, "host panel / raised")
        assertNotEquals(ui.panel, ui.raised, "the rung that makes settings rows visible")

        assertFalse(ui.isDark, "paper floor must read as a light theme")
    }

    @Test
    fun `boss-daylight maps to exact tokens and mirrors the host`() {
        val ui = UiTheme.fromTheme(BuiltinThemes.BOSS_DAYLIGHT)
        val t = BuiltinThemes.BOSS_DAYLIGHT
        assertEquals(t.backgroundColorValue, ui.ink, "chrome floor must be the terminal floor")

        // Shared with the host's BossLightColorScheme.
        assertEquals(Color(0xFFF5F7FA), ui.ink, "host ink")
        assertEquals(Color(0xFFE2E7EE), ui.line, "host line")
        assertEquals(Color(0xFFC9D2DC), ui.line2, "host lineStrong")
        assertEquals(Color(0xFF131820), ui.chalk, "host textPrimary")
        assertEquals(Color(0xFF5A6675), ui.mist, "host textSecondary")
        assertEquals(Color(0xFF94A0AE), ui.muted, "host textMuted")
        assertEquals(Color(0xFFD9871A), ui.signal, "host signal - the amber FILL")
        assertEquals(Color(0xFFB36F12), ui.signalDim, "host signalDim")
        assertEquals(Color(0xFFFBEFD8), ui.signalWash, "host signalWash")
        assertEquals(Color(0xFF2A1B05), ui.onSignal, "host onSignal")
        assertEquals(Color(0xFF1E7FA8), ui.data, "host data")
        assertEquals(Color(0xFF2F9E54), ui.ok, "host ok")
        assertEquals(Color(0xFFB87D0A), ui.warn, "host warn")
        assertEquals(Color(0xFFD2453B), ui.alert, "host alert")

        assertEquals(Color(0xFFFAFBFC), ui.panel, "the near-white rung the host omits")
        assertEquals(Color(0xFFFFFFFF), ui.raised, "host panel / raised")
        assertNotEquals(ui.panel, ui.raised, "the rung that makes settings rows visible")

        // The split that defines this identity on paper: the amber that works as a
        // FILL is unreadable as a GLYPH, so signalText is a much darker amber. This
        // is also why the terminal theme's cursor and ANSI 3 take the darker value
        // (see LightThemeContrastTest).
        assertEquals(Color(0xFF95580A), ui.signalText, "host signalText")
        assertNotEquals(ui.signal, ui.signalText, "#D9871A is 2.6:1 on this floor")
        assertTrue(
            contrastRatio(ui.signal, ui.ink) < 4.5f,
            "precondition for the split: the amber signal is below the text floor here",
        )
        assertTrue(
            contrastRatio(ui.signalText, ui.ink) >= 4.5f,
            "the darker amber is what clears it",
        )

        assertFalse(ui.isDark, "paper floor must read as a light theme")
    }

    /**
     * All four hand-authored identities must actually be reached by
     * [UiTheme.fromTheme]. `EXACT_TOKENS` is private and keyed by theme id, so a
     * typo in a key is invisible: the theme silently falls through to derivation and
     * produces plausible-looking values that are not the design-system ones.
     */
    @Test
    fun `every BOSS identity is wired into the exact-token map`() {
        val authored = mapOf(
            BuiltinThemes.BOSS_BLUEPRINT to UiTheme.BOSS_BLUEPRINT,
            BuiltinThemes.BOSS_BLUEPRINT_LIGHT to UiTheme.BOSS_BLUEPRINT_LIGHT,
            BuiltinThemes.BOSS_OPERATOR to UiTheme.BOSS_OPERATOR,
            BuiltinThemes.BOSS_DAYLIGHT to UiTheme.BOSS_DAYLIGHT,
        )
        for ((theme, tokens) in authored) {
            assertEquals(
                tokens,
                UiTheme.fromTheme(theme),
                "${theme.id} fell through to derivation instead of its authored tokens",
            )
        }
        // And each identity is a distinct set of tokens, so a copy-paste that pointed
        // two ids at one UiTheme would fail here rather than ship a duplicate.
        assertEquals(
            authored.size,
            authored.values.toSet().size,
            "two BOSS identities resolve to the same tokens",
        )
    }

    @Test
    fun `the BossUiTheme seed expression resolves to the default identity`() {
        // BossUiTheme's initial state is `fromTheme(PRODUCT_DEFAULT)`. Its live value
        // cannot be asserted here — sibling tests call update() and test order is not
        // guaranteed — so assert the expression instead. A wrong seed shows up as a
        // flash of the other identity in chrome composed before ThemeManager loads.
        assertEquals(UiTheme.BOSS_BLUEPRINT, UiTheme.fromTheme(BuiltinThemes.PRODUCT_DEFAULT))
    }

    @Test
    fun `settings chrome follows the boss ui theme`() {
        BossUiTheme.update(BuiltinThemes.BOSS_BLUEPRINT)
        assertEquals(Color(0xFF0F5BFF), SettingsTheme.AccentColor, "accent fill should be the blue signal")
        assertEquals(Color(0xFF88A9FF), SettingsTheme.AccentTextColor, "accent TEXT must clear the text floor")
        assertEquals(Color(0xFF080B11), SettingsTheme.BackgroundColor)
        assertEquals(Color(0xFF0E141E), SettingsTheme.SurfaceColor)
        assertEquals(Color(0xFFFFFFFF), SettingsTheme.TextOnAccent)

        BossUiTheme.update(BuiltinThemes.BOSS_OPERATOR)
        assertEquals(Color(0xFFF2A93B), SettingsTheme.AccentColor, "accent should be the amber signal")
        assertEquals(Color(0xFF161D26), SettingsTheme.BackgroundColor)
        assertEquals(Color(0xFF1E2731), SettingsTheme.SurfaceColor)
        assertEquals(Color(0xFF0E1217), SettingsTheme.TextOnAccent)

        BossUiTheme.update(BuiltinThemes.SOLARIZED_LIGHT)
        assertNotEquals(Color(0xFF161D26), SettingsTheme.BackgroundColor, "chrome must follow theme switches")

        BossUiTheme.update(BuiltinThemes.PRODUCT_DEFAULT)
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }

    /**
     * `cursorText` painted nowhere until it was wired, so no builtin's value had
     * ever been reviewed as a visible colour. Now an opaque block repaints the
     * covered glyph in it, making `cursorText`-on-`cursor` a real glyph-on-fill
     * pair held to the same 4.5:1 floor as `signalText`.
     *
     * The two exemptions are faithful ports of published themes (Solarized Light,
     * One Dark); restyling someone else's palette to pass our floor would
     * misrepresent it, so they are recorded rather than changed. BOSS Blueprint was
     * ours and was fixed (white gave 4.15:1, ink gives 4.80:1).
     */
    @Test
    fun `cursorText is legible on the cursor it is painted over`() {
        val debt = setOf("solarized-light", "one-dark")
        val failing = mutableListOf<String>()
        for (theme in BuiltinThemes.ALL) {
            val ratio = contrastRatio(theme.cursorTextColor, theme.cursorColor)
            if (theme.id in debt) continue
            if (ratio < 4.5f) failing += "${theme.id} (${"%.2f".format(ratio)}:1)"
        }
        assertTrue(failing.isEmpty(), "cursorText below the 4.5:1 glyph floor: $failing")

        // And the debt list cannot quietly grow or go stale.
        val stillFailing = BuiltinThemes.ALL
            .filter { contrastRatio(it.cursorTextColor, it.cursorColor) < 4.5f }
            .map { it.id }
            .toSet()
        assertEquals(
            debt,
            stillFailing,
            "cursorText contrast debt changed: fix the theme or update the exemption list",
        )
    }


}
