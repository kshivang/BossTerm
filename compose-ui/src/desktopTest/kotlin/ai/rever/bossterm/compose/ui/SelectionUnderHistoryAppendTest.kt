package ai.rever.bossterm.compose.ui

import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.compose.selection.SelectionTracker
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Does compensating the scroll offset make a selection slide against the text it covers?
 *
 * The PR claimed it might. This test exists to settle that rather than leave a guess in the
 * description: selection anchors are content-anchored (`SelectionTracker` resolves them by
 * `TerminalLine` object identity against the render snapshot, every frame), so an append moves the
 * resolved buffer row and the compensated offset by the same amount, and the on-screen row
 * (`bufferRow + scrollOffset`) is what has to stay put.
 *
 * Checked both ways round, because "stays aligned" is only meaningful against the alternative.
 */
class SelectionUnderHistoryAppendTest {

    private fun scenario(): Triple<TerminalTextBuffer, SelectionTracker, Int> {
        val buffer = TerminalTextBuffer(20, 4, StyleState(), 100)
        for (row in 1..4) {
            val chars = "L$row".toCharArray()
            buffer.writeString(0, row, CharBuffer(chars, 0, chars.size))
        }
        // Push two lines into history so there is something to be scrolled up over.
        repeat(2) { buffer.scrollArea(1, -1, 4) }

        val tracker = SelectionTracker(buffer)
        // Select the oldest history line, at buffer row -2.
        tracker.setSelection(0, -2, 3, -2, SelectionMode.NORMAL)
        return Triple(buffer, tracker, 2)
    }

    /** Where the selection actually paints: buffer row plus the offset. */
    private fun screenRow(buffer: TerminalTextBuffer, tracker: SelectionTracker, offset: Int): Int? =
        tracker.resolveToCoordinates(buffer.createIncrementalSnapshot())?.startRow?.plus(offset)

    @Test
    fun compensatingTheOffsetKeepsTheSelectionOnScreenWhereItWas() {
        val (buffer, tracker, offset) = scenario()
        val before = screenRow(buffer, tracker, offset)

        // A line of streaming output arrives, and the fix folds it into the offset.
        buffer.scrollArea(1, -1, 4)
        val compensated = foldHistoryAppends(offset, appended = 1, historyCount = buffer.historyLinesCount)

        assertEquals(
            before,
            screenRow(buffer, tracker, compensated),
            "the selection must paint on the same screen row it did before the append",
        )
    }

    /**
     * The counterfactual. Without compensation the selection moves up a row - but so does the text
     * under it, which is the drift being fixed. So the selection was never sliding *against* the
     * text in either regime; both are resolved through the same buffer-row mapping.
     */
    @Test
    fun withoutCompensationTheSelectionMovesWithTheTextNotAgainstIt() {
        val (buffer, tracker, offset) = scenario()
        val before = screenRow(buffer, tracker, offset)

        buffer.scrollArea(1, -1, 4)
        val uncompensated = screenRow(buffer, tracker, offset)

        assertEquals(
            before!! - 1,
            uncompensated,
            "uncompensated, the selection moves up exactly one row - the same one row the text moves",
        )
    }

    /** The anchored row itself tracks its line, which is what makes the above hold. */
    @Test
    fun theAnchorFollowsItsLineDeeperIntoHistory() {
        val (buffer, tracker, _) = scenario()
        val startBefore = tracker.resolveToCoordinates(buffer.createIncrementalSnapshot())?.startRow

        buffer.scrollArea(1, -1, 4)
        val startAfter = tracker.resolveToCoordinates(buffer.createIncrementalSnapshot())?.startRow

        assertEquals(
            startBefore!! - 1,
            startAfter,
            "one append moves the anchored line one row further from the live bottom",
        )
    }
}
