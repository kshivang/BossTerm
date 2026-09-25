package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableHyperlinksTest {
    private val first = " Terminal-tab #111 (https://github.com/risa-labs-inc/boss-plugin-"
    private val continuation = " terminal-tab/pull/111)"
    private val url = "https://github.com/risa-labs-inc/boss-plugin-terminal-tab/pull/111"
    private val width = first.length + 1
    private val rule = "─".repeat(width) + "  " + "─".repeat(width)

    private fun write(buffer: TerminalTextBuffer, row: Int, text: String) {
        val padded = text.padEnd(buffer.width)
        buffer.writeString(0, row + 1, CharBuffer(padded.toCharArray(), 0, padded.length))
    }

    private fun fixture(second: String = continuation): TerminalTextBuffer =
        TerminalTextBuffer(width * 2 + 2, 8, StyleState(), 100).also {
            write(it, 0, rule)
            write(it, 1, first.padEnd(width + 2) + " CI green; font fallback concerns.")
            write(it, 2, second.padEnd(width + 2) + " Hold pending review.")
            write(it, 3, rule)
        }

    private fun links(buffer: TerminalTextBuffer, row: Int) = HyperlinkDetector.detectForBufferRow(
        buffer.createIncrementalSnapshot(), row, buffer.width, null, false,
    )

    @Test
    fun codexTableLinkHasTheCompleteTargetOnBothHardRows() {
        val buffer = fixture()
        for (row in 1..2) {
            val link = links(buffer, row).single()
            assertEquals(url, link.url)
            assertEquals(url, link.matchedText)
            assertTrue(link.containsPosition(first.indexOf("https"), 1))
            assertTrue(link.containsPosition(1, 2))
            assertFalse(link.containsPosition(width + 3, row), "neighbouring column is not clickable")
            assertFalse(link.containsPosition(0, 2), "cell padding is not clickable")
            assertFalse(link.containsPosition(continuation.lastIndex, 2), "closing parenthesis is not part of the link")
        }
    }

    @Test
    fun rightColumnAndThreeRowLinksKeepSeparateSpans() {
        val buffer = fixture()
        val middle = " a".padEnd(width - 1, 'x')
        write(buffer, 1, " left cell".padEnd(width + 2) + first)
        write(buffer, 2, " unrelated prose".padEnd(width + 2) + middle)
        write(buffer, 3, " another left cell".padEnd(width + 2) + continuation)
        write(buffer, 4, rule)
        for (row in 1..3) {
            val link = links(buffer, row).single()
            assertEquals(url.replace("plugin-terminal", "plugin-" + middle.trim() + "terminal"), link.url)
            assertEquals(setOf(1, 2, 3), link.rowSpans.keys)
            assertTrue(link.rowSpans.values.all { it.first >= width + 2 })
            assertFalse(link.containsPosition(2, row))
        }
    }

    @Test
    fun tableLinkInHistoryKeepsItsBufferCoordinates() {
        val buffer = fixture()
        repeat(4) { buffer.scrollArea(1, -1, 8) }
        for (row in -3..-2) {
            val link = links(buffer, row).single()
            assertEquals(url, link.url)
            assertEquals(setOf(-3, -2), link.rowSpans.keys)
        }
    }

    @Test
    fun ordinaryHardNewlinesAreNotJoined() {
        val buffer = fixture()
        write(buffer, 0, " ".repeat(rule.length))
        write(buffer, 3, " ".repeat(rule.length))
        assertEquals("https://github.com/risa-labs-inc/boss-plugin-", links(buffer, 1).single().url)
        assertTrue(links(buffer, 2).isEmpty())
    }

    @Test
    fun blankCellAndProseDoNotCompleteTheUrl() {
        for (second in listOf("", " unrelated prose)", " https://other.example/path)")) {
            val buffer = fixture(second)
            assertTrue(links(buffer, 1).none { it.url == url })
        }
    }

    @Test
    fun separatorTerminatesTheTableRow() {
        val buffer = fixture()
        write(buffer, 2, rule)
        write(buffer, 3, continuation)
        write(buffer, 4, rule)
        assertTrue(links(buffer, 1).none { it.url == url })
        assertTrue(links(buffer, 3).isEmpty())
    }

    @Test
    fun shortFirstFragmentDoesNotImplyAWrap() {
        val buffer = fixture()
        write(buffer, 1, " (https://example.com/".padEnd(width + 2) + " unrelated text")
        assertEquals("https://example.com/", links(buffer, 1).single().url)
        assertTrue(links(buffer, 2).isEmpty())
    }

    @Test
    fun disabledHttpPatternIsRespected() {
        val buffer = fixture()
        val registry = HyperlinkRegistry().apply { clear() }
        assertTrue(HyperlinkDetector.detectForBufferRow(
            buffer.createIncrementalSnapshot(), 2, buffer.width, null, false, registry,
        ).isEmpty())
    }

    @Test
    fun continuationAndRuleChangesInvalidateMemoizedFirstRow() {
        val buffer = fixture()
        val cache = HyperlinkRowCache()
        fun cached() = buffer.createIncrementalSnapshot().let { snapshot ->
            cache.linksAt(1, HyperlinkDetector.runLinesAt(snapshot, 1), null, false, 0) {
                HyperlinkDetector.detectForBufferRow(snapshot, 1, buffer.width, null, false)
            }
        }
        assertEquals(url, cached().single().url)
        write(buffer, 2, " terminal-tab/pull/112)".padEnd(width + 2) + " Hold pending review.")
        assertEquals(url.replace("111", "112"), cached().single().url)
        write(buffer, 3, " ".repeat(rule.length))
        assertEquals("https://github.com/risa-labs-inc/boss-plugin-", cached().single().url)
    }

    @Test
    fun terminalSoftWrapStillJoinsBothClickableRows() {
        val buffer = TerminalTextBuffer(20, 4, StyleState(), 100)
        write(buffer, 0, "https://example.com/a")
        write(buffer, 1, "bc")
        buffer.getLine(0).isWrapped = true
        for (row in 0..1) {
            val link = links(buffer, row).single()
            assertEquals("https://example.com/abc", link.url)
            assertTrue(link.containsPosition(0, 0))
            assertTrue(link.containsPosition(0, 1))
        }
    }
}
