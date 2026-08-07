package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.ArrayTerminalDataStream
import ai.rever.bossterm.terminal.TerminalMode
import ai.rever.bossterm.terminal.emulator.BossEmulator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Claude Code positions everything on its scrolled viewport with CURSOR-RELATIVE moves. A live
 * capture shows one frame as:
 *
 * ```
 * ESC[?2026h ESC[?25l ESC[H ESC[2;26r ESC[6T ESC[r ESC[H
 *   per row:   CR ESC[5C ESC[1B <text>
 *   indicator: CR ESC[24C ESC[18B " Jump to bottom (click) v "
 * ESC[32;1H ESC[28;3H ESC[?25h ESC[?2026l
 * ```
 *
 * Every position is counted from wherever the previous write left the cursor, so the app's layout
 * only holds while its cursor model and the emulator's agree. These tests pin that arithmetic.
 *
 * They are NOT regression coverage for the scroll-region corruption that prompted them - that was
 * `LinesStorage` moving rows past a margin, and it is covered by `ScrollRegionContainmentTest` in
 * `bossterm-core-mpp`, where the defect lives. Cursor drift was one of several hypotheses for that
 * artifact and was ruled out by these very tests: deferred wrap is correct, and full-width rows
 * accumulate no drift. They are kept because that arithmetic had no coverage at all and a
 * regression in it would corrupt every relative-positioned TUI.
 *
 * Each assertion is on where a marker actually LANDS in the buffer. Asserting a cursor field would
 * only prove the emulator agrees with itself, not that an app's model of it holds.
 */
class CursorRowDriftTest {

    private val esc = "\u001b"
    private val width = 20
    private val height = 10

    private fun feed(sequence: String, assertions: (TerminalTextBuffer) -> Unit) {
        val styleState = StyleState()
        val buffer = TerminalTextBuffer(width = width, height = height, styleState = styleState)
        val terminal = BossTerminal(NoopTerminalDisplay(), buffer, styleState)
        terminal.setModeEnabled(TerminalMode.AlternateBuffer, true)
        buffer.getLine(height - 1) // materialize the lazily allocated screen rows
        val emulator = BossEmulator(ArrayTerminalDataStream(sequence.toCharArray()), terminal)
        while (emulator.hasNext()) emulator.next()
        assertions(buffer)
    }

    private fun TerminalTextBuffer.rowText(row: Int) = getLine(row).text.trimEnd()

    /**
     * The DEC deferred-wrap rule: writing the LAST column leaves the cursor on that column with a
     * pending-wrap flag set, not on the next row. A `CUD 1` afterwards therefore moves exactly one
     * row. Advancing eagerly instead costs one extra row per full-width line, and Claude Code pads
     * every diff row to the full width.
     */
    @Test
    fun writingTheLastColumnDefersTheWrapInsteadOfAdvancingTheRow() {
        feed("$esc[H" + "X".repeat(width) + "\r$esc[1BMARK") { buffer ->
            assertEquals("X".repeat(width), buffer.rowText(0), "row 1 holds the full-width write")
            assertEquals(
                "MARK",
                buffer.rowText(1),
                "a pending wrap must not have consumed a row before CUD",
            )
        }
    }

    /**
     * The same rule across several full-width rows, which is what a real frame sends. Drift is
     * cumulative, so this is the shape that would make the artifact worse the more rows a frame
     * repaints - the reported "worse the faster you scroll".
     */
    @Test
    fun fullWidthRowsDoNotAccumulateDrift() {
        val rows = 4
        val writes = (0 until rows).joinToString("") { "\r$esc[1B" + "%-${width}d".format(it) }
        feed("$esc[H" + writes + "\r$esc[1BMARK") { buffer ->
            for (i in 0 until rows) {
                assertEquals(
                    "$i",
                    buffer.rowText(i + 1),
                    "row ${i + 2} should hold full-width write $i",
                )
            }
            assertEquals(
                "MARK",
                buffer.rowText(rows + 1),
                "after $rows full-width rows the cursor must be exactly $rows rows down",
            )
        }
    }

    /**
     * SD (`CSI n T`) pans the region's content down and, per DEC, does NOT move the cursor.
     *
     * The cursor is placed AFTER the region is set, because DECSTBM homes the cursor - so a
     * sequence that set the region between positioning and scrolling would be measuring DECSTBM's
     * homing rather than SD. Claude Code does not depend on the cursor surviving DECSTBM either;
     * it re-homes with an explicit `ESC[H` before counting rows downward.
     */
    @Test
    fun scrollDownLeavesTheCursorWhereItWas() {
        feed("$esc[2;9r" + "$esc[4;1HROW4" + "$esc[3T" + "\rAFTER") { buffer ->
            assertEquals(
                "AFTER",
                buffer.rowText(3),
                "SD must not move the cursor, so row 4 receives AFTER",
            )
            assertEquals(
                "ROW4",
                buffer.rowText(6),
                "the panned content should have moved down 3 rows, not vanished",
            )
        }
    }
}
