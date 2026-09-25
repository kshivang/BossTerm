package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import ai.rever.bossterm.terminal.util.ColumnConversionUtils

/**
 * Codex prints a table cell's wrapped text as separate hard terminal lines. Reconstruct only
 * parenthesized HTTP links inside a ruled table: ordinary hard newlines remain boundaries.
 * The bounding rules supply column geometry, so text in neighbouring cells is never appended.
 */
internal object TableHyperlinks {
    const val MAX_ROWS = 16
    private val rule = Regex("[─━]{3,}")
    private val start = Regex("\\(https?://")

    fun rows(snapshot: VersionedBufferSnapshot, row: Int): IntRange =
        maxOf(-snapshot.historyLinesCount, row - MAX_ROWS)..minOf(snapshot.height - 1, row + MAX_ROWS)

    private fun isRuleLine(text: String): Boolean =
        text.all { it == '─' || it == '━' || it == ' ' } && rule.containsMatchIn(text)

    private fun columns(text: String): List<IntRange>? {
        if (text.any { it != '─' && it != '━' && it != ' ' }) return null
        return rule.findAll(text).map { it.range }.toList().takeIf { it.size >= 2 }
    }

    fun detect(snapshot: VersionedBufferSnapshot, row: Int, registry: HyperlinkRegistry): List<Hyperlink> =
        detectColumns(snapshot, row, registry) + StackedTableHyperlinks.detect(snapshot, row, registry)

    /** Rule offsets are terminal cells, whereas line text contains UTF-16 and DWC markers. */
    private fun asciiCell(snapshot: VersionedBufferSnapshot, row: Int, column: IntRange, text: String): String? {
        val line = snapshot.getLine(row)
        val begin = ColumnConversionUtils.visualColToBufferCol(line, column.first, text.length)
        val end = ColumnConversionUtils.visualColToBufferCol(line, column.last + 1, text.length)
        val cell = text.substring(begin, end)
        // Within an ASCII cell the offsets used below are visual columns. Other cells may
        // contain arbitrary Unicode; only a candidate cell with ambiguous offsets abstains.
        return cell.takeIf { it.all { char -> char.code in 32..126 } }
    }

    private fun guttersClear(snapshot: VersionedBufferSnapshot, row: Int, columns: List<IntRange>, text: String): Boolean =
        columns.zipWithNext().all { (left, right) ->
            asciiCell(snapshot, row, left.last + 1 until right.first, text)?.all { it == ' ' } == true
        }

    private fun detectColumns(snapshot: VersionedBufferSnapshot, row: Int, registry: HyperlinkRegistry): List<Hyperlink> {
        val bounds = rows(snapshot, row)
        val top = (row - 1 downTo bounds.first).firstOrNull {
            isRuleLine(snapshot.getLine(it).text)
        } ?: return emptyList()
        val cols = columns(snapshot.getLine(top).text) ?: return emptyList()
        val bottom = (row + 1..bounds.last).firstOrNull {
            isRuleLine(snapshot.getLine(it).text)
        } ?: return emptyList()
        if (columns(snapshot.getLine(bottom).text) != cols) return emptyList()
        // Soft terminal wrapping has its own detector and must not be interpreted as cell wrapping.
        if ((top until bottom).any { snapshot.getLine(it).isWrapped }) return emptyList()
        val texts = (top + 1 until bottom).associateWith { snapshot.getLine(it).text }
        val clearGutters = texts.mapValues { (r, text) -> guttersClear(snapshot, r, cols, text) }
        val result = mutableListOf<Hyperlink>()
        for (column in cols) {
            for (firstRow in top + 1 until bottom - 1) {
                if (!clearGutters.getValue(firstRow)) continue
                val cell = asciiCell(snapshot, firstRow, column, texts.getValue(firstRow)) ?: continue
                for (opening in start.findAll(cell)) {
                    val begin = opening.range.first + 1
                    val first = cell.substring(begin).trimEnd()
                    // A wrap must reach the cell edge (allow the renderer's one-column padding).
                    if (first.length > 8192 || begin + first.length < column.count() - 1 || first.any { it.isWhitespace() || it == ')' }) continue
                    val joined = StringBuilder(first)
                    val spans = linkedMapOf(firstRow to (column.first + begin to column.first + begin + first.length))
                    for (nextRow in firstRow + 1 until bottom) {
                        if (!clearGutters.getValue(nextRow)) break
                        val continuation = asciiCell(snapshot, nextRow, column, texts.getValue(nextRow)) ?: break
                        val indent = continuation.indexOfFirst { it != ' ' }
                        if (indent < 0) break
                        val fragment = continuation.substring(indent).trimEnd()
                        // Numeric table columns can right-align every wrapped fragment. Accept
                        // either cell-left alignment or the same right edge as the first fragment.
                        if (indent > 1 && indent + fragment.length < column.count() - 1) break
                        if (fragment.isEmpty() || fragment.any { it.isWhitespace() } || fragment.contains("(") || fragment.contains("://")) break
                        val closed = fragment.endsWith(')')
                        val part = if (closed) fragment.dropLast(1) else fragment
                        if (part.contains(')') || part.isEmpty()) break
                        if (!closed && indent + part.length < column.count() - 1) break
                        joined.append(part)
                        if (joined.length > 8192) break
                        spans[nextRow] = column.first + indent to column.first + indent + part.length
                        if (closed) {
                            val url = joined.toString()
                            // Respect registry overrides/disabled patterns rather than hardcoding an opener.
                            val detected = HyperlinkDetector.detectHyperlinks(url, firstRow, detectFilePaths = false, registry = registry)
                                .singleOrNull { it.startCol == 0 && it.endCol == url.length && it.url == url }
                            if (detected != null && row in spans) {
                                result += detected.copy(
                                    startCol = spans.getValue(firstRow).first,
                                    endCol = spans.getValue(nextRow).second,
                                    startRow = firstRow,
                                    endRow = nextRow,
                                    rowSpans = spans.toMap(),
                                )
                            }
                            break
                        }
                    }
                }
            }
        }
        return result
    }
}
