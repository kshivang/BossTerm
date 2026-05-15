package ai.rever.bossterm.compose.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One-click attach helper that registers this BossTerm MCP endpoint with a
 * third-party AI CLI via the CLI's native `mcp` subcommand. Falls back to
 * dropping a ready-to-paste config snippet on the system clipboard if the
 * CLI binary is missing, the command fails, or the call times out.
 *
 * The `{NAME}` placeholder in command/clipboard templates is filled with
 * the embedder's `BossTermMcpConfig.serverName` (or `"bossterm"` for the
 * default app), and `{URL}` with the resolved
 * `http://127.0.0.1:<port>/mcp` endpoint.
 *
 * Tested CLI shapes (last verified May 2026):
 *  - Claude Code:  `claude mcp add --transport sse <name> <url>` (verified)
 *  - Codex:        `codex mcp add <name> --transport sse <url>` (best-effort; falls back to ~/.codex/config.toml snippet)
 *  - Gemini CLI:   `gemini mcp add <name> --transport sse <url>` (best-effort; falls back to ~/.gemini/settings.json snippet)
 *  - OpenCode:     `opencode mcp add <name> --transport sse <url>` (best-effort; falls back to ~/.config/opencode/opencode.json snippet)
 *
 * If any of the best-effort commands turn out to have a different
 * subcommand in your installed version, the clipboard fallback path
 * handles it gracefully — the only visual symptom is the amber
 * "exit N — config copied to clipboard" status instead of the green
 * success line.
 */
enum class McpAttachTarget(
    val displayName: String,
    /** Command run first to remove any existing entry. Errors ignored. `{NAME}` is replaced. */
    private val removeCommand: List<String>?,
    /** Primary command. `{URL}` and `{NAME}` get replaced at call time. */
    private val addCommand: List<String>,
    /** Help text + ready-to-paste config when the shell-out fails. */
    private val clipboardFallback: String
) {
    CLAUDE_CODE(
        displayName = "Claude Code",
        removeCommand = listOf("claude", "mcp", "remove", "{NAME}"),
        addCommand = listOf("claude", "mcp", "add", "--transport", "sse", "{NAME}", "{URL}"),
        clipboardFallback = "claude mcp add --transport sse {NAME} {URL}"
    ),
    CODEX(
        displayName = "Codex",
        removeCommand = listOf("codex", "mcp", "remove", "{NAME}"),
        addCommand = listOf("codex", "mcp", "add", "{NAME}", "--transport", "sse", "{URL}"),
        clipboardFallback = """
            # Append to ~/.codex/config.toml
            [mcp_servers.{NAME}]
            type = "sse"
            url = "{URL}"
        """.trimIndent()
    ),
    GEMINI(
        displayName = "Gemini CLI",
        removeCommand = listOf("gemini", "mcp", "remove", "{NAME}"),
        addCommand = listOf("gemini", "mcp", "add", "{NAME}", "--transport", "sse", "{URL}"),
        clipboardFallback = """
            // Merge into ~/.gemini/settings.json
            {
              "mcpServers": {
                "{NAME}": {
                  "httpUrl": "{URL}"
                }
              }
            }
        """.trimIndent()
    ),
    OPENCODE(
        displayName = "OpenCode",
        removeCommand = listOf("opencode", "mcp", "remove", "{NAME}"),
        addCommand = listOf("opencode", "mcp", "add", "{NAME}", "--transport", "sse", "{URL}"),
        clipboardFallback = """
            // Merge into ~/.config/opencode/opencode.json
            {
              "mcp": {
                "{NAME}": {
                  "type": "remote",
                  "url": "{URL}"
                }
              }
            }
        """.trimIndent()
    );

    fun resolvedAddCommand(name: String, url: String): List<String> =
        addCommand.map { it.replace("{NAME}", name).replace("{URL}", url) }

    fun resolvedRemoveCommand(name: String): List<String>? =
        removeCommand?.map { it.replace("{NAME}", name) }

    fun resolvedClipboard(name: String, url: String): String =
        clipboardFallback.replace("{NAME}", name).replace("{URL}", url)
}

/**
 * Result of a one-click attach attempt. The UI uses these to render an
 * inline status line under each button.
 */
sealed class McpAttachResult {
    abstract val target: McpAttachTarget

    /** The CLI accepted the registration. `detail` is up to ~160 chars of output. */
    data class Success(override val target: McpAttachTarget, val detail: String) : McpAttachResult()

