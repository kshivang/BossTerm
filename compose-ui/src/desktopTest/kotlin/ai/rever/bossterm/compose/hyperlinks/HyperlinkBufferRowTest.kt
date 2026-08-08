package ai.rever.bossterm.compose.hyperlinks

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
        HyperlinkDetector.detectForBufferRow(
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
            cache.linksAt(1, listOf(snapshot.getLine(1)), cwd = null, detectFilePaths = false) {
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

        cache.linksAt(1, listOf(buffer.createIncrementalSnapshot().getLine(1)), null, false, detect)
        write(buffer, 2, "second line entirely")
        cache.linksAt(1, listOf(buffer.createIncrementalSnapshot().getLine(1)), null, false, detect)

        assertEquals(2, detections, "a changed row must miss")
    }

    /**
     * A wrapped row must still memoize.
     *
     * The first version of this cache bypassed the memo for any wrapped row, which meant every
     * pointer-move event re-ran the whole-logical-line rejoin plus the registry sweep - on the
     * UI thread, in a session where wrapped rows are the common case, not the exception.
     */
    @Test
    fun aWrappedRunMemoizesWhileEveryLineInItIsUnchanged() {
        val buffer = TerminalTextBuffer(20, 4, StyleState(), 100)
        write(buffer, 1, "https://example.com/")
        write(buffer, 2, "deep/path")
        buffer.getLine(0).isWrapped = true
        val snapshot = buffer.createIncrementalSnapshot()
        val cache = HyperlinkRowCache()
        var detections = 0

        repeat(5) {
            cache.linksAt(
                bufferRow = 0,
                runLines = HyperlinkDetector.runLinesAt(snapshot, 0),
                cwd = null,
                detectFilePaths = false,
            ) { detections++; emptyList() }
        }
        assertEquals(1, detections, "an unchanged wrapped run must not re-detect per event")
    }

    @Test
    fun aWrappedRunReDetectsWhenASiblingLineChanges() {
        val buffer = TerminalTextBuffer(20, 4, StyleState(), 100)
        write(buffer, 1, "https://example.com/")
        write(buffer, 2, "deep/path")
        buffer.getLine(0).isWrapped = true
        val cache = HyperlinkRowCache()
        var detections = 0
        val detect = { detections++; emptyList<Hyperlink>() }

        val first = buffer.createIncrementalSnapshot()
        cache.linksAt(0, HyperlinkDetector.runLinesAt(first, 0), null, false, detect)
        // Row 0 itself is untouched - the CONTINUATION changes, and it is part of the answer.
        write(buffer, 2, "other/path/here")
        val second = buffer.createIncrementalSnapshot()
        cache.linksAt(0, HyperlinkDetector.runLinesAt(second, 0), null, false, detect)

        assertEquals(2, detections, "the run's other lines are part of the key")
    }

    /**
     * Detection resolves relative paths against the working directory, so an unchanged line
     * must still re-detect after a `cd` - otherwise a stale `file://` target survives forever,
     * since the line instance never changes.
     */
    @Test
    fun aWorkingDirectoryChangeReDetectsAnUnchangedLine() {
        val buffer = TerminalTextBuffer(40, 4, StyleState(), 100)
        write(buffer, 2, "see ./src/main.kt")
        val snapshot = buffer.createIncrementalSnapshot()
        val cache = HyperlinkRowCache()
        var detections = 0
        val detect = { detections++; emptyList<Hyperlink>() }
        val run = listOf(snapshot.getLine(1))

        cache.linksAt(1, run, cwd = "/a", detectFilePaths = true, detect = detect)
        cache.linksAt(1, run, cwd = "/a", detectFilePaths = true, detect = detect)
        assertEquals(1, detections, "same cwd, same line: one detection")

        cache.linksAt(1, run, cwd = "/b", detectFilePaths = true, detect = detect)
        assertEquals(2, detections, "a cd must invalidate paths resolved against the old cwd")

        cache.linksAt(1, run, cwd = "/b", detectFilePaths = false, detect = detect)
        assertEquals(3, detections, "toggling path detection must invalidate too")
    }
}
