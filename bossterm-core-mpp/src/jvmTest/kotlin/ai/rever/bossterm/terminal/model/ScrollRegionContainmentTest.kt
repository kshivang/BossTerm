package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.ArrayTerminalDataStream
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.TerminalMode
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A scrolling region pins every row outside it. Claude Code scrolls its transcript with `ESC[2;Nr`
 * plus SD/SU and keeps its prompt box and "Jump to bottom" affordance in the pinned band below, so
 * a scroll that reaches past a margin corrupts rows the app believes it owns - and it never repaints
 * them, because from its model nothing there changed. That is how a live session ended up with
 *
 *     Update(.worktrees/bossconsole-or Jump to bottom (click) v /organisation/views/layout.ts)
 *
 * where `g-pages/supabase/functions` should have been.
 *
 * Both directions are covered here because they were separately wrong: SD walked past the bottom
 * margin on ANY count, and SU reached past it on any count larger than the region. The oversized
 * and partly painted cases each hid behind a test that only used a small count on a fully painted
 * screen, so they are pinned explicitly.
 */
class ScrollRegionContainmentTest {

    private val esc = "\u001b"
    private val width = 8
    private val height = 10

    private fun replay(
        sequence: String,
        screenHeight: Int = height,
        materializeAll: Boolean = true,
        assertions: (TerminalTextBuffer) -> Unit,
    ) {
        val styleState = StyleState()
        val buffer = TerminalTextBuffer(width, screenHeight, styleState)
        val terminal = BossTerminal(NoopTerminalDisplay(), buffer, styleState)
        terminal.setModeEnabled(TerminalMode.AlternateBuffer, true)
        if (materializeAll) buffer.getLine(screenHeight - 1)
        val emulator = BossEmulator(ArrayTerminalDataStream(sequence.toCharArray()), terminal)
        while (emulator.hasNext()) emulator.next()
        assertions(buffer)
    }

    /** Label every row with its own 1-based name so any movement is identifiable. */
    private fun fillRows(n: Int = height) = (1..n).joinToString("") { "$esc[$it;1Hr$it" }

    private fun TerminalTextBuffer.rows(n: Int = height) =
        (0 until n).map { getLine(it).text.trimEnd() }

    @Test
    fun scrollDownKeepsContentInsideTheRegion() {
        replay(fillRows() + "$esc[2;9r$esc[3T") { buffer ->
            val rows = buffer.rows()
            assertEquals("r1", rows[0], "row 1 is above the region and must not move")
            assertEquals("r10", rows[9], "row 10 is below the region and must not move")
            assertEquals(listOf("", "", ""), rows.subList(1, 4), "region top is exposed as blank")
            assertEquals(
                listOf("r2", "r3", "r4", "r5", "r6"),
                rows.subList(4, 9),
                "region content shifts down by 3 and clips at the bottom margin",
            )
        }
    }

    @Test
    fun scrollUpKeepsContentInsideTheRegion() {
        replay(fillRows() + "$esc[2;9r$esc[3S") { buffer ->
            val rows = buffer.rows()
            assertEquals("r1", rows[0], "row 1 is above the region and must not move")
            assertEquals("r10", rows[9], "row 10 is below the region and must not move")
            assertEquals(
                listOf("r5", "r6", "r7", "r8", "r9"),
                rows.subList(1, 6),
                "region content shifts up by 3",
            )
            assertEquals(listOf("", "", ""), rows.subList(6, 9), "region bottom is exposed as blank")
        }
    }

    /**
     * A count past the region height blanks the region and touches nothing else. The region here is
     * 8 rows; at exactly 8 both directions were already correct, so the bug only showed from 9 up.
     */
    @Test
    fun anOversizedScrollBlanksOnlyTheRegion() {
        for (op in listOf("T", "S")) {
            replay(fillRows() + "$esc[2;9r$esc[40$op") { buffer ->
                val rows = buffer.rows()
                assertEquals("r1", rows[0], "row 1 must survive an oversized $op")
                assertEquals("r10", rows[9], "row 10 must survive an oversized $op")
                assertEquals(
                    List(8) { "" },
                    rows.subList(1, 9),
                    "an oversized $op blanks the region and nothing outside it",
                )
            }
        }
    }

    /**
     * The region's bottom margin is a property of the REGION, not of how far painting has reached.
     * Rendering never materializes trailing rows (`IncrementalSnapshotBuilder` iterates
     * `0 until size`), so a screen can stay short indefinitely; clamping the margin to `size - 1`
     * silently dropped the rows that should have moved into the unpainted part.
     */
    @Test
    fun scrollDownOnAPartlyPaintedScreenKeepsEveryRow() {
        replay(fillRows(n = 12) + "$esc[2;23r$esc[1T", screenHeight = 24, materializeAll = false) { buffer ->
            val rows = buffer.rows(n = 24)
            assertEquals("r1", rows[0], "row 1 is pinned above the region")
            assertEquals("", rows[1], "the region top is exposed as blank")
            assertEquals(
                (2..12).map { "r$it" },
                rows.subList(2, 13),
                "every painted row shifts down one, including the last one painted",
            )
        }
    }

    /**
     * Scrolling a region that does NOT start at row 1 is not scrollback. Uses SU with an oversized
     * count, because that is the combination that used to push rows from below the bottom margin
     * into history.
     */
    @Test
    fun aRegionBelowTheTopAddsNoScrollback() {
        replay(fillRows() + "$esc[2;9r$esc[40S") { buffer ->
            assertEquals(0, buffer.historyLinesCount, "a region below row 1 must not feed scrollback")
        }
    }

    /**
     * A top-anchored region DOES feed scrollback, but only with the rows that actually left the
     * region - never the rows pinned below its bottom margin. This ran on the main screen because
     * the alternate screen suppresses scrollback entirely, which would mask the assertion.
     */
    @Test
    fun aTopAnchoredRegionScrollsOnlyItsOwnRowsIntoHistory() {
        val styleState = StyleState()
        val buffer = TerminalTextBuffer(width, height, styleState)
        val terminal = BossTerminal(NoopTerminalDisplay(), buffer, styleState)
        buffer.getLine(height - 1)
        val sequence = fillRows() + "$esc[1;5r$esc[40S"
        val emulator = BossEmulator(ArrayTerminalDataStream(sequence.toCharArray()), terminal)
        while (emulator.hasNext()) emulator.next()

        assertEquals(
            listOf("r6", "r7", "r8", "r9", "r10"),
            (5 until height).map { buffer.getLine(it).text.trimEnd() },
            "rows below the bottom margin are pinned and must stay on screen",
        )
        assertEquals(
            5,
            buffer.historyLinesCount,
            "only the region's own 5 rows reach history, not the 5 pinned below it",
        )
    }

    private class NoopTerminalDisplay : TerminalDisplay {
        override var windowTitle: String? = null
        override var iconTitle: String? = null
        override val selection: TerminalSelection? = null

        override fun setCursor(x: Int, y: Int) = Unit
        override fun setCursorShape(cursorShape: CursorShape?) = Unit
        override fun beep() = Unit
        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) = Unit
        override fun setCursorVisible(isCursorVisible: Boolean) = Unit
        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) = Unit
        override fun terminalMouseModeSet(mouseMode: MouseMode) = Unit
        override fun setMouseFormat(mouseFormat: MouseFormat) = Unit
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
    }
}