    /** Binary missing or command failed — fallback text was dropped on the clipboard. */
    data class CopiedToClipboard(
        override val target: McpAttachTarget,
        val reason: String
    ) : McpAttachResult()
}

/**
 * Best-effort registration of this BossTerm MCP endpoint with a third-party
 * AI CLI. See [McpAttachTarget] kdoc for the contract and tested-versions
 * matrix.
 *
 * Idempotent: the `remove` subcommand runs first (errors ignored) so
 * repeated clicks don't fail with "already exists".
 */
object McpCliAttacher {

    private val log = LoggerFactory.getLogger(McpCliAttacher::class.java)

    /** Per-process timeout for any single shell-out. */
    private const val PROCESS_TIMEOUT_SECONDS = 15L

    /**
     * Attempt to register the BossTerm MCP server with [target].
     *
     * @param serverName MCP identifier the third-party CLI will use. Pass
     *   `BossTermMcpConfig.serverName` so embedders' brand is honored.
     * @param port loopback port the server is bound to.
     */
    suspend fun attach(target: McpAttachTarget, serverName: String, port: Int): McpAttachResult =
        withContext(Dispatchers.IO) {
            val url = "http://127.0.0.1:$port/mcp"

            // First, best-effort remove. Many CLIs' `mcp add` is not
            // idempotent — this turns "already exists" errors into a
            // silent no-op so a re-click of the button just works.
            target.resolvedRemoveCommand(serverName)?.let { removeCmd ->
                runProcess(removeCmd, allowFailure = true)
            }

            val cmd = target.resolvedAddCommand(serverName, url)
            log.info("Attaching {} via: {}", target.displayName, cmd.joinToString(" "))
            try {
                val result = runProcess(cmd, allowFailure = false)
                if (result.exitCode == 0) {
                    log.info("Attach succeeded for {}", target.displayName)
                    McpAttachResult.Success(target, result.output.trim().take(160))
                } else {
                    val reason = if (result.timedOut) {
                        "timed out after ${PROCESS_TIMEOUT_SECONDS}s"
                    } else {
                        "exit ${result.exitCode}"
                    }
                    log.warn("Attach for {} failed ({}); copying fallback to clipboard", target.displayName, reason)
                    copyToClipboard(target.resolvedClipboard(serverName, url))
                    McpAttachResult.CopiedToClipboard(target, "$reason — config copied to clipboard")
                }
            } catch (e: IOException) {
                // Binary not on PATH is the most common cause.
                log.warn("Could not spawn {} ({}); copying fallback to clipboard", target.displayName, e.message)
                copyToClipboard(target.resolvedClipboard(serverName, url))
                McpAttachResult.CopiedToClipboard(target, "CLI not found — config copied to clipboard")
            } catch (e: Exception) {
                log.warn("Unexpected error attaching {}: {}", target.displayName, e.message)
                copyToClipboard(target.resolvedClipboard(serverName, url))
                McpAttachResult.CopiedToClipboard(target, "${e::class.simpleName} — config copied to clipboard")
            }
        }

    private data class ProcessOutcome(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Run [cmd] with a hard 15s timeout. Closes the child's stdin immediately
     * to defuse interactive prompts, and waits for the process before reading
     * stdout — so a hung CLI gets killed by destroyForcibly instead of
     * stalling our read forever.
     *
     * If [allowFailure], non-zero exit and timeouts are returned as-is
     * (caller decides what to do). Otherwise the caller treats anything
     * non-zero as failure.
     */
    private fun runProcess(cmd: List<String>, allowFailure: Boolean): ProcessOutcome {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        // Signal EOF to any CLI that might be waiting on stdin.
        try {
            process.outputStream.close()
        } catch (_: Throwable) {
            // ignore
        }
        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            // Drain whatever the child already wrote, but cap so we don't
            // sit on a giant buffer.
            val partial = try {
                process.inputStream.bufferedReader().readText().take(160)
            } catch (_: Throwable) {
                ""
            }
            return ProcessOutcome(exitCode = -1, output = partial, timedOut = true)
        }
        // Process is done; read is bounded.
        val output = try {
            process.inputStream.bufferedReader().readText()
        } catch (_: Throwable) {
            ""
        }
        return ProcessOutcome(exitCode = process.exitValue(), output = output, timedOut = false)
    }

    private fun copyToClipboard(text: String) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        } catch (e: Throwable) {
            log.warn("Failed to write to system clipboard: {}", e.message)
        }
    }
}
