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
 *  1. The default theme maps to the exact design-system tokens
 *     (docs/design-system.html `:root`), not a lerp approximation.
 *  2. Derivation for arbitrary themes yields a readable surface ladder and a
 *     signal distinct from the floor (including light themes).
 *  3. SettingsTheme stays wired to BossUiTheme (a fresh state = BOSS Operator
 *     tokens, amber accent — not the old static blue).
 */
class UiThemeTest {

    @Test
    fun `boss-operator theme maps to exact design-system tokens`() {
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
        }
    }

    @Test
    fun `settings chrome follows the boss ui theme`() {
        BossUiTheme.update(BuiltinThemes.BOSS_OPERATOR)
        assertEquals(Color(0xFFF2A93B), SettingsTheme.AccentColor, "accent should be the amber signal")
        assertEquals(Color(0xFF161D26), SettingsTheme.BackgroundColor)
        assertEquals(Color(0xFF1E2731), SettingsTheme.SurfaceColor)
        assertEquals(Color(0xFF0E1217), SettingsTheme.TextOnAccent)

        BossUiTheme.update(BuiltinThemes.SOLARIZED_LIGHT)
        assertNotEquals(Color(0xFF161D26), SettingsTheme.BackgroundColor, "chrome must follow theme switches")

        BossUiTheme.update(BuiltinThemes.BOSS_OPERATOR)
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }
}
