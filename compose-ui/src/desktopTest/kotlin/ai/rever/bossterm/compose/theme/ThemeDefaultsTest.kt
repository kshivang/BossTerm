package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.theme.BuiltinColorPalettes
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.withThemeColors
import ai.rever.bossterm.compose.settings.theme.ColorPalette
import ai.rever.bossterm.compose.settings.theme.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Locks in the invariants around the default identity:
 *  1. DEFAULT_THEME_ID always resolves to a real builtin (catches an id rename
 *     that would otherwise silently fall back at runtime), and PRODUCT_DEFAULT is
 *     the theme it names.
 *  2. Each BOSS palette's ANSI 16 stays identical to its theme's, so the
 *     "derive the palette from the theme" wiring can never drift.
 *  3. TerminalSettings' fresh-install colors equal the default theme's.
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
    fun `PRODUCT_DEFAULT is the theme DEFAULT_THEME_ID names`() {
        // Two ways of saying "the default": ThemeManager reaches for the object,
        // settings persist the id. A mismatch means the pre-load seed and the
        // loaded theme disagree, which shows up as a flash of the wrong palette.
        assertEquals(BuiltinThemes.DEFAULT_THEME_ID, BuiltinThemes.PRODUCT_DEFAULT.id)
    }

    @Test
    fun `boss-blueprint is the default and leads both builtin lists`() {
        assertEquals("boss-blueprint", BuiltinThemes.DEFAULT_THEME_ID)
        assertEquals("boss-blueprint", BuiltinThemes.ALL.first().id)
        assertEquals("boss-blueprint", BuiltinColorPalettes.ALL.first().id)
    }

    @Test
    fun `boss-blueprint palette ANSI matches the theme`() {
        assertAnsiMatches(BuiltinThemes.BOSS_BLUEPRINT, BuiltinColorPalettes.BOSS_BLUEPRINT)
    }

    @Test
    fun `boss-operator palette ANSI matches the theme`() {
        assertAnsiMatches(BuiltinThemes.BOSS_OPERATOR, BuiltinColorPalettes.BOSS_OPERATOR)
    }

    @Test
    fun `terminal settings color defaults match the default theme`() {
        // A fresh install renders these TerminalSettings defaults until a theme is
        // applied via updateSetting, so they must equal the default theme's colors.
        val t = BuiltinThemes.PRODUCT_DEFAULT
        val s = TerminalSettings()
        assertEquals(t.foreground, s.defaultForeground)
        assertEquals(t.background, s.defaultBackground)
        assertEquals(t.selection, s.selectionColor)
        assertEquals(t.searchMatch, s.foundPatternColor)
        assertEquals(t.hyperlink, s.hyperlinkColor)
        assertEquals(t.id, s.activeThemeId)
        assertEquals(1f, s.cursorFocusedAlpha, "focused cursor should match the opaque browser cursor")
    }

    /**
     * `colorToHex(theme.backgroundColorValue)` must reproduce `theme.background`
     * byte for byte.
     *
     * The terminal-tab plugin's host-theme bridge identifies a curated builtin by
     * comparing `colorToHex(hostInk)` against these stored strings, so an
     * inexact round-trip would not throw — it would just never match, silently
     * downgrading both BOSS identities to a derived approximation. `colorToHex`
     * truncates (`(channel * 255).toInt()`), which is exactly where a float
     * packed by `Color(0xFF……)` could come back one below.
     */
    @Test
    fun `builtin background hex survives a Color round-trip`() {
        for (theme in BuiltinThemes.ALL) {
            assertEquals(
                theme.background,
                Theme.colorToHex(theme.backgroundColorValue),
                "${theme.id}: background hex does not survive parse → colorToHex",
            )
        }
    }


    /**
     * Every colour a [Theme] exposes must reach a setting that something renders.
     *
     * `searchMatch` and `cursorText` were both dead for a long time: themes set
     * them, the theme editor let you change them, and nothing on screen moved.
     *
     * This drives the REAL mapping ([withThemeColors], which `applyTheme` calls)
     * rather than restating it. The first version of this test hand-built the same
     * `copy(...)` and asserted its own values, so it passed green even with the
     * mapping deleted - a guard that cannot fail is worse than no guard, because
     * it reads as covered.
     */
    @Test
    fun `withThemeColors carries every rendered colour slot`() {
        val t = BuiltinThemes.BOSS_OPERATOR
        val s = TerminalSettings().withThemeColors(t)
        assertEquals(t.id, s.activeThemeId)
        assertEquals(t.foreground, s.defaultForeground)
        assertEquals(t.background, s.defaultBackground)
        assertEquals(t.selection, s.selectionColor)
        assertEquals(t.hyperlink, s.hyperlinkColor)
        // The slot this PR revived: the in-buffer search highlight reads
        // foundPatternColorValue, so theme.searchMatch has to land here.
        assertEquals(t.searchMatch, s.foundPatternColor, "in-buffer search highlight")
    }

    /**
     * The scrollbar markers are deliberately user-owned, not theme-driven: they
     * are two same-sized ticks told apart only by hue, and several builtins put a
     * pale yellow `searchMatch` next to an off-white `cursor`. Pinned so the
     * decision is visible rather than looking like an oversight.
     */
    @Test
    fun `scrollbar search markers stay independent of the theme`() {
        val before = TerminalSettings()
        val after = before.withThemeColors(BuiltinThemes.BOSS_BLUEPRINT)
        assertEquals(before.searchMarkerColor, after.searchMarkerColor)
        assertEquals(before.currentSearchMarkerColor, after.currentSearchMarkerColor)
    }

    private fun assertAnsiMatches(
        t: Theme,
        p: ColorPalette,
    ) {
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
}
