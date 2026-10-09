package ai.rever.bossterm.compose.terminal

import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalDataStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalStreamBarrierTest {
    @Test
    fun `bulk text finishes on old grid before queued resize action`() {
        val stream = BlockingTerminalDataStream(PerformanceMode.LATENCY)
        val style = StyleState()
        val buffer = TerminalTextBuffer(4, 2, style, 100)
        val terminal = BossTerminal(HeadlessTerminalDisplay(4, 2), buffer, style)
        val emulator = BossEmulator(stream, terminal, false)
        val captures = mutableListOf<String>()
        stream.append("ABCDEF")
        stream.appendAction {
            captures.add(buffer.createSnapshot().getLine(0).text)
            terminal.resize(TermSize(8, 2), RequestOrigin.User)
        }
        stream.append("G")
        stream.close()
        drainTerminalEmulator(emulator, stream, terminal, { true })
        assertEquals(listOf("ABCD"), captures, "resize must execute after the old-grid model write")
        assertEquals(8, buffer.width)
        assertTrue(buffer.getScreenLines().contains("ABCDEFG"))
    }

    @Test
    fun `EOF and barriers retain incomplete final graphemes without sentinel collisions`() {
        val stream = BlockingTerminalDataStream(PerformanceMode.LATENCY)
        val data = "ordinary\u0000CLOSE_SENTINEL\u0000 trailing\uD83D"
        val captured = mutableListOf<Char>()
        stream.append(data)
        stream.close()
        try {
            while (true) captured.add(stream.char)
        } catch (_: TerminalDataStream.EOF) {}
        assertEquals(data, captured.joinToString(""))
        assertFailsWith<TerminalDataStream.EOF> { stream.char }

        val withBarrier = BlockingTerminalDataStream(PerformanceMode.LATENCY)
        val seen = StringBuilder()
        var preceding = ""
        withBarrier.append("emoji\uD83D")
        withBarrier.appendAction { preceding = seen.toString() }
        withBarrier.append("after")
        withBarrier.close()
        try {
            while (true) seen.append(withBarrier.char)
        } catch (_: TerminalDataStream.EOF) {}
        assertEquals("emoji\uD83D", preceding)
        assertEquals("emoji\uD83Dafter", seen.toString())
    }
}
