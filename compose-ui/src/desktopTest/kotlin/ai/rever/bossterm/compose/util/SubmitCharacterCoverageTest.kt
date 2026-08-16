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
    private val scanRoots = listOf(
        "src/desktopMain/kotlin",
        "../bossterm-app/src/desktopMain/kotlin",
        "../tabbed-example/src/desktopMain/kotlin",
        "../embedded-example/src/desktopMain/kotlin",
    ).map { File(it) }.filter { it.isDirectory }

    /**
     * `writeUserInput("…\n")` / `terminalWriter("…\n")` — a literal LF handed to a terminal write.
     *
     * Deliberately does NOT include a bare `write(`: `DaemonClient` and `DaemonControlChannel` write
     * newline-delimited JSON to a socket, where LF is the protocol and correct. Only the names that
     * mean "type this at a shell prompt" belong here.
     */
    private val literalLfSubmit = Regex(
        """$WRITERS\(\s*"(?:\\.|[^"\\])*\\n"\s*\)"""
    )

    /** `writeUserInput(cmd + "\n")` — the same mistake spelled with concatenation. */
    private val concatenatedLfSubmit = Regex(
        """$WRITERS\(.*\+\s*"\\n"\s*\)"""
    )

    private fun sources() = scanRoots.asSequence()
        .flatMap { it.walkTopDown() }
        .filter { it.isFile && it.extension == "kt" }

    @Test
    fun `no write path submits with a bare line feed`() {
        val offenders = mutableListOf<String>()

        sources().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.contains(VERBATIM_MARKER)) return@forEachIndexed
                if (literalLfSubmit.containsMatchIn(line) || concatenatedLfSubmit.containsMatchIn(line)) {
                    offenders += "${file.name}:${index + 1}  ${line.trim()}"
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("These write paths submit with LF, which does not press Enter under ConPTY.")
                appendLine("Wrap the command in submitLine(), or normalizeSubmitNewlines() at the writer.")
                appendLine("If a line genuinely needs a raw LF, mark it `// $VERBATIM_MARKER` and say why:")
                offenders.forEach { appendLine("  $it") }
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
        assertTrue(
            scanRoots.size >= 4,
            "the example modules must be scanned - that is where writeToFocusedPane slipped through",
        )
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

    /** And it has to be capable of failing — the regex must match the shape it is meant to catch. */
    @Test
    fun `the pattern matches the regression it exists to prevent`() {
        assertTrue(literalLfSubmit.containsMatchIn("""tab.writeUserInput("clear\n")"""))
        assertTrue(literalLfSubmit.containsMatchIn("""terminalWriter("git status\n")"""))
        assertTrue(concatenatedLfSubmit.containsMatchIn("""session.writeUserInput(cmd + "\n")"""))
        // A command containing an escaped quote. `[^"]*` stopped dead at the backslash-quote, so
        // ~10 real lines in ShellCustomizationMenuProvider — starship_setup_*, *_set_default,
        // ohmyzsh_current_theme — were invisible to this scan while looking guarded.
        assertTrue(
            literalLfSubmit.containsMatchIn("""terminalWriter("echo \"theme: \${'$'}ZSH_THEME\"\n")"""),
            "an escaped quote inside the command must not hide the LF",
        )
        // The initialCommand path behind run_in_panel, which the plain-name list used to miss.
        assertTrue(concatenatedLfSubmit.containsMatchIn("""handle.write(initialCommand + "\n")"""))
        assertTrue(concatenatedLfSubmit.containsMatchIn("""processHandle.write(initialCommand + "\n")"""))
        // A call nested in the argument, which `[^)"]*` used to let through.
        assertTrue(concatenatedLfSubmit.containsMatchIn("""tab.writeUserInput(render(x) + "\n")"""))
        // The normalizing public API is NOT in the pattern: "cmd\n" is its documented spelling and
        // is correct there. Its own guarantee is covered by the test above instead.
        assertTrue(!literalLfSubmit.containsMatchIn("""state.writeToFocusedPane("echo hi\n")"""))
        // Must not fire on the fixed forms, or it would block the correct code.
        assertTrue(!literalLfSubmit.containsMatchIn("""tab.writeUserInput(submitLine("clear"))"""))
        assertTrue(!literalLfSubmit.containsMatchIn("""session.writeUserInput(normalizeSubmitNewlines(text))"""))
        // Socket protocols legitimately write LF — the scan must not reach for those.
        assertTrue(!literalLfSubmit.containsMatchIn("""write(line); write("\n"); flush()"""))
    }

    private companion object {
        /**
         * The names that mean "type this at a shell prompt".
         *
         * `handle`/`processHandle` are qualified rather than a bare `write(`, because `DaemonClient`
         * and `DaemonControlChannel` write newline-delimited JSON to a socket, where LF is the
         * protocol and correct. Those two are the `initialCommand` path behind MCP `run_in_panel`,
         * which is one of the bugs this PR fixed and so has to stay guarded.
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
    }
}
