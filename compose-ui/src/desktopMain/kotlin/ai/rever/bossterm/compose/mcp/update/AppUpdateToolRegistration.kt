package ai.rever.bossterm.compose.mcp.update

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One coordinator across transports/windows; only the standalone app opts into registration. */
internal object AppUpdateToolRegistration {
    private val commands by lazy { AppUpdateCommands(ManagedAppUpdateBackend()) }

    fun register(server: Server, action: String, name: String) {
        val descriptions = mapOf(
            "status" to "Report BossTerm update state, progress, version and restart requirement.",
            "check" to "Check configured release sources for a BossTerm update. Poll app_update_status for completion.",
            "download" to "Download the available BossTerm update. Poll app_update_status for completion.",
            "install" to "Install the staged BossTerm version. May prompt, quit/relaunch the app and disconnect MCP.",
        )
        server.addTool(
            name = name,
            description = descriptions.getValue(action),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    if (action == "install") putJsonObject("version") {
                        put("type", "string")
                        put("description", "Exact install_version returned by app_update_status")
                    }
                },
                required = if (action == "install") listOf("version") else emptyList(),
            ),
        ) { request ->
            val version = request.arguments?.get("version")?.jsonPrimitive?.contentOrNull
            val result = if (action == "status") commands.status() else commands.start(action, version)
            CallToolResult(content = listOf(TextContent(result.payload.toString())), isError = result.isError)
        }
    }
}
