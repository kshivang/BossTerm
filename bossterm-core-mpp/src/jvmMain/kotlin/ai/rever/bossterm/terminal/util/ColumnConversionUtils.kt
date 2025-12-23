package ai.rever.bossterm.terminal.util

import ai.rever.bossterm.terminal.model.TerminalLine

/**
 * Utility functions for converting between buffer columns and visual columns.
 *
 * Buffer columns include DWC markers, surrogate pairs, variation selectors, etc.
 * Visual columns represent what the user sees on screen.
 */
object ColumnConversionUtils {

    /**
     * Result of checking if a character should be skipped during column iteration.
     * @param shouldSkip True if the character should be skipped
     * @param colsToAdvance Number of columns to advance (1 for single char, 2 for surrogate pair)
     */
    private data class SkipResult(val shouldSkip: Boolean, val colsToAdvance: Int = 1)

    /**
     * Check if character at given column should be skipped (doesn't consume visual space).
     * This encapsulates the common skip logic used by both bufferColToVisualCol and visualColToBufferCol.
     *
     * Characters that don't consume visual space:
     * - DWC markers (placeholder for second cell of double-width char)
     * - Variation selectors (FE0E, FE0F)
     * - Zero-Width Joiner (ZWJ)
     * - Low surrogates (part of previous high surrogate)
     * - Skin tone modifiers (when part of emoji sequence)
     * - Gender symbols (when preceded by ZWJ)
     */
    private fun shouldSkipChar(line: TerminalLine, col: Int, width: Int): SkipResult {
        val char = line.charAt(col)

        // Skip DWC markers (they don't add visual width)
        if (char == CharUtils.DWC) {
            return SkipResult(true, 1)
        }

        // Skip variation selectors (FE0E, FE0F)
        if (char.code == 0xFE0E || char.code == 0xFE0F) {
            return SkipResult(true, 1)
        }

        // Skip ZWJ (U+200D)
        if (char.code == 0x200D) {
            return SkipResult(true, 1)
        }

        // Skip low surrogates (they're part of previous high surrogate)
        if (Character.isLowSurrogate(char)) {
            return SkipResult(true, 1)
        }

        // Skip skin tone modifiers (U+1F3FB-U+1F3FF, encoded as surrogate pairs)
        if (Character.isHighSurrogate(char) && col + 1 < width) {
            val nextChar = line.charAt(col + 1)
            if (Character.isLowSurrogate(nextChar)) {
                val codePoint = Character.toCodePoint(char, nextChar)
                if (codePoint in 0x1F3FB..0x1F3FF) {
                    return SkipResult(true, 2)
                }
            }
        }

        // Skip gender symbols only when preceded by ZWJ (part of ZWJ sequences)
        if (char.code == 0x2640 || char.code == 0x2642) {
            if (col > 0 && line.charAt(col - 1).code == 0x200D) {
                return SkipResult(true, 1)
            }
        }

        return SkipResult(false, 0)
    }

    /**
     * Convert buffer column to visual column.
     * Accounts for DWC markers, surrogate pairs, ZWJ sequences, and other grapheme extenders
     * that don't consume visual space.
     *
     * @param line The terminal line to analyze
     * @param bufferCol The buffer column to convert
     * @param width The terminal width (max columns)
     * @return The visual column corresponding to the buffer column
     */
    fun bufferColToVisualCol(line: TerminalLine, bufferCol: Int, width: Int): Int {
        if (bufferCol <= 0) return 0

        var visualCol = 0
        var col = 0

        while (col < bufferCol && col < width) {
            val skipResult = shouldSkipChar(line, col, width)
            if (skipResult.shouldSkip) {
                col += skipResult.colsToAdvance
                continue
            }

            // Regular character - count visual width (1 or 2 for double-width)
            visualCol += getCharacterVisualWidth(line, col, width)
            col++
        }
        return visualCol
    }

    /**
     * Convert visual column to buffer column.
     * Returns the buffer column at the START of the grapheme at the given visual position.
     * This is used for click handling to snap to grapheme boundaries.
     *
     * @param line The terminal line to analyze
     * @param visualCol The visual column to convert
     * @param width The terminal width (max columns)
     * @return The buffer column corresponding to the visual column
     */
    fun visualColToBufferCol(line: TerminalLine, visualCol: Int, width: Int): Int {
        if (visualCol <= 0) return 0

        var currentVisualCol = 0
        var col = 0

        while (col < width && currentVisualCol < visualCol) {
            val skipResult = shouldSkipChar(line, col, width)
            if (skipResult.shouldSkip) {
                col += skipResult.colsToAdvance
                continue
            }

            // Regular character - count visual width
            val charWidth = getCharacterVisualWidth(line, col, width)

            // Check if visualCol falls within this character's visual range
            if (visualCol < currentVisualCol + charWidth) {
                return col  // Snap to start of this grapheme
            }

            currentVisualCol += charWidth
            col++
        }
        return col
    }

    /**
     * Get visual width of character at buffer position (1 or 2 cells).
     *
     * Detection strategy: Look ahead through grapheme extenders to find DWC marker.
     * If DWC follows, character is double-width.
     *
     * @param line The terminal line
     * @param col The buffer column
     * @param width The terminal width
     * @return 1 for single-width, 2 for double-width characters
     */
    fun getCharacterVisualWidth(line: TerminalLine, col: Int, width: Int): Int {
        if (col >= width) return 1

        val char = line.charAt(col)

        // Simple case: next char is DWC (single BMP double-width char like CJK)
        if (col + 1 < width && line.charAt(col + 1) == CharUtils.DWC) {
            return 2
        }

        // For surrogate pairs and complex graphemes, scan forward through extenders to find DWC
        if (Character.isHighSurrogate(char)) {
            var nextCol = col + 1
            while (nextCol < width) {
                val nextChar = line.charAt(nextCol)
                // Found DWC marker - this grapheme is double-width
                if (nextChar == CharUtils.DWC) return 2
                // Continue through grapheme extenders
                if (Character.isLowSurrogate(nextChar) ||
                    nextChar.code == 0xFE0E || nextChar.code == 0xFE0F ||
                    nextChar.code == 0x200D ||
                    nextChar.code == 0x2640 || nextChar.code == 0x2642) {
                    nextCol++
                    continue
                }
                // Check for skin tone modifier (surrogate pair starting with high surrogate)
                if (Character.isHighSurrogate(nextChar) && nextCol + 1 < width) {
                    val afterNext = line.charAt(nextCol + 1)
                    if (Character.isLowSurrogate(afterNext)) {
                        val cp = Character.toCodePoint(nextChar, afterNext)
                        if (cp in 0x1F3FB..0x1F3FF) {
                            nextCol += 2
                            continue
                        }
                    }
                }
                break
            }
        }

        return 1  // Default: single width
    }
}
