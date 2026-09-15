package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.core.Color
import ai.rever.bossterm.terminal.ArrayTerminalDataStream
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.TerminalOutputStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalColorQueryTest {
    @Test
    fun colorQueriesReplyBeforeFirstFrameAndAfterThemeChange() {
        val display = ComposeTerminalDisplay(TerminalSettings(
            defaultForeground = "0xFFABCDEF", defaultBackground = "0xFF123456",
        ))
        try {
            assertEquals(listOf("\u001b]10;rgb:abab/cdcd/efef\u0007", "\u001b]11;rgb:1212/3434/5656\u001b\\"), query(display))
            display.updateColorSettings(TerminalSettings(
                defaultForeground = "0xFF123456", defaultBackground = "0xFFABCDEF",
            ))
            assertEquals(listOf("\u001b]10;rgb:1212/3434/5656\u0007", "\u001b]11;rgb:abab/cdcd/efef\u001b\\"), query(display))
        } finally {
            display.dispose()
        }
    }

    @Test
    fun headlessDisplayAlsoAnswersColorQueries() {
        val display = HeadlessTerminalDisplay(windowForeground = Color(255, 255, 255), windowBackground = Color(0, 0, 0))
        assertEquals(listOf("\u001b]10;rgb:ffff/ffff/ffff\u0007", "\u001b]11;rgb:0000/0000/0000\u001b\\"), query(display))
    }

    private fun query(display: TerminalDisplay): List<String> {
        val style = StyleState()
        val terminal = BossTerminal(display, TerminalTextBuffer(80, 24, style), style)
        val replies = mutableListOf<String>()
        terminal.setTerminalOutput(object : TerminalOutputStream {
            override fun sendBytes(response: ByteArray, userInput: Boolean) { replies += response.decodeToString() }
            override fun sendString(string: String, userInput: Boolean) { replies += string }
        })
        val emulator = BossEmulator(ArrayTerminalDataStream("\u001b]10;?\u0007\u001b]11;?\u001b\\".toCharArray()), terminal)
        while (emulator.hasNext()) emulator.next()
        return replies
    }
}
