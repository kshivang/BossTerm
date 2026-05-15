package ai.rever.bossterm.compose.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.util.concurrent.TimeUnit

// Node one-liners that edit ~/.config/opencode/opencode.json directly —
// OpenCode's own `mcp add` is an interactive TUI prompt with no scripted
// form. Each script takes argv positional[s]: NAME (and URL for the add
// variant). Node is guaranteed to be on PATH wherever `opencode` is
// installed (OpenCode ships as an npm package), so the dependency is
// essentially free.
//
// Kept as top-level file-private constants because Kotlin enum entries
// can't reference their own companion-object members at construction
// time — the companion initializes after the entries.
private const val OPENCODE_ADD_SCRIPT = "" +
    "const fs=require('fs'),os=require('os'),p=require('path');" +
    "const f=os.homedir()+'/.config/opencode/opencode.json';" +
    "fs.mkdirSync(p.dirname(f),{recursive:true});" +
    "const c=fs.existsSync(f)&&fs.statSync(f).size>0?JSON.parse(fs.readFileSync(f,'utf8')):{};" +
    "c.mcp=c.mcp||{};" +
    "c.mcp[process.argv[1]]={type:'remote',url:process.argv[2],enabled:true};" +
    "fs.writeFileSync(f,JSON.stringify(c,null,2));" +
    "console.log('Wrote '+f);"

private const val OPENCODE_REMOVE_SCRIPT = "" +
    "const fs=require('fs'),os=require('os');" +
    "const f=os.homedir()+'/.config/opencode/opencode.json';" +
    "if(!fs.existsSync(f))process.exit(0);" +
    "const c=JSON.parse(fs.readFileSync(f,'utf8')||'{}');" +
    "if(!c.mcp||!c.mcp[process.argv[1]])process.exit(0);" +
    "delete c.mcp[process.argv[1]];" +
    "fs.writeFileSync(f,JSON.stringify(c,null,2));"

/**
 * One-click attach helper that registers this BossTerm MCP endpoint with a
 * third-party AI CLI via the CLI's native `mcp` subcommand. Falls back to
 * dropping a ready-to-paste config snippet on the system clipboard if the
 * CLI binary is missing, the command fails, or the call times out.
 *
 * The `{NAME}` placeholder in command/clipboard templates is filled with
 * the embedder's `BossTermMcpConfig.serverName` (or `"bossterm"` for the
 * default app), and `{URL}` with the resolved
 * `http://127.0.0.1:<port>` endpoint.
 *
 * Tested CLI shapes (last verified May 2026):
 *  - Claude Code: `claude mcp add --transport sse <name> <url>` (verified)
 *  - Codex 0.130: `codex mcp add <name> --url <url>` (registers OK, but
 *      Codex only speaks streamable HTTP — runtime connection to BossTerm's
 *      current SSE-only SDK will fail until the SDK is upgraded)
 *  - Gemini 0.28: `gemini mcp add <name> --transport sse <url>` (verified)
 *  - OpenCode 1.1: `opencode mcp add` is interactive in this version, no
 *      flags accepted — will exit non-zero, clipboard fallback fires.
 *
 * If any best-effort command turns out to have a different subcommand
 * in your installed version, the clipboard fallback path handles it
 * gracefully — visual symptom is the amber "exit N — config copied to
 * clipboard" status.
 */
