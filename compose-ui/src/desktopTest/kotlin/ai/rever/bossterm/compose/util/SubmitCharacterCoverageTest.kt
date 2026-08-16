package ai.rever.bossterm.compose.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No write path may submit with a bare LF.
 *
 * The helper's own unit tests were never the hard part — `submitLine` was correct from the first
 * commit. What actually broke was call sites: the context menu built its own `terminalWriter` that
 * skipped normalization, so right-click → Git → Status stranded at a `>>` prompt on Windows while
 * the identical item on the Tools menu worked. That class of drift is invisible to anyone developing
 * on macOS or Linux, where `ICRNL` makes LF submit anyway, so it has to be caught by the build
 * rather than by review. Same reasoning as [ai.rever.bossterm.compose.mcp.AttachTargetCoverageTest],
 * which fails when the two MCP registries drift apart.
 *
 * Scans source rather than behaviour because the failure is a literal in an argument list, and there
 * is no seam to observe it through without booting a PTY per menu item. Its reach is the limit of
 * what it can promise: a writer handed a `String` parameter has no literal to match, so this catches
 * the common shape, not every possible one.
 */
class SubmitCharacterCoverageTest {

    /**
     * Every module that can type at a prompt, not just this one.
     *
     * The examples are in scope deliberately — `tabbed-example` called `writeToFocusedPane("…\n")`,
     * and because it sat outside the original scan the public API behind it stayed broken through a
     * whole review round. Sample code is the copy-paste source for embedders, so it has to hold the
     * same line as the library.
     */
    private val scanRoots = moduleNames()
        .map { File(repoRoot, "$it/src/desktopMain/kotlin") }
        .filter { it.isDirectory }

