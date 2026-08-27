package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.util.CharUtils
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A model check on overlapping writes into a [TerminalLine].
 *
 * `writeString` into the middle of a line goes through `merge`, which rebuilds the whole
 * line into a char array plus a style array and then re-derives the style runs. Any faster
 * path for that has to produce the identical line, and "identical" here means every cell's
 * character and every cell's style - not a similar-looking entry list.
 *
 * This validates against an independent model (a plain char array and style array the test
 * maintains itself) rather than against the previous implementation, so it pins the
 * specification instead of the incumbent behaviour, and it keeps `TextEntries` private.
 *
 * Randomised with a fixed seed: the interesting cases are overlaps that start and end
 * partway through an existing run, and enumerating those by hand reliably misses some.
 */
class TerminalLineWriteModelTest {

    private val styles = listOf(
        TextStyle.EMPTY,
        TextStyle(null, null, setOf(TextStyle.Option.BOLD)),
        TextStyle(null, null, setOf(TextStyle.Option.ITALIC)),
        TextStyle(null, null, setOf(TextStyle.Option.UNDERLINED))
    )

    /** The line as the terminal should see it, maintained independently of TerminalLine. */
    private class Model {
        val chars = ArrayList<Char>()
        val styles = ArrayList<TextStyle>()

        fun write(x: Int, text: String, style: TextStyle) {
            // A gap is written as NUL, but TextEntries.add un-nullifies it as soon as any
            // non-NUL entry lands after it, so it reads back as EMPTY_CHAR. Every case here
            // writes text after the gap, so that conversion has always happened.
            while (chars.size < x) {
                chars.add(CharUtils.EMPTY_CHAR)
                styles.add(TextStyle.EMPTY)
            }
            for (i in text.indices) {
                val at = x + i
                if (at < chars.size) {
                    chars[at] = text[i]
                    styles[at] = style
                } else {
                    chars.add(text[i])
                    styles.add(style)
                }
            }
        }
    }

    private fun assertMatches(line: TerminalLine, model: Model, note: String) {
        assertEquals(model.chars.size, line.length(), "length differs after $note")
        for (i in model.chars.indices) {
            assertEquals(model.chars[i], line.charAt(i), "char at $i differs after $note")
            assertEquals(
                model.styles[i],
                line.getStyleAt(i) ?: TextStyle.EMPTY,
                "style at $i differs after $note"
            )
        }
    }

    @Test
    fun overlappingWritesMatchTheModel() {
        val random = Random(20260827)
        repeat(200) { case ->
            val line = TerminalLine()
            val model = Model()
            val ops = StringBuilder()

            repeat(6) {
                val x = random.nextInt(0, 24)
                val len = random.nextInt(1, 12)
                val style = styles[random.nextInt(styles.size)]
                val text = (0 until len).map { ('a' + random.nextInt(26)) }.joinToString("")

                ops.append("write($x, \"$text\") ")
                line.writeString(x, CharBuffer(text), style)
                model.write(x, text, style)
            }
            assertMatches(line, model, "case $case: $ops")
        }
    }

    @Test
    fun aWriteStrictlyInsideAnExistingRunSplitsItCorrectly() {
        // The case a faster merge is most likely to get wrong: the overwrite starts and
        // ends partway through one run, so the run has to become head + new + tail.
        val line = TerminalLine()
        val model = Model()
        line.writeString(0, CharBuffer("aaaaaaaaaa"), styles[0])
        model.write(0, "aaaaaaaaaa", styles[0])
        line.writeString(3, CharBuffer("XY"), styles[1])
        model.write(3, "XY", styles[1])
        assertMatches(line, model, "inner split")
    }

    @Test
    fun aWriteExtendingPastTheEndKeepsTheTail() {
        val line = TerminalLine()
        val model = Model()
        line.writeString(0, CharBuffer("abcde"), styles[0])
        model.write(0, "abcde", styles[0])
        line.writeString(3, CharBuffer("ZZZZ"), styles[2])
        model.write(3, "ZZZZ", styles[2])
        assertMatches(line, model, "write past end")
    }

    @Test
    fun aWritePastTheEndFillsTheGap() {
        val line = TerminalLine()
        val model = Model()
        line.writeString(0, CharBuffer("ab"), styles[0])
        model.write(0, "ab", styles[0])
        line.writeString(6, CharBuffer("Q"), styles[1])
        model.write(6, "Q", styles[1])
        assertMatches(line, model, "gap fill")
    }

    @Test
    fun awholeLineOverwriteReplacesEverything() {
        val line = TerminalLine()
        val model = Model()
        line.writeString(0, CharBuffer("abcdef"), styles[0])
        model.write(0, "abcdef", styles[0])
        line.writeString(0, CharBuffer("ABCDEF"), styles[3])
        model.write(0, "ABCDEF", styles[3])
        assertMatches(line, model, "full overwrite")
    }
}
