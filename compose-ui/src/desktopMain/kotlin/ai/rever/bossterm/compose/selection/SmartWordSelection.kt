package ai.rever.bossterm.compose.selection

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pattern for smart word selection.
 * Similar to HyperlinkPattern but for double-click word selection.
 *
 * @property id Unique identifier for this pattern
 * @property regex The regex pattern to match
 * @property priority Higher priority patterns are matched first (default: 0)
 * @property quickCheck Optional fast check before applying regex (for performance)
 */
data class WordSelectionPattern(
    val id: String,
    val regex: Regex,
    val priority: Int = 0,
    val quickCheck: ((text: String) -> Boolean)? = null
)

/**
 * Registry for word selection patterns.
 * Thread-safe: All operations are safe to call from any thread.
 *
 * Built-in patterns (by priority):
 * - URL (100): HTTP/HTTPS URLs
 * - Unix path (90): /path/to/file
 * - Windows path (90): C:\path\to\file
 * - Email (85): user@domain.com
 * - Quoted double (80): "string"
 * - Quoted single (80): 'string'
 * - Backtick (80): `string`
 * - Default word (0): alphanumeric + underscore
 */
class WordSelectionPatternRegistry {
    private val patterns = CopyOnWriteArrayList<WordSelectionPattern>()

    init {
        addBuiltinPatterns()
    }

    private fun addBuiltinPatterns() {
        // URLs with various schemes (highest priority)
        // RFC 3986 compliant character set: unreserved + reserved chars
        // Handles http, https, ftp, ftps, ssh, file, mailto
        // Uses two-part pattern to exclude trailing punctuation (., ,, ;, !, etc.)
        addPattern(WordSelectionPattern(
            id = "builtin:url",
            regex = Regex("""(?:https?|ftps?|ssh|file)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]*[\w\-_~/?#@$&=%]"""),
            priority = 100,
            quickCheck = { it.contains("://") }
        ))

        // Mailto URLs (email links)
        addPattern(WordSelectionPattern(
            id = "builtin:mailto",
            regex = Regex("""mailto:[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}(?:\?[^\s<>"'`]*)?"""),
            priority = 99,
            quickCheck = { it.contains("mailto:") }
        ))

        // www URLs without protocol (common shorthand)
        // Uses two-part pattern to exclude trailing punctuation
        addPattern(WordSelectionPattern(
            id = "builtin:www",
            regex = Regex("""(?<![/\w])www\.[\w\-._~:/?#\[\]@!$&'()*+,;=%]*[\w\-_~/?#@$&=%]"""),
            priority = 95,
            quickCheck = { it.contains("www.") }
        ))

        // Unix file paths
        addPattern(WordSelectionPattern(
            id = "builtin:path-unix",
            regex = Regex("""(?:^|(?<=[\s"'`]))(/[\w.+-]+)+/?"""),
            priority = 90,
            quickCheck = { it.contains('/') }
        ))

        // Windows file paths
        addPattern(WordSelectionPattern(
            id = "builtin:path-windows",
            regex = Regex("""[A-Za-z]:\\[\w\\.\-+]+"""),
            priority = 90,
            quickCheck = { it.contains(":\\") }
        ))

        // Email addresses
        addPattern(WordSelectionPattern(
            id = "builtin:email",
            regex = Regex("""[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}"""),
            priority = 85,
            quickCheck = { it.contains('@') }
        ))

        // Double-quoted strings
        addPattern(WordSelectionPattern(
            id = "builtin:quoted-double",
            regex = Regex(""""[^"\\]*(?:\\.[^"\\]*)*""""),
            priority = 80,
            quickCheck = { it.contains('"') }
        ))

        // Single-quoted strings
        addPattern(WordSelectionPattern(
            id = "builtin:quoted-single",
            regex = Regex("""'[^'\\]*(?:\\.[^'\\]*)*'"""),
            priority = 80,
            quickCheck = { it.contains('\'') }
        ))

        // Backtick strings (common in shells)
        addPattern(WordSelectionPattern(
            id = "builtin:backtick",
            regex = Regex("""`[^`]+`"""),
            priority = 80,
            quickCheck = { it.contains('`') }
        ))

        // Default word (alphanumeric + underscore) - lowest priority
        addPattern(WordSelectionPattern(
            id = "builtin:word",
            regex = Regex("""\w+"""),
            priority = 0
        ))
    }

    /**
     * Add a pattern to the registry.
     * If a pattern with the same ID exists, it will be replaced.
     */
    fun addPattern(pattern: WordSelectionPattern) {
        patterns.removeIf { it.id == pattern.id }
        patterns.add(pattern)
    }

    /**
     * Remove a pattern by ID.
     */
    fun removePattern(id: String): Boolean {
        return patterns.removeIf { it.id == id }
    }

    /**
     * Get all patterns sorted by priority (highest first).
     */
    fun getPatterns(): List<WordSelectionPattern> {
        return patterns.sortedByDescending { it.priority }
    }

    /**
     * Reset to default built-in patterns only.
     */
    fun resetToDefaults() {
        patterns.clear()
        addBuiltinPatterns()
    }
}

/**
 * Smart word selection for double-click.
 * Uses pattern registry to select URLs, paths, quoted strings, etc.
 */
object SmartWordSelection {
    val registry = WordSelectionPatternRegistry()

