package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import ai.rever.bossterm.terminal.util.ColumnConversionUtils
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Represents a detected hyperlink in terminal text that may span multiple terminal lines.
 *
 * All row numbers here are BUFFER rows: 0 is the top of the live screen and negative indices
 * reach into history, matching `VersionedBufferSnapshot.getLine`. They used to be screen rows,
 * which meant they silently changed meaning as the viewport scrolled; add `scrollOffset` to
 * convert one to a screen row for painting or hit-testing.
 *
 * @property url The URL to open when clicked
 * @property startCol Start column (0-based) in the first row
 * @property endCol End column (exclusive) in the last row
 * @property row Buffer row of the first line (for backwards compatibility)
 * @property startRow First buffer row of the hyperlink (same as row)
 * @property endRow Last buffer row of the hyperlink (same as startRow for single-line links)
 * @property rowSpans Map of buffer row -> (startCol, endCol) for each row the hyperlink spans
 * @property patternId The ID of the pattern that matched this hyperlink (e.g., "builtin:http")
 * @property matchedText The original text that was matched before URL transformation
 */
data class Hyperlink(
    val url: String,
    val startCol: Int,
    val endCol: Int,
    val row: Int,
    val startRow: Int = row,
    val endRow: Int = row,
    val rowSpans: Map<Int, Pair<Int, Int>> = mapOf(row to Pair(startCol, endCol)),
    val patternId: String? = null,
    val matchedText: String? = null
) {
    /**
     * Check if this hyperlink contains the given position.
     */
    fun containsPosition(col: Int, checkRow: Int): Boolean {
        val span = rowSpans[checkRow] ?: return false
        return col >= span.first && col < span.second
    }
}

/**
 * Holds information about joined wrapped lines for hyperlink detection.
 *
 * @property joinedText The concatenated text from all wrapped lines
 * @property startRow The first row (buffer coordinates) of the logical line
 * @property endRow The last row (buffer coordinates) of the logical line
 * @property rowOffsets Character offset in joinedText where each row starts
 */
data class JoinedLineInfo(
    val joinedText: String,
    val startRow: Int,
    val endRow: Int,
    val rowOffsets: List<Int>
)

/**
 * Represents a hyperlink pattern that can be registered with the detector.
 *
 * @property id Unique identifier for this pattern (used for removal)
 * @property regex The regex pattern to match
 * @property priority Higher priority patterns are matched first (default: 0)
 * @property urlTransformer Transforms the matched text into a URL (default: identity)
 * @property quickCheck Optional fast check before applying regex (for performance)
 * @property pathValidator Optional validator for file paths. Takes (match, workingDir) and returns
 *           the resolved file:// URL if the path exists, or null to skip this match.
 *           When set, urlTransformer is ignored and pathValidator takes precedence.
 */
data class HyperlinkPattern(
    val id: String,
    val regex: Regex,
    val priority: Int = 0,
    val urlTransformer: (matchedText: String) -> String = { it },
    val quickCheck: ((line: String) -> Boolean)? = null,
    val pathValidator: ((match: String, workingDir: String?) -> String?)? = null
)

/**
 * Registry for managing hyperlink patterns.
 *
 * Thread-safe: All operations are safe to call from any thread.
 *
 * Built-in patterns (registered by default):
 * - HTTP/HTTPS URLs
 * - File URLs
 * - Mailto links
 *
 * Example custom patterns:
 * ```kotlin
 * // Jira ticket pattern
 * registry.addPattern(HyperlinkPattern(
 *     id = "jira",
 *     regex = Regex("\\b([A-Z]{2,}-\\d+)\\b"),
 *     priority = 10,
 *     urlTransformer = { "https://jira.company.com/browse/$it" },
 *     quickCheck = { line -> line.any { it.isUpperCase() } && line.any { it.isDigit() } }
 * ))
 *
 * // GitHub issue pattern
 * registry.addPattern(HyperlinkPattern(
 *     id = "github-issue",
 *     regex = Regex("#(\\d+)\\b"),
 *     priority = 5,
 *     urlTransformer = { "https://github.com/org/repo/issues/${it.removePrefix("#")}" }
 * ))
 * ```
 */
