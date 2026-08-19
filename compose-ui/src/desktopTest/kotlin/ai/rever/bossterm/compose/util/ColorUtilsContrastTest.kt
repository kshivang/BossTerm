package ai.rever.bossterm.compose.util

import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The light-background legibility guard.
 *
 * The bug these pin: a truecolor CLI hardcodes a dark-terminal palette, so on a light
 * theme its primary text is painted at 1.07:1 and vanishes. Captured from a live
 * Claude Code session, which emits literal `ESC[38;2;255;255;255m`.
 */
class ColorUtilsContrastTest {

    /** `BossBlueprintLightColorScheme.ink` — the paper floor a light host theme yields. */
    private val paper = Color(0xFFF5F7FB)

    /** `BuiltinThemes.BOSS_BLUEPRINT.background` — the default dark theme's floor. */
    private val blueprintInk = Color(0xFF05070B)

    private val floor = 4.5f

    private fun guard(fg: Color, bg: Color, minRatio: Float = floor) =
        ColorUtils.legibleOnLightBackground(fg, bg, minRatio)

    @Test
    fun `pure white on paper becomes legible ink`() {
        val before = ColorUtils.contrastRatio(Color.White, paper)
        assertTrue(before < 1.1f, "precondition: white on paper should be invisible, was $before:1")

        val guarded = guard(Color.White, paper)
        val after = ColorUtils.contrastRatio(guarded, paper)
        assertTrue(after >= floor, "white on paper guarded to $guarded, only $after:1")
        // The mirror should send lightness 1.0 to 0.0, not merely to the floor — that is
        // what makes primary text read as ink instead of a washed-out grey.
        assertTrue(
            guarded.luminance() < 0.02f,
            "expected near-black, got $guarded (luminance ${guarded.luminance()})"
        )
    }

    @Test
    fun `white on its own black background is untouched`() {
        // Claude Code paints ESC[48;2;0;0;0m blocks. White there is already 21:1; the
        // guard must not follow the theme and wreck them.
        assertEquals(Color.White, guard(Color.White, Color.Black))
    }

    @Test
    fun `dim grey on the default dark theme is untouched`() {
        // The scope decision: the guard is a no-op on dark backgrounds, so every dark
        // theme renders byte-identically. #505050 is 2.35:1 on blueprint ink — genuinely
        // dim — and stays exactly that.
        val dim = Color(0xFF505050)
        assertEquals(dim, guard(dim, blueprintInk))
    }

    @Test
    fun `greys stay grey and keep their hierarchy`() {
        val guarded = guard(Color(0xFF999999), paper)
        val ratio = ColorUtils.contrastRatio(guarded, paper)
        assertTrue(ratio >= floor, "#999999 on paper guarded to $guarded, only $ratio:1")

        // Still neutral: mirroring preserves saturation, so a grey must not gain a tint.
        assertEquals(guarded.red, guarded.green, "guarded grey $guarded is not neutral")
        assertEquals(guarded.green, guarded.blue, "guarded grey $guarded is not neutral")

        // And still lighter than guarded primary text, so the CLI's own hierarchy survives.
        assertTrue(
            guarded.luminance() > guard(Color.White, paper).luminance(),
            "#999999 guarded to $guarded, no lighter than guarded white"
        )
    }

    @Test
    fun `a brand hue survives the correction`() {
        // Claude Code's ESC[38;2;215;119;87m — 2.94:1 on paper.
        val orange = Color(0xFFD77757)
        val guarded = guard(orange, paper)
        val ratio = ColorUtils.contrastRatio(guarded, paper)
        assertTrue(ratio >= floor, "#D77757 on paper guarded to $guarded, only $ratio:1")

        // Hue is preserved by construction; assert the channel ordering that makes it
        // recognisably orange rather than a neutral grey.
        assertTrue(
            guarded.red > guarded.green && guarded.green > guarded.blue,
            "expected an orange, got $guarded"
        )
    }

    @Test
    fun `a fully saturated colour is rescued by the clamp`() {
        // ESC[38;2;255;193;7m sits at HSL lightness 0.51, so mirroring barely moves it
        // (#FFC107 -> #F8BC00). This is the case only the clamp step can fix.
        val amber = Color(0xFFFFC107)
        val mirroredOnly = ColorUtils.contrastRatio(Color(0xFFF8BC00), paper)
        assertTrue(mirroredOnly < floor, "precondition: the mirror alone should fall short")

        val ratio = ColorUtils.contrastRatio(guard(amber, paper), paper)
        assertTrue(ratio >= floor, "#FFC107 on paper reached only $ratio:1")
    }

