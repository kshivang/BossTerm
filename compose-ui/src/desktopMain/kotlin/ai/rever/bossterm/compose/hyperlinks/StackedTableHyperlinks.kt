package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import ai.rever.bossterm.terminal.util.ColumnConversionUtils

/**
 * At narrow widths Codex stacks a table record as aligned label/value fields, with a single
 * rule AFTER the record (the first record has no opening rule). Continuations align with the
 * value column, and word wrapping may leave unused space on the right. Require that geometry
 * and at least three fields; indentation alone is not evidence that prose should be joined.
 */
internal object StackedTableHyperlinks {
    private val separator = Regex(" *─{3,} *")
    private val field = Regex("^( +)([A-Za-z][A-Za-z0-9_-]*(?: [A-Za-z0-9_-]+)*)( {2,})(\\S.*)$")
    private val opening = Regex("\\(https?://")

    fun detect(snapshot: VersionedBufferSnapshot, row: Int, registry: HyperlinkRegistry): List<Hyperlink> {
        val bounds = TableHyperlinks.rows(snapshot, row)
        val bottom = (row + 1..bounds.last).firstOrNull {
            val text = snapshot.getLine(it).text
            text.isBlank() || separator.matches(text)
        } ?: return emptyList()
        val rule = snapshot.getLine(bottom).text
        if (!separator.matches(rule)) return emptyList()
        val left = rule.indexOf('─') + 1
        val right = rule.lastIndexOf('─') + 1
        var top = row
        while (top > bounds.first) {
            val previous = snapshot.getLine(top - 1).text
            if (previous.isBlank() || separator.matches(previous)) break
            top--
        }
        val fields = (top until bottom).mapNotNull { r ->
            field.matchEntire(snapshot.getLine(r).text.trimEnd())?.let { r to it }
        }
        if (fields.size < 3) return emptyList()
        val valueCol = fields.first().second.groups[4]!!.range.first
        if (fields.any { (_, match) -> match.groups[2]!!.range.first != left || match.groups[4]!!.range.first != valueCol }) return emptyList()
        val fieldRows = fields.map { it.first }.toSet()
        if ((top until bottom).any { r ->
            val line = snapshot.getLine(r)
            val text = line.text.trimEnd()
            line.isWrapped || ColumnConversionUtils.bufferColToVisualCol(line, text.length, text.length) > right || text.isEmpty() ||
                (r !in fieldRows && text.indexOfFirst { it != ' ' } != valueCol)
        }) return emptyList()

        val result = mutableListOf<Hyperlink>()
        for ((firstRow, match) in fields) {
            if (firstRow >= row + 1) continue
            val text = match.value
            for (start in opening.findAll(text.substring(valueCol))) {
                val firstCol = valueCol + start.range.first + 1
                val first = text.substring(firstCol)
                if (first.length > 8192 || first.any { it.code !in 33..126 || it == ')' }) continue
                val joined = StringBuilder(first)
                val line = snapshot.getLine(firstRow)
                val visualStart = ColumnConversionUtils.bufferColToVisualCol(line, firstCol, text.length)
                val visualEnd = ColumnConversionUtils.bufferColToVisualCol(line, text.length, text.length)
                val spans = linkedMapOf(firstRow to (visualStart to visualEnd))
                for (next in firstRow + 1 until bottom) {
                    if (next in fieldRows) break
                    val fragment = snapshot.getLine(next).text.substring(valueCol).trimEnd()
                    if (fragment.any { it.code !in 33..126 || it == '(' } || fragment.contains("://")) break
                    val closed = fragment.endsWith(')')
                    val part = if (closed) fragment.dropLast(1) else fragment
                    if (part.isEmpty() || ')' in part) break
                    joined.append(part)
                    if (joined.length > 8192) break
                    spans[next] = valueCol to valueCol + part.length
                    if (closed) {
                        val url = joined.toString()
                        val detected = HyperlinkDetector.detectHyperlinks(url, firstRow, detectFilePaths = false, registry = registry)
                            .singleOrNull { it.startCol == 0 && it.endCol == url.length && it.url == url }
                        if (detected != null && row in spans) {
                            result += detected.copy(
                                startCol = visualStart,
                                endCol = spans.getValue(next).second,
                                startRow = firstRow,
                                endRow = next,
                                rowSpans = spans.toMap(),
                            )
                        }
                        break
                    }
                }
            }
        }
        return result
    }
}