class HyperlinkRegistry {
    private val patterns = CopyOnWriteArrayList<HyperlinkPattern>()

    private val _revision = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Bumped on every mutation, so a caller memoizing detection results can key on it.
     *
     * Without it an embedder adding or removing a pattern at runtime would never reach an
     * already-memoized row: the row's line instance does not change, so the memo hits forever.
     */
    val revision: Int get() = _revision.get()

    init {
        // Register built-in patterns
        addBuiltinPatterns()
    }

    private fun addBuiltinPatterns() {
        // HTTP/HTTPS URL pattern (priority 0 - lowest built-in)
        // Uses two-part pattern to exclude trailing punctuation (., ,, ;, !, etc.)
        addPattern(HyperlinkPattern(
            id = "builtin:http",
            regex = Regex("\\bhttps?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]*[\\w\\-_~/?#@\$&=%]"),
            priority = 0,
            quickCheck = { it.contains("http://") || it.contains("https://") }
        ))

        // File URL pattern (priority 0)
        addPattern(HyperlinkPattern(
            id = "builtin:file",
            regex = Regex("\\bfile:(?:///|/)[-A-Za-z0-9+\$&@#/%?=~_|!:,.;]*[-A-Za-z0-9+\$&@#/%=~_|]"),
            priority = 0,
            quickCheck = { it.contains("file:/") }
        ))

        // Mailto pattern (priority 0)
        addPattern(HyperlinkPattern(
            id = "builtin:mailto",
            regex = Regex("\\bmailto:[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}"),
            priority = 0,
            quickCheck = { it.contains("mailto:") }
        ))

        // SSH URL pattern (priority 0)
        // Supports ssh://[user@]host[:port][/path] format
        addPattern(HyperlinkPattern(
            id = "builtin:ssh",
            regex = Regex("\\bssh://[\\w.@:-]+[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]*[\\w\\-_~/?#@\$&=%]?"),
            priority = 0,
            quickCheck = { it.contains("ssh://") }
        ))

        // FTP URL pattern (priority 0)
        // Uses two-part pattern to exclude trailing punctuation
        addPattern(HyperlinkPattern(
            id = "builtin:ftp",
            regex = Regex("\\bftps?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]*[\\w\\-_~/?#@\$&=%]"),
            priority = 0,
            quickCheck = { it.contains("ftp://") || it.contains("ftps://") }
        ))

        // www. URL pattern (priority -1, lower than explicit protocols)
        // Uses two-part pattern to exclude trailing punctuation
        addPattern(HyperlinkPattern(
            id = "builtin:www",
            regex = Regex("(?<![\\p{L}0-9_.])www\\.[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]*[\\w\\-_~/?#@\$&=%]"),
            priority = -1,
            urlTransformer = { "https://$it" },
            quickCheck = { it.contains("www.") }
        ))

        // ================ File Path Patterns ================
        // These patterns validate that paths exist before creating hyperlinks

        // Home-relative paths: ~/path/to/file (priority -5)
        // Must come before absolute Unix paths to avoid ~/... matching as Unix path
        addPattern(HyperlinkPattern(
            id = "builtin:path-home",
            regex = Regex("""(?:^|(?<=[\s"'`]))~/[^\s<>"'`\[\](){}|;]+"""),
            priority = -5,
            quickCheck = { FilePathResolver.looksLikeHomePath(it) },
            pathValidator = { match, cwd ->
                FilePathResolver.resolveAndValidate(match, cwd)?.let {
                    FilePathResolver.toFileUrl(it)
                }
            }
        ))

        // Relative paths: ./path, ../path (priority -6)
        addPattern(HyperlinkPattern(
            id = "builtin:path-relative",
            regex = Regex("""(?:^|(?<=[\s"'`]))\.\.?/[^\s<>"'`\[\](){}|;]+"""),
            priority = -6,
            quickCheck = { FilePathResolver.looksLikeRelativePath(it) },
            pathValidator = { match, cwd ->
                FilePathResolver.resolveAndValidate(match, cwd)?.let {
                    FilePathResolver.toFileUrl(it)
                }
            }
        ))

        // Absolute Unix paths: /path/to/file (priority -7)
        // Lower priority to avoid matching URLs like http://path
        addPattern(HyperlinkPattern(
            id = "builtin:path-unix",
            regex = Regex("""(?:^|(?<=[\s"'`]))/(?:[^\s<>"'`\[\](){}|;:/]+/)*[^\s<>"'`\[\](){}|;:/]+"""),
            priority = -7,
            quickCheck = { FilePathResolver.looksLikeUnixPath(it) },
            pathValidator = { match, cwd ->
                FilePathResolver.resolveAndValidate(match, cwd)?.let {
                    FilePathResolver.toFileUrl(it)
                }
            }
        ))

        // Windows paths: C:\path\to\file (priority -7)
        addPattern(HyperlinkPattern(
            id = "builtin:path-windows",
            regex = Regex("""(?:^|(?<=[\s"'`]))[A-Za-z]:\\[^\s<>"'`\[\](){}|;]+"""),
            priority = -7,
            quickCheck = { FilePathResolver.looksLikeWindowsPath(it) },
            pathValidator = { match, cwd ->
                FilePathResolver.resolveAndValidate(match, cwd)?.let {
                    FilePathResolver.toFileUrl(it)
                }
            }
        ))
    }