    /**
     * Window size for windowed regex search optimization.
     * Patterns are searched within +/- this many characters from click position.
     */
    private const val SEARCH_WINDOW_RADIUS = 150

    /**
     * Find the word/pattern at the given column position in text.
     *
     * Uses windowed search for performance: instead of scanning the entire line
     * for each pattern, we extract a window around the click position and search
     * only within that window. This provides O(window_size) instead of O(line_length)
     * complexity per pattern.
     *
     * @param text The full line text
     * @param col Column position (0-based)
     * @return Pair of (startCol, endCol exclusive) or null if no match
     */
    fun selectWordAt(text: String, col: Int): Pair<Int, Int>? {
        if (text.isEmpty() || col < 0 || col >= text.length) {
            return null
        }

        // For short lines, search the whole thing
        if (text.length <= SEARCH_WINDOW_RADIUS * 2) {
            return selectWordAtFullScan(text, col)
        }

        // Extract a window around the click position
        val windowStart = maxOf(0, col - SEARCH_WINDOW_RADIUS)
        val windowEnd = minOf(text.length, col + SEARCH_WINDOW_RADIUS)
        val window = text.substring(windowStart, windowEnd)
        val colInWindow = col - windowStart

        // Try each pattern in priority order
        for (pattern in registry.getPatterns()) {
            // Skip if quick check fails (check window only for efficiency)
            if (pattern.quickCheck != null && !pattern.quickCheck.invoke(window)) {
                continue
            }

            // Search within the window only
            for (match in pattern.regex.findAll(window)) {
                if (colInWindow >= match.range.first && colInWindow <= match.range.last) {
                    // Adjust coordinates back to full line (inclusive end)
                    return Pair(
                        windowStart + match.range.first,
                        windowStart + match.range.last
                    )
                }
            }
        }

        return null
    }

    /**
     * Full scan implementation for short lines where windowing overhead isn't worth it.
     */
    private fun selectWordAtFullScan(text: String, col: Int): Pair<Int, Int>? {
        for (pattern in registry.getPatterns()) {
            if (pattern.quickCheck != null && !pattern.quickCheck.invoke(text)) {
                continue
            }
            for (match in pattern.regex.findAll(text)) {
                if (col >= match.range.first && col <= match.range.last) {
                    return Pair(match.range.first, match.range.last)  // Inclusive end
                }
            }
        }
        return null
    }

    /**
     * Select word at position, returning buffer coordinates.
     * Handles the case where line text needs to be retrieved.
     *
     * @param lineText The line text
     * @param col Column position in line
     * @param row Row position (passed through for result)
     * @return Pair of start and end coordinates (col, row)
     */
    fun selectWordAtPosition(
        lineText: String,
        col: Int,
        row: Int
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val result = selectWordAt(lineText, col) ?: return null
        return Pair(Pair(result.first, row), Pair(result.second, row))
    }
}
