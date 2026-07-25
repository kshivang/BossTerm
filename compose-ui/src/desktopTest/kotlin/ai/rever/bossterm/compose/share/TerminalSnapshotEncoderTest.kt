package ai.rever.bossterm.compose.share

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
        )

        assertEquals(1, snapshot.historyLinesCount)
        assertTrue(repaint.contains("screen"))
        assertFalse(repaint.contains("history"))
        assertFalse(repaint.contains("\u001bc"), "screen repaint must not reset or clear scrollback")
        assertTrue(repaint.contains("\u001b[1;1H\u001b[2K"))
        assertTrue(repaint.endsWith("\u001b[0m\u001b[2;3H"))
    }
}