    /**
     * Add a pattern to the registry.
     *
     * If a pattern with the same ID already exists, it will be replaced.
     *
     * @param pattern The pattern to add
     */
    fun addPattern(pattern: HyperlinkPattern) {
        // Remove existing pattern with same ID
        patterns.removeIf { it.id == pattern.id }
        patterns.add(pattern)
        _revision.incrementAndGet()
    }

    /**
     * Remove a pattern by ID.
     *
     * @param id The pattern ID to remove
     * @return true if pattern was found and removed
     */
    fun removePattern(id: String): Boolean =
        patterns.removeIf { it.id == id }.also { if (it) _revision.incrementAndGet() }

    /**
     * Get a pattern by ID.
     *
     * @param id The pattern ID
     * @return The pattern or null if not found
     */
    fun getPattern(id: String): HyperlinkPattern? {
        return patterns.find { it.id == id }
    }

    /**
     * Get all registered patterns sorted by priority (highest first).
     */
    fun getPatterns(): List<HyperlinkPattern> {
        return patterns.sortedByDescending { it.priority }
    }

    /**
     * Clear all patterns (including built-in).
     */
    fun clear() {
        patterns.clear()
        _revision.incrementAndGet()
    }

    /**
     * Reset to default built-in patterns only.
     */
    fun resetToDefaults() {
        patterns.clear()
        addBuiltinPatterns()
    }

    /**
     * Get the number of registered patterns.
     */
    fun size(): Int = patterns.size
}

/**
 * Extensible hyperlink detector for terminal text.
 *
 * Detects hyperlinks in terminal lines based on registered patterns.
 * Patterns are applied in priority order (highest first).
 *
 * Usage:
 * ```kotlin
 * // Get the singleton instance
 * val detector = HyperlinkDetector
 *
 * // Detect hyperlinks in a line
 * val hyperlinks = detector.detectHyperlinks("Visit https://example.com", row = 5)
 *
 * // Register a custom pattern
 * detector.registry.addPattern(HyperlinkPattern(
 *     id = "jira",
 *     regex = Regex("\\b([A-Z]{2,}-\\d+)\\b"),
 *     priority = 10,
 *     urlTransformer = { "https://jira.company.com/browse/$it" }
 * ))
 *
 * // Remove a pattern
 * detector.registry.removePattern("jira")
 * ```
 */
