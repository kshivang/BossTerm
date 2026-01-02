package ai.rever.bossterm.compose.selection

import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.core.compatibility.Point
import ai.rever.bossterm.terminal.model.BufferSnapshot
import ai.rever.bossterm.terminal.model.SelectionUtil.getNextSeparator
import ai.rever.bossterm.terminal.model.SelectionUtil.getPreviousSeparator
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.util.CharUtils

/**
 * Engine for terminal text selection operations.
 * Handles word selection, line selection, and text extraction.
 */
object SelectionEngine {

    /**
     * Select word at the given character coordinates.
     * Uses SelectionUtil functions to find word boundaries at separator characters.
     *
     * Note: SelectionUtil functions expect TerminalTextBuffer and may handle locking internally.
     *
     * @param col Column position
     * @param row Row position (buffer-relative, can be negative for history)
     * @param textBuffer The terminal text buffer
     * @return Pair of start and end coordinates (col, row) for the word
     */
    fun selectWordAt(
        col: Int,
        row: Int,
        textBuffer: TerminalTextBuffer
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        // Convert Pair<Int, Int> to Point for SelectionUtil
        val clickPoint = Point(col, row)

        // Get word boundaries using SelectionUtil
        // SelectionUtil may handle its own locking internally
        val startPoint = getPreviousSeparator(clickPoint, textBuffer)
        val endPoint = getNextSeparator(clickPoint, textBuffer)

        // Convert Point back to Pair<Int, Int>
        // getNextSeparator returns separator position (exclusive), convert to inclusive
        return Pair(Pair(startPoint.x, startPoint.y), Pair(endPoint.x - 1, endPoint.y))
    }

    /**
     * Select entire logical line at the given character coordinates.
     * Handles wrapped lines by walking backwards and forwards through isWrapped property.
     *
     * @param col Column position (unused but kept for consistency)
     * @param row Row position (buffer-relative)
     * @param textBuffer The terminal text buffer
     * @return Pair of start and end coordinates for the logical line
     */
    fun selectLineAt(
        col: Int,
        row: Int,
        textBuffer: TerminalTextBuffer
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val snapshot = textBuffer.createSnapshot()
        val (startLine, endLine) = findLogicalLineBounds(row, snapshot)

        // Select from start of first line to end of last line
        return Pair(Pair(0, startLine), Pair(snapshot.width - 1, endLine))
    }

    /**
     * Find the logical line bounds (start and end row) for wrapped lines.
     * Walks backwards while previous line is wrapped, and forwards while current line is wrapped.
     *
     * @param row Row position (buffer-relative)
     * @param snapshot Buffer snapshot for line access
     * @return Pair of (startRow, endRow) for the logical line
     */
    private fun findLogicalLineBounds(
        row: Int,
        snapshot: BufferSnapshot
    ): Pair<Int, Int> {
        var startRow = row
        var endRow = row

        // Walk backwards: if the previous line's content continues onto this line (isWrapped),
        // then that line is part of the same logical line
        while (startRow > -snapshot.historyLinesCount) {
            val prevLine = snapshot.getLine(startRow - 1)
            if (prevLine.isWrapped) {
                startRow--
            } else {
                break
            }
        }

        // Walk forwards: if the current line's content continues onto the next line (isWrapped),
        // then the next line is part of the same logical line
        while (endRow < snapshot.height - 1) {
            val currentLine = snapshot.getLine(endRow)
            if (currentLine.isWrapped) {
                endRow++
            } else {
                break
            }
        }

        return Pair(startRow, endRow)
    }

