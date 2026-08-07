package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [insertLines] / [deleteLines] contract, exercised on the storage directly.
 *
 * `ScrollRegionContainmentTest` drives the same code through `BossEmulator`, which is the right
 * level for "does a Claude Code frame corrupt the screen". This one holds the clamp contract itself:
 * the region bottom is [lastLine] and never a `size`-derived value, the move is bounded by the
 * region height, and a region that does not exist is a no-op. Those are the properties someone
 * reintroducing a `size - 1` margin would break, and they should fail here without an emulator in
 * the way.
 */
class LinesStorageRotationTest {

    private val filler = TerminalLine.TextEntry(TextStyle.EMPTY, CharBuffer(' ', 4))

    private fun storage(rows: Int): LinesStorage {
        val storage = CyclicBufferLinesStorage(maxCapacity = -1)
        repeat(rows) { i ->
            storage.addToBottom(TerminalLine(TerminalLine.TextEntry(TextStyle.EMPTY, CharBuffer("r${i + 1}"))))
        }
        return storage
    }

    private fun LinesStorage.labels() = (0 until size).map { this[it].text.trimEnd() }

    @Test
    fun panDownTakesFromTheBottomOfTheRegionAndOpensTheTop() {
        val s = storage(6)
        s.insertLines(y = 1, count = 2, lastLine = 4, filler = filler)
        assertEquals(listOf("r1", "", "", "r2", "r3", "r6"), s.labels())
    }

    @Test
    fun panUpTakesFromTheTopOfTheRegionAndOpensTheBottom() {
        val s = storage(6)
        s.deleteLines(y = 1, count = 2, lastLine = 4, filler = filler)
        assertEquals(listOf("r1", "r4", "r5", "", "", "r6"), s.labels())
    }

    @Test
    fun aMoveOfExactlyTheRegionHeightBlanksIt() {
        val s = storage(6)
        s.insertLines(y = 1, count = 4, lastLine = 4, filler = filler)
        assertEquals(listOf("r1", "", "", "", "", "r6"), s.labels())
    }

    @Test
    fun anOversizedMoveIsClampedToTheRegion() {
        val s = storage(6)
        s.deleteLines(y = 1, count = 999, lastLine = 4, filler = filler)
        assertEquals(listOf("r1", "", "", "", "", "r6"), s.labels())
        assertEquals(6, s.size, "the screen height must not change")
    }

    @Test
    fun aSingleRowRegionRotatesInPlace() {
        val s = storage(3)
        s.insertLines(y = 1, count = 1, lastLine = 1, filler = filler)
        assertEquals(listOf("r1", "", "r3"), s.labels())
    }

    /** The bottom margin comes from `lastLine`, not from how many rows have been materialized. */
    @Test
    fun theRegionBottomIsMaterializedRatherThanClampedToSize() {
        val s = storage(3)
        s.insertLines(y = 0, count = 5, lastLine = 9, filler = filler)
        assertEquals(10, s.size, "the region is materialized to its bottom margin")
        assertEquals(
            listOf("", "", "", "", "", "r1", "r2", "r3", "", ""),
            s.labels(),
            "the painted rows shift down instead of being dropped",
        )
    }

    @Test
    fun aDegenerateOrEmptyRegionIsANoOp() {
        for (args in listOf(Triple(3, 2, 1), Triple(1, 0, 4), Triple(-1, 2, 4))) {
            val (y, count, lastLine) = args
            val s = storage(4)
            s.insertLines(y = y, count = count, lastLine = lastLine, filler = filler)
            s.deleteLines(y = y, count = count, lastLine = lastLine, filler = filler)
            assertEquals(listOf("r1", "r2", "r3", "r4"), s.labels(), "no-op for y=$y count=$count last=$lastLine")
        }
    }

    /** Panning down discards the rows that leave; only panning up hands them back for scrollback. */
    @Test
    fun onlyPanningUpReturnsTheRowsThatLeft() {
        val s = storage(6)
        val removed = s.deleteLines(y = 0, count = 2, lastLine = 5, filler = filler)
        assertEquals(listOf("r1", "r2"), removed.map { it.text.trimEnd() })
    }
}
