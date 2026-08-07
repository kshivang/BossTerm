package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.ArrayTerminalDataStream
import ai.rever.bossterm.terminal.TerminalMode
import ai.rever.bossterm.terminal.emulator.BossEmulator
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
 * Both directions are covered because they were separately wrong: SD walked past the bottom margin
 * on ANY count, and SU reached past it on any count larger than the region. The oversized and
 * partly painted cases each hid behind a test that used a small count on a fully painted screen, so
 * they are pinned explicitly. IL/DL reach the same code with a CURSOR-derived top, a materially
 * different input space, so they get their own cases.
 *
 * Assertions are on ROWS wherever possible. A history assertion under a region that does not start
 * at row 1 proves nothing: `TerminalTextBuffer.scrollArea` only feeds scrollback when
 * `scrollRegionTop == 1`, so such a test passes with the rotation completely broken.
 */
class ScrollRegionContainmentTest {

    private val esc = "\u001b"
    private val width = 8
    private val height = 10

    private fun replay(
        sequence: String,
        screenHeight: Int = height,
        alternateScreen: Boolean = true,
        materializeAll: Boolean = true,
        assertions: (TerminalTextBuffer) -> Unit,
    ) {
        val styleState = StyleState()
        val buffer = TerminalTextBuffer(width, screenHeight, styleState)
        val terminal = BossTerminal(NoopTerminalDisplay(), buffer, styleState)
        if (alternateScreen) terminal.setModeEnabled(TerminalMode.AlternateBuffer, true)
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
     * The bottom margin is a property of the REGION, not of how far painting has reached. Rendering
     * never materializes trailing rows (`IncrementalSnapshotBuilder` iterates `0 until size`), so a
     * screen can stay short indefinitely; clamping the margin to `size - 1` silently dropped the
     * rows that should have moved into the unpainted part.
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
     * The same containment, measured on rows rather than on history: a region below row 1 cannot
     * feed scrollback anyway, so asserting `historyLinesCount == 0` there proves nothing.
     */
    @Test
    fun aRegionBelowTheTopLeavesThePinnedRowsAlone() {
        replay(fillRows() + "$esc[2;9r$esc[40S") { buffer ->
            val rows = buffer.rows()
            assertEquals("r1", rows[0], "row 1 is pinned above the region")
            assertEquals("r10", rows[9], "row 10 is pinned below the region")
        }
    }

    /**
     * A top-anchored region DOES feed scrollback, but only with the rows that actually left it -
     * never the rows pinned below its bottom margin. On the MAIN screen, because the alternate
     * screen suppresses scrollback entirely and would mask this.
     */
    @Test
    fun aTopAnchoredRegionScrollsOnlyItsOwnRowsIntoHistory() {
        replay(fillRows() + "$esc[1;5r$esc[40S", alternateScreen = false) { buffer ->
            assertEquals(
                listOf("r6", "r7", "r8", "r9", "r10"),
                buffer.rows().subList(5, 10),
                "rows below the bottom margin are pinned and must stay on screen",
            )
            assertEquals(
                5,
                buffer.historyLinesCount,
                "only the region's own 5 rows reach history, not the 5 pinned below it",
            )
        }
    }

    /**
     * Materializing the region to its bottom margin also decides what a partly painted MAIN screen
     * hands to scrollback. A real 24-row screen genuinely has blank rows to scroll away, so a
     * 5-row scroll moves 5 rows even when only 3 have ever been painted. Pinned because this is a
     * scrollback-content change, not merely an internal one.
     */
    @Test
    fun aPartlyPaintedScreenScrollsItsBlankRowsIntoHistoryToo() {
        replay(
            fillRows(n = 3) + "$esc[1;24r$esc[5S",
            screenHeight = 24,
            alternateScreen = false,
            materializeAll = false,
        ) { buffer ->
            assertEquals(5, buffer.historyLinesCount, "a 5-row scroll moves 5 rows, painted or not")
            assertEquals(
                listOf("r1", "r2", "r3", "", ""),
                (-5..-1).map { buffer.getLine(it).text.trimEnd() },
                "the three painted rows, then the blanks a full-height screen really has",
            )
        }
    }

    /**
     * IL (`CSI L`) reaches the same rotation with a CURSOR-derived top. Inside the region it must
     * still respect the bottom margin.
     */
    @Test
    fun insertLineWithTheCursorInsideTheRegionKeepsPinnedRows() {
        replay(fillRows() + "$esc[2;9r$esc[3;1H$esc[3L") { buffer ->
            val rows = buffer.rows()
            assertEquals("r1", rows[0], "row 1 is pinned above the region")
            assertEquals("r10", rows[9], "row 10 is pinned below the region")
            assertEquals("r2", rows[1], "rows above the cursor do not move")
            assertEquals(listOf("", "", ""), rows.subList(2, 5), "three blanks open at the cursor")
        }
    }

    /**
     * IL with the cursor BELOW the bottom margin is a no-op. The old code skipped the removal but
     * still inserted, growing the screen past the terminal height.
     */
    @Test
    fun insertLineWithTheCursorBelowTheMarginChangesNothing() {
        replay(fillRows() + "$esc[2;5r$esc[8;1H$esc[3L") { buffer ->
            assertEquals(
                (1..height).map { "r$it" },
                buffer.rows(),
                "IL outside the region must not move any row",
            )
            assertEquals(
                height,
                buffer.screenLinesCount,
                "and must not grow the screen past the terminal height",
            )
        }
    }
}
