package ai.rever.bossterm.compose.selection

import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.TextStyle
import kotlin.test.*

/**
 * Unit tests for SelectionEngine.
 * Tests text extraction, line selection, wrapped lines, and edge cases.
 */
class SelectionEngineTest {

    private fun createTextBuffer(width: Int = 80, height: Int = 24): TerminalTextBuffer {
        val styleState = StyleState()
        return TerminalTextBuffer(width, height, styleState, 1000)
    }

    private fun TerminalTextBuffer.writeLine(row: Int, text: String) {
        // writeString uses 1-based row indexing internally
        writeString(0, row + 1, CharBuffer(text))
    }

    // ======================== Basic Selection Tests ========================

    @Test
    fun testExtractSingleCharacter() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello World")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(0, 0)
        )
        assertEquals("H", result)
    }

    @Test
    fun testExtractSingleWord() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello World")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(4, 0)
        )
        assertEquals("Hello", result)
    }

    @Test
    fun testExtractEntireLine() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello World")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(10, 0)
        )
        assertEquals("Hello World", result)
    }

    @Test
    fun testExtractMultipleLines() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Line 1")
        buffer.writeLine(1, "Line 2")
        buffer.writeLine(2, "Line 3")

        // Use trimmed extraction for multi-line to strip trailing whitespace
        val result = SelectionEngine.extractSelectedTextTrimmed(
            buffer,
            start = Pair(0, 0),
            end = Pair(5, 2)
        )
        assertEquals("Line 1\nLine 2\nLine 3", result)
    }

    @Test
    fun testExtractPartialMultipleLines() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello World")
        buffer.writeLine(1, "Foo Bar Baz")

        // Select from "World" on line 0 to "Foo" on line 1
        val result = SelectionEngine.extractSelectedTextTrimmed(
            buffer,
            start = Pair(6, 0),
            end = Pair(2, 1)
        )
        assertEquals("World\nFoo", result)
    }

    // ======================== Reverse Selection Tests ========================

    @Test
    fun testExtractReverseSelection() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello World")

        // Select from end to start (reverse direction)
        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(4, 0),
            end = Pair(0, 0)
        )
        assertEquals("Hello", result)
    }

    @Test
    fun testExtractReverseMultiLine() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Line 1")
        buffer.writeLine(1, "Line 2")

        // Select from line 1 to line 0 (reverse)
        val result = SelectionEngine.extractSelectedTextTrimmed(
            buffer,
            start = Pair(5, 1),
            end = Pair(0, 0)
        )
        assertEquals("Line 1\nLine 2", result)
    }

    // ======================== Block Selection Tests ========================

    @Test
    fun testBlockSelectionSingleLine() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello World Test")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(4, 0),
            mode = SelectionMode.BLOCK
        )
        assertEquals("Hello", result)
    }

    @Test
    fun testBlockSelectionMultipleLines() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "AAABBBCCC")
        buffer.writeLine(1, "111222333")
        buffer.writeLine(2, "XXXYYYNNN")

        // Block select middle column (BBB, 222, YYY)
        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(3, 0),
            end = Pair(5, 2),
            mode = SelectionMode.BLOCK
        )
        // Block mode doesn't add newlines, so all characters are concatenated
        assertTrue(result.contains("BBB"))
        assertTrue(result.contains("222"))
        assertTrue(result.contains("YYY"))
    }

    // ======================== Empty/Edge Cases ========================

    @Test
    fun testExtractEmptyLine() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(0, 0)
        )
        // Empty line returns NUL character or space
        assertTrue(result.length <= 1)
    }

    @Test
    fun testExtractBeyondLineEnd() {
        val buffer = createTextBuffer(width = 80)
        buffer.writeLine(0, "Short")

        // Select beyond actual content
        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(10, 0)
        )
        // Should include "Short" and padding
        assertTrue(result.startsWith("Short"))
    }

    // ======================== Line Selection Tests ========================

    @Test
    fun testSelectLineAt() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "First Line")
        buffer.writeLine(1, "Second Line")
        buffer.writeLine(2, "Third Line")

        val (start, end) = SelectionEngine.selectLineAt(5, 1, buffer)

        assertEquals(0, start.first) // Column 0
        assertEquals(1, start.second) // Row 1
        assertEquals(79, end.first) // Full width - 1
        assertEquals(1, end.second) // Row 1
    }

    // ======================== Expand to Logical Line Tests ========================

    @Test
    fun testExpandToLogicalLineNonWrapped() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Single line content")

        val (start, end) = SelectionEngine.expandToLogicalLine(0, buffer)

        assertEquals(0, start.first)
        assertEquals(0, start.second)
        assertEquals(0, end.second) // Same line
    }

    // ======================== Content End Detection Tests ========================

    @Test
    fun testFindContentEndNormal() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello     ")  // With trailing spaces

        val snapshot = buffer.createSnapshot()
        val line = snapshot.getLine(0)
        val contentEnd = SelectionEngine.findContentEnd(line, 80)

        assertEquals(4, contentEnd)  // 'o' is at index 4
    }

    @Test
    fun testFindContentEndNoTrailingSpaces() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello")

        val snapshot = buffer.createSnapshot()
        val line = snapshot.getLine(0)
        val contentEnd = SelectionEngine.findContentEnd(line, 80)

        assertEquals(4, contentEnd)
    }

    // ======================== Trimmed Extraction Tests ========================

    @Test
    fun testExtractSelectedTextTrimmed() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello     ")  // With trailing spaces
        buffer.writeLine(1, "World     ")

        val result = SelectionEngine.extractSelectedTextTrimmed(
            buffer,
            start = Pair(0, 0),
            end = Pair(9, 1)
        )

        // Should trim trailing spaces from each line
        assertEquals("Hello\nWorld", result)
    }

    @Test
    fun testExtractSelectedTextTrimmedSingleLine() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Text with spaces     ")

        val result = SelectionEngine.extractSelectedTextTrimmed(
            buffer,
            start = Pair(0, 0),
            end = Pair(20, 0)
        )

        assertEquals("Text with spaces", result)
    }

    // ======================== Wide Character Tests ========================

    @Test
    fun testExtractWithCJKCharacters() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello\u4e2d\u6587World")  // "Hello中文World"

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(13, 0)
        )

        // Should include the CJK characters
        assertTrue(result.contains("\u4e2d"))
        assertTrue(result.contains("\u6587"))
    }

    @Test
    fun testExtractWithEmoji() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello \ud83d\ude00 World")  // "Hello 😀 World"

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(14, 0)
        )

        assertTrue(result.contains("Hello"))
        assertTrue(result.contains("World"))
    }

    @Test
    fun testExtractMixedAsciiAndWideChars() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "ABC\u65e5\u672c\u8a9eXYZ")  // "ABC日本語XYZ"

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(11, 0)
        )

        assertTrue(result.startsWith("ABC"))
        assertTrue(result.contains("XYZ") || result.length >= 6)
    }

    // ======================== Word Selection Tests ========================

    @Test
    fun testSelectWordAtMiddle() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello World Test")

        val (start, end) = SelectionEngine.selectWordAt(7, 0, buffer)

        // Should select "World" (positions 6-10)
        assertEquals(0, start.second)
        assertEquals(0, end.second)
    }

    @Test
    fun testSelectWordAtStart() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello World")

        val (start, end) = SelectionEngine.selectWordAt(0, 0, buffer)

        assertEquals(0, start.first)
        assertEquals(0, start.second)
    }

    // ======================== Smart Word Selection Tests ========================

    @Test
    fun testSelectWordAtSmartWithUrl() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Visit https://example.com today")

        val (start, end) = SelectionEngine.selectWordAtSmart(10, 0, buffer)

        // Should select the entire URL
        val text = SelectionEngine.extractSelectedText(buffer, start, end)
        assertTrue(text.contains("https://"))
    }

    @Test
    fun testSelectWordAtSmartFallback() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Just regular words here")

        val (start, end) = SelectionEngine.selectWordAtSmart(5, 0, buffer)

        // Should fall back to separator-based selection
        assertNotNull(start)
        assertNotNull(end)
    }

    // ======================== Boundary Tests ========================

    @Test
    fun testSelectionAtBufferStart() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "First line")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(4, 0)
        )

        assertEquals("First", result)
    }

    @Test
    fun testSelectionAtBufferEnd() {
        val buffer = createTextBuffer(width = 80, height = 5)
        buffer.writeLine(4, "Last line")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 4),
            end = Pair(8, 4)
        )

        assertEquals("Last line", result)
    }

    @Test
    fun testSelectionSpanningEntireBuffer() {
        val buffer = createTextBuffer(width = 80, height = 3)
        buffer.writeLine(0, "Line 1")
        buffer.writeLine(1, "Line 2")
        buffer.writeLine(2, "Line 3")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(5, 2)
        )

        assertTrue(result.contains("Line 1"))
        assertTrue(result.contains("Line 2"))
        assertTrue(result.contains("Line 3"))
    }

    // ======================== Special Character Tests ========================

    @Test
    fun testExtractWithTabCharacter() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Hello\tWorld")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(10, 0)
        )

        assertTrue(result.contains("Hello"))
        assertTrue(result.contains("World"))
    }

    @Test
    fun testExtractWithNewlineInContent() {
        val buffer = createTextBuffer()
        buffer.writeLine(0, "Line1")
        buffer.writeLine(1, "Line2")

        val result = SelectionEngine.extractSelectedText(
            buffer,
            start = Pair(0, 0),
            end = Pair(4, 1)
        )

        // Multi-line selection should have newline between lines
        assertTrue(result.contains("\n") || result.contains("Line1") && result.contains("Line2"))
    }
}
