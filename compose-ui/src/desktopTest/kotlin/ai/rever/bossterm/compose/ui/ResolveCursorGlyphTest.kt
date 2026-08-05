package ai.rever.bossterm.compose.ui

import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.compose.rendering.CursorGlyph
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
        assertEquals(CursorGlyph("a", isBold = false, isItalic = false), resolveCursorGlyph(line("abc"), column = 0, width = 3, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
        assertEquals(CursorGlyph("c", isBold = false, isItalic = false), resolveCursorGlyph(line("abc"), column = 2, width = 3, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
    }

    @Test
    fun `a blank cell has nothing to put back`() {
        assertNull(resolveCursorGlyph(line("a c"), column = 1, width = 3, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
        // Past the end of the line reads as an empty cell, not a crash.
        assertNull(resolveCursorGlyph(line("a"), column = 4, width = 8, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
    }

    @Test
    fun `out of range columns are rejected`() {
        assertNull(resolveCursorGlyph(line("abc"), column = -1, width = 3, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
        assertNull(resolveCursorGlyph(line("abc"), column = 3, width = 3, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
    }

    /**
     * `charAt` returns the private-use DWC marker for the trailing cell of a
     * double-width character. Stringifying it paints U+E000 tofu, which is what
     * the first version of this feature did.
     */
    @Test
    fun `a DWC trailing cell is rejected`() {
        val wide = line("你${CharUtils.DWC}")
        assertNull(resolveCursorGlyph(wide, column = 1, width = 2, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
    }

    /**
     * And the LEAD cell of that same character is rejected too: the block covers
     * one cell, so a two-cell glyph would be clipped in half.
     */
    @Test
    fun `a double-width lead cell is rejected`() {
        val wide = line("你${CharUtils.DWC}")
        assertNull(resolveCursorGlyph(wide, column = 0, width = 2, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
    }

    /**
     * One UTF-16 unit cannot carry an astral code point; a lone surrogate shapes
     * to U+FFFD.
     */
    @Test
    fun `surrogate halves are rejected`() {
        val emoji = line("😀") // U+1F600
        assertNull(resolveCursorGlyph(emoji, column = 0, width = 2, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
        assertNull(resolveCursorGlyph(emoji, column = 1, width = 2, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
    }

    /**
     * SGR 8. Password prompts use conceal, and the cursor sits exactly on the
     * character just typed - revealing it only under the cursor would be a
     * disclosure, not a cosmetic bug.
     */
    @Test
    fun `a concealed cell is never revealed`() {
        val hidden = line("secret", styleWith(TextStyle.Option.HIDDEN))
        assertNull(resolveCursorGlyph(hidden, column = 0, width = 6, ambiguousCharsAreDoubleWidth = false, slowBlinkVisible = true, rapidBlinkVisible = true))
    }

    /**
     * Each SGR blink option is gated against its OWN clock. The first version of
     * this passed one `blinkVisible` for both and was handed the CARET timer, so a
     * blink-off cell was repainted whenever the caret happened to be solid, and a
     * steady cell flashed on the caret's clock. One parameter could not see it.
     */
    @Test
    fun `each blink option follows its own clock`() {
        val slow = line("x", styleWith(TextStyle.Option.SLOW_BLINK))
        assertEquals(
            CursorGlyph("x", isBold = false, isItalic = false),
            resolveCursorGlyph(slow, 0, 1, false, slowBlinkVisible = true, rapidBlinkVisible = false),
            "slow blink on",
        )
        assertNull(
            resolveCursorGlyph(slow, 0, 1, false, slowBlinkVisible = false, rapidBlinkVisible = true),
            "slow blink off must not be repainted just because the rapid clock is on",
        )

        val rapid = line("x", styleWith(TextStyle.Option.RAPID_BLINK))
        assertEquals(
            CursorGlyph("x", isBold = false, isItalic = false),
            resolveCursorGlyph(rapid, 0, 1, false, slowBlinkVisible = false, rapidBlinkVisible = true),
            "rapid blink on",
        )
        assertNull(
            resolveCursorGlyph(rapid, 0, 1, false, slowBlinkVisible = true, rapidBlinkVisible = false),
            "rapid blink off must not be repainted just because the slow clock is on",
        )
    }

    /**
     * `renderCharacter` routes these to a fallback family (Apple Color Emoji,
     * NotoSansSymbols2, FontFamily.Default), and the overlay only carries the
     * terminal font - repainting them here would draw a different glyph than the
     * text pass drew a frame earlier.
     *
     * Checked against the classifier's ACTUAL ranges rather than an assumption
     * about them: `isTechnicalSymbol` is U+23E9..U+23FF, and `isCursiveOrMath` is
     * U+1D400..U+1D7FF which is astral, so the surrogate rejection already covers
     * it. Plain BMP math like U+2200 is NOT flagged and is drawn in the terminal
     * font by both paths, so repainting it is correct - an earlier version of this
     * test asserted otherwise and was simply wrong about the code.
     */
    @Test
    fun `characters needing a fallback font are rejected`() {
        // U+23F8 PAUSE, inside the technical-symbol range.
        assertNull(
            resolveCursorGlyph(line("\u23F8"), 0, 1, false, slowBlinkVisible = true, rapidBlinkVisible = true),
            "a technical symbol routes to a fallback family",
        )
        // U+231A WATCH, listed in UnicodeConstants.isBmpEmoji. Picked by reading
        // that predicate rather than guessing: U+2764 HEAVY BLACK HEART is NOT in
        // it, so an earlier version of this assertion failed for being wrong.
        assertNull(
            resolveCursorGlyph(line("\u231A"), 0, 1, false, slowBlinkVisible = true, rapidBlinkVisible = true),
            "a BMP emoji-presentation symbol routes to a fallback family",
        )
    }

    /** BMP math the terminal font handles is repainted, since both paths agree. */
    @Test
    fun `plain BMP math is repainted in the terminal font`() {
        assertEquals(
            CursorGlyph("\u2200", isBold = false, isItalic = false),
            resolveCursorGlyph(line("\u2200"), 0, 1, false, slowBlinkVisible = true, rapidBlinkVisible = true),
        )
    }

    /** Bold and italic are carried, so the cell does not flatten under the cursor. */
    @Test
    fun `bold and italic are carried through`() {
        val bold = line("b", styleWith(TextStyle.Option.BOLD))
        assertEquals(
            CursorGlyph("b", isBold = true, isItalic = false),
            resolveCursorGlyph(bold, 0, 1, false, slowBlinkVisible = true, rapidBlinkVisible = true),
        )
        val italic = line("i", styleWith(TextStyle.Option.ITALIC))
        assertEquals(
            CursorGlyph("i", isBold = false, isItalic = true),
            resolveCursorGlyph(italic, 0, 1, false, slowBlinkVisible = true, rapidBlinkVisible = true),
        )
    }
}
