package ai.rever.bossterm.compose.rendering

import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.TerminalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ASCII fast path skips a 20-char lookahead and three sequence probes. It is only
 * sound if it never skips a probe that would have fired: one false positive turns a flag,
 * a family emoji or a skin-toned hand into mojibake, and none of the existing tests would
 * notice because they never render.
 *
 * So rather than trusting the reasoning, this compares the fast path against the very
 * probes it elides, over a corpus that includes every sequence kind the renderer special
 * cases, at every column.
 */
class PlainAsciiFastPathTest {

    private val corpus = listOf(
        "plain ascii text 0123456789 !@#\$%^&*()",
        "ls -la  drwxr-xr-x  12 user  staff   384 Aug 26 10:11  src",
        "👨‍💻 developer",            // ZWJ: man technologist
        "👩‍👩‍👧 fam", // ZWJ family
        "flag 🇺🇸 usa",                    // regional indicators
        "wave 👋🏽 done",                   // skin tone modifier
        "check ✔️ mark",                              // variation selector
        "cjk 你好 world",                               // wide chars
        "powerline  prompt",
        "a‍b",                                             // ZWJ between ASCII
        "x🏽y",                                       // skin tone right after ASCII
    )

    @Test
    fun fastPathNeverSkipsAProbeThatWouldHaveFired() {
        for (text in corpus) {
            val line = terminalLine(text)
            val limit = line.length()
            for (col in 0 until limit) {
                if (!TerminalCanvasRendererTestAccess.isPlainAsciiRun(line, col, limit)) continue

                assertFalse(
                    TerminalCanvasRenderer.checkFollowingSkinTone(line, col, limit),
                    "skin tone at col $col of ${text.escaped()} would have been skipped"
                )
                assertTrue(
                    TerminalCanvasRenderer.checkRegionalIndicatorSequence(line, col, limit) == 0,
                    "regional indicator at col $col of ${text.escaped()} would have been skipped"
                )
                // A grapheme cluster can only pull a ZWJ into THIS cell from the very next
                // one, so ASCII at col and col+1 is what rules the ZWJ branch out.
                assertTrue(line.charAt(col).code < 0x80, "col $col of ${text.escaped()} is not ASCII")
                if (col + 1 < limit) {
                    assertTrue(
                        line.charAt(col + 1).code < 0x80,
                        "col ${col + 1} of ${text.escaped()} is not ASCII"
                    )
                }
            }
        }
    }

    @Test
    fun fastPathStillFiresOnOrdinaryText() {
        // The guard must not be so conservative that it never engages - a fast path that
        // is always off would pass the test above trivially while changing nothing.
        val line = terminalLine("the quick brown fox jumps over the lazy dog")
        val limit = line.length()
        val engaged = (0 until limit).count {
            TerminalCanvasRendererTestAccess.isPlainAsciiRun(line, col = it, bufferLimit = limit)
        }
        assertTrue(engaged == limit, "expected the fast path at all $limit columns, got $engaged")
    }

    @Test
    fun fastPathDisengagesAroundNonAscii() {
        val line = terminalLine("ab你cd")
        val limit = line.length()
        // The wide char sits at index 2, and the guard looks at col..col+2, so columns 0,
        // 1 and 2 must all decline.
        assertFalse(TerminalCanvasRendererTestAccess.isPlainAsciiRun(line, 0, limit))
        assertFalse(TerminalCanvasRendererTestAccess.isPlainAsciiRun(line, 1, limit))
        assertFalse(TerminalCanvasRendererTestAccess.isPlainAsciiRun(line, 2, limit))
        assertTrue(TerminalCanvasRendererTestAccess.isPlainAsciiRun(line, 3, limit))
    }

    private fun String.escaped(): String = replace(Regex("[^\\x20-\\x7E]")) { m ->
        "\\u%04X".format(m.value.first().code)
    }

    private fun terminalLine(text: String): TerminalLine = TerminalLine(
        TerminalLine.TextEntry(TextStyle.EMPTY, CharBuffer(text))
    )
}

/** File-private top-level functions are not reachable from a test; this is. */
internal object TerminalCanvasRendererTestAccess {
    fun isPlainAsciiRun(line: TerminalLine, col: Int, bufferLimit: Int): Boolean =
        ai.rever.bossterm.compose.rendering.isPlainAsciiRun(line, col, bufferLimit)
}

/**
 * Blanks now extend a batched run instead of breaking it, which is what collapses one
 * drawText per word into one per line. The risk that buys is dropping a character that
 * should have been drawn, so the trim is pinned here.
 */
class VisibleRunLengthTest {

    @Test
    fun trailingBlanksAreNotShaped() {
        assertEquals(3, visibleRunLength("abc   ", underlined = false))
        assertEquals(0, visibleRunLength("    ", underlined = false))
        assertEquals(0, visibleRunLength("", underlined = false))
    }

    @Test
    fun interiorBlanksAreKept() {
        // The whole point: "a   b" must stay one run of five cells, not be cut to "a".
        assertEquals(5, visibleRunLength("a   b", underlined = false))
        assertEquals(8, visibleRunLength("a   b  c", underlined = false))
    }

    @Test
    fun underlinedRunsKeepTheirTrailingBlanks() {
        // The blanks carry the rule, so trimming them would shorten a visible underline.
        assertEquals(6, visibleRunLength("abc   ", underlined = true))
        assertEquals(4, visibleRunLength("    ", underlined = true))
    }

    @Test
    fun nonBlankRunsAreUntouched() {
        assertEquals(3, visibleRunLength("abc", underlined = false))
        assertEquals(3, visibleRunLength("abc", underlined = true))
    }
}
