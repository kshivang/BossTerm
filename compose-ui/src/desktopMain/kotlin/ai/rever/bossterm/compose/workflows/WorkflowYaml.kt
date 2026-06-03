package ai.rever.bossterm.compose.workflows

/**
 * Minimal, dependency-free YAML reader for the Warp **workflow** schema only —
 * NOT a general YAML parser. It deliberately supports just the shapes that
 * appear in workflow files:
 *
 * - top-level scalars `name` / `command` / `description` (plain, quoted, or a
 *   `|` literal / `>` folded block scalar),
 * - `arguments:` as a block list of `{name, description, default_value}` maps,
 * - `tags:` / `shells:` as an inline `[a, b]` list or a block `- item` list.
 *
 * Anchors, flow maps, multi-document streams, and inline comments are not
 * supported; unparseable files are skipped by [WorkflowStore].
 */
object WorkflowYaml {

    fun parse(text: String, sourcePath: String? = null): Workflow? {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")

        var name: String? = null
        var command: String? = null
        var description: String? = null
        var arguments: List<WorkflowArgument> = emptyList()
        var tags: List<String> = emptyList()
        var shells: List<String> = emptyList()

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || indentOf(raw) != 0 || !trimmed.contains(":")) {
                i++
                continue
            }
            val key = trimmed.substringBefore(":").trim()
            val rest = trimmed.substringAfter(":").trim()
            when (key) {
                "name", "command", "description" -> {
                    val value: String
                    if (rest.startsWith("|") || rest.startsWith(">")) {
                        val (block, next) = readBlockScalar(lines, i + 1, fold = rest.startsWith(">"))
                        value = block
                        i = next
                    } else {
                        value = unquote(rest)
                        i++
                    }
                    when (key) {
                        "name" -> name = value
                        "command" -> command = value
                        "description" -> description = value
                    }
                }
                "arguments" -> {
                    val (args, next) = readArguments(lines, i + 1)
                    arguments = args
                    i = next
                }
                "tags", "shells" -> {
                    val list: List<String>
                    if (rest.startsWith("[")) {
                        list = parseInlineList(rest)
                        i++
                    } else if (rest.isEmpty()) {
                        val (block, next) = readBlockList(lines, i + 1)
                        list = block
                        i = next
                    } else {
                        list = emptyList()
                        i++
                    }
                    if (key == "tags") tags = list else shells = list
                }
                else -> i++
            }
        }

        val resolvedName = name?.takeIf { it.isNotBlank() } ?: return null
        val resolvedCommand = command?.takeIf { it.isNotBlank() } ?: return null
        return Workflow(resolvedName, resolvedCommand, description, arguments, tags, shells, sourcePath)
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length

    private fun unquote(s: String): String {
        if (s.length >= 2) {
            if (s.first() == '"' && s.last() == '"') {
                return s.substring(1, s.length - 1)
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\")
            }
            if (s.first() == '\'' && s.last() == '\'') {
                return s.substring(1, s.length - 1).replace("''", "'")
            }
        }
        return s
    }

    /** Read an indented block scalar; `fold` joins lines with spaces (`>`) vs newlines (`|`). */
    private fun readBlockScalar(lines: List<String>, start: Int, fold: Boolean): Pair<String, Int> {
        val content = mutableListOf<String>()
        var i = start
        var baseIndent = -1
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) { content.add(""); i++; continue }
            if (indentOf(raw) == 0) break
            if (baseIndent < 0) baseIndent = indentOf(raw)
            content.add(raw.drop(baseIndent))
            i++
        }
        while (content.isNotEmpty() && content.last().isBlank()) content.removeAt(content.size - 1)
        val joined = if (fold) content.joinToString(" ") { it.trim() }.trim() else content.joinToString("\n")
        return joined to i
    }

    private fun readArguments(lines: List<String>, start: Int): Pair<List<WorkflowArgument>, Int> {
        val args = mutableListOf<WorkflowArgument>()
        var i = start
        var curName: String? = null
        var curDesc: String? = null
        var curDef: String? = null

        fun flush() {
            curName?.let { args.add(WorkflowArgument(it, curDesc, curDef)) }
            curName = null; curDesc = null; curDef = null
        }

        fun applyKey(k: String, v: String) {
            when (k) {
                "name" -> curName = v
                "description" -> curDesc = v
                "default_value" -> curDef = v
            }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { i++; continue }
            if (indentOf(raw) == 0) break
            if (trimmed.startsWith("-")) {
                flush()
                val afterDash = trimmed.removePrefix("-").trim()
                if (afterDash.contains(":")) {
                    applyKey(afterDash.substringBefore(":").trim(), unquote(afterDash.substringAfter(":").trim()))
                }
            } else if (trimmed.contains(":")) {
                applyKey(trimmed.substringBefore(":").trim(), unquote(trimmed.substringAfter(":").trim()))
            }
            i++
        }
        flush()
        return args to i
    }

    private fun readBlockList(lines: List<String>, start: Int): Pair<List<String>, Int> {
        val out = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { i++; continue }
            if (indentOf(raw) == 0) break
            if (trimmed.startsWith("-")) out.add(unquote(trimmed.removePrefix("-").trim()))
            i++
        }
        return out to i
    }

    private fun parseInlineList(s: String): List<String> {
        val inner = s.trim().removePrefix("[").substringBeforeLast("]")
        if (inner.isBlank()) return emptyList()
        return inner.split(",").map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }
}
