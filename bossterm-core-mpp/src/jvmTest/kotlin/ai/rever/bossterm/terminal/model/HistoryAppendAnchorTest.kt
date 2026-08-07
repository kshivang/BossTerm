package ai.rever.bossterm.terminal.model

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
 * report appends, and the `scrollArea` path a full-screen TUI drives (DECSTBM with top == 1)
 * previously reported nothing at all.
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
        assertTrue(listener.discarded > 0, "a capped history must also report its discards")
        assertEquals(3, buffer.historyLinesCount)
    }
}