enum class McpAttachTarget(
    val displayName: String,
    /**
     * Stable string written into settings.json's `mcpAttachedTo` set.
     * Keep these values frozen across enum renames — old persisted state
     * depends on them. New targets must pick a fresh, non-colliding key.
     */
    val persistenceKey: String,
    /** Command run first to remove any existing entry. Errors ignored. `{NAME}` is replaced. */
    private val removeCommand: List<String>?,
    /**
     * Primary command. `{URL}` and `{NAME}` get replaced at call time. `null`
     * marks the CLI as having no non-interactive `mcp add` form — the attach
     * helper skips the shell-out entirely and goes straight to the clipboard
     * fallback. (Currently OpenCode, which only exposes a TUI prompt.)
     */
    private val addCommand: List<String>?,
    /** Help text + ready-to-paste config when the shell-out fails or is skipped. */
    private val clipboardFallback: String
) {
    CLAUDE_CODE(
        displayName = "Claude Code",
        persistenceKey = "CLAUDE_CODE",
        removeCommand = listOf("claude", "mcp", "remove", "{NAME}"),
        addCommand = listOf("claude", "mcp", "add", "--transport", "sse", "{NAME}", "{URL}"),
        clipboardFallback = "claude mcp add --transport sse {NAME} {URL}"
    ),
    CODEX(
        displayName = "Codex",
        persistenceKey = "CODEX",
        // codex-cli 0.130 uses `--url <url>` and only supports streamable
        // HTTP (no SSE client). The registration will succeed but at
        // runtime Codex won't actually connect to BossTerm's SDK-0.8.3
        // SSE endpoint — that needs an SDK upgrade to expose streamable
        // HTTP transport. Tracked as a follow-up.
        removeCommand = listOf("codex", "mcp", "remove", "{NAME}"),
        addCommand = listOf("codex", "mcp", "add", "{NAME}", "--url", "{URL}"),
        clipboardFallback = """
            # Append to ~/.codex/config.toml
            # Note: codex only speaks streamable HTTP; BossTerm currently
            # serves SSE so the runtime connection will fail until the SDK
            # is upgraded.
            [mcp_servers.{NAME}]
            url = "{URL}"
        """.trimIndent()
    ),
    GEMINI(
        displayName = "Gemini CLI",
        persistenceKey = "GEMINI",
        // Verified against `gemini mcp add --help` (gemini-cli 0.28.x):
        //   Usage: gemini mcp add [options] <name> <commandOrUrl> [args...]
        // The previous "{NAME} --transport sse {URL}" order had Gemini
        // parse "--transport" as the commandOrUrl positional → exit 52.
        // `--scope user` writes to the user-global config file rather
        // than the project-local one, matching the behavior of the
        // other CLIs' `mcp add` defaults.
        removeCommand = listOf("gemini", "mcp", "remove", "{NAME}"),
        addCommand = listOf(
            "gemini", "mcp", "add",
            "{NAME}", "{URL}",
            "--transport", "sse",
            "--scope", "user"
        ),
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
        persistenceKey = "OPENCODE",
        // opencode 1.x's `mcp add` is an interactive TUI prompt with no
        // scriptable form. Workaround: edit `~/.config/opencode/opencode.json`
        // directly via a tiny node one-liner. node is guaranteed to be on
        // PATH whenever `opencode` is installed (opencode ships as an npm
        // package), so the dependency is essentially free.
        removeCommand = listOf(
            "node", "-e", OPENCODE_REMOVE_SCRIPT,
            "{NAME}"
        ),
        addCommand = listOf(
            "node", "-e", OPENCODE_ADD_SCRIPT,
            "{NAME}", "{URL}"
        ),
        clipboardFallback = """
            // Merge into ~/.config/opencode/opencode.json
            {
              "mcp": {
                "{NAME}": {
                  "type": "remote",
                  "url": "{URL}",
                  "enabled": true
                }
              }
            }
        """.trimIndent()
    );

    fun resolvedAddCommand(name: String, url: String): List<String>? =
        addCommand?.map { it.replace("{NAME}", name).replace("{URL}", url) }

    fun resolvedRemoveCommand(name: String): List<String>? =
        removeCommand?.map { it.replace("{NAME}", name) }

    fun resolvedClipboard(name: String, url: String): String =
        clipboardFallback.replace("{NAME}", name).replace("{URL}", url)

    companion object {
        /** Look up a target by its stable [persistenceKey]. Null if unknown. */
        fun fromPersistenceKey(key: String): McpAttachTarget? =
            entries.firstOrNull { it.persistenceKey == key }
    }
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
     * @param quiet when true, failures don't write the clipboard fallback
     *   (used by the manager's auto-reattach on startup so a missing CLI
     *   doesn't pollute the user's clipboard at launch).
     */
    suspend fun attach(
        target: McpAttachTarget,
        serverName: String,
        port: Int,
        quiet: Boolean = false
    ): McpAttachResult =
        withContext(Dispatchers.IO) {
            // SDK 0.8.3 mounts SSE+POST at the application root; the path
            // overload is broken (see BossTermMcpManager kdoc). So the URL
            // we register with each CLI is loopback + port, no path suffix.
            val url = "http://127.0.0.1:$port"
            try {
                // First, best-effort remove. Many CLIs' `mcp add` is not
                // idempotent — this turns "already exists" errors into a
                // silent no-op so a re-click of the button just works.
                // Wrapped in its own try so a missing binary on remove
                // doesn't short-circuit the whole attempt (although the
                // outer catch would still reach a CopiedToClipboard).
                target.resolvedRemoveCommand(serverName)?.let { removeCmd ->
                    try {
                        runProcess(removeCmd)
                    } catch (_: Throwable) {
                        // ignore: idempotency convenience only
                    }
                }

                val cmd = target.resolvedAddCommand(serverName, url)
                if (cmd == null) {
                    // CLI has no non-interactive `mcp add` (e.g. OpenCode TUI
                    // prompt). Skip the shell-out and drop the config snippet
                    // on the clipboard so the user can paste it manually.
                    log.info(
                        "{} has no scriptable `mcp add` — {}",
                        target.displayName,
                        if (quiet) "skipping (quiet mode)" else "copying fallback to clipboard"
                    )
                    if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                    val suffix = if (quiet) "" else " — config copied to clipboard"
                    return@withContext McpAttachResult.CopiedToClipboard(
                        target,
                        "manual setup required (no scriptable `mcp add`)$suffix"
                    )
                }
                log.info("Attaching {} via: {}", target.displayName, cmd.joinToString(" "))
                val result = runProcess(cmd)
                if (result.exitCode == 0) {
                    log.info("Attach succeeded for {}", target.displayName)
                    McpAttachResult.Success(target, result.output.trim().take(160))
                } else {
                    val baseReason = if (result.timedOut) {
                        "timed out after ${PROCESS_TIMEOUT_SECONDS}s"
                    } else {
                        "exit ${result.exitCode}"
                    }
                    // Surface the CLI's own last error line so the user can
                    // tell e.g. "MCP is disabled by your administrator" apart
                    // from generic "exit 1" failures.
                    val cliMessage = result.output.lastErrorLine()
                    val combinedReason = if (cliMessage.isNullOrEmpty()) {
                        baseReason
                    } else {
                        "$baseReason: $cliMessage"
                    }
                    log.warn("Attach for {} failed ({}); {}", target.displayName, combinedReason,
                        if (quiet) "quiet mode — clipboard untouched" else "copying fallback to clipboard")
                    if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                    val suffix = if (quiet) "" else " — config copied to clipboard"
                    McpAttachResult.CopiedToClipboard(target, "$combinedReason$suffix")
                }
            } catch (e: CancellationException) {
                // Coroutine cancellation must propagate untouched — never
                // swallow it into a CopiedToClipboard result.
                throw e
            } catch (e: IOException) {
                // Binary not on PATH is the most common cause.
                log.warn("Could not spawn {} ({}); {}", target.displayName, e.message,
                    if (quiet) "quiet mode — clipboard untouched" else "copying fallback to clipboard")
                if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                val suffix = if (quiet) "" else " — config copied to clipboard"
                McpAttachResult.CopiedToClipboard(target, "CLI not found$suffix")
            } catch (e: Exception) {
                log.warn("Unexpected error attaching {}: {}", target.displayName, e.message)
                if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                val suffix = if (quiet) "" else " — config copied to clipboard"
                McpAttachResult.CopiedToClipboard(target, "${e::class.simpleName}$suffix")
            }
        }

    private data class ProcessOutcome(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Run [cmd] with a hard 15s timeout. Closes the child's stdin immediately
     * to defuse interactive prompts, and waits for the process before reading
     * stdout — so a hung CLI gets killed by destroyForcibly instead of
     * stalling our read forever. The caller decides how to interpret
     * non-zero exits / timeouts.
     *
     * Coroutine-cancellation-aware: if the dispatcher interrupts the thread
     * mid-wait (e.g. the user closed the window), the child process is
     * destroyForcibly'd before the InterruptedException is rethrown, so we
     * don't leave a `claude mcp add` zombie running after dispose.
     */
    private fun runProcess(cmd: List<String>): ProcessOutcome {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        try {
            // Signal EOF to any CLI that might be waiting on stdin.
            try {
                process.outputStream.close()
            } catch (_: Throwable) {
                // ignore
            }
            val finished = try {
                process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                // Coroutine cancelled while we waited; rethrow as cancellation
                // so callers don't mistake it for a CLI failure.
                Thread.currentThread().interrupt()
                throw CancellationException("Attach process interrupted").also { it.initCause(e) }
            }
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
        } finally {
            // Defense in depth: never leak a child process if anything above
            // throws (cancellation, I/O failure, etc).
            if (process.isAlive) {
                try {
                    process.destroyForcibly()
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
    }

    /**
     * Pull the last non-blank line from a CLI's combined stdout+stderr so we
     * can include it in the toast / log when a shell-out fails. Trims to a
     * reasonable length and strips ANSI sequences that some CLIs leak.
     */
    private fun String.lastErrorLine(): String? {
        if (isBlank()) return null
        val stripped = replace(ANSI_ESCAPE_REGEX, "")
        return stripped.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.take(160)
    }

    private val ANSI_ESCAPE_REGEX = Regex("\\[[;\\d]*[a-zA-Z]")

    private fun copyToClipboard(text: String) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        } catch (e: Throwable) {
            log.warn("Failed to write to system clipboard: {}", e.message)
        }
    }
}
