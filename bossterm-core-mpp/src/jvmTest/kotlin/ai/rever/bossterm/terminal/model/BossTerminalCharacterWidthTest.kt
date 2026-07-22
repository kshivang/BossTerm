package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import ai.rever.bossterm.terminal.util.CharUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BossTerminalCharacterWidthTest {

    @Test
    fun emojiStillAdvancesTwoTerminalCells() {
        val terminal = createTerminal()

        terminal.writeCharacters(String(Character.toChars(0x1F600)))

        assertEquals(3, terminal.cursorX)
    }

    @Test
    fun clearingYaziRowRemovesFinalCharacterAfterSingleWidthNerdFontIcon() {
        val terminal = createTerminal()
        val icon = String(Character.toChars(0xF0331))
        val filename = " firebase-debug.log"
        val row = icon + filename
        val rowWidth = CharUtils.getTextLengthGraphemeAware(row, ambiguousIsDWC = false)

        // Yazi emits the icon and filename in separate styled output runs.
        terminal.writeCharacters(icon)
        terminal.writeCharacters(filename)

        assertEquals(rowWidth + 1, terminal.cursorX)
        assertEquals(row, terminal.terminalTextBuffer.getLine(0).text)

        // Repainting the same terminal cells with spaces must consume both
        // UTF-16 units of the old icon. The previous bug left the final "g".
        terminal.cursorPosition(1, 1)
        terminal.writeCharacters(" ".repeat(rowWidth))

        val cleared = terminal.terminalTextBuffer.getLine(0).text
        assertEquals(" ".repeat(rowWidth), cleared)
        assertFalse('g' in cleared)
    }

    private fun createTerminal(): BossTerminal {
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(width = 80, height = 24, styleState = styleState)
        return BossTerminal(NoopTerminalDisplay(), textBuffer, styleState)
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
