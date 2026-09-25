package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.terminal.ArrayTerminalDataStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableHyperlinkGeometryTest {
    private val first = " (https://example.com/" + "a".repeat(8)
    private val url = first.drop(2) + "b"
    private val rule = "─".repeat(30) + "  " + "─".repeat(30)
    private fun at(row: Int, col: Int, text: String) = "\u001b[${row + 1};${col + 1}H$text"

    private fun replay(output: String): TerminalTextBuffer {
        val style = StyleState()
        val buffer = TerminalTextBuffer(100, 20, style, 100)
        val terminal = BossTerminal(HeadlessTerminalDisplay(), buffer, style)
        val emulator = BossEmulator(ArrayTerminalDataStream(output.toCharArray()), terminal)
        while (emulator.hasNext()) emulator.next()
        return buffer
    }

    private fun links(buffer: TerminalTextBuffer, row: Int) = HyperlinkDetector.detectForBufferRow(
        buffer.createIncrementalSnapshot(), row, buffer.width, null, false,
    )

    @Test
    fun unicodeInNeighbouringCellDoesNotShiftRightColumnTargetOrHitBoxes() {
        for (left in listOf("😀", "e\u0301", "中", "👩\u200d💻")) {
            val buffer = replay(at(0, 0, rule) + at(1, 0, left) + at(1, 32, first) +
                at(2, 0, left) + at(2, 32, " b)") + at(3, 0, rule))
            for (row in 1..2) {
                val link = links(buffer, row).single { it.url == url }
                assertEquals(mapOf(1 to (34 to 62), 2 to (33 to 34)), link.rowSpans, left)
                assertTrue(link.containsPosition(34, 1))
                assertTrue(link.containsPosition(33, 2))
                assertFalse(link.containsPosition(2, row))
            }
        }
    }

    @Test
    fun manyEmojiBeforeShortFirstFragmentStillSuppressTheTruncatedRegexLink() {
        val first = "(https://a.co".padStart(30)
        val buffer = replay(at(0, 0, rule) + at(1, 0, "😀".repeat(15)) + at(1, 32, first) +
            at(2, 32, " /b)") + at(3, 0, rule))
        val link = links(buffer, 1).single()
        assertEquals("https://a.co/b", link.url)
        assertEquals(50, link.startCol)
    }

    @Test
    fun unicodePrefixInStackedValueKeepsVisualHitBoxes() {
        val rows = listOf(
            "   PR      😀 (https://example.com/",
            "           path)",
            "   Title   emoji prefix",
            "   Author  name",
            "  " + "─".repeat(60),
        )
        val buffer = replay(rows.joinToString("\r\n"))
        val link = links(buffer, 1).single()
        assertEquals("https://example.com/path", link.url)
        assertEquals(15, link.startCol)
        assertTrue(link.containsPosition(15, 0))
        assertFalse(link.containsPosition(14, 0))
    }

    @Test
    fun textSpillingThroughGutterCannotBeTruncatedIntoATableUrl() {
        val buffer = replay(at(0, 0, rule) + at(1, 0, first + "bad/path") +
            at(2, 0, " b)") + at(3, 0, rule))
        assertTrue(links(buffer, 1).none { it.endRow > it.startRow })
    }

    @Test
    fun singleRuleSeparatesAnUnrelatedTableBlock() {
        val buffer = replay(at(0, 0, rule) + at(1, 0, "─".repeat(62)) +
            at(2, 0, first) + at(3, 0, " b)") + at(4, 0, rule))
        assertTrue(links(buffer, 2).none { it.endRow > it.startRow })
    }
}
