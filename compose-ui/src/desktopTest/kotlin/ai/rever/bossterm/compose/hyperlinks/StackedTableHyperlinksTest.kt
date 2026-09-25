package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackedTableHyperlinksTest {
    // Exact rows from the user's narrow Codex table, captured through read_scrollback.
    // No opening rule; the first URL fragment ends at column 63, well before the rule's 71.
    private val record = listOf(
        "   PR      #1671 (https://github.com/risa-labs-inc/BossConsole/",
        "           pull/1671)",
        "   Title   fix(db): clamp search_plugins paging in SQL",
        "   Author  arjun28115 (https://github.com/arjun28115)",
        "   Opened  Sep 24, 2026",
        "   Review  5 comments",
        "  " + "─".repeat(69),
    )
    private val url = "https://github.com/risa-labs-inc/BossConsole/pull/1671"

    private fun fixture(lines: List<String> = record): TerminalTextBuffer =
        TerminalTextBuffer(80, 24, StyleState(), 100).also { buffer ->
            lines.forEachIndexed { row, text -> write(buffer, row, text) }
        }

    private fun write(buffer: TerminalTextBuffer, row: Int, text: String) {
        val padded = text.padEnd(buffer.width)
        buffer.writeString(0, row + 1, CharBuffer(padded.toCharArray(), 0, padded.length))
    }

    private fun links(buffer: TerminalTextBuffer, row: Int) = HyperlinkDetector.detectForBufferRow(
        buffer.createIncrementalSnapshot(), row, buffer.width, null, false,
    )

    @Test
    fun capturedFirstRecordWorksWithoutAnOpeningRuleOrEdgeFillingUrl() {
        val buffer = fixture()
        for (row in 0..1) {
            val link = links(buffer, row).single()
            assertEquals(url, link.url)
            assertTrue(link.containsPosition(record[0].indexOf("https"), 0))
            assertTrue(link.containsPosition(11, 1))
            assertFalse(link.containsPosition(3, 0), "field label is not part of the link")
            assertFalse(link.containsPosition(10, 1), "indentation is not part of the link")
            assertFalse(link.containsPosition(20, 1), "closing delimiter is not part of the link")
        }
        assertEquals("https://github.com/arjun28115", links(buffer, 3).single().url)
    }

    @Test
    fun repeatedRecordsDoNotShareUrlFragments() {
        val buffer = fixture(record + record.map { it.replace("1671", "1662") })
        assertEquals(url, links(buffer, 1).single().url)
        assertEquals(url.replace("1671", "1662"), links(buffer, 8).single().url)
    }

    @Test
    fun capturedRecordKeepsNegativeHistoryCoordinates() {
        val buffer = fixture()
        repeat(7) { buffer.scrollArea(1, -1, 24) }
        for (row in -7..-6) {
            val link = links(buffer, row).single()
            assertEquals(url, link.url)
            assertEquals(setOf(-7, -6), link.rowSpans.keys)
        }
    }

    @Test
    fun ordinaryIndentedProseWithATrailingRuleIsNotJoined() {
        val buffer = fixture(listOf(record[0], record[1], "   This is ordinary prose.", record.last()))
        assertTrue(links(buffer, 0).none { it.url == url })
        assertTrue(links(buffer, 1).isEmpty())
    }

    @Test
    fun missingRuleAndMisalignedFieldsAreNotAStackedTable() {
        for (lines in listOf(record.dropLast(1), record.map { it.replace("Title   ", "Title  ") })) {
            val buffer = fixture(lines)
            assertTrue(links(buffer, 0).none { it.url == url })
            assertTrue(links(buffer, 1).isEmpty())
        }
    }

    @Test
    fun blankSeparatorProseAndNewFieldTerminateUrlContinuation() {
        for (fragment in listOf("", record.last(), "           unrelated prose)", "   Other   pull/1671)", "          pull/1671)")) {
            val lines = record.toMutableList().also { it[1] = fragment }
            assertTrue(links(fixture(lines), 0).none { it.url == url }, fragment)
        }
    }

    @Test
    fun threeFragmentsAndWrappedOtherValuesKeepFieldGeometry() {
        val lines = record.toMutableList().also {
            it[1] = "           pull/"
            it.add(2, "           1671)")
            it.add(4, "           more title text")
        }
        val buffer = fixture(lines)
        for (row in 0..2) {
            val link = links(buffer, row).single()
            assertEquals(url, link.url)
            assertEquals(setOf(0, 1, 2), link.rowSpans.keys)
        }
    }

    @Test
    fun disabledRegistryDoesNotRecreateHttpLinks() {
        val buffer = fixture()
        val registry = HyperlinkRegistry().apply { clear() }
        assertTrue(HyperlinkDetector.detectForBufferRow(
            buffer.createIncrementalSnapshot(), 1, buffer.width, null, false, registry,
        ).isEmpty())
    }

    @Test
    fun continuationEditsInvalidateTheCachedTarget() {
        val buffer = fixture()
        val cache = HyperlinkRowCache()
        fun cached() = buffer.createIncrementalSnapshot().let { snapshot ->
            cache.linksAt(0, HyperlinkDetector.runLinesAt(snapshot, 0), null, false, 0) {
                HyperlinkDetector.detectForBufferRow(snapshot, 0, buffer.width, null, false)
            }
        }
        assertEquals(url, cached().single().url)
        write(buffer, 1, "           pull/1662)")
        assertEquals(url.replace("1671", "1662"), cached().single().url)
    }
}
