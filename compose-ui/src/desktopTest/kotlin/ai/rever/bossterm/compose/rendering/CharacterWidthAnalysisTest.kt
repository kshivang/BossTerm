package ai.rever.bossterm.compose.rendering

import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.util.CharUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharacterWidthAnalysisTest {

    @Test
    fun yaziNerdFontIconIsOneCellByDefault() {
        val icon = String(Character.toChars(0xF0331))
        val line = terminalLine(icon + " firebase-debug.log")

        val analysis = analyzeCharacter(
            char = line.charAt(0),
            line = line,
            col = 0,
            width = line.length(),
            ambiguousCharsAreDoubleWidth = false
        )

        assertEquals(0xF0331, analysis.actualCodePoint)
        assertEquals(icon, analysis.charTextToRender)
        assertEquals(icon[1], analysis.lowSurrogate)
        assertFalse(analysis.isDoubleWidth)
        assertEquals(1, analysis.visualWidth)
    }

    @Test
    fun yaziNerdFontIconCanFollowAmbiguousDoubleWidthSetting() {
        val icon = String(Character.toChars(0xF021B))
        val line = terminalLine(icon)

        val analysis = analyzeCharacter(
            char = line.charAt(0),
            line = line,
            col = 0,
            width = line.length(),
            ambiguousCharsAreDoubleWidth = true
        )

        assertTrue(analysis.isDoubleWidth)
        assertEquals(2, analysis.visualWidth)
    }

    @Test
    fun supplementaryEmojiRemainsTwoCellsWhenBufferMarksItWide() {
        val emoji = String(Character.toChars(0x1F600))
        val line = terminalLine(emoji + CharUtils.DWC)

        val analysis = analyzeCharacter(
            char = line.charAt(0),
            line = line,
            col = 0,
            width = line.length(),
            ambiguousCharsAreDoubleWidth = false
        )

        assertTrue(analysis.isWcwidthDoubleWidth)
        assertTrue(analysis.isDoubleWidth)
        assertEquals(2, analysis.visualWidth)
    }

    private fun terminalLine(text: String): TerminalLine = TerminalLine(
        TerminalLine.TextEntry(TextStyle.EMPTY, CharBuffer(text))
    )
}
