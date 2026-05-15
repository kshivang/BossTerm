package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.debug.ChunkSource
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.tabs.TerminalTab
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the in-process MCP server that exposes BossTerm tabs/terminals to
 * external Model Context Protocol clients.
 *
 * This class is transport-agnostic — it only wires tool definitions and
 * handlers onto a [Server] instance. Binding the server to a transport
 * (e.g. Ktor + SSE) is the caller's responsibility.
 *
 * The server aggregates tabs from every registered [TabbedTerminalState] via
 * [McpTerminalRegistry], so a single MCP endpoint can expose all open
 * Windows. Tab ids are UUIDs (globally unique), which lets tools resolve a
 * tab back to its owning state without needing a window id.
 *
 * Tool surface (all tools take a `tab_id` argument unless otherwise noted):
 *   - `list_tabs`           — enumerate all open tabs across all windows.
 *   - `get_active_tab`      — return the active tab of the primary window.
 *   - `read_scrollback`     — read the last N lines from a tab's buffer.
 *   - `search_output`       — regex-search a tab's buffer.
 *   - `get_last_command`    — return the most recently completed OSC 133 command.
 *   - `read_debug_console`  — read recent entries from a tab's debug-data buffer.
 *
 * Write tools (gated by `BossTermMcpConfig.allowWriteTools`):
 *   - `send_input`          — write raw text to a tab's stdin (queued).
 *   - `send_signal`         — send ctrl_c / ctrl_d / ctrl_z to a tab (queued).
 *   - `run_in_panel`        — open a new tab / split pane and run a script in it.
 *
 * Threading: tool handlers run on whatever coroutine context the MCP
 * transport dispatches them on. All operations performed here are either
 * thread-safe (buffer snapshots, [TabbedTerminalState] input queue) or
 * read-only accesses to [TerminalTab] fields that are themselves
 * thread-safe (`MutableState`, `MutableStateFlow`).
 */
