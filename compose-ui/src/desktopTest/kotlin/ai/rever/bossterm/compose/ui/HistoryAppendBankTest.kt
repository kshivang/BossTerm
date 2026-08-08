package ai.rever.bossterm.compose.ui

import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two decisions that decide whether the viewport ends up somewhere sensible. Neither is
 * arithmetic - [FoldHistoryAppendsTest] covers that - and neither was reachable from a test while
 * they lived inside the composable.
 */
class HistoryAppendBankTest {

    private fun buffer() = TerminalTextBuffer(20, 4, StyleState(), 100)

    private fun TerminalTextBuffer.bankWiredUp(): Pair<TerminalTextBuffer, HistoryAppendBank> {
        val bank = HistoryAppendBank(this)
        addChangesListener(bank)
        return this to bank
    }

    /** One line of streaming output, one line banked. */
    @Test
    fun mainScreenAppendsAreCounted() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)

        repeat(3) { buffer.scrollArea(1, -1, 4) }

        assertEquals(3, bank.peek().appended)
    }

    /**
     * The alternate screen accumulates scrollback in this buffer, but it is discarded when the TUI
     * exits - so folding those appends would leave the restored main view at an offset derived
     * from a buffer that no longer exists.
     */
    @Test
    fun alternateScreenAppendsAreNotCounted() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.useAlternateBuffer(true)
        buffer.getLine(3)

        repeat(3) { buffer.scrollArea(1, -1, 4) }

        assertEquals(0, bank.peek().appended, "alt-screen appends must never reach the bank")
    }

    /**
     * The exclusion is decided when the append fires, not when the count is drained. Draining on
     * the UI thread a frame later would race: these main-screen lines arrived before the TUI
     * started, and must survive the switch.
     */
    @Test
    fun appendsKeepTheScreenTheyArrivedOn() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        repeat(2) { buffer.scrollArea(1, -1, 4) }

        // A TUI starts before the viewport takes its next frame.
        buffer.useAlternateBuffer(true)
        repeat(5) { buffer.scrollArea(1, -1, 4) }

        assertEquals(
            2,
            bank.peek().appended,
            "the 2 main-screen lines survive, the 5 alt-screen ones were never banked",
        )
    }

    @Test
    fun consumingTakesExactlyWhatWasPeeked() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        buffer.scrollArea(1, -1, 4)

        val delta = bank.peek()
        assertEquals(1, delta.appended)
        bank.consume(delta)
        assertEquals(0, bank.peek().appended, "consuming takes exactly what was peeked")
    }

    /** Leaving the live bottom discards the bank: those appends were not a scrolled viewport's. */
    @Test
    fun clearForgetsWhatWasBanked() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        repeat(4) { buffer.scrollArea(1, -1, 4) }

        bank.clear()

        assertEquals(0, bank.peek().appended)
    }

    /**
     * A cleared history is reported as such, not merely as "no appends". A viewport scrolled up
     * when `CSI 3 J` lands is addressing lines that no longer exist, so the fold needs to know the
     * difference between "nothing happened" and "everything went away".
     */
    @Test
    fun clearingHistoryIsReportedAndEmptiesTheBank() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        repeat(4) { buffer.scrollArea(1, -1, 4) }

        buffer.clearHistory()
        val delta = bank.peek()

        assertEquals(true, delta.cleared, "the clear must be reported, not just counted away")
        assertEquals(0, delta.appended)
        bank.consume(delta)
        assertEquals(false, bank.peek().cleared, "and taken only once")
    }

    @Test
    fun anOrdinaryAppendIsNotReportedAsAClear() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        buffer.scrollArea(1, -1, 4)

        assertEquals(false, bank.peek().cleared)
    }

    /**
     * A composition can be discarded before it applies. Peeking must therefore leave the count in
     * place, or those appends vanish and the viewport drifts permanently - the exact failure this
     * class exists to prevent.
     */
    @Test
    fun peekingWithoutConsumingKeepsTheCount() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        repeat(3) { buffer.scrollArea(1, -1, 4) }

        assertEquals(3, bank.peek().appended)
        assertEquals(3, bank.peek().appended, "a discarded composition must not swallow appends")
    }

    /** Appends landing between the peek and the commit survive into the next frame. */
    @Test
    fun appendsArrivingDuringAFrameAreNotLost() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        repeat(2) { buffer.scrollArea(1, -1, 4) }
        val inFlight = bank.peek()

        buffer.scrollArea(1, -1, 4) // arrives after the frame was computed
        bank.consume(inFlight)

        assertEquals(1, bank.peek().appended, "only the applied count is taken back")
    }
}
