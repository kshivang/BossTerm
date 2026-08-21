package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.util.ColorUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The light-palette guard for the BOSS light identities.
 *
 * A light terminal palette is not a dark one with the floor swapped, and the three
 * rules it has to follow are all counter-intuitive enough that each was violated in
 * the first draft of these two themes. This test is what turned them from comments
 * into constraints:
 *
 *  1. **Every `bright*` slot must be DARKER than its base.** Bold text renders in
 *     ANSI 8-15. On a dark theme "bright" means lighter; brighten those on paper and
 *     every bold line goes pale and disappears. ANSI 8 is the single exemption, and
 *     it is a structural one rather than an excuse: 8 is the dim companion of ANSI 0,
 *     and on paper ANSI 0 is the ink, so 8 has nowhere to go but lighter. It is still
 *     held to the UI floor, and still required to be dimmer than ANSI 7.
 *  2. **No ANSI slot may sit near the floor.** This is the bug fixed in
 *     `terminal-tab` PR #72 from the other end: a near-white ANSI 7 is 1.27:1 on
 *     paper. Nothing downstream can rescue it, because
 *     [ColorUtils.legibleOnLightBackground] guards colours a CLI hardcodes - when the
 *     PALETTE is the source of the bad colour, the palette is the only place to fix it.
 *  3. **The cursor is an opaque block, so it is measured against the FLOOR.** This is
 *     what forces `boss-daylight` to give its cursor and its ANSI 3 the darker amber
 *     #95580A instead of the identity's #D9871A, which is 2.6:1 on paper.
 *
 * Scoped to the BOSS light themes on purpose. `solarized-light` is a faithful port of
 * a published palette that deliberately inverts (its `brightBlack` IS its background,
 * because Solarized uses ANSI 8 as an alternate surface); restyling someone else's
 * palette to pass our floors would misrepresent it. It is named as an exemption below
 * rather than skipped silently, and the exemption is itself asserted so it cannot go
 * stale.
 */
class LightThemeContrastTest {

    /** UI-component floor: fills, block cursors, hairlines. WCAG 1.4.11. */
    private val uiFloor = 3.0f

    /** Text floor for anything painted as a glyph. WCAG 1.4.3 AA. */
    private val textFloor = 4.5f

    /**
     * A wash (selection, search highlight) is a fill behind text, so it has no text
     * floor of its own - but it does have to be *seen*. 1.25:1 is a deliberate step
     * up from the reference point in this repo: Solarized Light's selection wash is
     * 1.14:1 against its own background, and the host's `signalWash` composited onto
     * paper is 1.06:1, because that value was authored to sit behind a 2.dp indicator
     * on a selected sidebar row. A terminal selection has no indicator, so the fill
     * carries the whole signal by itself.
     */
    private val washFloor = 1.25f

    private val lightIdentities = listOf(
        BuiltinThemes.BOSS_BLUEPRINT_LIGHT,
        BuiltinThemes.BOSS_DAYLIGHT,
    )

    private fun ratio(a: Color, b: Color) = ColorUtils.contrastRatio(a, b)

    @Test
    fun `the BOSS light identities really are light`() {
        // Precondition for everything below. If a floor were darkened past the pivot,
        // every other assertion here would still pass while testing the wrong thing.
        for (t in lightIdentities) {
            assertTrue(
                ColorUtils.isLightBackground(t.backgroundColorValue),
                "${t.id}: background ${t.background} is not above the light pivot, so the " +
                    "render-time guard would not engage for it either",
            )
        }
    }

    @Test
    fun `text slots clear the text floor on the floor they are painted on`() {
        for (t in lightIdentities) {
            val bg = t.backgroundColorValue
            assertFloor(t, "foreground", ratio(t.foregroundColor, bg), textFloor)
            // A hyperlink is text, which is why boss-daylight cannot use the host's
            // `data` #1E7FA8 verbatim: 4.20:1 clears the UI floor but not this one.
            assertFloor(t, "hyperlink", ratio(t.hyperlinkColor, bg), textFloor)
        }
    }

