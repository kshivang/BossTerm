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
 * is no seam to observe it through without booting a PTY per menu item.
 */
class SubmitCharacterCoverageTest {

    private val sourceRoot = File("src/desktopMain/kotlin")

    /**
     * Writers that intentionally pass bytes straight through, keyed by the file that owns them.
     *
     * `send_input` is the deliberate one: a bare LF is Ctrl+J, a keystroke a caller may genuinely
     * want (a newline inside a multi-line prompt to an AI CLI), so normalizing it centrally would
     * remove the only way to send one. Anything added here needs the same argument in writing.
     */
    private val verbatimByDesign = setOf(
        "BossTermMcpServer.kt",   // send_input — documented escape hatch
        "DaemonMcpTools.kt",      // daemon send_input, same contract
        "TerminalSubmit.kt",      // the helper itself
    )

    /**
     * `writeUserInput("…\n")` / `terminalWriter("…\n")` — a literal LF handed to a terminal write.
     *
     * Deliberately does NOT include a bare `write(`: `DaemonClient` and `DaemonControlChannel` write
     * newline-delimited JSON to a socket, where LF is the protocol and correct. Only the names that
     * mean "type this at a shell prompt" belong here.
     */
    private val literalLfSubmit = Regex(
        """(writeUserInput|terminalWriter|writeToTerminal)\(\s*"[^"]*\\n"\s*\)"""
    )

    /** `writeUserInput(cmd + "\n")` — the same mistake spelled with concatenation. */
    private val concatenatedLfSubmit = Regex(
        """(writeUserInput|terminalWriter|writeToTerminal)\([^)"]*\+\s*"\\n"\s*\)"""
    )

    @Test
    fun `no write path submits with a bare line feed`() {
        val offenders = mutableListOf<String>()

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name in verbatimByDesign }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (literalLfSubmit.containsMatchIn(line) || concatenatedLfSubmit.containsMatchIn(line)) {
                        offenders += "${file.name}:${index + 1}  ${line.trim()}"
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("These write paths submit with LF, which does not press Enter under ConPTY.")
                appendLine("Wrap the command in submitLine(), or normalizeSubmitNewlines() at the writer:")
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
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 100, "expected to scan the desktop sources, found ${files.size}")
        assertTrue(
            files.any { it.name == "TabbedTerminal.kt" } && files.any { it.name == "ProperTerminal.kt" },
            "the files that regressed must be in scope",
        )
    }

    /** And it has to be capable of failing — the regex must match the shape it is meant to catch. */
    @Test
    fun `the pattern matches the regression it exists to prevent`() {
        assertTrue(literalLfSubmit.containsMatchIn("""tab.writeUserInput("clear\n")"""))
        assertTrue(literalLfSubmit.containsMatchIn("""terminalWriter("git status\n")"""))
        assertTrue(concatenatedLfSubmit.containsMatchIn("""session.writeUserInput(cmd + "\n")"""))
        // Socket protocols legitimately write LF — the scan must not reach for those.
        assertTrue(!literalLfSubmit.containsMatchIn("""write(line); write("\n"); flush()"""))
        // And must not fire on the fixed forms, or it would block the correct code.
        assertTrue(!literalLfSubmit.containsMatchIn("""tab.writeUserInput(submitLine("clear"))"""))
        assertTrue(!literalLfSubmit.containsMatchIn("""session.writeUserInput(normalizeSubmitNewlines(text))"""))
    }
}
