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

        assertEquals(3, bank.drain().appended)
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

        assertEquals(0, bank.drain().appended, "alt-screen appends must never reach the bank")
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
            bank.drain().appended,
            "the 2 main-screen lines survive, the 5 alt-screen ones were never banked",
        )
    }

    @Test
    fun drainingEmptiesTheBank() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        buffer.scrollArea(1, -1, 4)

        assertEquals(1, bank.drain().appended)
        assertEquals(0, bank.drain().appended, "a second drain sees nothing new")
    }

    /** Leaving the live bottom discards the bank: those appends were not a scrolled viewport's. */
    @Test
    fun clearForgetsWhatWasBanked() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        repeat(4) { buffer.scrollArea(1, -1, 4) }

        bank.clear()

        assertEquals(0, bank.drain().appended)
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
        val delta = bank.drain()

        assertEquals(true, delta.cleared, "the clear must be reported, not just counted away")
        assertEquals(0, delta.appended)
        assertEquals(false, bank.drain().cleared, "and taken only once")
    }

    @Test
    fun anOrdinaryAppendIsNotReportedAsAClear() {
        val (buffer, bank) = buffer().bankWiredUp()
        buffer.getLine(3)
        buffer.scrollArea(1, -1, 4)

        assertEquals(false, bank.drain().cleared)
    }
}