    /**
     * Every module `settings.gradle.kts` includes, so the guard extends itself.
     *
     * Was a hardcoded four-module list, which meant a new module was silently unguarded and a
     * renamed one failed with a message about example modules. Reading the include block instead
     * matters more here than it would elsewhere: the whole point of this suite is catching drift
     * that nobody is looking for, and "nobody added the new module to the scan" is exactly that.
     */
    private fun moduleNames(): List<String> {
        val settings = File(repoRoot, "settings.gradle.kts").readText()
        val includeBlock = settings.substringAfter("include(").substringBefore(")")
        return Regex(""""\s*:([\w-]+)\s*"""").findAll(includeBlock).map { it.groupValues[1] }.toList()
    }

    /** Finds where a write call starts; the argument is then read by balancing parentheses. */
    private val writerCall = Regex("""$WRITERS\s*\(""")

    private fun sources() = scanRoots.asSequence()
        .flatMap { it.walkTopDown() }
        .filter { it.isFile && it.extension == "kt" }

    /**
     * The argument text of the call starting at [openParen], up to its matching close.
     *
     * Balances parentheses rather than matching a regex, and tracks string state so a `)` inside a
     * command literal doesn't end the scan early. Line-scoped regexes were the previous approach and
     * they missed two of the shapes this rule exists for — a call wrapped across lines, and a
     * separator chosen by a conditional — because in both the writer name and the `\n` sit on
     * different lines, or the `\n` isn't adjacent to the closing paren.
     */
    private fun argumentText(text: String, openParen: Int): String? {
        var depth = 0
        var i = openParen
        var inString = false
        var escaped = false
        // A write call's argument is one expression. If the parens haven't balanced by here, the
        // scanner has lost track — a raw string, a char literal holding a quote, a quote inside a
        // comment — and running on to EOF would blame a later `\n` in the file on this call. Give up
        // instead: `argumentText` returns null and the caller reports it as unparseable, which sends
        // the next reader to the right place rather than a confidently wrong one.
        val limit = minOf(text.length, openParen + MAX_CALL_CHARS)
        while (i < limit) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openParen + 1, i)
                }
            }
            i++
        }
        return null
    }

    /** An LF escape appearing inside a string literal — `"…\n"`, however the call is spelled. */
    private val lfInsideStringLiteral = Regex(""""(?:\\.|[^"\\])*\\n(?:\\.|[^"\\])*"""")

    @Test
    fun `no write path submits with a bare line feed`() {
        val offenders = mutableListOf<String>()
        val unparseable = mutableListOf<String>()

        sources().forEach { file ->
            val text = file.readText()
            // Precomputed so a match's offset can be reported as a line number.
            val lineStarts = text.mapIndexedNotNull { i, c -> if (c == '\n') i + 1 else null }
            fun lineOf(offset: Int) = lineStarts.count { it <= offset } + 1

            for (match in writerCall.findAll(text)) {
                val openParen = match.range.last
                val argument = argumentText(text, openParen)
                if (argument == null) {
                    unparseable += "${file.name}:${lineOf(match.range.first)}  ${match.value}…"
                    continue
                }
                if (!lfInsideStringLiteral.containsMatchIn(argument)) continue
                // The opt-out may sit on any line the call spans.
                val start = lineOf(match.range.first)
                val end = lineOf(openParen + argument.length)
                val spanned = text.lines().subList(start - 1, minOf(end, text.lines().size))
                if (spanned.any { it.contains(VERBATIM_MARKER) }) continue
                offenders += "${file.name}:$start  ${match.value}${argument.trim().take(90)})"
            }
        }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("These write paths submit with LF, which does not press Enter under ConPTY.")
                appendLine("Wrap the command in submitLine(), or normalizeSubmitNewlines() at the writer.")
                appendLine("If one genuinely needs a raw LF, mark it `// $VERBATIM_MARKER` and say why:")
                offenders.forEach { appendLine("  $it") }
            },
        )
        // Reported separately from a plain pass: a call the scanner couldn't read is a call it
        // didn't check, and silently treating that as clean is how a guard rots into decoration.
        assertTrue(
            unparseable.isEmpty(),
            buildString {
                appendLine("Could not find the end of these write calls within $MAX_CALL_CHARS chars.")
                appendLine("argumentText balances parens and tracks double quotes only, so a raw")
                appendLine("string, a char literal holding a quote, or a quote inside a comment in")
                appendLine("the call will throw it off. Teach it that shape, or simplify the call:")
                unparseable.forEach { appendLine("  $it") }
            },
        )
    }

    /**
     * The scan has to actually be looking at something — an empty or mis-rooted walk would pass
     * this suite while catching nothing, which is the failure mode of every source-scanning test.
     */
    @Test
    fun `the scan reaches the sources it claims to cover`() {
        val files = sources().toList()
        assertTrue(files.size > 100, "expected to scan the desktop sources, found ${files.size}")
        for (regressed in listOf("TabbedTerminal.kt", "ProperTerminal.kt", "TabbedTerminalState.kt")) {
            assertTrue(files.any { it.name == regressed }, "$regressed must be in scope")
        }
        // Derived from settings.gradle.kts, so this also fails if the include block stops parsing.
        assertTrue(moduleNames().size >= 5, "expected every included module, got ${moduleNames()}")
        for (module in listOf("tabbed-example", "embedded-example")) {
            assertTrue(
                scanRoots.any { it.path.replace('\\', '/').contains("/$module/") },
                "$module must be scanned - sample code is what embedders copy, and it is where " +
                    "writeToFocusedPane's LF call slipped through a whole review round",
            )
        }
    }

    /**
     * The other half of the rule: the public API must normalize, so that `"cmd\n"` at ITS call sites
     * — the documented spelling, used throughout both example apps — actually presses Enter.
     *
     * This is the check that would have caught `writeToFocusedPane`, which shipped LF-only through a
     * whole review round. The literal scan above structurally cannot: the bug is in a function whose
     * argument is a parameter, so there is no `\n` literal to match. Asserted on the source because
     * the alternative is booting a PTY per overload.
     */
    @Test
    fun `every public write entry point normalizes newlines`() {
        val surfaces = mapOf(
            "TabbedTerminalState.kt" to listOf("fun write(", "fun writeToFocusedPane("),
            "EmbeddableTerminal.kt" to listOf("fun write("),
        )
        for ((fileName, signatures) in surfaces) {
            val file = sources().firstOrNull { it.name == fileName }
                ?: error("$fileName not found in the scan roots")
            val lines = file.readLines()
            for (signature in signatures) {
                val declarations = lines.withIndex().filter { it.value.contains(signature) }
                assertTrue(declarations.isNotEmpty(), "no `$signature` found in $fileName")
                for ((index, decl) in declarations) {
                    // The body is short by construction; a handful of lines covers every overload.
                    val body = lines.subList(index, minOf(index + 6, lines.size)).joinToString("\n")
                    assertTrue(
                        body.contains("normalizeSubmitNewlines") || body.contains("submitLine"),
                        "$fileName:${index + 1} `${decl.trim()}` must normalize newlines — it is a " +
                            "public write API and its documented contract is that \\n presses Enter",
                    )
                }
            }
        }
    }

    /** Runs the real scan over one snippet, so these cases can't drift from what the scan does. */
    private fun flags(snippet: String): Boolean {
        val match = writerCall.find(snippet) ?: return false
        val argument = argumentText(snippet, match.range.last) ?: return false
        return lfInsideStringLiteral.containsMatchIn(argument)
    }

    /** And it has to be capable of failing — the scan must catch the shapes it is meant to. */
    @Test
    fun `the pattern matches the regression it exists to prevent`() {
        assertTrue(flags("""tab.writeUserInput("clear\n")"""))
        assertTrue(flags("""terminalWriter("git status\n")"""))
        assertTrue(flags("""session.writeUserInput(cmd + "\n")"""))
        // A command containing an escaped quote. `[^"]*` stopped dead at the backslash-quote, so
        // ~10 real lines in ShellCustomizationMenuProvider — starship_setup_*, *_set_default,
        // ohmyzsh_current_theme — were invisible to this scan while looking guarded.
        assertTrue(
            flags("""terminalWriter("echo \"theme: \${'$'}ZSH_THEME\"\n")"""),
            "an escaped quote inside the command must not hide the LF",
        )
        // The initialCommand path behind run_in_panel, which the plain-name list used to miss.
        assertTrue(flags("""handle.write(initialCommand + "\n")"""))
        assertTrue(flags("""processHandle.write(initialCommand + "\n")"""))
        // A call nested in the argument, which `[^)"]*` used to let through.
        assertTrue(flags("""tab.writeUserInput(render(x) + "\n")"""))
        // The two shapes the line-scoped version missed. Both were real pre-fix code in this PR, in
        // the two files this suite names as must-be-in-scope, so "the common shape" was too generous
        // a description of what it covered.
        assertTrue(
            // The ollama launch line: writer name and literal ended up on different lines.
            flags("writeToTerminal(\n    if (a == null) \"run \" else \"run \$a\\n\"\n)"),
            "a call spanning lines must still be scanned",
        )
        assertTrue(
            // Workflow auto-run: the "\n" is not adjacent to the closing paren.
            flags("""tab.writeUserInput(rendered + if (auto) "\n" else "")"""),
            "a conditional separator must still be scanned",
        )
        // A `)` inside the command must not end the argument scan early.
        assertTrue(flags("""terminalWriter("echo \$(date)\n")"""))
        // The normalizing public API is NOT scanned: "cmd\n" is its documented spelling and is
        // correct there. Its own guarantee is covered by `every public write entry point…` instead.
        assertTrue(!flags("""state.writeToFocusedPane("echo hi\n")"""))
        // Must not fire on the fixed forms, or it would block the correct code.
        assertTrue(!flags("""tab.writeUserInput(submitLine("clear"))"""))
        assertTrue(!flags("""session.writeUserInput(normalizeSubmitNewlines(text))"""))
        assertTrue(!flags("""tab.writeUserInput(if (auto) submitLine(rendered) else rendered)"""))
        // Socket protocols legitimately write LF — the scan must not reach for those.
        assertTrue(!flags("""write(line); write("\n"); flush()"""))
        // Nor for a non-writer that happens to share a line with one.
        assertTrue(!flags("""sb.append(x + "\n")"""))
    }

    private companion object {
        /**
         * The names that mean "type this at a shell prompt".
         *
         * `handle`/`processHandle` are qualified rather than a bare `write(`, because `DaemonClient`
         * and `DaemonControlChannel` write newline-delimited JSON to a socket, where LF is the
         * protocol and correct. Those two are the `initialCommand` path behind MCP `run_in_panel`,
         * which is one of the bugs this PR fixed and so has to stay guarded.
         *
         * Three names are excluded on purpose, for two different reasons:
         *  - `write` / `writeToFocusedPane` normalize internally, so `"cmd\n"` is their documented
         *    spelling and correct at their call sites. That they normalize is asserted separately by
         *    `every public write entry point normalizes newlines`.
         *  - `writeVerbatim` exists precisely to send raw bytes, so calling it IS the opt-out; adding
         *    it here would mean marking every legitimate use `$VERBATIM_MARKER`. The name is the
         *    warning, and its KDoc says `\r` submits and `\n` does not.
         */
        const val WRITERS =
            """(writeUserInput|terminalWriter|writeToTerminal|(?:handle|processHandle)\.write)"""

        /**
         * Per-line opt-out. Narrow on purpose: excluding a whole file left `BossTermMcpServer`'s
         * `run_command` and `run_in_panel` — two of the paths this rule exists to protect —
         * unguarded just because `send_input` shares the file. A line claiming the exemption has to
         * say so on the line itself.
         */
        const val VERBATIM_MARKER = "verbatim-by-design"

        /** Generous for one expression, short enough that a lost scanner gives up near the call. */
        const val MAX_CALL_CHARS = 600

        /**
         * Injected by the build (`bossterm.repoRoot`), so the scan roots resolve the same whichever
         * directory the runner picks.
         *
         * Falls back to sniffing upward for `settings.gradle.kts`, which covers running the class
         * outside Gradle. Relative paths were the original approach and they resolved to nothing
         * when an IDE run config started at the repo root — an empty scan that the LF test passes
         * vacuously. Failing loudly here beats a green test that looked at no files.
         */
        val repoRoot: File = System.getProperty("bossterm.repoRoot")?.let(::File)
            ?: generateSequence(File(".").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error(
                "repo root not found: pass -Dbossterm.repoRoot, or run from inside the repo " +
                    "(cwd was ${File(".").absolutePath})"
            )
    }
}
