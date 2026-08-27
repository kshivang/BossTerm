package ai.rever.bossterm.terminal.util

import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.TerminalLine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `visualColToBufferCol` short-circuits to the identity for lines that need no
 * visual-column mapping. That is sound only because such a line holds nothing above
 * U+007F: no double-width character, no DWC marker (U+E000), no combining mark, no
 * surrogate. Every buffer column is then exactly one terminal cell.
 *
 * The guard is the whole correctness argument, so it is tested here rather than assumed.
 * Without these, removing the guard entirely - taking the fast path for every line - passed
 * the whole suite, which is exactly the silent breakage that matters: columns would be
 * mapped as if wide characters were one cell wide, so selections, mouse hit-testing and
 * line-wrap truncation would all land on the wrong cell whenever CJK or emoji is on screen.
 */
class ColumnConversionFastPathTest {

    private val style = TextStyle.EMPTY

    private fun asciiLine(text: String) = TerminalLine().apply {
        writeString(0, CharBuffer(text), style)
    }

    /** A line where each wide char occupies two cells: the char then a DWC marker. */
    private fun wideLine(): TerminalLine {
        val sb = StringBuilder()
        // "ab" then two double-width CJK characters, each followed by its DWC spacer.
        sb.append("ab")
        sb.append('你').append(CharUtils.DWC)
        sb.append('好').append(CharUtils.DWC)
        sb.append("cd")
        return TerminalLine().apply { writeString(0, CharBuffer(sb.toString()), style) }
    }

    @Test
    fun plainAsciiMapsAsTheIdentity() {
        val line = asciiLine("hello world")
        val width = line.length()
        for (v in 0..width) {
            assertEquals(minOf(v, width), ColumnConversionUtils.visualColToBufferCol(line, v, width))
        }
    }

    @Test
    fun wideCharactersDoNotMapAsTheIdentity() {
        // The assertion that kills a missing guard: visual column 4 sits inside the CJK
        // run, whose buffer index is further along because each wide char carries a DWC
        // spacer. If the fast path were taken here, this would come back as 4.
        val line = wideLine()
        val width = line.length()

        // buffer:  0=a 1=b 2=你 3=DWC 4=好 5=DWC 6=c 7=d
        // visual:  0=a 1=b 2..3=你     4..5=好      6=c 7=d
        //
        // Most columns agree with the identity by coincidence. The ones that do NOT are the
        // second cell of each wide character, where the visual column points into the glyph
        // and the buffer index must snap back to its start - landing on the DWC marker
        // instead is precisely what a missing guard would do.
        assertEquals(
            2, ColumnConversionUtils.visualColToBufferCol(line, 3, width),
            "visual col 3 is the second cell of the first wide char: it must snap back to " +
                "buffer 2, not land on the DWC marker at buffer 3"
        )
        assertEquals(
            4, ColumnConversionUtils.visualColToBufferCol(line, 5, width),
            "visual col 5 is the second cell of the second wide char: it must snap back to " +
                "buffer 4, not land on the DWC marker at buffer 5"
        )
    }

    @Test
    fun theGuardRejectsAWideLineAndAcceptsAnAsciiOne() {
        // Direct check on the predicate the fast path keys off, so a change to how
        // `requiresVisualColumnMapping` is maintained cannot silently widen the fast path.
        assertEquals(false, asciiLine("plain ascii").requiresVisualColumnMapping)
        assertEquals(true, wideLine().requiresVisualColumnMapping)
    }
}