object HyperlinkDetector {
    /**
     * How far a wrapped-line walk may run in each direction.
     *
     * Without it both walks are bounded only by scrollback depth: `cat` a minified file and one
     * logical line is thousands of rows, so a single pointer move onto a new row walks all of
     * them, allocates a list that size, and hands the hover memo a key it then compares
     * element-by-element on the UI thread. At 80 columns this still spans ~5k characters, which
     * is past the point where a link is usefully detectable anyway.
     */
    const val MAX_WRAPPED_RUN_ROWS = 64

    /**
     * The pattern registry. Use this to add/remove custom patterns.
     */
    val registry = HyperlinkRegistry()

    /**
     * Detect all hyperlinks in a line of text.
     *
     * Patterns are applied in priority order. If multiple patterns match
     * overlapping regions, only the first match (highest priority) is kept.
     *
     * @param text The line of text to scan
     * @param row The row number in the terminal buffer
     * @param workingDirectory The current working directory for resolving relative paths (optional)
     * @param detectFilePaths Whether to detect file paths as hyperlinks (default: true)
     * @param registry The pattern registry to use (default: global registry)
     * @return List of detected hyperlinks, sorted by column position
     */
    fun detectHyperlinks(
        text: String,
        row: Int,
        workingDirectory: String? = null,
        detectFilePaths: Boolean = true,
        registry: HyperlinkRegistry = this.registry
    ): List<Hyperlink> {
        if (text.isEmpty()) return emptyList()

        val hyperlinks = mutableListOf<Hyperlink>()
        val coveredRanges = mutableListOf<IntRange>()

        // Apply patterns in priority order (use passed registry, not this.registry)
        for (pattern in registry.getPatterns()) {
            // Skip file path patterns if detectFilePaths is disabled
            if (!detectFilePaths && pattern.pathValidator != null) {
                continue
            }

            // Skip if quick check fails
            if (pattern.quickCheck != null && !pattern.quickCheck.invoke(text)) {
                continue
            }

            val matches = pattern.regex.findAll(text)
            for (match in matches) {
                val range = match.range

                // Check if this range overlaps with already detected hyperlinks
                val overlaps = coveredRanges.any { existing ->
                    range.first <= existing.last && range.last >= existing.first
                }

                if (!overlaps) {
                    // If pattern has pathValidator, use it (for file paths)
                    // pathValidator returns null if path doesn't exist, so skip the match
                    val url = if (pattern.pathValidator != null) {
                        pattern.pathValidator.invoke(match.value, workingDirectory) ?: continue
                    } else {
                        pattern.urlTransformer(match.value)
                    }

                    hyperlinks.add(Hyperlink(
                        url = url,
                        startCol = range.first,
                        endCol = range.last + 1,
                        row = row,
                        patternId = pattern.id,
                        matchedText = match.value
                    ))
                    coveredRanges.add(range)
                }
            }
        }

        // Sort by column position
        return hyperlinks.sortedBy { it.startCol }
    }