    @Test
    fun `dim text stays secondary after guarding`() {
        val normal = guard(Color.White, paper)
        val dimmed = guard(ColorUtils.applyDimColor(Color.White), paper)

        assertTrue(
            ColorUtils.contrastRatio(dimmed, paper) < ColorUtils.contrastRatio(normal, paper),
            "ESC[2m guarded to $dimmed, not weaker than plain text at $normal"
        )
        assertTrue(
            ColorUtils.contrastRatio(dimmed, paper) >= floor,
            "dim text fell below the floor at $dimmed"
        )
    }

    @Test
    fun `a ratio of one or less disables the guard`() {
        assertEquals(Color.White, guard(Color.White, paper, minRatio = 1f))
        assertEquals(Color.White, guard(Color.White, paper, minRatio = 0f))
    }

    @Test
    fun `guarding is idempotent`() {
        for (fg in listOf(Color.White, Color(0xFF999999), Color(0xFFD77757), Color(0xFFFFC107))) {
            val once = guard(fg, paper)
            assertEquals(once, guard(once, paper), "guard is not idempotent for $fg")
        }
    }

    @Test
    fun `alpha is preserved`() {
        // Compared against the input's own alpha, not the 0.5f literal: Compose packs
        // sRGB channels at 8-bit precision, so 0.5f is already 128/255 by the time it
        // reaches the guard.
        val translucent = Color.White.copy(alpha = 0.5f)
        assertEquals(translucent.alpha, guard(translucent, paper).alpha, "guard dropped alpha")

        // Also through the clamp path, which rebuilds the color a second time.
        val amber = Color(0xFFFFC107).copy(alpha = 0.75f)
        assertEquals(amber.alpha, guard(amber, paper).alpha, "clamp path dropped alpha")
    }

    @Test
    fun `every dark builtin theme is unaffected and every light one is corrected`() {
        val samples = listOf(Color.White, Color(0xFF999999), Color(0xFFD77757), Color(0xFF505050))
        for (theme in BuiltinThemes.ALL) {
            val bg = theme.backgroundColorValue
            if (bg.luminance() > 0.1791f) {
                for (fg in samples) {
                    val ratio = ColorUtils.contrastRatio(guard(fg, bg), bg)
                    assertTrue(
                        ratio >= floor,
                        "${theme.id}: $fg guarded to only $ratio:1 on a light floor"
                    )
                }
            } else {
                for (fg in samples) {
                    assertEquals(fg, guard(fg, bg), "${theme.id}: dark theme must be untouched")
                }
            }
        }
    }

    @Test
    fun `the cache returns the same answer as a cold computation`() {
        // The guard memoises per (fg, bg, minRatio). Interleave pairs so the single-entry
        // memo is evicted between repeats, then confirm the repeats still agree.
        val pairs = listOf(
            Color.White to paper,
            Color(0xFF999999) to paper,
            Color.White to Color.Black,
            Color(0xFF505050) to blueprintInk
        )
        val first = pairs.map { (fg, bg) -> guard(fg, bg) }
        repeat(3) {
            pairs.forEachIndexed { i, (fg, bg) ->
                assertEquals(first[i], guard(fg, bg), "cached result diverged for $fg on $bg")
            }
        }

        // A changed floor must not serve a stale entry.
        val strict = guard(Color(0xFF999999), paper, minRatio = 7f)
        assertTrue(
            ColorUtils.contrastRatio(strict, paper) >= 7f,
            "raising the floor served a stale result: $strict"
        )
    }

    @Test
    fun `contrastRatio matches known WCAG values`() {
        assertClose(21f, ColorUtils.contrastRatio(Color.White, Color.Black))
        assertClose(1f, ColorUtils.contrastRatio(Color.White, Color.White))
        // Sanity-check the pivot: white and black are equally legible on it.
        val pivot = Color(0xFF777777)
        val onWhite = ColorUtils.contrastRatio(Color.White, pivot)
        val onBlack = ColorUtils.contrastRatio(Color.Black, pivot)
        assertTrue(abs(onWhite - onBlack) < 0.6f, "pivot check: $onWhite vs $onBlack")
    }

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.1f) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected but was $actual"
        )
    }
}
