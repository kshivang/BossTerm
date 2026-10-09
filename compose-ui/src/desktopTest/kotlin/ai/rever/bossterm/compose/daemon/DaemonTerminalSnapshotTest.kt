package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.terminal.TerminalMode
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonTerminalSnapshotTest {
    @Test
    fun `reattach restores alternate screen main content and input modes`() {
        val source = TerminalSessionCore(settings = TerminalSettings.DEFAULT, workingDir = null)
        val mirror = TerminalSessionCore(settings = TerminalSettings.DEFAULT, workingDir = null)
        try {
            source.terminal.writeCharacters("shell underneath")
            source.terminal.setModeEnabled(TerminalMode.StoreCursor, true)
            source.terminal.setModeEnabled(TerminalMode.AlternateBuffer, true)
            source.terminal.writeCharacters("running tui")
            source.terminal.setModeEnabled(TerminalMode.CursorKey, true)
            source.terminal.setModeEnabled(TerminalMode.BracketedPasteMode, true)
            source.terminal.setMouseMode(MouseMode.MOUSE_REPORTING_BUTTON_MOTION)
            source.terminal.setMouseFormat(MouseFormat.MOUSE_FORMAT_SGR)
            mirror.dataStream.append("\u001bc" + DaemonTerminalSnapshot.encode(source))
            while (!mirror.dataStream.isEmpty) mirror.emulator.processChar(mirror.dataStream.char, mirror.terminal)
            assertTrue(mirror.display.usingAlternateBuffer)
            assertTrue(mirror.terminal.isModelEnabled(TerminalMode.CursorKey))
            assertTrue(mirror.display.bracketedPasteMode)
            assertEquals(MouseMode.MOUSE_REPORTING_BUTTON_MOTION, mirror.display.mouseModeFlow.value)
            assertEquals(MouseFormat.MOUSE_FORMAT_SGR, mirror.display.mouseFormat)
            assertTrue(mirror.textBuffer.createSnapshot().getLine(0).text.contains("running tui"))
            mirror.terminal.setModeEnabled(TerminalMode.AlternateBuffer, false)
            assertTrue(mirror.textBuffer.createSnapshot().getLine(0).text.contains("shell underneath"))
        } finally {
            source.close()
            mirror.close()
        }
    }
    @Test
    fun `reattach restores scrolling origin insertion and active and saved styles`() {
        val source = TerminalSessionCore(settings = TerminalSettings.DEFAULT, workingDir = null)
        val mirror = TerminalSessionCore(settings = TerminalSettings.DEFAULT, workingDir = null)
        fun feed(core: TerminalSessionCore, data: String) {
            core.dataStream.append(data)
            while (!core.dataStream.isEmpty) core.emulator.processChar(core.dataStream.char, core.terminal)
        }
        try {
            feed(source, "\u001b[2;10r\u001b[?6h\u001b[3;5H\u001b[31m\u001b7\u001b[32m\u001b[4h\u001b[20h")
            feed(mirror, "\u001bc" + DaemonTerminalSnapshot.encode(source))
            assertEquals(source.terminal.scrollRegion, mirror.terminal.scrollRegion)
            assertTrue(mirror.terminal.isOriginMode)
            assertTrue(mirror.terminal.isModelEnabled(TerminalMode.InsertMode))
            assertTrue(mirror.terminal.isAutoNewLine())
            assertEquals(source.terminal.cursorPosition, mirror.terminal.cursorPosition)
            assertEquals(source.terminal.currentTextStyle(), mirror.terminal.currentTextStyle())
            feed(source, "live\u001b8")
            feed(mirror, "live\u001b8")
            assertEquals(source.terminal.cursorPosition, mirror.terminal.cursorPosition)
            assertEquals(source.terminal.currentTextStyle(), mirror.terminal.currentTextStyle())
        } finally {
            source.close()
            mirror.close()
        }
    }

}
