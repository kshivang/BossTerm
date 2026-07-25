package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpServer
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [VoiceToolExecutor] for in-app (GUI) shares: executes the curated voice tools by invoking the
 * GUI MCP tool handlers **in-process** — a private transport-free [BossTermMcpServer.createServer]
 * that exists regardless of whether the user's MCP endpoint is enabled. `createServer()` already
 * honors `disabledMcpTools`, so user tool-disables carry over to voice for free.
 *
 * The server is built from the EMBEDDER's [BossTermMcpConfig] (published on
 * [McpTerminalRegistry.mcpConfig]), not a fabricated one: an app that configured a deliberately
 * read-only MCP surface (`allowWriteTools = false`) must not get run_command / send_input back
 * through voice, and a `toolNamePrefix` has to be applied when resolving handlers or the surface
 * would silently collapse to the two locally-answered tools.
 *
 * The network MCP path resolves a caller's tab from its TCP connection (ProcessAncestry); that is
 * meaningless in-process, so every call gets an explicit `tab_id` — the model's, else
 * [defaultTabId] (the tab the caller is looking at), else [anchorTabId].
 * `list_tabs` / `get_active_tab` are answered directly from [McpTerminalRegistry], filtered to
 * [inScopeTabIds], so the agent never sees tabs outside the share.
 */
