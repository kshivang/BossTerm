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
import kotlinx.serialization.json.booleanOrNull
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
    private val config: BossTermMcpConfig = BossTermMcpConfig()
) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    /** Returns the built-in tool name with the embedder's configured prefix applied. */
    private fun toolName(builtin: String): String = config.toolNamePrefix + builtin

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

        registerListTabs(server)
        registerGetActiveTab(server)
        registerReadScrollback(server)
        registerSearchOutput(server)
        registerGetLastCommand(server)
        registerReadDebugConsole(server)
        if (config.allowWriteTools) {
            registerSendInput(server)
            registerSendSignal(server)
            registerRunInPanel(server)
        }

        // Embedder hook: register app-specific tools after built-ins. Names
        // are NOT prefixed — embedder owns them.
        config.additionalTools(server)

        return server
    }

    // -----------------------------------------------------------------
    // Tool: list_tabs
    // -----------------------------------------------------------------

    private fun registerListTabs(server: Server) {
        server.addTool(
            name = toolName("list_tabs"),
            description = "List all open terminal tabs across all windows. Each tab includes " +
                    "id, title, working directory, pid, and isActive (true if the tab is the " +
                    "currently selected tab of its window).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) { _ ->
            val infos = registry.collectTabInfos()
            val primaryActiveId = registry.primaryState()?.activeTabId
            val payload = ListTabsResult(tabs = infos, activeTabId = primaryActiveId)
            successJson(json.encodeToString(ListTabsResult.serializer(), payload))
        }
    }

    // -----------------------------------------------------------------
    // Tool: get_active_tab
    // -----------------------------------------------------------------

    private fun registerGetActiveTab(server: Server) {
        server.addTool(
            name = toolName("get_active_tab"),
            description = "Return the active tab of the primary window (the first window opened " +
                    "that is still alive), or null if no tab is active.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) { _ ->
            val primary = registry.primaryState()
            val activeId = primary?.activeTabId
            val info = primary?.activeTab?.toTabInfo(activeId)
            // The literal JSON `null` is valid output — clients calling
            // JSON.parse get a real null. Cheaper than wrapping in `{tab: null}`.
            val text = if (info == null) "null" else json.encodeToString(TabInfo.serializer(), info)
            successJson(text)
        }
    }

    // -----------------------------------------------------------------
    // Tool: read_scrollback
    // -----------------------------------------------------------------

    private fun registerReadScrollback(server: Server) {
        server.addTool(
            name = toolName("read_scrollback"),
            description = "Read the last N lines from a tab's terminal buffer (history + visible " +
                    "screen) as plain UTF-8 text. Trailing whitespace per line is stripped.",
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
            val tab = registry.findTab(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")

            val snapshot = tab.textBuffer.createSnapshot()
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
            successJson(json.encodeToString(ReadScrollbackResult.serializer(), payload))
        }
    }

    // -----------------------------------------------------------------
    // Tool: search_output
    // -----------------------------------------------------------------

    private fun registerSearchOutput(server: Server) {
        server.addTool(
            name = toolName("search_output"),
            description = "Regex-search the entire scrollback (history + screen) of a tab. " +
                    "Returns matching rows with positional info. Row numbers follow buffer " +
                    "convention: negative for history (oldest = -historyLinesCount), 0..height-1 " +
                    "for screen. The response also includes historyLinesCount and height so " +
                    "clients can convert to 1-based line numbers if desired.",
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
            val tab = registry.findTab(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")

            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = try {
                Regex(pattern, options)
            } catch (e: Exception) {
                return@addTool errorResult("Invalid regex: ${e.message ?: e::class.simpleName}")
            }

            val snapshot = tab.textBuffer.createSnapshot()
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

            val payload = SearchOutputResult(
                matches = matches,
                truncated = truncated,
                historyLinesCount = snapshot.historyLinesCount,
                height = snapshot.height
            )
            successJson(json.encodeToString(SearchOutputResult.serializer(), payload))
        }
    }

    // -----------------------------------------------------------------
    // Tool: get_last_command
    // -----------------------------------------------------------------

    private fun registerGetLastCommand(server: Server) {
        server.addTool(
            name = toolName("get_last_command"),
            description = "Return the most recently completed shell command for a tab " +
                    "(as captured via OSC 133), or null if no command has finished yet. " +
                    "Requires shell integration — see shell-integration.md. " +
                    "Note: `commandText` is currently always null; only `exitCode`, `startedAtMs`, " +
                    "`finishedAtMs`, `durationMs`, and `cwd` are populated. Capturing the typed " +
                    "command text reliably is a follow-up.",
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
            description = "Write text to the tab's shell stdin. The caller is responsible for " +
                    "appending a trailing '\\n' if they want a command to actually execute.",
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
                },
                required = listOf("tab_id", "text")
            )
        ) { request ->
            val args = request.arguments
            val tabId = args.requireString("tab_id")
                ?: return@addTool errorResult("Missing required argument: tab_id")
            val text = args.requireString("text")
                ?: return@addTool errorResult("Missing required argument: text")

            val state = registry.findState(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")
            val sent = state.write(text, tabId)
            if (!sent) {
                return@addTool errorResult("Failed to write to tab_id: $tabId")
            }
            successJson(json.encodeToString(OkResult.serializer(), OkResult(ok = true)))
        }
    }

    // -----------------------------------------------------------------
    // Tool: send_signal
    // -----------------------------------------------------------------

    private fun registerSendSignal(server: Server) {
        server.addTool(
            name = toolName("send_signal"),
            description = "Send a control signal to the tab's shell. Allowed signals: " +
                    "'ctrl_c' (interrupt), 'ctrl_d' (EOF), 'ctrl_z' (suspend).",
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
                },
                required = listOf("tab_id", "signal")
            )
        ) { request ->
            val args = request.arguments
            val tabId = args.requireString("tab_id")
                ?: return@addTool errorResult("Missing required argument: tab_id")
            val signal = args.requireString("signal")
                ?: return@addTool errorResult("Missing required argument: signal")

            val state = registry.findState(tabId)
                ?: return@addTool errorResult("Unknown tab_id: $tabId")

            val delivered = when (signal.lowercase()) {
                "ctrl_c" -> state.sendCtrlC(tabId)
                "ctrl_d" -> state.sendInput(byteArrayOf(0x04), tabId)
                "ctrl_z" -> state.sendInput(byteArrayOf(0x1A), tabId)
                else -> return@addTool errorResult(
                    "Unknown signal: '$signal'. Expected one of: ctrl_c, ctrl_d, ctrl_z."
                )
            }
            if (!delivered) {
                return@addTool errorResult("Failed to deliver signal to tab_id: $tabId")
            }
            successJson(json.encodeToString(OkResult.serializer(), OkResult(ok = true)))
        }
    }

    // -----------------------------------------------------------------
    // Tool: run_in_panel
    // -----------------------------------------------------------------

    private fun registerRunInPanel(server: Server) {
        server.addTool(
            name = toolName("run_in_panel"),
            description = "Open a new terminal panel and write a script to it. Modes: " +
                    "'new_tab' (fresh tab with initialCommand), 'horizontal_split' (split " +
                    "below focused pane), 'vertical_split' (split beside focused pane). " +
                    "Include '\\n' in the script to submit it as a command. " +
                    "Note: in 'new_tab' mode, the response returns as soon as the tab is " +
                    "created; the shell may still be initializing for ~1s before the script " +
                    "actually runs. Use list_tabs / read_scrollback after a short delay if " +
                    "you need to observe results.",
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
                    // TabController.createTab auto-appends '\n' to
                    // initialCommand, while the split path uses raw
                    // writeUserInput (no auto-newline). Normalize a single
                    // trailing newline so the tool's `\n means submit`
                    // contract is consistent across all three modes.
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
                    val paneId = if (panel == "horizontal_split") {
                        state.splitHorizontal(targetTabId, ratio = effectiveRatio)
                    } else {
                        state.splitVertical(targetTabId, ratio = effectiveRatio)
                    } ?: return@addTool errorResult("Split failed (terminal too small?)")
                    // After splitFocusedPane the focus moves to the new pane,
                    // so writeToFocusedPane targets it.
                    val wrote = state.writeToFocusedPane(script, targetTabId)
                    if (!wrote) {
                        return@addTool errorResult("Split succeeded but write to new pane failed")
                    }
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
            description = "Read recent entries from a tab's debug-data buffer (PTY output, " +
                    "user input, console-log entries). Per-tab circular buffer; cap is " +
                    "settings.debugMaxChunks (default 1000). Supports incremental polling via " +
                    "since_index and filtering via sources.",
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
                                    "silently ignored."
                        )
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
            val sourcesFilter: Set<ChunkSource>? = (args?.get("sources") as? JsonArray)
                ?.mapNotNull { item ->
                    val raw = item.jsonPrimitive.content
                    // Case-insensitive lookup — agents writing "pty_output"
                    // shouldn't silently see zero results.
                    ChunkSource.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
                ?.toSet()
                ?.takeUnless { it.isEmpty() }

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

            val payload = ReadDebugConsoleResult(chunks = resultChunks, stats = statsDto)
            successJson(json.encodeToString(ReadDebugConsoleResult.serializer(), payload))
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

    companion object {
        private const val DEFAULT_SCROLLBACK_LINES = 200
        private const val DEFAULT_SEARCH_MAX_MATCHES = 50
        private const val DEFAULT_DEBUG_CHUNKS = 100
    }
}