    @Test
    fun `the block cursor is legible against the floor and carries its own glyph`() {
        for (t in lightIdentities) {
            // Against the FLOOR, not against the text: an opaque block covers the cell.
            assertFloor(t, "cursor on background", ratio(t.cursorColor, t.backgroundColorValue), uiFloor)
            // And the character it covers is repainted in cursorText, so that pair is
            // a real glyph-on-fill and takes the text floor.
            assertFloor(t, "cursorText on cursor", ratio(t.cursorTextColor, t.cursorColor), textFloor)
        }
    }

    @Test
    fun `washes are visible without swallowing the text on top of them`() {
        for (t in lightIdentities) {
            val bg = t.backgroundColorValue
            for ((name, wash) in listOf(
                "selection" to t.selectionColor,
                "searchMatch" to t.searchMatchColor,
            )) {
                assertFloor(t, "$name vs background", ratio(wash, bg), washFloor)
                assertFloor(t, "foreground on $name", ratio(t.foregroundColor, wash), textFloor)
            }
            // selectionText is the pair the theme editor advertises for a selection,
            // even though the renderer does not read it yet (see ThemeDefaultsTest's
            // dead-slot list). Authoring it wrong now means shipping the bug the day
            // it gets wired.
            assertFloor(
                t,
                "selectionText on selection",
                ratio(t.selectionTextColor, t.selectionColor),
                textFloor,
            )
        }
    }

    @Test
    fun `every ANSI slot clears the UI floor on paper`() {
        for (t in lightIdentities) {
            val bg = t.backgroundColorValue
            for (i in 0..15) {
                assertFloor(
                    t,
                    "ANSI $i (${Theme.ANSI_COLOR_NAMES[i]})",
                    ratio(t.getAnsiColor(i), bg),
                    uiFloor,
                )
            }
        }
    }

    @Test
    fun `bright ANSI slots are darker than their base, except the dim slot`() {
        for (t in lightIdentities) {
            for (i in 9..15) {
                val base = t.getAnsiColor(i - 8)
                val bright = t.getAnsiColor(i)
                assertTrue(
                    bright.luminance() <= base.luminance(),
                    "${t.id}: ANSI $i (${Theme.ANSI_COLOR_NAMES[i]}) is LIGHTER than ANSI ${i - 8} " +
                        "(${Theme.ANSI_COLOR_NAMES[i - 8]}) - on paper that makes bold text fade out",
                )
            }
            // ANSI 8's exemption comes with its own obligation: it must still be the
            // dimmer of the two greys, or the palette has no dim slot at all.
            assertTrue(
                t.getAnsiColor(8).luminance() > t.getAnsiColor(7).luminance(),
                "${t.id}: ANSI 8 must be dimmer than ANSI 7 to serve as the dim slot",
            )
        }
    }

    /**
     * The exemption list cannot quietly grow, and cannot go stale either.
     *
     * Written as "which builtin light themes fail" rather than "solarized-light is
     * exempt" so that adding a third light identity and forgetting this file fails
     * here, instead of the new theme joining an unexamined exemption.
     */
    @Test
    fun `solarized-light is the only light builtin below these floors`() {
        val failing = BuiltinThemes.ALL
            .filter { ColorUtils.isLightBackground(it.backgroundColorValue) }
            .filter { t ->
                val bg = t.backgroundColorValue
                ratio(t.foregroundColor, bg) < textFloor ||
                    (0..15).any { ratio(t.getAnsiColor(it), bg) < uiFloor }
            }
            .map { it.id }
            .toSet()
        assertEquals(
            setOf("solarized-light"),
            failing,
            "light-palette exemptions changed: fix the theme, or record why it is a " +
                "faithful port that must keep its published values",
        )
    }

    private fun assertFloor(theme: Theme, what: String, actual: Float, floor: Float) {
        assertTrue(
            actual >= floor,
            "${theme.id}: $what is ${"%.2f".format(actual)}:1, below the " +
                "${"%.2f".format(floor)}:1 floor",
        )
    }
}
