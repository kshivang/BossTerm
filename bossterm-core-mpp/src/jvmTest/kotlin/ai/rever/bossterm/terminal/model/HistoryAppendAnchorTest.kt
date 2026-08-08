package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.core.util.CellPosition
import ai.rever.bossterm.core.util.TermSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A viewport anchored to the bottom of the buffer addresses different content every time lines
 * are appended to history: `bufferLine = screenRow - scrollOffset`, so growing the history moves
 * the user's content one row further from the anchor per appended line.
 *
 * iTerm2 has the mirror-image problem — its viewport is anchored to the top, so lines evicted
 * from the head move it — and compensates in `PTYTextView.handleScrollbackOverflow:` using a
 * count accumulated since the last sync. Doing the same here requires the buffer to actually
 * report appends, and the `scrollArea` path a streaming process drives (DECSTBM with top == 1)
 * previously reported nothing at all.
 *
 * Note this buffer accumulates scrollback on the ALTERNATE screen too, so appends are reported
 * there as well; the consumer decides what to do with those. See [theAlternateScreenAlsoReportsItsAppends].
 */
class HistoryAppendAnchorTest {

    private class RecordingListener : TextBufferChangesListener {
        var appended = 0
        var appendCallbacks = 0
        var discarded = 0

        override fun linesAddedToHistory(count: Int) {
            appended += count
            appendCallbacks++
        }

        override fun linesDiscardedFromHistory(lines: List<TerminalLine>) {
            discarded += lines.size
        }
    }

    private fun buffer(width: Int = 20, height: Int = 4, maxHistory: Int = 100) =
        TerminalTextBuffer(width, height, StyleState(), maxHistory)

    private fun TerminalTextBuffer.write(row: Int, text: String) {
        val chars = text.toCharArray()
        writeString(0, row, CharBuffer(chars, 0, chars.size))
    }

    /** Text of the line at a bottom-anchored viewport position, via the render snapshot. */
    private fun TerminalTextBuffer.lineAt(screenRow: Int, scrollOffset: Int): String =
        createIncrementalSnapshot().getLine(screenRow - scrollOffset).text.trimEnd()

    @Test
    fun scrollAreaReportsLinesItAppendsToHistory() {
        val buffer = buffer()
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "L$row")

        // DECSTBM region starting at row 1 — what a full-screen TUI scrolls content inside.
        buffer.scrollArea(1, -2, 4)

