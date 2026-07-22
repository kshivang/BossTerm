package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalLineVisualWriteTest {

    @Test
    fun bulkAsciiAppendsRemainOnTheDirectCellPath() {
        val line = TerminalLine()
        val chunk = CharBuffer("0123456789abcdef")

        repeat(10_000) {
            line.writeStringVisual(
                visualX = line.length(),
                str = chunk,
                style = TextStyle.EMPTY,
                ambiguousCharsAreDoubleWidth = false
            )
        }

        assertEquals(160_000, line.length())
        assertFalse(line.requiresVisualColumnMapping)
    }

    @Test
    fun supplementaryGlyphEnablesVisualColumnMapping() {
        val line = TerminalLine()
        val icon = CharBuffer(String(Character.toChars(0xF0331)))

        line.writeStringVisual(0, icon, TextStyle.EMPTY, ambiguousCharsAreDoubleWidth = false)

        assertTrue(line.requiresVisualColumnMapping)
        assertEquals(1, line.writeStringVisual(0, CharBuffer(" "), TextStyle.EMPTY, false))
        assertEquals(" ", line.text)
    }
}
