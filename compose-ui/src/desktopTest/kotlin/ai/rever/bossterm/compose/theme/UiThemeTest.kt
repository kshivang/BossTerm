package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.SettingsTheme
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.UiTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locks the terminal-theme → UI-chrome bridge:
 *  1. BOTH BOSS identities map to their exact design-system tokens, not a lerp
 *     approximation. Two tests, because [UiTheme.EXACT_TOKENS] is keyed by theme
 *     id: when the default moved from boss-operator to boss-blueprint, keying off
 *     `DEFAULT_THEME_ID` would have silently dropped boss-operator into the
 *     derivation path.
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

    @Test
    fun `derived themes keep a readable surface ladder`() {
        for (theme in BuiltinThemes.ALL) {
            val ui = UiTheme.fromTheme(theme)
            val inkLum = ui.ink.luminance()
            val panelLum = ui.panel.luminance()
            val chalkLum = ui.chalk.luminance()
            // Surfaces step from the floor toward the text color.
            if (chalkLum > inkLum) {
                assertTrue(panelLum > inkLum, "${theme.id}: panel should be lighter than ink")
                assertTrue(ui.raised.luminance() > panelLum, "${theme.id}: raised should be lighter than panel")
            } else {
                assertTrue(panelLum < inkLum, "${theme.id}: panel should be darker than ink (light theme)")
                assertTrue(ui.raised.luminance() < panelLum, "${theme.id}: raised should be darker than panel (light theme)")
            }
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