    /**
     * Open a URL using the system default handler.
     *
     * @param url The URL to open
     */
    fun openUrl(url: String) {
        try {
            when {
                ShellCustomizationUtils.isMacOS() -> {
                    Runtime.getRuntime().exec(arrayOf("open", url))
                }
                ShellCustomizationUtils.isWindows() -> {
                    Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", url))
                }
                else -> {
                    Runtime.getRuntime().exec(arrayOf("xdg-open", url))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Check if a URL is an SSH URL.
     */
    fun isSshUrl(url: String): Boolean = SshUrlParser.isSshUrl(url)

    /**
     * Parse an SSH URL into connection information.
     *
     * @param url The SSH URL to parse
     * @return SshConnectionInfo if valid, null otherwise
     */
    fun parseSshConnection(url: String): SshConnectionInfo? = SshUrlParser.parse(url)

    /**
     * Check if a line potentially contains any hyperlinks.
     *
     * This is a fast check that can be used to skip expensive regex matching
     * for lines that definitely don't contain URLs or file paths.
     *
     * @param line The line to check
     * @param includeFilePaths Whether to include file path checks (default: true)
     * @return true if the line might contain hyperlinks
     */
    fun canContainHyperlink(line: String, includeFilePaths: Boolean = true): Boolean {
        // URL checks
        if (line.contains("://") ||
            line.contains("www.") ||
            line.contains("mailto:") ||
            line.contains("file:/")) {
            return true
        }

        // File path checks (when enabled)
        if (includeFilePaths) {
            // Quick checks for various path types
            if (FilePathResolver.looksLikeHomePath(line) ||
                FilePathResolver.looksLikeRelativePath(line) ||
                FilePathResolver.looksLikeWindowsPath(line)) {
                return true
            }
            // Unix absolute paths - check for / but exclude URLs
            if (line.contains('/') && !line.contains("://")) {
                return true
            }
        }

        return false
    }

    /**
     * Collect wrapped lines starting from a given row, walking backwards to find the start
     * and forwards to find the end of the logical line.
     *
     * Rows here are BUFFER rows: 0 is the top of the live screen and negative indices reach
     * into history, matching [VersionedBufferSnapshot.getLine]. This used to take a screen row
     * plus a scroll offset and add them, while every caller computed screen rows by
     * subtracting the offset - so a scrolled-back viewport walked the wrong lines and bounded
     * the forward walk against the wrong end of the buffer. Working in buffer space removes
     * the conversion, and with it the chance of getting its sign wrong.
     *
     * @param snapshot The buffer snapshot
     * @param bufferRow The row to start from, in buffer coordinates
     * @param terminalWidth Terminal width in columns
     * @return JoinedLineInfo containing the complete logical line text and row mapping
     */
    fun collectWrappedLines(
        snapshot: VersionedBufferSnapshot,
        bufferRow: Int,
        terminalWidth: Int
    ): JoinedLineInfo {
        // Walk the same rows, with the same bounds, as runLinesAt: it produces the memo key
        // for this result, so a row this reads and it omits would be a row whose change went
        // unnoticed. Both stop one short of `height` because getLine returns a fresh
        // createEmpty() past it - which would pad a full terminal width for a row that is not
        // there, and put a never-identity-equal instance in that key.
        val oldest = maxOf(-snapshot.historyLinesCount, bufferRow - MAX_WRAPPED_RUN_ROWS)
        var startRow = bufferRow
        while (startRow > oldest) {
            if (snapshot.getLine(startRow - 1).isWrapped) startRow-- else break
        }

        var endRow = startRow
        val furthest = minOf(snapshot.height - 1, bufferRow + MAX_WRAPPED_RUN_ROWS)
        while (endRow < furthest && snapshot.getLine(endRow).isWrapped) endRow++

        // Join lines with VISUAL WIDTH-aware position tracking
        val joinedText = StringBuilder()
        val rowOffsets = mutableListOf<Int>()

        for (idx in startRow..endRow) {
            rowOffsets.add(joinedText.length)
            val line = snapshot.getLine(idx)
            val text = line.text
            joinedText.append(text)

            // Pad to terminal width based on VISUAL width, not character count
            // This correctly handles double-width characters (CJK, emoji) and DWC markers
            if (idx < endRow) {
                val visualWidth = calculateVisualWidth(line, terminalWidth)
                if (visualWidth < terminalWidth) {
                    repeat(terminalWidth - visualWidth) {
                        joinedText.append(' ')
                    }
                }
            }
        }

        return JoinedLineInfo(
            joinedText = joinedText.toString(),
            startRow = startRow,
            endRow = endRow,
            rowOffsets = rowOffsets
        )
    }

    /**
     * Calculate visual width of a line, accounting for:
     * - Double-width characters (CJK, emoji) = 2 columns
     * - DWC markers = 0 columns (already counted with their parent)
     * - Variation selectors, ZWJ = 0 columns
     */
    private fun calculateVisualWidth(line: TerminalLine, terminalWidth: Int): Int {
        val text = line.text
        var visualWidth = 0
        var col = 0

        while (col < text.length && visualWidth < terminalWidth) {
            val skipResult = ColumnConversionUtils.shouldSkipChar(line, col, terminalWidth)
            if (skipResult.shouldSkip) {
                col += skipResult.colsToAdvance
            } else {
                visualWidth += ColumnConversionUtils.getCharacterVisualWidth(line, col, terminalWidth)
                col++
            }
        }

        return visualWidth
    }

    /**
     * Whether [bufferRow] is part of a wrapped logical line, in either direction.
     *
     * `isWrapped` means "this line wraps INTO the next", so a row is a continuation when its
     * PREDECESSOR is wrapped.
     */
    fun isPartOfWrappedRun(snapshot: VersionedBufferSnapshot, bufferRow: Int): Boolean =
        snapshot.getLine(bufferRow).isWrapped ||
            (bufferRow - 1 >= -snapshot.historyLinesCount && snapshot.getLine(bufferRow - 1).isWrapped)

    /**
     * The line instances [bufferRow]'s detection result depends on.
     *
     * One line for an ordinary row; the whole logical run for a wrapped one, since the rejoin
     * walks to the run's start and end. Callers memoizing detection must key on all of them, or
     * an edit to a sibling row goes unnoticed. Just the wrap-flag walk - no rejoin, no regex.
     */
    fun runLinesAt(snapshot: VersionedBufferSnapshot, bufferRow: Int): List<TerminalLine> {
        val oldest = maxOf(-snapshot.historyLinesCount, bufferRow - MAX_WRAPPED_RUN_ROWS)
        if (!isPartOfWrappedRun(snapshot, bufferRow)) {
            // The predecessor's wrap flag is what decided this row is NOT a continuation, so it
            // is part of the answer and has to be in the key: a TUI rewriting row R-1 so it now
            // wraps into R would otherwise hit this memo on R's unchanged line and keep
            // returning the single-row result forever.
            return if (bufferRow - 1 >= oldest) {
                listOf(snapshot.getLine(bufferRow), snapshot.getLine(bufferRow - 1))
            } else {
                listOf(snapshot.getLine(bufferRow))
            }
        }
        var start = bufferRow
        while (start > oldest) {
            if (snapshot.getLine(start - 1).isWrapped) start-- else break
        }
        // Bounds FIRST: getLine returns a fresh createEmpty() out of range, so letting `end`
        // reach `height` would put a never-identity-equal instance in the key and miss the memo
        // on every single event - for a run that ends on the last screen row, which is exactly
        // where a live TUI's wrapped output sits.
        var end = start
        val furthest = minOf(snapshot.height - 1, bufferRow + MAX_WRAPPED_RUN_ROWS)
        while (end < furthest && snapshot.getLine(end).isWrapped) end++
        val lines = ArrayList<TerminalLine>(end - start + 2)
        for (row in start..end) lines.add(snapshot.getLine(row))
        // Same reason as above, for the walk that found `start`.
        if (start - 1 >= oldest) lines.add(snapshot.getLine(start - 1))
        return lines
    }

    /**
     * Hyperlinks on one BUFFER row, in buffer coordinates.
     *
     * Offset-free by construction: the same buffer row yields the same answer wherever the
     * viewport happens to be sitting, which is what lets a caller memo the result across a
     * scroll. This replaces a per-frame sweep of every visible row that ran the whole registry
     * per row inside the draw phase - for a result the text pass never read. Only the hover
     * hit-test and the hover underline consume it, so it is resolved one row at a time, on
     * demand.
     */
    fun detectForBufferRow(
        snapshot: VersionedBufferSnapshot,
        bufferRow: Int,
        terminalWidth: Int,
        workingDirectory: String?,
        detectFilePaths: Boolean,
        registry: HyperlinkRegistry = this.registry,
    ): List<Hyperlink> = if (isPartOfWrappedRun(snapshot, bufferRow)) {
        detectHyperlinksWithWrapping(
            snapshot, bufferRow, terminalWidth, workingDirectory, detectFilePaths, registry
        )
    } else {
        detectHyperlinks(
            snapshot.getLine(bufferRow).text, bufferRow, workingDirectory, detectFilePaths, registry
        )
    }

    /**
     * Detect hyperlinks in wrapped lines, returning hyperlinks with proper row spans.
     *
     * @param snapshot The buffer snapshot
     * @param bufferRow The row to check, in buffer coordinates (negative reaches into history)
     * @param terminalWidth Terminal width
     * @param workingDirectory The current working directory for resolving relative paths (optional)
     * @param detectFilePaths Whether to detect file paths as hyperlinks (default: true)
     * @param registry The pattern registry to use (default: global registry)
     * @return List of hyperlinks that are part of this logical line, in buffer rows
     */
    fun detectHyperlinksWithWrapping(
        snapshot: VersionedBufferSnapshot,
        bufferRow: Int,
        terminalWidth: Int,
        workingDirectory: String? = null,
        detectFilePaths: Boolean = true,
        registry: HyperlinkRegistry = this.registry
    ): List<Hyperlink> {
        val lineInfo = collectWrappedLines(snapshot, bufferRow, terminalWidth)

        // Detect hyperlinks in the joined text (pass the registry)
        val rawHyperlinks = detectHyperlinks(lineInfo.joinedText, lineInfo.startRow, workingDirectory, detectFilePaths, registry)

        // Convert flat positions to row-based spans
        return rawHyperlinks.map { hyperlink ->
            convertToMultiRowHyperlink(hyperlink, lineInfo, terminalWidth)
        }
    }

    /**
     * Convert a hyperlink detected in joined text to a multi-row hyperlink.
     */
    private fun convertToMultiRowHyperlink(
        hyperlink: Hyperlink,
        lineInfo: JoinedLineInfo,
        terminalWidth: Int
    ): Hyperlink {
        val rowSpans = mutableMapOf<Int, Pair<Int, Int>>()

        // Iterate through each row in the logical line
        for ((rowIdx, offset) in lineInfo.rowOffsets.withIndex()) {
            val row = lineInfo.startRow + rowIdx
            val nextOffset = lineInfo.rowOffsets.getOrElse(rowIdx + 1) { lineInfo.joinedText.length }

            // Calculate intersection of hyperlink range with this row's range
            val hyperlinkStartInJoined = hyperlink.startCol
            val hyperlinkEndInJoined = hyperlink.endCol

            val rowStartInJoined = offset
            val rowEndInJoined = nextOffset

            val intersectStart = maxOf(hyperlinkStartInJoined, rowStartInJoined)
            val intersectEnd = minOf(hyperlinkEndInJoined, rowEndInJoined)

            if (intersectStart < intersectEnd) {
                // Convert back to row-relative columns
                val startCol = intersectStart - offset
                val endCol = intersectEnd - offset
                rowSpans[row] = Pair(startCol, endCol)
            }
        }

        if (rowSpans.isEmpty()) {
            // Fallback: shouldn't happen, but return original
            return hyperlink
        }

        // Safe access with descriptive error - rowSpans is guaranteed non-empty after check above
        val startRow = rowSpans.keys.minOrNull()
            ?: error("rowSpans unexpectedly empty after non-empty check")
        val endRow = rowSpans.keys.maxOrNull()
            ?: error("rowSpans unexpectedly empty after non-empty check")
        val startSpan = rowSpans[startRow]
            ?: error("rowSpans[$startRow] unexpectedly null")
        val endSpan = rowSpans[endRow]
            ?: error("rowSpans[$endRow] unexpectedly null")

        return Hyperlink(
            url = hyperlink.url,
            startCol = startSpan.first,
            endCol = endSpan.second,
            row = startRow,
            startRow = startRow,
            endRow = endRow,
            rowSpans = rowSpans,
            patternId = hyperlink.patternId,
            matchedText = hyperlink.matchedText
        )
    }
}
