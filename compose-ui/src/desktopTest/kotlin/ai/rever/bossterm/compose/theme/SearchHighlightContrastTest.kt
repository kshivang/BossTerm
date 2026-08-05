package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Why the search highlight is a background at [SEARCH_FILL_ALPHA] rather than a
 * heavier wash over the glyphs.
 *
 * The old arrangement drew the wash in Pass 3, on top of the text, so alpha served
 * emphasis and legibility at once. Moving it to Pass 1.5 (as selection already
 * was) makes it a true background - but a background still has to leave the text
 * readable, and in several builtins `searchMatch` sits close in luminance to the
 * foreground, so "just use it at full strength" does not work.
 *
 * These are the numbers that picked 0.35. They are asserted rather than written in
 * a comment because the constant is exactly the kind of thing that gets nudged up
 * for prominence, and the themes that suffer most are the ones nobody is looking
 * at when they nudge it.
 */
class SearchHighlightContrastTest {
    /** Mirrors `TerminalCanvasRenderer.SEARCH_FILL_ALPHA` (private). */
    private val fillAlpha = 0.35f

    /** The two alphas the old over-the-glyph wash used. */
    private val oldOtherAlpha = 0.32f
    private val oldCurrentAlpha = 0.65f

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun over(
        top: Color,
        bottom: Color,
        alpha: Float,
    ): Color =
        Color(
            red = top.red * alpha + bottom.red * (1 - alpha),
            green = top.green * alpha + bottom.green * (1 - alpha),
            blue = top.blue * alpha + bottom.blue * (1 - alpha),
        )

    /**
     * A fill BEHIND the glyphs must leave every theme a usable fraction of its own
     * foreground contrast. Expressed as a fraction, not an absolute floor: a couple
     * of builtins (both Solarizeds) do not clear 4.5:1 even unhighlighted, so an
     * absolute floor would be unreachable rather than informative.
     */
    @Test
    fun `the search fill keeps a third of every theme's own contrast`() {
        val worst =
            BuiltinThemes.ALL.minOf { theme ->
                val fg = theme.foregroundColor
                val bg = theme.backgroundColorValue
                contrast(fg, over(theme.searchMatchColor, bg, fillAlpha)) / contrast(fg, bg)
            }
        assertTrue(
            worst >= 0.33,
            "the search fill leaves the worst theme only ${(worst * 100).toInt()}% of its own " +
                "contrast; lower SEARCH_FILL_ALPHA or pick a different searchMatch",
        )
    }

    /**
     * The strong guarantee: a background fill beats the CURRENT-match wash it
     * replaced, on every theme. That wash was the worst-reading state in the
     * terminal - at 0.65 it left one builtin 11.6% of its own contrast.
     *
     * Against the old OTHER-match wash (0.32) the comparison is closer, and
     * measured honestly it is not a clean win everywhere: the ratio runs 0.87
     * (one-dark) to 1.05 (solarized-light). The number cannot express what
     * actually improved - the old wash tinted the GLYPH toward the highlight
     * colour, and a background does not - so this pins a floor rather than
     * claiming a gain.
     */
    @Test
    fun `a background fill beats the current-match wash and holds against the other`() {
        for (theme in BuiltinThemes.ALL) {
            val fg = theme.foregroundColor
            val bg = theme.backgroundColorValue
            val match = theme.searchMatchColor

            val behind = contrast(fg, over(match, bg, fillAlpha))
            // The old wash tinted the glyph AND the cell behind it.
            val oldOther = contrast(over(match, fg, oldOtherAlpha), over(match, bg, oldOtherAlpha))
            val oldCurrent = contrast(over(match, fg, oldCurrentAlpha), over(match, bg, oldCurrentAlpha))

            assertTrue(
                behind > oldCurrent,
                "${theme.id}: behind-text ($behind) must beat the old current-match wash ($oldCurrent)",
            )
            assertTrue(
                behind >= oldOther * 0.85,
                "${theme.id}: behind-text ($behind) fell more than 15% below the old " +
                    "other-match wash ($oldOther); measured worst is 0.87 on one-dark",
            )
        }
    }

    /**
     * The reason the current match no longer gets a heavier fill: a heavier one is
     * not available. Pins that raising the alpha makes the worst theme worse, so
     * the outline really does have to carry the distinction.
     */
    @Test
    fun `a heavier fill would degrade the worst theme further`() {
        fun worstRetention(alpha: Float) =
            BuiltinThemes.ALL.minOf { theme ->
                val fg = theme.foregroundColor
                val bg = theme.backgroundColorValue
                contrast(fg, over(theme.searchMatchColor, bg, alpha)) / contrast(fg, bg)
            }
        assertTrue(
            worstRetention(0.5f) < worstRetention(fillAlpha),
            "if a heavier fill did not cost contrast, the current match should use one",
        )
    }
}