class BossTermMcpServer(
    private val registry: McpTerminalRegistry = McpTerminalRegistry,
    private val config: BossTermMcpConfig = BossTermMcpConfig(),
    private val settingsManager: SettingsManager = SettingsManager.instance
) {

    private val log = org.slf4j.LoggerFactory.getLogger(BossTermMcpServer::class.java)

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Omit null-valued fields from the wire format. Saves 7–12 bytes per
        // null per record. The one place we intentionally emit a literal JSON
        // `null` is get_active_tab's "no active tab" case, which is hand-built
        // (`if (info == null) "null" else …`), not via a serializer — so this
        // flip doesn't affect it.
        explicitNulls = false
    }

    /** Returns the built-in tool name with the embedder's configured prefix applied. */
    private fun toolName(builtin: String): String = config.toolNamePrefix + builtin

    /**
     * Returns the description an embedder has supplied for [builtin] via
     * [BossTermMcpConfig.customToolDescriptions], or [default] when none is set.
     * Lookup uses the unprefixed built-in name so embedders write
     * `customToolDescriptions = mapOf("list_tabs" to "…")` regardless of any
     * configured tool-name prefix.
     */
    private fun describe(builtin: String, default: String): String =
        config.customToolDescriptions[builtin] ?: default

    // Name-indexed registration functions. Defined here (not inline in createServer) so
    // manage_tools / applyDisabledSet can re-register a previously-disabled tool against
    // the live Server without rebuilding the whole instance. Names mirror
    // BUILT_IN_READ_TOOLS / BUILT_IN_WRITE_TOOLS so the settings UI can render toggles
    // without duplicating the list.
    private val readToolRegistrations: Map<String, (Server) -> Unit> = mapOf(
        "list_tabs" to ::registerListTabs,
        "get_active_tab" to ::registerGetActiveTab,
        "read_scrollback" to ::registerReadScrollback,
        "search_output" to ::registerSearchOutput,
        "get_last_command" to ::registerGetLastCommand,
        "read_debug_console" to ::registerReadDebugConsole
    )
    private val writeToolRegistrations: Map<String, (Server) -> Unit> = mapOf(
        "send_input" to ::registerSendInput,
        "send_signal" to ::registerSendSignal,
        "run_in_panel" to ::registerRunInPanel
    )

    /** Reserved tools that callers cannot disable. */
    private val undisablableTools: Set<String> = UNDISABLABLE_TOOLS

    // Hold the live Server so applyDisabledSet can mutate it.
    private var serverRef: Server? = null

    // Serializes concurrent applyDisabledSet callers. Two paths invoke it: the
    // manage_tools MCP handler (no external lock) and the BossTermMcpManager
    // settings watcher (holds the manager's mutex, but that mutex doesn't cover
    // the MCP-handler path). The MCP SDK's Server.tools is a plain MutableMap
    // without documented thread-safety, so a containsKey → addTool/removeTool
    // sequence on two threads could otherwise double-register or corrupt state.
    private val toolsLock = Any()

    /**
     * Names of every built-in tool the current config could expose (write tools are
     * excluded when [BossTermMcpConfig.allowWriteTools] is false). Stable order:
     * reads first, then writes.
     */
    fun availableToolNames(): List<String> =
        readToolRegistrations.keys.toList() +
            if (config.allowWriteTools) writeToolRegistrations.keys.toList() else emptyList()

    /**
     * Build and return a fully configured [Server] with all BossTerm tools
     * registered. Caller is responsible for connecting a transport.
     */
    fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = config.serverName,
                version = config.serverVersion
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )
        serverRef = server

        val disabled = settingsManager.settings.value.disabledMcpTools
        for ((name, register) in readToolRegistrations) {
            if (name !in disabled) register(server)
        }
        if (config.allowWriteTools) {
            for ((name, register) in writeToolRegistrations) {
                if (name !in disabled) register(server)
            }
        }
        // manage_tools is always registered — without it there's no way to
        // re-enable other tools from MCP after they've been disabled.
        registerManageTools(server)

        // Embedder hook: register app-specific tools after built-ins. Names
        // are NOT prefixed — embedder owns them.
        config.additionalTools(server)

        return server
    }

    /**
     * Sync the live server's exposed tool set to match [disabled]. Adds back any
     * available tool not in the set; removes any tool that is. No-op if no server
     * has been built yet.
     *
     * Safe to call from any thread; the body runs under [toolsLock] so concurrent
     * callers (the manage_tools MCP handler and the settings watcher in
     * BossTermMcpManager) are serialized. Without this, the containsKey →
     * addTool/removeTool sequence could double-register a tool against the SDK's
     * non-thread-safe Server.tools map.
     */
    /**
     * Signal that the live [Server] this wrapper was bound to has been torn down
     * (engine stop or app shutdown). Subsequent [applyDisabledSet] calls become
     * no-ops. The wrapper itself is not reusable after this — a new instance is
     * created for each engine start in [BossTermMcpManager].
     */
    fun detachServer() {
        synchronized(toolsLock) {
            serverRef = null
        }
    }

    fun applyDisabledSet(disabled: Set<String>) {
        synchronized(toolsLock) {
            // Read the ref inside the lock so a concurrent detachServer() can't
            // null it between the check and the mutations.
            val server = serverRef ?: return
            for (name in availableToolNames()) {
                val prefixed = toolName(name)
                val present = server.tools.containsKey(prefixed)
                val shouldBeExposed = name !in disabled
                if (shouldBeExposed && !present) {
                    (readToolRegistrations[name] ?: writeToolRegistrations[name])?.invoke(server)
                } else if (!shouldBeExposed && present) {
                    server.removeTool(prefixed)
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Tool: list_tabs
    // -----------------------------------------------------------------

    private fun registerListTabs(server: Server) {
        server.addTool(
            name = toolName("list_tabs"),
            description = describe(
                "list_tabs",
                "List all open terminal tabs across all windows. Each tab includes " +
                        "id, title, working directory, pid, and isActive (true if the tab is the " +
                        "currently selected tab of its window)."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("include_fields") {
                        put("type", "array")
                        put("description", "Optional allow-list over TabInfo fields: " +
                                "id, title, cwd, pid, isActive. Omit to get every field. " +
                                "Useful when you only need a subset (e.g. `[\"id\",\"isActive\"]`).")
                    }
                },
                required = emptyList()
            )
        ) { request ->
            val args = request.arguments
            val includeFields = args.optionalStringSet("include_fields")
            val infos = registry.collectTabInfos()
            val primaryActiveId = registry.primaryState()?.activeTabId
            val text = if (includeFields == null) {
                val payload = ListTabsResult(tabs = infos, activeTabId = primaryActiveId)
                json.encodeToString(ListTabsResult.serializer(), payload)
            } else {
                buildJsonObject {
                    put("tabs", buildJsonArray {
                        for (info in infos) add(tabInfoToJson(info, includeFields))
                    })
                    if (primaryActiveId != null) put("activeTabId", primaryActiveId)
                }.toString()
            }
            successJson(text)
        }
    }

    /** Projects a [TabInfo] onto the given field allow-list. Empty set = no fields. */
    private fun tabInfoToJson(info: TabInfo, fields: Set<String>): JsonObject = buildJsonObject {
        if ("id" in fields) put("id", info.id)
        if ("title" in fields) put("title", info.title)
        if ("cwd" in fields && info.cwd != null) put("cwd", info.cwd)
        if ("pid" in fields && info.pid != null) put("pid", info.pid)
        if ("isActive" in fields) put("isActive", info.isActive)
    }

    // -----------------------------------------------------------------
    // Tool: get_active_tab
    // -----------------------------------------------------------------

    private fun registerGetActiveTab(server: Server) {
        server.addTool(
            name = toolName("get_active_tab"),
            description = describe(
                "get_active_tab",
                "Return the active tab of the primary window (the first window opened " +
                        "that is still alive), or null if no tab is active."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("include_fields") {
                        put("type", "array")
                        put("description", "Optional allow-list over TabInfo fields: " +
                                "id, title, cwd, pid, isActive. Omit to get every field.")
                    }
                },
                required = emptyList()
            )
        ) { request ->
            val args = request.arguments
            val includeFields = args.optionalStringSet("include_fields")
            val primary = registry.primaryState()
            val activeId = primary?.activeTabId
            val info = primary?.activeTab?.toTabInfo(activeId)
            // The literal JSON `null` is valid output — clients calling
            // JSON.parse get a real null. Cheaper than wrapping in `{tab: null}`.
            val text = when {
                info == null -> "null"
                includeFields == null -> json.encodeToString(TabInfo.serializer(), info)
                else -> tabInfoToJson(info, includeFields).toString()
            }
            successJson(text)
        }
    }

    // -----------------------------------------------------------------
    // Tool: read_scrollback
    // -----------------------------------------------------------------

    private fun registerReadScrollback(server: Server) {
        server.addTool(
            name = toolName("read_scrollback"),
            description = describe(
                "read_scrollback",
                "Read the last N lines from a tab/pane's terminal buffer " +
                        "(history + visible screen) as plain UTF-8 text. Trailing whitespace per " +
                        "line is stripped. When `pane_id` is supplied, reads the specific split " +
                        "pane (returned by run_in_panel); otherwise reads the focused pane."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("tab_id") {
                        put("type", "string")
                        put("description", "Stable tab id (see list_tabs).")
                    }
                    putJsonObject("lines") {
                        put("type", "integer")
                        put("description", "Maximum number of lines to return from the end. Default 200.")
                        put("minimum", 1)
                    }
                    putJsonObject("pane_id") {
                        put("type", "string")
                        put("description", "Optional specific pane within the tab " +
                                "(returned by run_in_panel). Omit to read the focused pane.")
                    }
                },
                required = listOf("tab_id")
            )
        ) { request ->
            val args = request.arguments
            val tabId = args.requireString("tab_id")
                ?: return@addTool errorResult("Missing required argument: tab_id")
            val requested = args.optionalInt("lines") ?: DEFAULT_SCROLLBACK_LINES
            if (requested < 1) {
                return@addTool errorResult("'lines' must be >= 1 (got $requested)")
            }
            val paneId = args.requireString("pane_id")
            val state = registry.findState(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")
            val session = state.findSession(tabId, paneId)
                ?: return@addTool errorResult(
                    if (paneId != null) "Unknown pane_id '$paneId' in tab '$tabId'"
                    else "No session for tab_id: $tabId"
                )

            val snapshot = session.textBuffer.createSnapshot()
            val totalAvailable = snapshot.historyLinesCount + snapshot.height
            val take = minOf(requested, totalAvailable)

            // Iterate the most recent `take` rows. Buffer row indices run from
            // `-historyLinesCount` (oldest) through `height - 1` (bottom of screen).
            val endExclusive = snapshot.height
            val startInclusive = endExclusive - take
            val lines = ArrayList<String>(take)
            var row = startInclusive
            while (row < endExclusive) {
                val text = snapshot.getLine(row).text
                lines.add(text.trimEnd())
                row++
            }

            val payload = ReadScrollbackResult(lines = lines, totalAvailable = totalAvailable)
            val full = json.encodeToString(ReadScrollbackResult.serializer(), payload)
            // Progressive fallbacks. Most callers want recent context, so the
            // first fallback keeps the tail; the final form gives the agent
            // enough to refine the next call (smaller `lines`).
            successJson(shorten(
                full,
                {
                    val tail = lines.takeLast(20)
                    buildJsonObject {
                        put("lines", buildJsonArray { for (l in tail) add(JsonPrimitive(l)) })
                        put("totalAvailable", totalAvailable)
                        put("shortened", "tail: last ${tail.size} of ${lines.size} requested lines")
                    }.toString()
                },
                {
                    buildJsonObject {
                        put("totalAvailable", totalAvailable)
                        put("shortened", "totals only; retry with a smaller `lines` value")
                    }.toString()
                }
            ))
        }
    }

    // -----------------------------------------------------------------
    // Tool: search_output
    // -----------------------------------------------------------------

    private fun registerSearchOutput(server: Server) {
        server.addTool(
            name = toolName("search_output"),
            description = describe(
                "search_output",
                "Regex-search the entire scrollback (history + screen) of a tab/pane. " +
                        "Returns matching rows with positional info. Row numbers follow buffer " +
                        "convention: negative for history (oldest = -historyLinesCount), 0..height-1 " +
                        "for screen. The response also includes historyLinesCount and height so " +
                        "clients can convert to 1-based line numbers if desired. When `pane_id` is " +
                        "supplied, searches that specific split pane; otherwise the focused pane."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("tab_id") {
                        put("type", "string")
                        put("description", "Stable tab id (see list_tabs).")
                    }
                    putJsonObject("pattern") {
                        put("type", "string")
                        put("description", "Regex pattern. Kotlin/Java regex syntax.")
                    }
                    putJsonObject("max_matches") {
                        put("type", "integer")
                        put("description", "Maximum matches to return before truncating. Default 50.")
                        put("minimum", 1)
                    }
                    putJsonObject("ignore_case") {
                        put("type", "boolean")
                        put("description", "If true, perform case-insensitive matching. Default false.")
                    }
                    putJsonObject("pane_id") {
                        put("type", "string")
                        put("description", "Optional specific pane within the tab " +
                                "(returned by run_in_panel). Omit to search the focused pane.")
                    }
                    putJsonObject("include_line_text") {
                        put("type", "boolean")
                        put("description", "If false, each match returns only row+matchStart+matchEnd " +
                                "(no line text). Cuts response size by 60–80% on typical scrollback " +
                                "searches. Default true.")
                    }
                },
                required = listOf("tab_id", "pattern")
            )
        ) { request ->
            val args = request.arguments
            val tabId = args.requireString("tab_id")
                ?: return@addTool errorResult("Missing required argument: tab_id")
            val pattern = args.requireString("pattern")
                ?: return@addTool errorResult("Missing required argument: pattern")
            val maxMatches = args.optionalInt("max_matches") ?: DEFAULT_SEARCH_MAX_MATCHES
            if (maxMatches < 1) {
                return@addTool errorResult("'max_matches' must be >= 1 (got $maxMatches)")
            }
            val ignoreCase = args.optionalBoolean("ignore_case") ?: false
            val includeLineText = args.optionalBoolean("include_line_text") ?: true
            val paneId = args.requireString("pane_id")
            val state = registry.findState(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")
            val session = state.findSession(tabId, paneId)
                ?: return@addTool errorResult(
                    if (paneId != null) "Unknown pane_id '$paneId' in tab '$tabId'"
                    else "No session for tab_id: $tabId"
                )

            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = try {
                Regex(pattern, options)
            } catch (e: Exception) {
                return@addTool errorResult("Invalid regex: ${e.message ?: e::class.simpleName}")
            }

            // Use the lock-free incremental snapshot for the full-scrollback scan
            // — matches the codebase's stated 94%-lock-reduction pattern (CLAUDE.md),
            // and search_output can touch the entire history so the savings matter.
            // read_scrollback above stays on createSnapshot() because it caps at
            // the most recent N lines and the lock window is trivially short.
            val snapshot = session.textBuffer.createIncrementalSnapshot()
            val matches = ArrayList<SearchMatch>()
            var truncated = false

            val firstRow = -snapshot.historyLinesCount
            val lastRowExclusive = snapshot.height
            var row = firstRow
            outer@ while (row < lastRowExclusive) {
                val lineText = snapshot.getLine(row).text
                for (m in regex.findAll(lineText)) {
                    if (matches.size >= maxMatches) {
                        truncated = true
                        break@outer
                    }
                    matches.add(
                        SearchMatch(
                            row = row,
                            line = lineText.trimEnd(),
                            matchStart = m.range.first,
                            matchEnd = m.range.last + 1
                        )
                    )
                }
                row++
            }

            // Build the "positions only" form once; it's the response when
            // `include_line_text=false`, and the first shortening fallback
            // when the full payload exceeds the cap.
            val positionsOnlyJson: () -> String = {
                val positions = buildJsonArray {
                    for (m in matches) add(
                        buildJsonObject {
                            put("row", m.row)
                            put("matchStart", m.matchStart)
                            put("matchEnd", m.matchEnd)
                        }
                    )
                }
                buildJsonObject {
                    put("matches", positions)
                    put("truncated", truncated)
                    put("historyLinesCount", snapshot.historyLinesCount)
                    put("height", snapshot.height)
                    if (!includeLineText) put("includeLineText", false)
                    else put("shortened", "matches: positions only (no line text)")
                }.toString()
            }
            val rowCountsJson: () -> String = {
                val counts = buildJsonObject {
                    val perRow = matches.groupingBy { it.row }.eachCount()
                    for ((r, n) in perRow) put(r.toString(), n)
                }
                buildJsonObject {
                    put("rowCounts", counts)
                    put("totalMatches", matches.size)
                    put("truncated", truncated)
                    put("historyLinesCount", snapshot.historyLinesCount)
                    put("height", snapshot.height)
                    put("shortened", "rowCounts: hit counts per row")
                }.toString()
            }
            val totalsJson: () -> String = {
                buildJsonObject {
                    put("totalMatches", matches.size)
                    put("truncated", truncated)
                    put("historyLinesCount", snapshot.historyLinesCount)
                    put("height", snapshot.height)
                    put("shortened", "totals only")
                }.toString()
            }

            val full: String = if (includeLineText) {
                val payload = SearchOutputResult(
                    matches = matches,
                    truncated = truncated,
                    historyLinesCount = snapshot.historyLinesCount,
                    height = snapshot.height
                )
                json.encodeToString(SearchOutputResult.serializer(), payload)
            } else {
                // Caller pre-opted-out of line text; skip the redundant
                // "drop line text" fallback in the ladder.
                positionsOnlyJson()
            }

            successJson(
                if (includeLineText) shorten(full, positionsOnlyJson, rowCountsJson, totalsJson)
                else shorten(full, rowCountsJson, totalsJson)
            )
        }
    }

    // -----------------------------------------------------------------
    // Tool: get_last_command
    // -----------------------------------------------------------------

    private fun registerGetLastCommand(server: Server) {
        server.addTool(
            name = toolName("get_last_command"),
            description = describe(
                "get_last_command",
                "Return the most recently completed shell command for a tab " +
                        "(as captured via OSC 133), or null if no command has finished yet. " +
                        "Requires shell integration — see shell-integration.md. " +
                        "Note: `commandText` is currently always null; only `exitCode`, `startedAtMs`, " +
                        "`finishedAtMs`, `durationMs`, and `cwd` are populated. Capturing the typed " +
                        "command text reliably is a follow-up."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("tab_id") {
                        put("type", "string")
                        put("description", "Stable tab id (see list_tabs).")
                    }
                },
                required = listOf("tab_id")
            )
        ) { request ->
            val args = request.arguments
            val tabId = args.requireString("tab_id")
                ?: return@addTool errorResult("Missing required argument: tab_id")
            val tab = registry.findTab(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")

            val last = tab.lastCommand.value
            // Literal JSON `null` when no command has completed yet (same pattern
            // as get_active_tab).
            val text = if (last == null) {
                "null"
            } else {
                val dto = LastCommandDto(
                    commandText = last.commandText,
                    exitCode = last.exitCode,
                    startedAtMs = last.startedAtMs,
                    finishedAtMs = last.finishedAtMs,
                    durationMs = last.finishedAtMs - last.startedAtMs,
                    cwd = last.cwd
                )
                json.encodeToString(LastCommandDto.serializer(), dto)
            }
            successJson(text)
        }
    }

    // -----------------------------------------------------------------
    // Tool: send_input
    // -----------------------------------------------------------------

    private fun registerSendInput(server: Server) {
        server.addTool(
            name = toolName("send_input"),
            description = describe(
                "send_input",
                "Write text to a tab's shell stdin. The caller is responsible for " +
                        "appending a trailing '\\n' if they want a command to actually execute. " +
                        "When `pane_id` is supplied (e.g. the value returned by run_in_panel), " +
                        "writes go to that specific split; otherwise to the tab's primary session."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("tab_id") {
                        put("type", "string")
                        put("description", "Stable tab id (see list_tabs).")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Raw text to write. Include '\\n' to submit.")
                    }
                    putJsonObject("pane_id") {
                        put("type", "string")
                        put("description", "Optional specific pane within the tab " +
                                "(returned by run_in_panel). Omit to target the tab's primary session.")
                    }
                },
                required = listOf("tab_id", "text")
            )
        ) { request ->
            val args = request.arguments
            val tabId = args.requireString("tab_id")
                ?: return@addTool errorResult("Missing required argument: tab_id")
            val text = args.requireString("text")
                ?: return@addTool errorResult("Missing required argument: text")
            val paneId = args.requireString("pane_id")

            val state = registry.findState(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")
            val session = state.findSession(tabId, paneId)
                ?: return@addTool errorResult(
                    if (paneId != null) "Unknown pane_id '$paneId' in tab '$tabId'"
                    else "No session for tab_id: $tabId"
                )
            session.writeUserInput(text)
            successJson(json.encodeToString(OkResult.serializer(), OkResult(ok = true)))
        }
    }

    // -----------------------------------------------------------------
    // Tool: send_signal
    // -----------------------------------------------------------------

    private fun registerSendSignal(server: Server) {
        server.addTool(
            name = toolName("send_signal"),
            description = describe(
                "send_signal",
                "Send a control signal to a tab's shell. Allowed signals: " +
                        "'ctrl_c' (interrupt), 'ctrl_d' (EOF), 'ctrl_z' (suspend). When `pane_id` " +
                        "is supplied, the signal targets that specific split; otherwise the tab's " +
                        "primary session."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("tab_id") {
                        put("type", "string")
                        put("description", "Stable tab id (see list_tabs).")
                    }
                    putJsonObject("signal") {
                        put("type", "string")
                        put("description", "One of: ctrl_c, ctrl_d, ctrl_z.")
                    }
                    putJsonObject("pane_id") {
                        put("type", "string")
                        put("description", "Optional specific pane within the tab " +
                                "(returned by run_in_panel).")
                    }
                },
                required = listOf("tab_id", "signal")
            )
        ) { request ->
            val args = request.arguments
            val tabId = args.requireString("tab_id")
                ?: return@addTool errorResult("Missing required argument: tab_id")
            val signal = args.requireString("signal")
                ?: return@addTool errorResult("Missing required argument: signal")
            val paneId = args.requireString("pane_id")

            val state = registry.findState(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")
            val session = state.findSession(tabId, paneId)
                ?: return@addTool errorResult(
                    if (paneId != null) "Unknown pane_id '$paneId' in tab '$tabId'"
                    else "No session for tab_id: $tabId"
                )

            val bytes = when (signal.lowercase()) {
                "ctrl_c" -> byteArrayOf(0x03)
                "ctrl_d" -> byteArrayOf(0x04)
                "ctrl_z" -> byteArrayOf(0x1A)
                else -> return@addTool errorResult(
                    "Unknown signal: '$signal'. Expected one of: ctrl_c, ctrl_d, ctrl_z."
                )
            }
            // TerminalSession interface doesn't surface writeRawBytes; cast
            // to the concrete TerminalTab implementation (the only one in
            // tree — both primary tabs and split-created sessions are
            // TerminalTab instances).
            val tab = session as? TerminalTab
                ?: return@addTool errorResult(
                    "Session does not support raw byte writes (signal delivery requires TerminalTab)"
                )
            tab.writeRawBytes(bytes)
            successJson(json.encodeToString(OkResult.serializer(), OkResult(ok = true)))
        }
    }

    // -----------------------------------------------------------------
    // Tool: run_in_panel
    // -----------------------------------------------------------------

    private fun registerRunInPanel(server: Server) {
        server.addTool(
            name = toolName("run_in_panel"),
            description = describe(
                "run_in_panel",
                "Open a new terminal panel and write a script to it. Modes: " +
                        "'new_tab' (fresh tab with initialCommand), 'horizontal_split' (split " +
                        "below focused pane), 'vertical_split' (split beside focused pane). " +
                        "Include '\\n' in the script to submit it as a command. " +
                        "All three modes wait for the shell's OSC 133;A prompt-ready signal " +
                        "(or the configured fallback delay) before sending the script, so the " +
                        "command runs cleanly rather than racing with shell startup."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("panel") {
                        put("type", "string")
                        put("description", "One of: new_tab, horizontal_split, vertical_split.")
                    }
                    putJsonObject("script") {
                        put("type", "string")
                        put("description", "Raw text to write to the new panel's shell. Include '\\n' to submit.")
                    }
                    putJsonObject("tab_id") {
                        put("type", "string")
                        put("description", "Optional source tab id. Required for splits; " +
                                "defaults to the primary window's active tab.")
                    }
                    putJsonObject("working_dir") {
                        put("type", "string")
                        put("description", "Optional working directory for the new panel. " +
                                "For splits, defaults to the inherited cwd via OSC 7.")
                    }
                    putJsonObject("split_ratio") {
                        put("type", "number")
                        put("description", "Optional split size (0.05..0.95) — fraction of " +
                                "the parent's dimension the NEW pane gets. Only meaningful " +
                                "for horizontal_split / vertical_split. Defaults to the " +
                                "user-configured `mcpDefaultSplitRatio` setting (typically 0.3).")
                    }
                },
                required = listOf("panel", "script")
            )
        ) { request ->
            val args = request.arguments
            val panel = args.requireString("panel")?.lowercase()
                ?: return@addTool errorResult("Missing required argument: panel")
            val script = args.requireString("script")
                ?: return@addTool errorResult("Missing required argument: script")
            val requestedTabId = args.requireString("tab_id")
            val workingDir = args.requireString("working_dir")

            // Resolve the target state. If tab_id given, find the state that
            // owns it. Otherwise use the primary registered window.
            val state: TabbedTerminalState = if (requestedTabId != null) {
                registry.findState(requestedTabId)
                    ?: return@addTool errorResult("Unknown tab_id: $requestedTabId")
            } else {
                registry.primaryState()
                    ?: return@addTool errorResult("No registered terminal window")
            }

            when (panel) {
                "new_tab" -> {
                    // Both createTab and the split path (createSessionForSplit) hold
                    // initialCommand until OSC 133;A and auto-append '\n', so strip
                    // any caller-provided trailing newline for a consistent contract.
                    val normalizedScript = script.removeSuffix("\n")
                    val newId = state.createTab(
                        workingDir = workingDir,
                        initialCommand = normalizedScript.ifEmpty { null }
                    ) ?: return@addTool errorResult("Failed to create tab")
                    val payload = RunInPanelResult(ok = true, tabId = newId, paneId = null)
                    successJson(json.encodeToString(RunInPanelResult.serializer(), payload))
                }
                "horizontal_split", "vertical_split" -> {
                    val targetTabId = requestedTabId
                        ?: state.activeTabId
                        ?: return@addTool errorResult("No active tab to split")
                    // Resolve effective ratio: per-call override > user setting > 0.3 fallback.
                    val configuredDefault = SettingsManager.instance.settings.value.mcpDefaultSplitRatio
                    val requestedRatio = args.optionalFloat("split_ratio")
                    val effectiveRatio = (requestedRatio ?: configuredDefault).coerceIn(0.05f, 0.95f)
                    // Normalize the trailing newline: createSessionForSplit's initialCommand
                    // path auto-appends '\n', matching the new_tab branch. An empty script
                    // means "just split, don't run anything".
                    val normalizedScript = script.removeSuffix("\n").ifEmpty { null }
                    val paneId = if (panel == "horizontal_split") {
                        state.splitHorizontal(targetTabId, ratio = effectiveRatio, initialCommand = normalizedScript)
                    } else {
                        state.splitVertical(targetTabId, ratio = effectiveRatio, initialCommand = normalizedScript)
                    } ?: return@addTool errorResult("Split failed (terminal too small?)")
                    val payload = RunInPanelResult(ok = true, tabId = targetTabId, paneId = paneId)
                    successJson(json.encodeToString(RunInPanelResult.serializer(), payload))
                }
                else -> errorResult(
                    "Unknown panel: '$panel'. Expected one of: new_tab, horizontal_split, vertical_split."
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // Tool: read_debug_console
    // -----------------------------------------------------------------

    private fun registerReadDebugConsole(server: Server) {
        server.addTool(
            name = toolName("read_debug_console"),
            description = describe(
                "read_debug_console",
                "Read recent entries from a tab's debug-data buffer (PTY output, " +
                        "user input, console-log entries). Per-tab circular buffer; cap is " +
                        "settings.debugMaxChunks (default 1000). Supports incremental polling via " +
                        "since_index and filtering via sources."
            ),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("tab_id") {
                        put("type", "string")
                        put("description", "Stable tab id (see list_tabs).")
                    }
                    putJsonObject("max_chunks") {
                        put("type", "integer")
                        put("description", "Maximum entries to return. Default 100, clamped to 1..1000.")
                        put("minimum", 1)
                    }
                    putJsonObject("since_index") {
                        put("type", "integer")
                        put("description", "Return only chunks with index > since_index. " +
                                "Use the previous response's stats.newestIndex for polling.")
                    }
                    putJsonObject("sources") {
                        put("type", "array")
                        put(
                            "description",
                            "Filter to a subset of sources: PTY_OUTPUT, USER_INPUT, " +
                                    "EMULATOR_GENERATED, CONSOLE_LOG. Unknown names are " +
                                    "silently dropped. Omit the key entirely to get every " +
                                    "source; an empty array (or one containing only unknown " +
                                    "names) returns no chunks."
                        )
                    }
                    putJsonObject("omit_data") {
                        put("type", "boolean")
                        put("description", "If true, each chunk returns only index+timestamp+source " +
                                "(no `data` payload). Use for cheap polling — `data` is the bulk of " +
                                "every chunk. Default false.")
                    }
                },
                required = listOf("tab_id")
            )
        ) { request ->
            val args = request.arguments
            val tabId = args.requireString("tab_id")
                ?: return@addTool errorResult("Missing required argument: tab_id")
            val tab = registry.findTab(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")
            val collector = tab.debugCollector
                ?: return@addTool errorResult("Debug collector not initialized for this tab")

            // Cap to the user's configured buffer size — bumping
            // `settings.debugMaxChunks` should actually let MCP read the
            // deeper history they opted into.
            val maxAllowed = SettingsManager.instance.settings.value
                .debugMaxChunks.coerceAtLeast(1)
            val maxChunks = (args.optionalInt("max_chunks") ?: DEFAULT_DEBUG_CHUNKS)
                .coerceIn(1, maxAllowed)
            val sinceIndex = args.optionalInt("since_index")?.coerceAtLeast(0)
            val omitData = args.optionalBoolean("omit_data") ?: false
            // null only when the caller omitted `sources` entirely (or it wasn't
            // an array). A supplied-but-empty filter is honored strictly: the
            // caller explicitly asked for nothing, so return nothing rather than
            // silently dropping the filter and surprising them with all chunks.
            val sourcesFilter: Set<ChunkSource>? = (args?.get("sources") as? JsonArray)
                ?.mapNotNull { item ->
                    val raw = item.jsonPrimitive.content
                    // Case-insensitive lookup — agents writing "pty_output"
                    // shouldn't silently see zero results.
                    ChunkSource.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
                ?.toSet()

            var seq = collector.getDebugChunks().asSequence()
            if (sinceIndex != null) seq = seq.filter { it.index > sinceIndex }
            if (sourcesFilter != null) seq = seq.filter { it.source in sourcesFilter }
            val resultChunks = seq.toList().takeLast(maxChunks).map { chunk ->
                DebugConsoleChunk(
                    index = chunk.index,
                    timestamp = chunk.timestamp,
                    source = chunk.source.name,
                    data = String(chunk.data)
                )
            }

            val rawStats = collector.getStats()
            val statsDto = DebugConsoleStats(
                totalChunks = rawStats.totalChunksRecorded,
                chunksStored = rawStats.chunksStored,
                oldestIndex = rawStats.earliestChunkIndex,
                newestIndex = rawStats.latestChunkIndex,
                debugEnabled = tab.debugEnabled.value
            )

            // Build the "metadata only" form once; it's the response when
            // `omit_data=true`, and the first shortening fallback when the
            // full payload exceeds the cap.
            val metadataOnlyJson: () -> String = {
                val metadataChunks = buildJsonArray {
                    for (c in resultChunks) add(
                        buildJsonObject {
                            put("index", c.index)
                            put("timestamp", c.timestamp)
                            put("source", c.source)
                        }
                    )
                }
                buildJsonObject {
                    put("chunks", metadataChunks)
                    put("stats", json.encodeToJsonElement(DebugConsoleStats.serializer(), statsDto))
                    if (omitData) put("omitData", true)
                    else put("shortened", "chunks: metadata only (data omitted)")
                }.toString()
            }
            val statsOnlyJson: () -> String = {
                buildJsonObject {
                    put("stats", json.encodeToJsonElement(DebugConsoleStats.serializer(), statsDto))
                    put("chunksReturned", resultChunks.size)
                    put("shortened", "stats only; retry with a smaller `max_chunks` or narrower `sources`")
                }.toString()
            }

            val full: String = if (omitData) {
                metadataOnlyJson()
            } else {
                val payload = ReadDebugConsoleResult(chunks = resultChunks, stats = statsDto)
                json.encodeToString(ReadDebugConsoleResult.serializer(), payload)
            }

            successJson(
                if (omitData) shorten(full, statsOnlyJson)
                else shorten(full, metadataOnlyJson, statsOnlyJson)
            )
        }
    }

    // -----------------------------------------------------------------
    // Tool: manage_tools
    // -----------------------------------------------------------------

    private fun registerManageTools(server: Server) {
        server.addTool(
            name = toolName("manage_tools"),
            description = "Manage which BossTerm MCP built-in tools are exposed to clients. " +
                    "Operation 'list' returns every available built-in with its current enabled " +
                    "state. 'enable' / 'disable' take a 'names' array of unprefixed built-in " +
                    "names (e.g. 'send_input'); changes apply live (the tool list updates without " +
                    "restarting the server) and persist to settings.json. 'manage_tools' itself " +
                    "cannot be disabled.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("operation") {
                        put("type", "string")
                        put("description", "One of: list, enable, disable.")
                    }
                    putJsonObject("names") {
                        put("type", "array")
                        put(
                            "description",
                            "Unprefixed built-in tool names. Required for 'enable' and 'disable'."
                        )
                    }
                },
                required = listOf("operation")
            )
        ) { request ->
            val args = request.arguments
            val operation = args.requireString("operation")?.lowercase()
                ?: return@addTool errorResult("Missing required argument: operation")

            when (operation) {
                "list" -> {
                    val currentDisabled = settingsManager.settings.value.disabledMcpTools
                    val items = availableToolNames().map { name ->
                        ManageToolItem(name = name, enabled = name !in currentDisabled)
                    }
                    successJson(
                        json.encodeToString(
                            ManageToolsListResult.serializer(),
                            ManageToolsListResult(tools = items)
                        )
                    )
                }
                "enable", "disable" -> {
                    val names = args.optionalStringList("names")
                        ?: return@addTool errorResult("Missing required argument: names")
                    if (names.isEmpty()) {
                        return@addTool errorResult("'names' must contain at least one tool name")
                    }
                    val available = availableToolNames().toSet()
                    val unknown = names.filter { it !in available }
                    if (unknown.isNotEmpty()) {
                        return@addTool errorResult(
                            "Unknown tool name(s): ${unknown.joinToString()}. " +
                                "Available: ${available.joinToString()}"
                        )
                    }
                    if (operation == "disable") {
                        val reserved = names.filter { it in undisablableTools }
                        if (reserved.isNotEmpty()) {
                            return@addTool errorResult(
                                "Cannot disable reserved tool(s): ${reserved.joinToString()}"
                            )
                        }
                    }
                    settingsManager.updateSetting {
                        val next = disabledMcpTools.toMutableSet()
                        if (operation == "enable") next.removeAll(names.toSet()) else next.addAll(names)
                        copy(disabledMcpTools = next)
                    }
                    applyDisabledSet(settingsManager.settings.value.disabledMcpTools)
                    successJson(json.encodeToString(OkResult.serializer(), OkResult(ok = true)))
                }
                else -> errorResult(
                    "Unknown operation '$operation'. Expected: list, enable, disable."
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun McpTerminalRegistry.collectTabInfos(): List<TabInfo> {
        return allTabs().map { tab ->
            val owning = findState(tab.id)
            tab.toTabInfo(owning?.activeTabId)
        }
    }

    private fun TerminalTab.toTabInfo(activeId: String?): TabInfo {
        return TabInfo(
            id = id,
            title = title.value,
            cwd = workingDirectory.value,
            pid = processHandle.value?.getPid(),
            isActive = id == activeId
        )
    }

    private fun successJson(text: String): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(text = text)),
            isError = false,
            structuredContent = null,
            meta = null
        )
    }

    private fun errorResult(message: String): CallToolResult {
        val body = json.encodeToString(ErrorResult.serializer(), ErrorResult(error = message))
        return CallToolResult(
            content = listOf(TextContent(text = body)),
            isError = true,
            structuredContent = null,
            meta = null
        )
    }

    /**
     * Progressive shortening for responses that can grow unboundedly.
     * Mirrors Serena's `_limit_length` pattern (tools_base.py:267-297).
     *
     * If [full] fits under the configured `mcpMaxAnswerChars`, it's returned.
     * Otherwise [fallbacks] are tried in order — each is a factory producing
     * a progressively smaller (but still well-formed JSON) summary; the first
     * one that fits wins. If nothing fits, returns a JSON error suggesting
     * the caller refine the query.
     *
     * Each fallback factory MUST return valid JSON the client can parse —
     * never a truncated prefix of [full]. The goal is "smaller well-formed
     * answer the agent can reason about", not "truncated blob".
     */
    private fun shorten(
        full: String,
        vararg fallbacks: () -> String
    ): String {
        val cap = settingsManager.settings.value.mcpMaxAnswerChars
        if (cap <= 0 || full.length <= cap) return full
        for ((i, factory) in fallbacks.withIndex()) {
            val short = factory()
            if (short.length <= cap) {
                log.debug(
                    "mcp shortening: cap={} full={} fallback#{}={} ({}% reduction)",
                    cap,
                    full.length,
                    i,
                    short.length,
                    if (full.isEmpty()) 0 else 100 - (short.length * 100 / full.length)
                )
                return short
            }
        }
        log.debug(
            "mcp shortening: cap={} full={} ran out of fallbacks; returning error result",
            cap,
            full.length
        )
        return json.encodeToString(
            ErrorResult.serializer(),
            ErrorResult(error = "Response too large (>$cap chars) and no fallback fit; refine the query.")
        )
    }

    // -----------------------------------------------------------------
    // Argument helpers (lenient on numeric/boolean string forms)
    // -----------------------------------------------------------------

    private fun JsonObject?.requireString(key: String): String? {
        val el: JsonElement = this?.get(key) ?: return null
        if (el is JsonNull) return null
        val prim = el.jsonPrimitive
        return prim.content
    }

    private fun JsonObject?.optionalInt(key: String): Int? {
        val el: JsonElement = this?.get(key) ?: return null
        if (el is JsonNull) return null
        val prim = el.jsonPrimitive
        return prim.intOrNull ?: prim.content.toIntOrNull()
    }

    private fun JsonObject?.optionalBoolean(key: String): Boolean? {
        val el: JsonElement = this?.get(key) ?: return null
        if (el is JsonNull) return null
        val prim = el.jsonPrimitive
        return prim.booleanOrNull ?: prim.content.toBooleanStrictOrNull()
    }

    private fun JsonObject?.optionalFloat(key: String): Float? {
        val el: JsonElement = this?.get(key) ?: return null
        if (el is JsonNull) return null
        val prim = el.jsonPrimitive
        return prim.floatOrNull ?: prim.content.toFloatOrNull()
    }

    private fun JsonObject?.optionalStringList(key: String): List<String>? {
        val el: JsonElement = this?.get(key) ?: return null
        if (el is JsonNull) return null
        val arr = el as? JsonArray ?: return null
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun JsonObject?.optionalStringSet(key: String): Set<String>? =
        optionalStringList(key)?.toSet()

    // -----------------------------------------------------------------
    // Wire-format DTOs
    // -----------------------------------------------------------------

    @Serializable
    data class TabInfo(
        val id: String,
        val title: String,
        val cwd: String?,
        val pid: Long?,
        val isActive: Boolean
    )

    @Serializable
    data class ListTabsResult(
        val tabs: List<TabInfo>,
        val activeTabId: String?
    )

    @Serializable
    data class ReadScrollbackResult(
        val lines: List<String>,
        val totalAvailable: Int
    )

    @Serializable
    data class SearchMatch(
        val row: Int,
        val line: String,
        val matchStart: Int,
        val matchEnd: Int
    )

    @Serializable
    data class SearchOutputResult(
        val matches: List<SearchMatch>,
        val truncated: Boolean,
        val historyLinesCount: Int,
        val height: Int
    )

    @Serializable
    data class LastCommandDto(
        val commandText: String?,
        val exitCode: Int,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val durationMs: Long,
        val cwd: String?
    )

    @Serializable
    data class OkResult(val ok: Boolean)

    @Serializable
    data class ErrorResult(val error: String)

    @Serializable
    data class RunInPanelResult(
        val ok: Boolean,
        val tabId: String,
        /** Non-null only for split modes — the new pane's session id. */
        val paneId: String?
    )

    @Serializable
    data class DebugConsoleChunk(
        val index: Int,
        val timestamp: Long,
        val source: String,
        val data: String
    )

    @Serializable
    data class DebugConsoleStats(
        val totalChunks: Int,
        val chunksStored: Int,
        val oldestIndex: Int?,
        val newestIndex: Int?,
        val debugEnabled: Boolean
    )

    @Serializable
    data class ReadDebugConsoleResult(
        val chunks: List<DebugConsoleChunk>,
        val stats: DebugConsoleStats
    )

    @Serializable
    data class ManageToolItem(val name: String, val enabled: Boolean)

    @Serializable
    data class ManageToolsListResult(val tools: List<ManageToolItem>)

    companion object {
        private const val DEFAULT_SCROLLBACK_LINES = 200
        private const val DEFAULT_SEARCH_MAX_MATCHES = 50
        private const val DEFAULT_DEBUG_CHUNKS = 100

        /**
         * Unprefixed built-in read tool names, in display order. Single source of
         * truth shared with the settings UI so toggle rows can't drift from the
         * tools actually registered.
         */
        val BUILT_IN_READ_TOOLS: List<String> = listOf(
            "list_tabs",
            "get_active_tab",
            "read_scrollback",
            "search_output",
            "get_last_command",
            "read_debug_console"
        )

        /** Unprefixed built-in write tool names, in display order. */
        val BUILT_IN_WRITE_TOOLS: List<String> = listOf(
            "send_input",
            "send_signal",
            "run_in_panel"
        )

        /**
         * Tools that may never be disabled. `manage_tools` is the only escape hatch
         * once everything else has been turned off, so disabling it would brick the
         * MCP surface.
         */
        val UNDISABLABLE_TOOLS: Set<String> = setOf("manage_tools")
    }
}