    /**
     * Extract selected text from the terminal text buffer.
     * Handles multi-line selection and normalizes coordinates.
     *
     * @param textBuffer The terminal text buffer
     * @param start Selection start position (col, row)
     * @param end Selection end position (col, row)
     * @param mode Selection mode (NORMAL for line-based, BLOCK for rectangular)
     * @return The extracted text as a string
     */
    fun extractSelectedText(
        textBuffer: TerminalTextBuffer,
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
        mode: SelectionMode = SelectionMode.NORMAL
    ): String {
        val (startCol, startRow) = start
        val (endCol, endRow) = end

        // Determine first (earlier row) and last (later row) points
        // This is direction-aware: first point is always the one with smaller row
        val (firstCol, firstRow, lastCol, lastRow) = if (startRow <= endRow) {
            listOf(startCol, startRow, endCol, endRow)
        } else {
            listOf(endCol, endRow, startCol, startRow)
        }

        // Use snapshot for lock-free text extraction
        val snapshot = textBuffer.createSnapshot()
        val result = StringBuilder()

        for (row in firstRow..lastRow) {
            val line = snapshot.getLine(row)

            // Calculate column bounds based on selection mode
            val (colStart, colEnd) = when (mode) {
                // BLOCK mode: rectangular selection - same columns for all rows
                SelectionMode.BLOCK -> {
                    minOf(firstCol, lastCol) to maxOf(firstCol, lastCol)
                }
                // NORMAL mode: line-based selection
                SelectionMode.NORMAL -> {
                    if (firstRow == lastRow) {
                        // Single line: use min/max columns
                        minOf(firstCol, lastCol) to maxOf(firstCol, lastCol)
                    } else {
                        // Multi-line: direction-aware columns
                        when (row) {
                            firstRow -> firstCol to (snapshot.width - 1)  // First row: from start col to end
                            lastRow -> 0 to lastCol                        // Last row: from 0 to end col
                            else -> 0 to (snapshot.width - 1)              // Middle rows: full line
                        }
                    }
                }
            }

            for (col in colStart..colEnd) {
                if (col < snapshot.width) {
                    val char = line.charAt(col)
                    // Skip DWC markers
                    if (char != CharUtils.DWC) {
                        result.append(char)
                    }
                }
            }

            // Add newline only for hard line breaks (non-wrapped lines)
            // isWrapped=true means content continues on next line (soft wrap, no newline)
            // isWrapped=false means hard line break (actual newline in content)
            if (!line.isWrapped && row < lastRow) {
                result.append('\n')
            }
        }

        return result.toString()
    }

    /**
     * Find the actual content end of a line (excluding trailing spaces/NULs).
     * iTerm2 behavior: selection ends at last non-whitespace character.
     *
     * @param line The terminal line
     * @param width The terminal width
     * @return Column index of content end (exclusive), or 0 if line is empty
     */
    fun findContentEnd(line: TerminalLine, width: Int): Int {
        var endCol = width - 1
        while (endCol >= 0) {
            val char = line.charAt(endCol)
            if (char != ' ' && char != '\u0000' && char != CharUtils.EMPTY_CHAR && char != CharUtils.DWC) {
                return endCol  // Return inclusive end
            }
            endCol--
        }
        return 0
    }

    /**
     * Expand selection to logical line boundaries.
     * Joins wrapped lines into single logical unit for triple-click selection.
     *
     * @param row Row position (buffer-relative)
     * @param textBuffer The terminal text buffer
     * @return Pair of start and end coordinates for the logical line,
     *         with end column trimmed to actual content (no trailing whitespace)
     */
    fun expandToLogicalLine(
        row: Int,
        textBuffer: TerminalTextBuffer
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val snapshot = textBuffer.createSnapshot()
        val (startRow, endRow) = findLogicalLineBounds(row, snapshot)

        // Get content end of last line (skip trailing whitespace)
        val lastLine = snapshot.getLine(endRow)
        val endCol = findContentEnd(lastLine, snapshot.width)

        return Pair(Pair(0, startRow), Pair(endCol, endRow))
    }

    /**
     * Extract selected text with trailing whitespace trimmed from each line.
     * iTerm2 behavior: copy doesn't include trailing spaces.
     *
     * @param textBuffer The terminal text buffer
     * @param start Selection start position (col, row)
     * @param end Selection end position (col, row)
     * @param mode Selection mode (NORMAL for line-based, BLOCK for rectangular)
     * @return The extracted text with trailing whitespace trimmed
     */
    fun extractSelectedTextTrimmed(
        textBuffer: TerminalTextBuffer,
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
        mode: SelectionMode = SelectionMode.NORMAL
    ): String {
        val rawText = extractSelectedText(textBuffer, start, end, mode)
        // Trim trailing whitespace from each line
        return rawText.lines().joinToString("\n") { it.trimEnd() }
    }

    /**
     * Smart word selection using pattern matching.
     * Selects URLs, paths, quoted strings, etc. based on SmartWordSelection patterns.
     *
     * Falls back to separator-based selection if no pattern matches.
     *
     * @param col Column position
     * @param row Row position (buffer-relative)
     * @param textBuffer The terminal text buffer
     * @return Pair of start and end coordinates for the selected word
     */
    fun selectWordAtSmart(
        col: Int,
        row: Int,
        textBuffer: TerminalTextBuffer
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val snapshot = textBuffer.createSnapshot()
        val line = snapshot.getLine(row)
        val lineText = line.text

        // Try smart pattern-based selection first
        val smartResult = SmartWordSelection.selectWordAtPosition(lineText, col, row)
        if (smartResult != null) {
            return smartResult
        }

        // Fall back to separator-based selection
        return selectWordAt(col, row, textBuffer)
    }
}
