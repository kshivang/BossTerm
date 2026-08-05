package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.ai.AIAssistantIds
import ai.rever.bossterm.compose.ai.AIAssistants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Top-level key holding server declarations in Kimi Code CLI's `mcp.json`. */
private const val KIMI_CONTAINER_KEY = "mcpServers"

/** Top-level key holding server declarations in OpenCode's `opencode.json`. */
private const val OPENCODE_CONTAINER_KEY = "mcp"

// OpenCode's attach used to be a pair of `node -e` one-liners that edited opencode.json, justified
// by "node is guaranteed wherever opencode is, because OpenCode ships as an npm package". That
// stopped being true on this branch: OpenCode's primary installCommand is now its
// `curl … | bash` installer, and that's what onboarding runs by default — so a user can easily have
// `opencode` without `node`. McpConfigFileEditor does the same merge on the JVM with no external
// dependency, so both scripts are gone.

/**
 * One-click attach helper that registers this BossTerm MCP endpoint with a
 * third-party AI CLI via the CLI's native `mcp` subcommand. Falls back to
 * dropping a ready-to-paste config snippet on the system clipboard if the
 * CLI binary is missing, the command fails, or the call times out.
 *
 * The `{NAME}` placeholder in command/clipboard templates is filled with
 * the embedder's `BossTermMcpConfig.serverName` (or `"bossterm"` for the
 * default app), and `{URL}` with the target's [McpAttachTarget.registrationUrl]
 * — plain `http://127.0.0.1:<port>` for most CLIs, the `${VAR:-port}`
 * env-expanded form for Claude Code, and the streamable HTTP `/mcp` URL for Codex.
 *
 * Tested CLI shapes:
 *  - Claude Code: `claude mcp add --transport sse <name> <url>` (verified May 2026)
 *  - Codex 0.130: `codex mcp add <name> --url <url>/mcp` (streamable HTTP)
 *  - Gemini 0.28: `gemini mcp add <name> --transport sse <url>` (verified May 2026)
 *  - OpenCode 1.1: `opencode mcp add` is interactive in this version, no
 *      flags accepted — we edit `opencode.json` via a node one-liner instead.
 *  - Grok Build 0.2.3: `grok mcp add <name> --url <url> --type sse` (verified
 *      July 2026 by round-tripping add/remove under a sandboxed `GROK_HOME`)
 *  - Hermes Agent 0.13.0: `hermes mcp add <name> --url <url>` (verified July
 *      2026 from `hermes mcp add --help`)
 *  - Kimi Code CLI: no scriptable `mcp add` at all — MCP is configured
 *      conversationally via `/mcp-config`, so we write `~/.kimi-code/mcp.json`
 *      in-process. NOT via a node one-liner like OpenCode: Kimi ships as a
 *      single self-contained binary and explicitly does not require Node.
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
     *
     * Declaration ORDER is not persisted anywhere, so it's safe to reorder;
     * UI surfaces render [ossFirst] rather than `entries` anyway.
     */
    val persistenceKey: String,
    /**
     * The [ai.rever.bossterm.compose.ai.AIAssistants] id this target attaches to. Links the two
     * registries so openness ordering and install/detect state come from one place —
     * `AttachTargetCoverageTest` asserts the mapping stays total in both directions.
     */
    val assistantId: String,
    /** Command run first to remove any existing entry. Errors ignored. `{NAME}` is replaced. */
    private val removeCommand: List<String>?,
    /**
     * Primary command. `{URL}` and `{NAME}` get replaced at call time. `null`
     * marks the CLI as having no non-interactive `mcp add` form — the attach
     * helper then tries [attachInProcess], and failing that falls back to the
     * clipboard. (Currently Kimi Code, which only exposes a TUI prompt.)
     */
    private val addCommand: List<String>?,
    /** Help text + ready-to-paste config when the shell-out fails or is skipped. */
    private val clipboardFallback: String,
    /**
     * Per-target ceiling for a single shell-out. Most `mcp add` implementations just rewrite a
     * config file and return in milliseconds; Hermes is a Python CLI that performs live tool
     * discovery against the URL on add, so it gets a longer budget than the shared default.
     */
    val timeoutSeconds: Long = DEFAULT_PROCESS_TIMEOUT_SECONDS
) {
    CLAUDE_CODE(
        displayName = "Claude Code",
        persistenceKey = "CLAUDE_CODE",
        assistantId = AIAssistantIds.CLAUDE_CODE,
        // `--scope user` is critical: without it, `claude mcp add` writes
        // to the LOCAL (project-cwd) scope, so the entry only works when
        // Claude Code is launched from the BossTerm directory. With user
        // scope the entry lives in ~/.claude.json's userScope block and
        // applies in every project. `claude mcp remove` without --scope
        // finds the entry regardless of where it lives, so this also
        // cleans up legacy local-scope entries from before this fix.
        removeCommand = listOf("claude", "mcp", "remove", "{NAME}"),
        addCommand = listOf(
            "claude", "mcp", "add",
            "--scope", "user",
            "--transport", "sse",
            "{NAME}", "{URL}"
        ),
        // {URL} is the ${VAR:-port} form (see registrationUrl) — single-quoted
        // so a shell the user pastes this into doesn't expand the variable
        // right there and freeze the URL to a concrete port.
        clipboardFallback = "claude mcp add --scope user --transport sse {NAME} '{URL}'"
    ) {
        // Claude Code expands ${VAR:-default} in registered URLs from the
        // env of each `claude` process (verified against Claude Code 2.x,
        // user scope). Our PTYs export the per-embedder port var, so a
        // session inside one of our terminals dials THIS instance's actual
        // bound port; sessions outside any of our terminals use the `:-`
        // fallback. That fallback is the CONFIGURED port, not [port]: when
        // the server fallback-walked off its configured port (something else
        // held it at launch), [port] is a transient collision artifact, and
        // baking it into the CLI's persistent config points the registration
        // at whichever app owns that port after this instance quits — e.g. a
        // stale `bossterm` fallback of 7677 dialing BossConsole's `boss`
        // server and mirroring its entire toolset under a second name. The
        // other CLIs have no documented expansion — they stay on plain URLs
        // with the live bound port.
        override fun registrationUrl(serverName: String, port: Int): String {
            val fallback = McpTerminalRegistry.configuredMcpPort ?: port
            return "http://127.0.0.1:\${${McpTerminalRegistry.portEnvVarName(serverName)}:-$fallback}"
        }
    },
    CODEX(
        displayName = "Codex",
        persistenceKey = "CODEX",
        assistantId = AIAssistantIds.CODEX,
        // codex-cli 0.130 uses `--url <url>` and only supports streamable
        // HTTP (no SSE client), so it gets BossTerm's /mcp endpoint rather
        // than the SSE root used by Claude/Gemini/OpenCode. An older note
        // here claimed streamable HTTP "needs an SDK upgrade" — wrong: MCP
        // SDK 0.8.3 (the pinned version) already ships the server transport,
        // exercised end-to-end by StreamableMcpSessionsTest. Don't re-add it.
        removeCommand = listOf("codex", "mcp", "remove", "{NAME}"),
        addCommand = listOf("codex", "mcp", "add", "{NAME}", "--url", "{URL}"),
        clipboardFallback = """
            # Append to ~/.codex/config.toml
            [mcp_servers.{NAME}]
            url = "{URL}"
        """.trimIndent()
    ) {
        override fun registrationUrl(serverName: String, port: Int): String =
            "http://127.0.0.1:$port/mcp"
    },
    GEMINI(
        displayName = "Gemini CLI",
        persistenceKey = "GEMINI",
        assistantId = AIAssistantIds.GEMINI_CLI,
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
        assistantId = AIAssistantIds.OPENCODE,
        // opencode 1.x's `mcp add` is an interactive TUI prompt with no scriptable form, so the
        // config is merged in-process (see attachInProcess below and the note at the top of this
        // file about why this is no longer a `node -e` shim).
        removeCommand = null,
        addCommand = null,
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
    ) {
        override fun attachInProcess(name: String, url: String, home: File): Boolean =
            McpConfigFileEditor.putServer(
                file = CliConfigPaths.opencodeConfigJson(home),
                containerKey = OPENCODE_CONTAINER_KEY,
                serverName = name,
                entry = McpConfigFileEditor.remoteEntry(url, type = "remote", enabled = true)
            )

        override fun detachInProcess(name: String, home: File): Boolean =
            McpConfigFileEditor.removeServer(
                CliConfigPaths.opencodeConfigJson(home),
                OPENCODE_CONTAINER_KEY,
                name
            )
    },
    GROK(
        displayName = "Grok Build",
        persistenceKey = "GROK",
        assistantId = AIAssistantIds.GROK_BUILD,
        // Verified against grok 0.2.3 by round-tripping add/remove with a sandboxed
        // GROK_HOME: `add` writes [mcp_servers.<name>] with url/type/enabled into
        // config.toml, `remove` deletes the section. Note that --type is NOT validated
        // at add time (a bogus value is accepted and only fails on connect), so `sse`
        // here has to be right; `grok mcp doctor` diagnoses a mismatch.
        removeCommand = listOf("grok", "mcp", "remove", "{NAME}"),
        addCommand = listOf("grok", "mcp", "add", "{NAME}", "--url", "{URL}", "--type", "sse"),
        clipboardFallback = """
            # Append to ~/.grok/config.toml
            [mcp_servers.{NAME}]
            url = "{URL}"
            type = "sse"
            enabled = true
        """.trimIndent()
    ),
    HERMES(
        displayName = "Hermes Agent",
        persistenceKey = "HERMES",
        assistantId = AIAssistantIds.HERMES,
        // Verified against Hermes Agent 0.13.0 (`hermes mcp add --help`):
        //   hermes mcp add [--url URL] [--command CMD] [--args ...] [--auth ...] name
        // `add` performs live tool discovery against the URL, which is why this target
        // raises the process timeout — a cold Python start plus a discovery round-trip
        // does not reliably fit in the 15s the other CLIs need.
        removeCommand = listOf("hermes", "mcp", "remove", "{NAME}"),
        addCommand = listOf("hermes", "mcp", "add", "{NAME}", "--url", "{URL}"),
        clipboardFallback = """
            # Merge into ~/.hermes/config.yaml
            mcp_servers:
              {NAME}:
                url: "{URL}"
                enabled: true
        """.trimIndent(),
        timeoutSeconds = 45L
    ),
    OPENCLAW(
        displayName = "OpenClaw",
        persistenceKey = "OPENCLAW",
        assistantId = AIAssistantIds.OPENCLAW,
        // Per docs.openclaw.ai/tools/mcp: `openclaw mcp add <name> --url <url> --transport
        // streamable-http|sse`, stored under the nested `mcp.servers.<name>` key of
        // ~/.openclaw/openclaw.json.
        //
        // NOT verified against a binary: the install on this machine (2026.3.13) has no `mcp`
        // subcommand at all — npm latest is 2026.7.1-2, so the docs are simply ahead of it. On such
        // an older build the add exits non-zero and the clipboard fallback fires with the exact
        // config snippet, which is the right degradation. Beware that `openclaw <unknown> --help`
        // prints top-level help and exits 0, so an exit code from a --help probe proves nothing
        // about whether a subcommand exists.
        removeCommand = listOf("openclaw", "mcp", "remove", "{NAME}"),
        addCommand = listOf(
            "openclaw", "mcp", "add", "{NAME}",
            "--url", "{URL}",
            "--transport", "sse"
        ),
        clipboardFallback = """
            // Merge into ~/.openclaw/openclaw.json
            {
              "mcp": {
                "servers": {
                  "{NAME}": {
                    "url": "{URL}",
                    "transport": "sse",
                    "enabled": true
                  }
                }
              }
            }
        """.trimIndent()
    ),
    KIMI_CODE(
        displayName = "Kimi Code CLI",
        persistenceKey = "KIMI_CODE",
        assistantId = AIAssistantIds.KIMI_CODE,
        // Kimi Code has no `mcp` subcommand at all (confirmed against kimi 0.30.0) — MCP servers are
        // added conversationally through the `/mcp-config` TUI. Both directions are handled
        // in-process by McpConfigFileEditor; these command slots stay null.
        //
        // The written shape is verified against the binary's own zod schema, not inferred:
        //   McpJsonFileSchema      = object({ mcpServers: record(string(), McpServerConfigSchema) })
        //   McpServerSseConfigSchema = object({ transport: literal("sse"), url: string().url(), … })
        // and the preprocessor defaults a bare `url` to transport "http" — so `transport: "sse"` has
        // to be written explicitly for the SSE endpoint we register. Note `kimi doctor` validates
        // only config.toml and tui.toml, so it can't be used to check this file.
        removeCommand = null,
        addCommand = null,
        clipboardFallback = """
            // Merge into ~/.kimi-code/mcp.json
            {
              "mcpServers": {
                "{NAME}": {
                  "url": "{URL}",
                  "transport": "sse"
                }
              }
            }
        """.trimIndent()
    ) {
        override fun attachInProcess(name: String, url: String, home: File): Boolean =
            McpConfigFileEditor.putServer(
                file = CliConfigPaths.kimiMcpJson(home),
                containerKey = KIMI_CONTAINER_KEY,
                serverName = name,
                entry = McpConfigFileEditor.remoteEntry(url, transport = "sse")
            )

        override fun detachInProcess(name: String, home: File): Boolean =
            McpConfigFileEditor.removeServer(
                CliConfigPaths.kimiMcpJson(home),
                KIMI_CONTAINER_KEY,
                name
            )
    };

    /**
     * The URL string registered with this CLI for a server named [serverName]
     * bound to [port]. Plain `http://127.0.0.1:<port>` by default; CLAUDE_CODE
     * overrides with the `${VAR:-port}` env-expanded form and CODEX appends
     * the streamable HTTP path.
     */
    open fun registrationUrl(serverName: String, port: Int): String =
        "http://127.0.0.1:$port"

    /**
     * Register in-process instead of shelling out, for CLIs whose only `mcp add` is interactive.
     *
     * Returns true when the CLI's config now carries the registration, false when the write failed
     * (malformed existing config, unwritable path) so the caller can fall back to the clipboard.
     * `null` — the default — means this target has no in-process path and should use
     * [resolvedAddCommand].
     */
    open fun attachInProcess(name: String, url: String, home: File): Boolean? = null

    /** The [attachInProcess] counterpart. Idempotent: true when the entry is absent afterwards. */
    open fun detachInProcess(name: String, home: File): Boolean? = null

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

        /** Look up the target that attaches to [assistantId]. Null when that CLI has none. */
        fun forAssistant(assistantId: String): McpAttachTarget? =
            entries.firstOrNull { it.assistantId == assistantId }

        /**
         * Attach targets ordered open-source-first, matching the AI menu and settings list: fully
         * open stacks, then open clients on vendor models, then proprietary. Ordering comes from the
         * tool registry via [assistantId], so it can't drift from what the rest of the UI shows.
         *
         * All UI surfaces should render this rather than `entries`.
         */
        val ossFirst: List<McpAttachTarget> by lazy {
            // Computed once, not per read. Every attach menu, the status tooltip and the remote
            // menu read this during composition, and the comparator used to call
            // AIAssistants.findById per comparison — which rebuilds `BUILTIN + _customAssistants`
            // and linear-scans it. That was ~24 list allocations per recomposition for an ordering
            // that only depends on the built-in registry, which never changes at runtime.
            //
            // Safe to cache for exactly that reason: openness comes from BUILTIN, and a custom
            // assistant can't introduce an attach target.
            val tierById = AIAssistants.BUILTIN.associate { it.id to it.openness.ordinal }
            entries.sortedWith(
                compareBy(
                    { tierById[it.assistantId] ?: Int.MAX_VALUE },
                    { it.displayName }
                )
            )
        }

        /** Shared ceiling for a single `mcp add`/`mcp remove` shell-out. */
        const val DEFAULT_PROCESS_TIMEOUT_SECONDS = 15L
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

    /** Home directory the in-process config editors write under. */
    private val home: File
        get() = File(System.getProperty("user.home").orEmpty())

    init {
        // Force-load the classes attach() needs on the stretch between
        // runProcess() returning and the next suspension point. runProcess
        // blocks uninterruptibly in waitFor(), so even a cancelled coroutine
        // executes that stretch before it can observe cancellation — and when
        // an embedding host has unloaded this classloader in the meantime
        // (BOSS plugin hot-swap/update), any lazy class load there dies with
        // NoClassDefFoundError. Touching them at object init pins them for
        // the classloader's lifetime.
        //
        // NOT dead code: evaluating each `::class.java` literal is the intended
        // side effect (it forces the JVM to load the class). Removing this
        // "unused" list silently reintroduces the hot-swap crash.
        listOf(
            McpAttachResult.Success::class.java,
            McpAttachResult.CopiedToClipboard::class.java,
            ProcessOutcome::class.java,
        )
    }

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
            // Per-target form: Claude Code gets the ${VAR:-port} env-expanded
            // variant, Codex gets /mcp, and SSE clients keep the root URL.
            val url = target.registrationUrl(serverName, port)
            try {
                // First, best-effort remove. Many CLIs' `mcp add` is not
                // idempotent — this turns "already exists" errors into a
                // silent no-op so a re-click of the button just works.
                // Wrapped in its own try so a missing binary on remove
                // doesn't short-circuit the whole attempt (although the
                // outer catch would still reach a CopiedToClipboard).
                target.resolvedRemoveCommand(serverName)?.let { removeCmd ->
                    try {
                        runProcess(removeCmd, target.timeoutSeconds)
                    } catch (_: Throwable) {
                        // ignore: idempotency convenience only
                    }
                }
                // In-process targets get the same courtesy: clear any stale entry
                // before writing, so a changed port can't leave two registrations.
                target.detachInProcess(serverName, home)

                // CLIs with no scriptable `mcp add` can still have an in-process
                // path (Kimi Code: we own the JSON merge). Try that before the
                // clipboard, since it's a real one-click attach for the user.
                target.attachInProcess(serverName, url, home)?.let { wrote ->
                    if (wrote) {
                        log.info("Attach succeeded for {} (config written in-process)", target.displayName)
                        return@withContext McpAttachResult.Success(target, "config written")
                    }
                    log.warn(
                        "In-process config write for {} failed; {}",
                        target.displayName,
                        if (quiet) "quiet mode - clipboard untouched" else "copying fallback to clipboard"
                    )
                    if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                    val suffix = if (quiet) "" else " - config copied to clipboard"
                    return@withContext McpAttachResult.CopiedToClipboard(
                        target,
                        "could not write config$suffix"
                    )
                }

                val cmd = target.resolvedAddCommand(serverName, url)
                if (cmd == null) {
                    // No scriptable `mcp add` and no in-process path — drop the
                    // config snippet on the clipboard so the user can paste it.
                    log.info(
                        "{} has no scriptable `mcp add` - {}",
                        target.displayName,
                        if (quiet) "skipping (quiet mode)" else "copying fallback to clipboard"
                    )
                    if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                    val suffix = if (quiet) "" else " - config copied to clipboard"
                    return@withContext McpAttachResult.CopiedToClipboard(
                        target,
                        "manual setup required (no scriptable `mcp add`)$suffix"
                    )
                }
                log.info("Attaching {} via: {}", target.displayName, cmd.joinToString(" "))
                val result = runProcess(cmd, target.timeoutSeconds)
                if (result.exitCode == 0) {
                    log.info("Attach succeeded for {}", target.displayName)
                    McpAttachResult.Success(target, result.output.trim().take(160))
                } else {
                    val baseReason = if (result.timedOut) {
                        "timed out after ${target.timeoutSeconds}s"
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
                        if (quiet) "quiet mode - clipboard untouched" else "copying fallback to clipboard")
                    if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                    val suffix = if (quiet) "" else " - config copied to clipboard"
                    McpAttachResult.CopiedToClipboard(target, "$combinedReason$suffix")
                }
            } catch (e: CancellationException) {
                // Coroutine cancellation must propagate untouched — never
                // swallow it into a CopiedToClipboard result.
                throw e
            } catch (e: IOException) {
                // Binary not on PATH is the most common cause.
                log.warn("Could not spawn {} ({}); {}", target.displayName, e.message,
                    if (quiet) "quiet mode - clipboard untouched" else "copying fallback to clipboard")
                if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                val suffix = if (quiet) "" else " - config copied to clipboard"
                McpAttachResult.CopiedToClipboard(target, "CLI not found$suffix")
            } catch (e: Exception) {
                log.warn("Unexpected error attaching {}: {}", target.displayName, e.message)
                if (!quiet) copyToClipboard(target.resolvedClipboard(serverName, url))
                val suffix = if (quiet) "" else " - config copied to clipboard"
                McpAttachResult.CopiedToClipboard(target, "${e::class.simpleName}$suffix")
            }
        }

    private data class ProcessOutcome(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Run [cmd] with a hard [timeoutSeconds] timeout (the target's
     * [McpAttachTarget.timeoutSeconds]). Closes the child's stdin immediately
     * to defuse interactive prompts, and waits for the process before reading
     * stdout — so a hung CLI gets killed by destroyForcibly instead of
     * stalling our read forever. The caller decides how to interpret
     * non-zero exits / timeouts.
     *
     * Coroutine-cancellation-aware: if the dispatcher interrupts the thread
     * mid-wait (e.g. the user closed the window), the child process is
     * destroyForcibly'd before the InterruptedException is rethrown, so we
     * don't leave a `claude mcp add` zombie running after dispose.
     *
     * The command head is resolved to an absolute path via [CliBinaryResolver]
     * — a packaged app launched from Finder/the Dock inherits the bare launchd
     * `PATH`, where none of these CLIs are findable and every attach would
     * otherwise die with "CLI not found".
     */
    private fun runProcess(cmd: List<String>, timeoutSeconds: Long): ProcessOutcome {
        val resolved = listOf(CliBinaryResolver.resolve(cmd.first())) + cmd.drop(1)
        val process = ProcessBuilder(resolved).redirectErrorStream(true).start()
        try {
            // Signal EOF to any CLI that might be waiting on stdin.
            try {
                process.outputStream.close()
            } catch (_: Throwable) {
                // ignore
            }
            // We waitFor() before reading stdout. This is safe because `mcp add`
            // output is at most a few lines of status text — far below the OS
            // pipe buffer (~64 KB on Linux, ~8 KB on small Windows shells). If a
            // future CLI ever spams more than that without exiting, it would
            // block on its write and we'd hit the timeout instead of a clean
            // read. If that becomes a problem, drain inputStream on a worker
            // thread alongside the waitFor.
            val finished = try {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
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

    // CSI-form ANSI escapes: ESC '[' params* final. Covers SGR / cursor
    // sequences a CLI typically emits in `mcp add` output. Does not cover
    // OSC / DCS / single-char escapes — those don't appear in CLI status text.
    private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[;\\d]*[a-zA-Z]")

    private fun copyToClipboard(text: String) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        } catch (e: Throwable) {
            log.warn("Failed to write to system clipboard: {}", e.message)
        }
    }
}