        assertEquals(2, listener.appended, "the two lines that scrolled off must be reported")
        assertEquals(2, buffer.historyLinesCount)
    }

    @Test
    fun everyAppendIsReportedNotJustTheFirst() {
        val buffer = buffer()
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "L$row")

        repeat(3) { buffer.scrollArea(1, -1, 4) }

        assertEquals(3, listener.appendCallbacks)
        assertEquals(3, listener.appended)
    }

    @Test
    fun regionNotStartingAtTheTopAppendsNothing() {
        val buffer = buffer()
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "L$row")

        // Content scrolled inside a region below row 1 never reaches history.
        buffer.scrollArea(2, -1, 4)

        assertEquals(0, listener.appended)
        assertEquals(0, buffer.historyLinesCount)
    }

    @Test
    fun uncompensatedBottomAnchorDriftsWhenHistoryGrows() {
        val buffer = buffer()
        for (row in 1..4) buffer.write(row, "L$row")
        val offset = 2
        buffer.scrollArea(1, -2, 4)
        for (row in 3..4) buffer.write(row, "N$row")

        val before = buffer.lineAt(screenRow = 0, scrollOffset = offset)
        buffer.scrollArea(1, -1, 4)
        val after = buffer.lineAt(screenRow = 0, scrollOffset = offset)

        assertTrue(
            before != after,
            "holding the offset fixed must drift — this is the bug being compensated " +
                "(row 0 showed '$before', now shows '$after')",
        )
    }

    @Test
    fun addingTheReportedCountKeepsTheViewportOnTheSameContent() {
        val buffer = buffer()
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "L$row")
        var offset = 2
        buffer.scrollArea(1, -2, 4)
        for (row in 3..4) buffer.write(row, "N$row")

        val before = buffer.lineAt(screenRow = 0, scrollOffset = offset)
        listener.appended = 0
        buffer.scrollArea(1, -1, 4)
        offset += listener.appended // what ProperTerminal folds in per frame

        assertEquals(
            before,
            buffer.lineAt(screenRow = 0, scrollOffset = offset),
            "compensating by the reported append count must re-address the same line",
        )
    }

    @Test
    fun cappedHistoryStillReportsAppendsWhileDiscarding() {
        val buffer = buffer(maxHistory = 3)
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "L$row")

        repeat(5) { buffer.scrollArea(1, -1, 4) }

        assertEquals(5, listener.appended, "appends must be reported even once history is capped")
        // Exact, not `> 0`: five appends into a 3-line history discards exactly 2, and a loose
        // assertion would not notice an off-by-one in the discard path.
        assertEquals(2, listener.discarded, "a capped history discards exactly the overflow")
        assertEquals(3, buffer.historyLinesCount)
    }

    /**
     * The alternate screen accumulates scrollback in this buffer - `useAlternateBuffer` swaps in a
     * live storage and `scrollArea` has no alt guard - so appends are reported there too. Pinned
     * because a consumer anchoring a viewport has to know this: folding alt-screen appends walks
     * the offset against a history that is discarded when the TUI exits.
     */
    @Test
    fun theAlternateScreenAlsoReportsItsAppends() {
        val buffer = buffer()
        buffer.useAlternateBuffer(true)
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "A$row")

        repeat(3) { buffer.scrollArea(1, -1, 4) }

        assertEquals(3, listener.appended, "alt-screen appends are reported like any other")
        assertEquals(3, buffer.historyLinesCount, "and they really do land in a history buffer")
    }

    /** `moveScreenLinesToHistory` also routes through `addLinesToHistory`, so it must report too. */
    @Test
    fun movingScreenLinesToHistoryReportsTheAppend() {
        val buffer = buffer()
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "M$row")

        buffer.moveScreenLinesToHistory()

        assertEquals(
            buffer.historyLinesCount,
            listener.appended,
            "every line moved into history must be reported",
        )
        assertTrue(listener.appended > 0, "the move must have produced at least one append")
    }

    /**
     * The third caller of `addLinesToHistory`: a height SHRINK pushes the rows that no longer fit
     * into history, and reports them like any other append.
     *
     * Pinned because it is the caller with the documented asymmetry - a height GROWTH pulls lines
     * back out of history with no corresponding event - so a consumer that only sums these counts
     * over-counts across a shrink/grow cycle. Inert in the app today, where the layout callback
     * resets the offset on any resize, but the event contract is what is being asserted here.
     */
    @Test
    fun aHeightShrinkReportsTheRowsItPushesIntoHistory() {
        val buffer = buffer(height = 4)
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "L$row")

        buffer.resize(TermSize(20, 2), CellPosition(1, 4), selection = null)

        assertEquals(
            buffer.historyLinesCount,
            listener.appended,
            "the rows that no longer fit are reported as appends",
        )
        assertTrue(listener.appended > 0, "a shrink from 4 rows to 2 must push something down")
    }

    /**
     * An empty append must not wake listeners with a zero count.
     *
     * Deliberately NOT `scrollArea(1, 0, 4)`: `dy == 0` returns at the top of `scrollArea` and
     * never reaches `addLinesToHistory`, so that would pass with the guard deleted. A degenerate
     * region does reach it - the rotation yields no rows, and the top-anchored branch then calls
     * `addLinesToHistory` with an empty list.
     */
    @Test
    fun anEmptyAppendReportsNothing() {
        val buffer = buffer()
        val listener = RecordingListener()
        buffer.addChangesListener(listener)
        for (row in 1..4) buffer.write(row, "L$row")

        buffer.scrollArea(1, -1, 0)

        assertEquals(0, listener.appendCallbacks, "no callback at all for an empty append")
    }
}
