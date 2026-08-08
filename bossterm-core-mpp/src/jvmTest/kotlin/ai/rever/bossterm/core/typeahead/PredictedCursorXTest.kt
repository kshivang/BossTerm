package ai.rever.bossterm.core.typeahead

import ai.rever.bossterm.core.typeahead.TypeAheadTerminalModel.LineWithCursorX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `predictedCursorX` answers "where should the caret be drawn while local echo is predicting",
 * and nothing else.
 *
 * The property it replaced, `cursorX`, fell back to the LIVE emulator column whenever there was
 * nothing predicted - which is the steady state. Its only caller paired that live value with a
 * row captured from an earlier render frame, producing a coordinate the terminal was never in,
 * and re-read it on every recomposition, so the caret's column jittered for the length of a
 * scroll gesture with no output change at all. Returning null is what lets the renderer fall
 * back to its own frame instead.
 */
class PredictedCursorXTest {

    private class FakeModel(
        var cursorX: Int = 0,
        lineText: String = "",
        override var isUsingAlternateBuffer: Boolean = false,
    ) : TypeAheadTerminalModel {
        var clearPredictionsCalls = 0
        private val line = StringBuffer(lineText)

        /**
         * The shell echoing a character back - the only thing that moves the REAL terminal.
         *
         * Kept separate from [insertCharacter] and friends on purpose: those are the display
         * side, where a prediction is painted speculatively, and they must not show up in
         * [currentLineWithCursor]. The manager compares that against its predictions to decide
         * whether one came true, so a fake that let prediction application feed back into it
         * would never match and would reset on every keystroke.
         */
        fun echo(ch: Char) {
            line.insert(cursorX, ch)
            cursorX++
        }

        override fun insertCharacter(ch: Char, index: Int) = Unit

        override fun removeCharacters(from: Int, count: Int) = Unit

        override fun moveCursor(index: Int) = Unit

        override fun forceRedraw() = Unit

        override fun clearPredictions() {
            clearPredictionsCalls++
        }

        override fun lock() = Unit
        override fun unlock() = Unit

        override val currentLineWithCursor: LineWithCursorX
            get() = LineWithCursorX(StringBuffer(line), cursorX)

        override val terminalWidth: Int get() = 80
        override val isTypeAheadEnabled: Boolean get() = true

        // Zero, so a prediction counts as "visible" immediately rather than waiting on latency.
        override val latencyThreshold: Long get() = 0L
        override val shellType: TypeAheadTerminalModel.ShellType?
            get() = TypeAheadTerminalModel.ShellType.Bash
    }

    private fun typed(ch: Char) = TerminalTypeAheadManager.TypeAheadEvent(
        TerminalTypeAheadManager.TypeAheadEvent.EventType.Character,
        ch,
    )

    /**
     * Type a character and let the "shell" echo it back.
     *
     * Predictions stay tentative - and so invisible - until the manager has seen a couple of
     * round-trips to measure latency with, so a test that wants a VISIBLE prediction has to
     * actually complete a few of them rather than just calling onKeyEvent.
     */
    private fun roundTrip(manager: TerminalTypeAheadManager, model: FakeModel, ch: Char) {
        manager.onKeyEvent(typed(ch))
        model.echo(ch)
        manager.onTerminalStateChanged()
    }

    @Test
    fun `nothing predicted means the renderer keeps its own frame's column`() {
        val manager = TerminalTypeAheadManager(FakeModel(cursorX = 7))
        manager.onTerminalStateChanged()

        assertNull(
            manager.predictedCursorX,
            "with no predictions this must NOT answer with the live emulator column - that is " +
                "the fallback that made the caret jitter",
        )
    }

    @Test
    fun `the live emulator column is never leaked, whatever it is`() {
        // The whole defect: this getter used to answer with the live column here, which the
        // renderer then paired with a row from an older frame.
        for (live in intArrayOf(0, 3, 42, 79)) {
            val manager = TerminalTypeAheadManager(FakeModel(cursorX = live))
            manager.onTerminalStateChanged()
            assertNull(manager.predictedCursorX, "leaked the live column at $live")
        }
    }

    @Test
    fun `a visible prediction supplies its own column, one-indexed`() {
        val model = FakeModel()
        val manager = TerminalTypeAheadManager(model)
        manager.onTerminalStateChanged()

        // Complete enough round-trips for the manager to trust its own predictions.
        roundTrip(manager, model, 'a')
        roundTrip(manager, model, 'b')
        roundTrip(manager, model, 'c')

        // Now type ahead of the shell: nothing echoes this one back.
        val columnBefore = model.cursorX
        manager.onKeyEvent(typed('d'))

        assertEquals(
            columnBefore + 2,
            manager.predictedCursorX,
            "the prediction advances the echoed column by one, and the getter is 1-indexed",
        )
    }

    @Test
    fun `the alternate screen drops predictions rather than reporting a stale column`() {
        val model = FakeModel()
        val manager = TerminalTypeAheadManager(model)
        manager.onTerminalStateChanged()
        roundTrip(manager, model, 'a')
        roundTrip(manager, model, 'b')
        roundTrip(manager, model, 'c')
        manager.onKeyEvent(typed('d'))

        // A full-screen app took over: predictions made against the shell line are meaningless.
        val clearsBefore = model.clearPredictionsCalls
        model.isUsingAlternateBuffer = true

        assertNull(
            manager.predictedCursorX,
            "the alt-screen reset must leave nothing predicted, not a column from the old line",
        )
        assertTrue(
            model.clearPredictionsCalls > clearsBefore,
            "the reset has to reach the model, so the painted prediction is taken back too",
        )
    }
}
