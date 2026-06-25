package ai.rever.bossterm.compose.daemon

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.ServerSocket

/**
 * The daemon's MCP server: exposes [SessionHost] sessions over a loopback Ktor SSE endpoint so MCP
 * clients (Claude Code, etc.) can drive daemon-hosted terminals — and keep working after the GUI
 * closes. Tool LOGIC lives in [DaemonMcpTools] (unit-tested); this class only wires those onto an
 * MCP SDK [Server] and binds the transport, mirroring [ai.rever.bossterm.compose.mcp.BossTermMcpManager]'s
 * loopback + DNS-rebinding-guard + mcp.port-marker behavior in a minimal, settings-free form.
 */
class DaemonMcpServer(
    private val host: SessionHost,
    private val serverName: String = "bossterm",
    private val serverVersion: String = "1.0",
) {
    private val log = LoggerFactory.getLogger(DaemonMcpServer::class.java)
    private val tools = DaemonMcpTools(host)

    @Volatile private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile var boundPort: Int? = null
        private set

    private fun result(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = text)), isError = false, structuredContent = null, meta = null)

    private fun args(request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest): JsonObject =
        request.arguments ?: JsonObject(emptyMap())

    fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = serverName, version = serverVersion),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
        )

        server.addTool(
            name = "list_sessions",
            description = "List terminal sessions hosted by the BossTerm daemon (survive the GUI closing).",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { result(tools.listSessions()) }

        server.addTool(
            name = "open_session",
            description = "Open a new daemon-hosted terminal session (background; survives GUI close). Returns its id.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("cwd") { put("type", "string"); put("description", "Working directory. Default: home.") }
                    putJsonObject("command") { put("type", "string"); put("description", "Program to run. Default: login shell.") }
                    putJsonObject("cols") { put("type", "integer"); put("minimum", 1) }
                    putJsonObject("rows") { put("type", "integer"); put("minimum", 1) }
                },
                required = emptyList(),
            ),
        ) { result(tools.openSession(args(it))) }

        server.addTool(
            name = "read_scrollback",
            description = "Read the last N lines (history + screen) of a daemon session's buffer as plain text.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("session_id") { put("type", "string"); put("description", "Session id (see list_sessions).") }
                    putJsonObject("lines") { put("type", "integer"); put("minimum", 1); put("description", "Default 200.") }
                },
                required = listOf("session_id"),
            ),
        ) { result(tools.readScrollback(args(it))) }

        server.addTool(
            name = "send_input",
            description = "Write text to a daemon session's stdin. Include '\\n' to submit.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("session_id") { put("type", "string") }
                    putJsonObject("text") { put("type", "string"); put("description", "Raw text; include '\\n' to submit.") }
                },
                required = listOf("session_id", "text"),
            ),
        ) { result(tools.sendInput(args(it))) }

        server.addTool(
            name = "send_signal",
            description = "Send a control signal (ctrl_c | ctrl_d | ctrl_z) to a daemon session.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("session_id") { put("type", "string") }
                    putJsonObject("signal") { put("type", "string"); put("description", "ctrl_c | ctrl_d | ctrl_z") }
                },
                required = listOf("session_id", "signal"),
            ),
        ) { result(tools.sendSignal(args(it))) }

        server.addTool(
            name = "resize_session",
            description = "Resize a daemon session's terminal grid.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("session_id") { put("type", "string") }
                    putJsonObject("cols") { put("type", "integer"); put("minimum", 1) }
                    putJsonObject("rows") { put("type", "integer"); put("minimum", 1) }
                },
                required = listOf("session_id", "cols", "rows"),
            ),
        ) { result(tools.resizeSession(args(it))) }

        server.addTool(
            name = "close_session",
            description = "Close (kill) a daemon-hosted terminal session.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { putJsonObject("session_id") { put("type", "string") } },
                required = listOf("session_id"),
            ),
        ) { result(tools.closeSession(args(it))) }

        return server
    }

    /** Bind the SSE endpoint on 127.0.0.1, trying [desiredPort]..+9. Returns the bound port or null. */
    fun start(desiredPort: Int): Int? {
        for (offset in 0 until 10) {
            val port = desiredPort + offset
            if (port > 65535) break
            if (!portAvailable(port)) continue
            val allowed = setOf("127.0.0.1", "localhost", "127.0.0.1:$port", "localhost:$port")
            try {
                val srv = embeddedServer(CIO, host = HOST, port = port) {
                    install(SSE)
                    intercept(ApplicationCallPipeline.Plugins) {
                        val hostHeader = call.request.headers["Host"]?.lowercase() ?: call.request.host().lowercase()
                        if (hostHeader !in allowed) {
                            call.respondText("Forbidden: '$hostHeader' is not a loopback target.", status = HttpStatusCode.Forbidden)
                            finish()
                        }
                    }
                    routing { mcp { createServer() } }
                }
                srv.start(wait = false)
                engine = srv
                boundPort = port
                writePortMarker(port)
                log.info("Daemon MCP server on http://{}:{}/ (SSE)", HOST, port)
                return port
            } catch (e: Throwable) {
                log.warn("Daemon MCP bind on {}:{} failed: {}", HOST, port, e.message)
            }
        }
        log.error("Daemon MCP server could not bind in [{},{}]", desiredPort, desiredPort + 9)
        return null
    }

    fun stop() {
        runCatching { engine?.stop(300, 800) }
        engine = null
        boundPort = null
        deletePortMarker()
    }

    private fun portAvailable(port: Int): Boolean =
        runCatching { ServerSocket().use { it.reuseAddress = false; it.bind(java.net.InetSocketAddress(HOST, port)); true } }.getOrDefault(false)

    // mcp.port marker — same contract the Claude Code hook / CLI already read, now daemon-written.
    private fun writePortMarker(port: Int) {
        runCatching {
            val target = BossTermPaths.mcpPortFile()
            val tmp = File(target.parentFile, ".mcp.port.tmp")
            tmp.writeText(port.toString())
            runCatching {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }.onFailure {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { log.warn("Failed to write mcp.port: {}", it.message) }
    }

    private fun deletePortMarker() {
        runCatching { BossTermPaths.mcpPortFile().let { if (it.exists()) it.delete() } }
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
