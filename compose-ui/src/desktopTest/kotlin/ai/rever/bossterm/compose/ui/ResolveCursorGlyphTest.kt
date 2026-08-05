package ai.rever.bossterm.compose.ui

import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.util.CharUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a block cursor is allowed to repaint on top of itself.
 *
 * Every rejection here mirrors something the main text pass already declines to
 * draw. Painting where it declines puts a character on screen that exists nowhere
 * else - which is worse than the blank block this feature set out to fix, because
 * it looks correct.
 *
 * These cases are deliberately unit tests rather than pixel tests: the pixel
 * tests in `CursorOverlayTest` prove a glyph reaches the canvas, but they pass a
 * plain ASCII "W" with no cell style, so they cannot see DWC markers, surrogates,
 * conceal or blink at all.
 */
class ResolveCursorGlyphTest {
    private fun line(
        text: String,
        style: TextStyle? = null,
    ): TerminalLine {
        val entry = TerminalLine.TextEntry(style ?: TextStyle.EMPTY, CharBuffer(text))
        return TerminalLine(entry)
    }

    private fun styleWith(option: TextStyle.Option): TextStyle = TextStyle.Builder().setOption(option, true).build()

    @Test
    fun `a plain character is repainted`() {
        assertEquals("a", resolveCursorGlyph(line("abc"), column = 0, width = 3, blinkVisible = true))
        assertEquals("c", resolveCursorGlyph(line("abc"), column = 2, width = 3, blinkVisible = true))
    }

    @Test
    fun `a blank cell has nothing to put back`() {
        assertNull(resolveCursorGlyph(line("a c"), column = 1, width = 3, blinkVisible = true))
        // Past the end of the line reads as an empty cell, not a crash.
        assertNull(resolveCursorGlyph(line("a"), column = 4, width = 8, blinkVisible = true))
    }

    @Test
    fun `out of range columns are rejected`() {
        assertNull(resolveCursorGlyph(line("abc"), column = -1, width = 3, blinkVisible = true))
        assertNull(resolveCursorGlyph(line("abc"), column = 3, width = 3, blinkVisible = true))
    }

    /**
     * `charAt` returns the private-use DWC marker for the trailing cell of a
     * double-width character. Stringifying it paints U+E000 tofu, which is what
     * the first version of this feature did.
     */
    @Test
    fun `a DWC trailing cell is rejected`() {
        val wide = line("你${CharUtils.DWC}")
        assertNull(resolveCursorGlyph(wide, column = 1, width = 2, blinkVisible = true))
    }

    /**
     * And the LEAD cell of that same character is rejected too: the block covers
     * one cell, so a two-cell glyph would be clipped in half.
     */
    @Test
    fun `a double-width lead cell is rejected`() {
        val wide = line("你${CharUtils.DWC}")
        assertNull(resolveCursorGlyph(wide, column = 0, width = 2, blinkVisible = true))
    }

    /**
     * One UTF-16 unit cannot carry an astral code point; a lone surrogate shapes
     * to U+FFFD.
     */
    @Test
    fun `surrogate halves are rejected`() {
        val emoji = line("😀") // U+1F600
        assertNull(resolveCursorGlyph(emoji, column = 0, width = 2, blinkVisible = true))
        assertNull(resolveCursorGlyph(emoji, column = 1, width = 2, blinkVisible = true))
    }

    /**
     * SGR 8. Password prompts use conceal, and the cursor sits exactly on the
     * character just typed - revealing it only under the cursor would be a
     * disclosure, not a cosmetic bug.
     */
    @Test
    fun `a concealed cell is never revealed`() {
        val hidden = line("secret", styleWith(TextStyle.Option.HIDDEN))
        assertNull(resolveCursorGlyph(hidden, column = 0, width = 6, blinkVisible = true))
    }

    /** A blink-off cell is invisible everywhere else at that moment. */
    @Test
    fun `a blinking cell follows the blink phase`() {
        for (option in listOf(TextStyle.Option.SLOW_BLINK, TextStyle.Option.RAPID_BLINK)) {
            val blinking = line("x", styleWith(option))
            assertEquals("x", resolveCursorGlyph(blinking, column = 0, width = 1, blinkVisible = true), "$option on")
            assertNull(resolveCursorGlyph(blinking, column = 0, width = 1, blinkVisible = false), "$option off")
        }
    }
}
