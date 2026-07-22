package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.theme.BuiltinColorPalettes
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Locks in two invariants around the default identity:
 *  1. DEFAULT_THEME_ID always resolves to a real builtin (catches an id rename
 *     that would otherwise silently fall back at runtime).
 *  2. The BOSS Operator palette's ANSI 16 stays identical to the theme's, so the
 *     "derive the palette from the theme" wiring can never drift.
 */
class ThemeDefaultsTest {

    @Test
    fun `default theme id resolves to a builtin theme`() {
        assertNotNull(
            BuiltinThemes.getById(BuiltinThemes.DEFAULT_THEME_ID),
            "DEFAULT_THEME_ID '${BuiltinThemes.DEFAULT_THEME_ID}' must resolve to a builtin theme",
        )
    }

    @Test
    fun `boss-operator palette ANSI matches the theme`() {
        val t = BuiltinThemes.BOSS_OPERATOR
        val p = BuiltinColorPalettes.BOSS_OPERATOR
        assertEquals(t.black, p.black)
        assertEquals(t.red, p.red)
        assertEquals(t.green, p.green)
        assertEquals(t.yellow, p.yellow)
        assertEquals(t.blue, p.blue)
        assertEquals(t.magenta, p.magenta)
        assertEquals(t.cyan, p.cyan)
        assertEquals(t.white, p.white)
        assertEquals(t.brightBlack, p.brightBlack)
        assertEquals(t.brightRed, p.brightRed)
        assertEquals(t.brightGreen, p.brightGreen)
        assertEquals(t.brightYellow, p.brightYellow)
        assertEquals(t.brightBlue, p.brightBlue)
        assertEquals(t.brightMagenta, p.brightMagenta)
        assertEquals(t.brightCyan, p.brightCyan)
        assertEquals(t.brightWhite, p.brightWhite)
    }

    @Test
    fun `terminal settings color defaults match the boss-operator theme`() {
        // A fresh install renders these TerminalSettings defaults until a theme is
        // applied via updateSetting, so they must equal the default theme's colors.
        val t = BuiltinThemes.BOSS_OPERATOR
        val s = TerminalSettings()
        assertEquals(t.foreground, s.defaultForeground)
        assertEquals(t.background, s.defaultBackground)
        assertEquals(t.selection, s.selectionColor)
        assertEquals(t.searchMatch, s.foundPatternColor)
        assertEquals(t.hyperlink, s.hyperlinkColor)
        assertEquals(1f, s.cursorFocusedAlpha, "focused cursor should match the opaque browser cursor")
    }
}