internal class GuiVoiceToolExecutor(
    private val inScopeTabIds: () -> Set<String>,
    /**
     * Fallback target when neither the model nor the caller names a tab. A provider, not a value:
     * a share anchors on its own tab, while an in-app host call follows whichever tab is focused.
     */
    private val anchorTabId: () -> String?,
    private val registry: McpTerminalRegistry = McpTerminalRegistry,
    private val settings: () -> ai.rever.bossterm.compose.settings.TerminalSettings =
        { ai.rever.bossterm.compose.settings.SettingsManager.instance.settings.value },
) : VoiceToolExecutor {

    private companion object {
        /** Safety net over handler execution; above run_command's own 600s clamp. */
        const val EXEC_TIMEOUT_MS = 610_000L

        /** Tools answered directly from the registry (scope-filtered), not via an MCP handler. */
        val LOCAL_TOOLS = setOf("list_tabs", "get_active_tab")
    }

    /** The embedder's config, or library defaults when no manager was ever constructed. */
    private val mcpConfig: BossTermMcpConfig get() = registry.mcpConfig ?: BossTermMcpConfig()

    // Built lazily so no MCP machinery spins up until the first call actually starts.
    private val wrapper: BossTermMcpServer by lazy { BossTermMcpServer(config = mcpConfig) }
    private val serverInstance: Server by lazy { wrapper.createServer() }
    @Volatile private var appliedDisabled: Set<String>? = null

    /**
     * The private server with the user's CURRENT tool disables applied.
     *
     * `createServer()` reads `disabledMcpTools` once, and only [BossTermMcpManager] drives
     * `applyDisabledSet` on its own instance — so this one froze the disables as they stood at the
     * first voice call. That matters because `manage_tools` exists to toggle them at runtime:
     * disabling run_command mid-session left it callable by voice until the app restarted.
     */
    private fun server(): Server {
        val server = serverInstance
        val disabled = settings().disabledMcpTools.toSet()
        if (disabled != appliedDisabled) {
            appliedDisabled = disabled
            runCatching { wrapper.applyDisabledSet(disabled) }
        }
        return server
    }

    /** The registered handler name for a catalog tool (the embedder may prefix them). */
    private fun handlerName(tool: String): String = mcpConfig.toolNamePrefix + tool

    override fun tools(): List<VoiceToolDef> {
        val registered = server().tools.keys
        return VoiceToolCatalog.ALL.filter { it.name in LOCAL_TOOLS || handlerName(it.name) in registered }
    }

    override fun contextSnapshot(defaultTabId: String?): String {
        val scope = inScopeTabIds()
        val sb = StringBuilder()
        for (tab in registry.allTabs()) {
            if (tab.id !in scope) continue
            val active = registry.findState(tab.id)?.activeTabId == tab.id
            sb.append("- \"").append(tab.title.value).append("\" (tab_id ").append(tab.id).append(')')
            tab.workingDirectory.value?.let { sb.append(", cwd ").append(it) }
            if (active) sb.append(" [active]")
            if (tab.id == (defaultTabId ?: anchorTabId())) sb.append(" ← the user is viewing this tab")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
        val def = tools().firstOrNull { it.name == name }
            ?: throw VoiceToolException("Unknown tool: $name")
        val scope = inScopeTabIds()

        // Resolve and scope-check the target BEFORE dispatching anywhere — including the locally
        // answered tools. [defaultTabId] comes from the viewer's own Focus/VoiceStart, and the
        // registry lookups behind get_active_tab span every window on the host, so checking only
        // the MCP path would let a viewer name a foreign tab and read back its title and cwd.
        // A tab the model NAMES is always scope-checked — that's the security boundary. The
        // viewer-reported focus is not a boundary: it was in scope when stored, but the tab can
        // close afterwards, and treating a stale id as a violation used to break every tool
        // including list_tabs/get_active_tab, leaving the agent no way to discover a valid id.
        // Stale focus therefore falls back to the anchor, which is in scope by construction.
        val requested = args.stringArg("tab_id")
        if (requested != null && requested !in scope) {
            throw VoiceToolException("Tab $requested is not part of this share")
        }
        val focused = defaultTabId?.takeIf { it in scope }
        val fallback = requested ?: focused ?: anchorTabId()?.takeIf { it in scope }

        // Tab enumeration is answered locally so it can be scope-filtered (the MCP handler
        // enumerates every window on the host), and needs no target at all.
        when (name) {
            "list_tabs" -> return listTabsJson(scope, fallback)
            "get_active_tab" -> return tabInfoJson(
                fallback ?: throw VoiceToolException("No terminal tab is available")
            )
        }

        val targetTabId = fallback ?: throw VoiceToolException("No terminal tab is available")

        // Pass through ONLY the keys this tool advertises. The underlying MCP schemas are wider
        // than the voice catalog — run_command also takes panel/working_dir/split_ratio — and
        // `panel: "new_tab"` would run the command in a tab the share doesn't mirror, undercutting
        // the "targets are limited to the share" guarantee even though a controller could open a tab
        // by other means.
        val allowed = declaredParameters(def)
        val effectiveArgs = buildJsonObject {
            args.forEach { (k, v) -> if (k != "tab_id" && k in allowed) put(k, v) }
            put("tab_id", targetTabId)
        }
        val registeredName = handlerName(def.name)
        val handler = server().tools[registeredName]?.handler
            ?: throw VoiceToolException("Tool not available on this host: $name")
        val result = withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            handler(CallToolRequest(CallToolRequestParams(name = registeredName, arguments = effectiveArgs)))
        } ?: throw VoiceToolException("Tool $name timed out")
        return result.content.filterIsInstance<TextContent>().firstOrNull()?.text ?: "{}"
    }

    private fun listTabsJson(scope: Set<String>, viewingTabId: String?): String = buildJsonObject {
        put("tabs", buildJsonArray {
            for (tab in registry.allTabs()) {
                if (tab.id !in scope) continue
                add(buildJsonObject {
                    put("id", tab.id)
                    put("title", tab.title.value)
                    tab.workingDirectory.value?.let { put("cwd", it) }
                    put("isActive", registry.findState(tab.id)?.activeTabId == tab.id)
                })
            }
        })
        viewingTabId?.let { put("viewingTabId", it) }
    }.toString()

    private fun tabInfoJson(tabId: String): String {
        val tab = registry.findTab(tabId) ?: return buildJsonObject {
            put("error", "Tab $tabId is no longer open")
        }.toString()
        return buildJsonObject {
            put("id", tab.id)
            put("title", tab.title.value)
            tab.workingDirectory.value?.let { put("cwd", it) }
            put("isActive", registry.findState(tab.id)?.activeTabId == tab.id)
        }.toString()
    }

    /** The property names declared in a catalog tool's own JSON schema. */
    private fun declaredParameters(def: VoiceToolDef): Set<String> =
        runCatching {
            (def.parameters["properties"] as? JsonObject)?.keys?.toSet()
        }.getOrNull().orEmpty()

    private fun JsonObject.stringArg(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
