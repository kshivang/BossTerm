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
 * The network MCP path resolves a caller's tab from its TCP connection (ProcessAncestry); that is
 * meaningless in-process, so every call gets an explicit `tab_id` — the model's, else
 * [defaultTabId] (the tab the viewer is looking at), else [anchorTabId] (the shared tab).
 * `list_tabs` / `get_active_tab` are answered directly from [McpTerminalRegistry], filtered to
 * [inScopeTabIds], so the agent never sees tabs outside the share.
 */
internal class GuiVoiceToolExecutor(
    private val inScopeTabIds: () -> Set<String>,
    private val anchorTabId: String,
    private val registry: McpTerminalRegistry = McpTerminalRegistry,
) : VoiceToolExecutor {

    private companion object {
        /** Safety net over handler execution; above run_command's own 600s clamp. */
        const val EXEC_TIMEOUT_MS = 610_000L

        /** Tools answered directly from the registry (scope-filtered), not via an MCP handler. */
        val LOCAL_TOOLS = setOf("list_tabs", "get_active_tab")
    }

    // Built lazily so no MCP machinery spins up until the first call actually starts.
    private val server: Server by lazy {
        BossTermMcpServer(config = BossTermMcpConfig(allowWriteTools = true)).createServer()
    }

    override fun tools(): List<VoiceToolDef> {
        val registered = server.tools.keys
        return VoiceToolCatalog.ALL.filter { it.name in LOCAL_TOOLS || it.name in registered }
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
            if (tab.id == (defaultTabId ?: anchorTabId)) sb.append(" ← the user is viewing this tab")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
        val def = tools().firstOrNull { it.name == name }
            ?: throw VoiceToolException("Unknown tool: $name")
        val scope = inScopeTabIds()

        // Tab enumeration is answered locally so it can be scope-filtered (the MCP handler
        // enumerates every window on the host).
        when (name) {
            "list_tabs" -> return listTabsJson(scope, defaultTabId)
            "get_active_tab" -> return tabInfoJson(defaultTabId ?: anchorTabId)
        }

        val targetTabId = args.stringArg("tab_id") ?: defaultTabId ?: anchorTabId
        if (targetTabId !in scope) {
            throw VoiceToolException("Tab $targetTabId is not part of this share")
        }
        val effectiveArgs = buildJsonObject {
            args.forEach { (k, v) -> if (k != "tab_id") put(k, v) }
            put("tab_id", targetTabId)
        }
        val handler = server.tools[def.name]?.handler
            ?: throw VoiceToolException("Tool not available on this host: $name")
        val result = withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            handler(CallToolRequest(CallToolRequestParams(name = def.name, arguments = effectiveArgs)))
        } ?: throw VoiceToolException("Tool $name timed out")
        return result.content.filterIsInstance<TextContent>().firstOrNull()?.text ?: "{}"
    }

    private fun listTabsJson(scope: Set<String>, defaultTabId: String?): String = buildJsonObject {
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
        (defaultTabId ?: anchorTabId).let { put("viewingTabId", it) }
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

    private fun JsonObject.stringArg(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
