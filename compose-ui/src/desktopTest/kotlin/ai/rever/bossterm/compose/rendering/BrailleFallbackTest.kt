package ai.rever.bossterm.compose.rendering

import ai.rever.bossterm.compose.util.extractBundledFont
import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.TerminalLine
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Braille Patterns (U+2800..U+28FF) are pinned to the bundled Noto Sans Symbols 2
 * instead of system fallback (issue #407). On macOS the "Apple Braille" family can
 * resolve to `AppleBraille-Outline6Dot`, which draws a hollow outline at every dot
 * the pattern does NOT set - a Codex spinner then shows a static six-dot grid.
 *
 * The glyph assertions below read the bundled font's actual outlines rather than
 * trusting the family name, because the family name is exactly what was misleading
 * in the original report: both runtimes reported "Apple Braille" while drawing
 * different faces.
 */
class BrailleFallbackTest {

    @Test
    fun `braille patterns are classified for the explicit fallback`() {
        for (codePoint in listOf(0x2800, 0x2801, 0x2802, 0x2840, 0x28FF)) {
            val line = terminalLine(String(Character.toChars(codePoint)))
            val analysis = analyzeCharacter(line.charAt(0), line, 0, line.length(), false)
            assertTrue(analysis.isBraille, "U+${codePoint.toString(16)} must be Braille")
            assertEquals(1, analysis.visualWidth, "Braille stays a single cell")
        }
    }

    @Test
    fun `the characters either side of the block are not braille`() {
        for (codePoint in listOf(0x27FF, 0x2900)) {
            val line = terminalLine(String(Character.toChars(codePoint)))
            assertFalse(analyzeCharacter(line.charAt(0), line, 0, line.length(), false).isBraille)
        }
    }

    /**
     * The acceptance criteria from the issue, checked against outlines: U+2800 blank,
     * one dot for a one-dot pattern, and dots 7/8 present (they sit below the baseline,
     * which is why a font missing them looks plausible until someone types U+28C0).
     */
    @Test
    fun `the bundled symbol font draws only the dots a pattern sets`() {
        val file = extractBundledFont("fonts/NotoSansSymbols2-Regular.ttf")
        assertNotNull(file, "bundled symbol font resource must be present")
        val typeface = FontMgr.default.makeFromData(Data.makeFromBytes(file.readBytes()))
        assertNotNull(typeface, "bundled symbol font must be loadable by Skia")
        val font = Font(typeface, 64f)

        fun boundsOf(codePoint: Int) = font.getPath(typeface.getUTF32Glyph(codePoint))?.bounds

        for (codePoint in 0x2800..0x28FF) {
            assertTrue(typeface.getUTF32Glyph(codePoint) != 0.toShort(), "U+${codePoint.toString(16)} must be covered")
        }

        val blank = boundsOf(0x2800)
        assertTrue(blank == null || blank.width == 0f, "U+2800 must be visually blank, was $blank")

        val oneDot = boundsOf(0x2801)!!
        val twoDot = boundsOf(0x2803)!!
        assertTrue(oneDot.width > 0f, "U+2801 must draw something")
        // A one-dot pattern covers one dot's worth of height; a two-dot column covers
        // strictly more. An outline face would make both span the whole six-dot grid.
        assertTrue(
            twoDot.height > oneDot.height * 1.5f,
            "U+2803 must be taller than U+2801 (got ${oneDot.height} vs ${twoDot.height}) - " +
                "equal heights mean unused dot positions are being drawn",
        )

        // Dot 8 alone (U+2880) sits below the baseline: negative y in Skia's coordinates.
        assertTrue(boundsOf(0x2880)!!.top > 0f, "dot 8 must render below the baseline")
    }

    private fun terminalLine(text: String): TerminalLine =
        TerminalLine(TerminalLine.TextEntry(TextStyle.EMPTY, CharBuffer(text)))
}
