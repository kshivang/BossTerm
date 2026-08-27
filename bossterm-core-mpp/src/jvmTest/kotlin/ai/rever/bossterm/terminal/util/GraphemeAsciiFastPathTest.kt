package ai.rever.bossterm.terminal.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `segmentIntoGraphemes` skips ICU entirely for printable ASCII, which is essentially all
 * terminal output. The claim is that ICU cannot disagree there: nothing below U+0080 is
 * wide, ambiguous, combining, a surrogate, a ZWJ or a variation selector.
 *
 * That claim is checkable rather than arguable, so it is checked here - against the very
 * BreakIterator path it replaces, over every printable ASCII character and a corpus of real
 * terminal output.
 */
class GraphemeAsciiFastPathTest {

    private fun assertSameSegmentation(text: String) {
        val fast = GraphemeUtils.segmentIntoGraphemes(text)
        val icu = GraphemeUtils.segmentViaBreakIterator(text)
        assertEquals(icu.size, fast.size, "cluster count differs for " + text.take(40))
        for (i in icu.indices) {
            assertEquals(icu[i].text, fast[i].text, "cluster $i text differs")
            assertEquals(icu[i].visualWidth, fast[i].visualWidth, "cluster $i width differs")
            assertTrue(
                icu[i].codePoints.contentEquals(fast[i].codePoints),
                "cluster $i code points differ"
            )
        }
    }

    @Test
    fun everyPrintableAsciiCharacterSegmentsIdentically() {
        for (code in 0x20..0x7E) {
            assertSameSegmentation(code.toChar().toString())
        }
    }

    @Test
    fun realTerminalOutputSegmentsIdentically() {
        listOf(
            "the quick brown fox jumps over the lazy dog 0123456789",
            "drwxr-xr-x  12 user  staff   384 Aug 26 10:11  src",
            "  INFO  module-07  request completed in  42 ms  path=/api/v1/x/9",
            "a b  c   d    e",
            "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        ).forEach(::assertSameSegmentation)
    }

    @Test
    fun nonAsciiStillGoesThroughIcu() {
        // The guard must reject anything ICU could segment differently, or the fast path
        // would flatten a family emoji into several one-column clusters.
        listOf(
            "👨‍💻",   // ZWJ: man technologist
            "🇺🇸",         // regional indicators: flag
            "👋🏽",         // skin tone modifier
            "✔️",                     // variation selector
            "你好",                     // CJK
            "é",                          // combining acute
            "ascii then 你"                 // mixed
        ).forEach { text ->
            assertFalse(
                GraphemeUtils.isAllPrintableAscii(text),
                "guard must not claim this is plain ASCII"
            )
            assertSameSegmentation(text)
        }
    }

    @Test
    fun controlCharactersAreNotTakenByTheFastPath() {
        // Their width is not simply 1, so admitting them would be a behaviour change
        // rather than an optimisation.
        assertFalse(GraphemeUtils.isAllPrintableAscii("a\tb"))
        assertFalse(GraphemeUtils.isAllPrintableAscii("a\nb"))
        assertFalse(GraphemeUtils.isAllPrintableAscii("a\u0000b"))
        assertFalse(GraphemeUtils.isAllPrintableAscii("a\u007Fb"))
        // A plain space, by contrast, IS printable ASCII and must stay on the fast
        // path - it is the most common character in terminal output.
        assertTrue(GraphemeUtils.isAllPrintableAscii("a b"))
    }

    @Test
    fun theFastPathActuallyEngages() {
        // A guard that never fires would pass every check above while changing nothing.
        assertTrue(GraphemeUtils.isAllPrintableAscii("plain ascii line 123"))
    }
}
