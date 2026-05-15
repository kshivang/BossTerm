package ai.rever.bossterm.compose.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The four AI CLIs we expose one-click attach buttons for in the MCP
 * settings panel. Each target has both a primary shell command we'll
 * attempt to run and a human-readable fallback string we drop on the
 * clipboard if the binary isn't installed or the command fails.
 *
 * URLs are templated with the placeholder `{URL}` so the resolved
 * endpoint (`http://127.0.0.1:<port>/mcp`) gets substituted at call time.
 */
enum class McpAttachTarget(
    val displayName: String,
    /** ProcessBuilder command list. `{URL}` gets replaced. */
    private val command: List<String>,
    /** Help text + ready-to-paste config when the shell-out fails. */
    private val clipboardFallback: String
) {
    CLAUDE_CODE(
        displayName = "Claude Code",
        command = listOf("claude", "mcp", "add", "--transport", "sse", "bossterm", "{URL}"),
        clipboardFallback = "claude mcp add --transport sse bossterm {URL}"
    ),
    CODEX(
        displayName = "Codex",
        command = listOf("codex", "mcp", "add", "bossterm", "--transport", "sse", "{URL}"),
        clipboardFallback = """
            # Add to ~/.codex/config.toml
            [mcp_servers.bossterm]
            type = "sse"
            url = "{URL}"
        """.trimIndent()
    ),
    GEMINI(
        displayName = "Gemini CLI",
        command = listOf("gemini", "mcp", "add", "bossterm", "--transport", "sse", "{URL}"),
        clipboardFallback = """
            // Add to ~/.gemini/settings.json under mcpServers
            "bossterm": {
              "httpUrl": "{URL}"
            }
        """.trimIndent()
    ),
    OPENCODE(
        displayName = "OpenCode",
        command = listOf("opencode", "mcp", "add", "bossterm", "--transport", "sse", "{URL}"),
        clipboardFallback = """
            // Add to ~/.config/opencode/opencode.json under mcp
            "bossterm": {
              "type": "remote",
              "url": "{URL}"
            }
        """.trimIndent()
    );

    fun resolvedCommand(url: String): List<String> = command.map { it.replace("{URL}", url) }
    fun resolvedClipboard(url: String): String = clipboardFallback.replace("{URL}", url)
}

/**
 * Result of a one-click attach attempt. The UI uses these to render an
 * inline status line under each button.
 */
sealed class McpAttachResult {
    abstract val target: McpAttachTarget

    /** The CLI accepted the registration. `detail` is up to ~120 chars of output. */
    data class Success(override val target: McpAttachTarget, val detail: String) : McpAttachResult()

    /** Binary missing or command failed — fallback text was dropped on the clipboard. */
    data class CopiedToClipboard(
        override val target: McpAttachTarget,
        val reason: String
    ) : McpAttachResult()
}

/**
 * Best-effort registration of this BossTerm MCP endpoint with a third-party
 * AI CLI. Tries the CLI's native `mcp add` subcommand; on failure (binary
 * not on PATH, command not supported, non-zero exit) the resolved config
 * snippet is written to the system clipboard so the user can paste it
 * manually.
 *
 * Pure I/O — invoke from a coroutine on [Dispatchers.IO].
 */
object McpCliAttacher {

    private val log = LoggerFactory.getLogger(McpCliAttacher::class.java)

    suspend fun attach(target: McpAttachTarget, port: Int): McpAttachResult =
        withContext(Dispatchers.IO) {
            val url = "http://127.0.0.1:$port/mcp"
            val cmd = target.resolvedCommand(url)
            log.info("Attaching {} via: {}", target.displayName, cmd.joinToString(" "))
            try {
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(15, TimeUnit.SECONDS)
                if (finished && process.exitValue() == 0) {
                    log.info("Attach succeeded for {}: {}", target.displayName, output.trim())
                    McpAttachResult.Success(target, output.trim().take(160))
                } else {
                    if (!finished) process.destroyForcibly()
                    val reason = if (!finished) "timed out after 15s" else "exit ${process.exitValue()}"
                    log.warn("Attach for {} failed ({}); copying fallback to clipboard", target.displayName, reason)
                    copyToClipboard(target.resolvedClipboard(url))
                    McpAttachResult.CopiedToClipboard(target, "$reason — config copied to clipboard")
                }
            } catch (e: IOException) {
                // Binary not on PATH is the most common cause.
                log.warn("Could not spawn {} ({}); copying fallback to clipboard", target.displayName, e.message)
                copyToClipboard(target.resolvedClipboard(url))
                McpAttachResult.CopiedToClipboard(target, "CLI not found — config copied to clipboard")
            } catch (e: Exception) {
                log.warn("Unexpected error attaching {}: {}", target.displayName, e.message)
                copyToClipboard(target.resolvedClipboard(url))
                McpAttachResult.CopiedToClipboard(target, "${e::class.simpleName} — config copied to clipboard")
            }
        }

    private fun copyToClipboard(text: String) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        } catch (e: Throwable) {
            log.warn("Failed to write to system clipboard: {}", e.message)
        }
    }
}
