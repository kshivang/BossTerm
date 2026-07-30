package ai.rever.bossterm.compose.share

import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSnapshotEncoderTest {
    @Test
    fun `screen repaint excludes history and preserves it without RIS`() {
        val buffer = TerminalTextBuffer(12, 2, StyleState(), maxHistoryLinesCount = 10)
        buffer.getLine(1)
        buffer.writeString(0, 1, CharBuffer("history"))
        buffer.scrollArea(scrollRegionTop = 1, dy = -1, scrollRegionBottom = 2)
        buffer.writeString(0, 1, CharBuffer("screen"))
        val snapshot = buffer.createSnapshot()

        val repaint = TerminalSnapshotEncoder.encodeScreenRepaint(
            snapshot = snapshot,
            cursorX = 3,
            cursorY = 2,
            cursorVisible = true,
            cursorShape = null,
        )

        assertEquals(1, snapshot.historyLinesCount)
        assertTrue(repaint.contains("screen"))
        assertFalse(repaint.contains("history"))
        assertFalse(repaint.contains("\u001bc"), "screen repaint must not reset or clear scrollback")
        assertTrue(repaint.contains("\u001b[1;1H\u001b[2K"))
        assertTrue(repaint.endsWith("\u001b[0m\u001b[?25h\u001b[2 q\u001b[2;3H"))
    }

    @Test
    fun `initial snapshot restores a hidden cursor instead of showing xterm default`() {
        val snapshot = TerminalTextBuffer(4, 1, StyleState()).createSnapshot()

        val encoded = TerminalSnapshotEncoder.encode(
            snapshot = snapshot,
            cursorX = 2,
            cursorY = 1,
            cursorVisible = false,
            cursorShape = CursorShape.BLINK_VERTICAL_BAR,
        )

        assertTrue(encoded.endsWith("\u001b[0m\u001b[?25l\u001b[5 q\u001b[1;2H"))
    }

    @Test
    fun `snapshot maps every host cursor shape to DECSCUSR`() {
        val snapshot = TerminalTextBuffer(2, 1, StyleState()).createSnapshot()
        val expected: Map<CursorShape?, Int> = mapOf(
            null to 2,
            CursorShape.BLINK_BLOCK to 1,
            CursorShape.STEADY_BLOCK to 2,
            CursorShape.BLINK_UNDERLINE to 3,
            CursorShape.STEADY_UNDERLINE to 4,
            CursorShape.BLINK_VERTICAL_BAR to 5,
            CursorShape.STEADY_VERTICAL_BAR to 6,
        )

        for ((shape, decscusr) in expected) {
            val encoded = TerminalSnapshotEncoder.encode(
                snapshot = snapshot,
                cursorX = 1,
                cursorY = 1,
                cursorVisible = true,
                cursorShape = shape,
            )
            assertTrue(
                encoded.endsWith("\u001b[0m\u001b[?25h\u001b[$decscusr q\u001b[1;1H"),
                "wrong DECSCUSR sequence for $shape",
            )
        }
    }
}
