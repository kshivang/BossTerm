package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.compose.rendering.TerminalCanvasRenderer
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hyperlink detection in BUFFER coordinates.
 *
 * The point of the conversion is that a viewport position can no longer be a parameter, so
 * "pure scrolling reuses detection" is a fact about the signature rather than a claim about a
 * cache hit rate. These tests pin that, and pin the wrapped-line walk that used to add the
 * scroll offset where every caller subtracted it.
 */
class HyperlinkBufferRowTest {

    private fun write(buffer: TerminalTextBuffer, row: Int, text: String) {
        val chars = text.toCharArray()
        buffer.writeString(0, row, CharBuffer(chars, 0, chars.size))
    }

    private fun links(buffer: TerminalTextBuffer, bufferRow: Int) =
        TerminalCanvasRenderer.detectHyperlinksForBufferRow(
            snapshot = buffer.createIncrementalSnapshot(),
            bufferRow = bufferRow,
            terminalWidth = buffer.width,
            workingDirectory = null,
            detectFilePaths = false,
            registry = HyperlinkDetector.registry,
        )

    @Test
    fun aLinkOnTheLiveScreenIsFoundAtItsBufferRow() {
        val buffer = TerminalTextBuffer(40, 4, StyleState(), 100)
        write(buffer, 2, "see https://example.com ok")

        val found = links(buffer, bufferRow = 1)
        assertEquals(1, found.size)
        assertEquals("https://example.com", found[0].url)
        assertEquals(1, found[0].row, "rows are buffer rows, not screen rows")
    }

    @Test
    fun aLinkScrolledIntoHistoryKeepsItsNegativeBufferRow() {
        val buffer = TerminalTextBuffer(40, 4, StyleState(), 100)
        write(buffer, 1, "see https://example.com ok")
        // Push it two rows back into history.
        repeat(2) { buffer.scrollArea(1, -1, 4) }

        val found = links(buffer, bufferRow = -2)
        assertEquals(1, found.size, "history rows are addressable by negative index")
        assertEquals("https://example.com", found[0].url)
        assertEquals(-2, found[0].row)
    }

    /**
     * The regression test for the sign bug.
     *
     * `collectWrappedLines` used to compute `row + scrollOffset` while its only caller computed
     * screen rows as `row - scrollOffset`, so a wrapped link that had scrolled into history was
     * assembled from the wrong lines entirely. With the offset gone there is nothing left to get
     * backwards, and the spans land on the two rows the link actually occupies.
     */
    @Test
    fun aWrappedLinkInHistorySpansTheRowsItActuallyOccupies() {
        val buffer = TerminalTextBuffer(20, 4, StyleState(), 100)
        // The first row must fill all 20 columns exactly. A short row is padded to the terminal
        // width when the logical line is rejoined, and that padding would put a space inside the
        // URL - a genuine gap, not a wrap.
        write(buffer, 1, "https://example.com/")
        write(buffer, 2, "deep/path")
        buffer.getLine(0).isWrapped = true
        repeat(2) { buffer.scrollArea(1, -1, 4) }

        // Both halves now sit in history at buffer rows -2 and -1.
        val found = links(buffer, bufferRow = -2)
        val link = found.firstOrNull { it.url.contains("example.com") }
        assertNotNull(link, "the wrapped link should be detected from its first row")
        assertTrue(
            link.rowSpans.keys.containsAll(listOf(-2, -1)),
            "spans should cover both history rows, were ${link.rowSpans.keys}",
        )
    }

    @Test
    fun theMemoReturnsTheCachedListWhileTheLineInstanceIsUnchanged() {
        val buffer = TerminalTextBuffer(40, 4, StyleState(), 100)
        write(buffer, 2, "see https://example.com ok")
        val snapshot = buffer.createIncrementalSnapshot()
        val cache = HyperlinkRowCache()
        var detections = 0

        repeat(5) {
            cache.linksAt(1, snapshot.getLine(1)) {
                detections++
                emptyList()
            }
        }
        assertEquals(1, detections, "a scroll must not re-detect an unchanged row")
    }

    @Test
    fun theMemoReDetectsWhenTheLineInstanceIsReplaced() {
        val buffer = TerminalTextBuffer(40, 4, StyleState(), 100)
        write(buffer, 2, "first")
        val cache = HyperlinkRowCache()
        var detections = 0
        val detect = { detections++; emptyList<Hyperlink>() }

        cache.linksAt(1, buffer.createIncrementalSnapshot().getLine(1), detect = detect)
        write(buffer, 2, "second line entirely")
        cache.linksAt(1, buffer.createIncrementalSnapshot().getLine(1), detect = detect)

        assertEquals(2, detections, "a changed row must miss")
    }

    @Test
    fun bypassSkipsTheMemoEntirely() {
        val buffer = TerminalTextBuffer(40, 4, StyleState(), 100)
        write(buffer, 2, "wrapped content")
        val line = buffer.createIncrementalSnapshot().getLine(1)
        val cache = HyperlinkRowCache()
        var detections = 0

        repeat(3) {
            cache.linksAt(1, line, bypass = true) {
                detections++
                emptyList()
            }
        }
        assertEquals(3, detections, "wrapped rows depend on neighbours, so they never memoize")
    }
}
