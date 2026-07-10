package ai.rever.bossterm.compose.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import java.util.concurrent.ConcurrentHashMap

/**
 * Streamable HTTP MCP endpoint for clients that don't speak SSE (Codex).
 *
 * One [StreamableHttpServerTransport] per client session, JSON-response mode
 * (no server-initiated SSE stream). Each transport gets its own `ServerSession`
 * inside the shared [mcpServer] via [Server.createSession], so streamable
 * clients and the SSE clients on the root path multiplex without sharing
 * connection state.
 *
 * Session lifecycle:
 *  - POST without `mcp-session-id` may only be an `initialize` request. The
 *    SDK enforces that (anything else is rejected 400 before a session id is
 *    minted), and [handlePost] closes the speculatively created transport when
 *    initialization didn't happen, so a stray POST can't park a phantom
 *    `ServerSession` in [mcpServer]'s registry.
 *  - Unknown session ids get 404 (per the streamable HTTP spec, telling the
 *    client to re-initialize), matching the SDK's own `validateSession`.
 *  - DELETE tears the session down via the transport, whose
 *    `onSessionClosed` callback drops the map entry and whose close cascades
 *    (transport → ServerSession.onClose) to registry removal in [mcpServer].
 *  - Streamable HTTP has no connection whose drop the server could observe: a
 *    client that crashes — or simply exits without DELETE, as each `codex`
 *    invocation does — would park its session here forever. [evictIdle] sweeps
 *    those; an evicted client that returns sees 404 and re-initializes.
 *
 * [clock] is injectable for tests; production uses wall time.
 */
internal class StreamableMcpSessions(
    private val mcpServer: Server,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private class Entry(val transport: StreamableHttpServerTransport) {
        @Volatile
        var lastActivityMs: Long = 0L
    }

    private val sessions = ConcurrentHashMap<String, Entry>()

    val sessionCount: Int
        get() = sessions.size

    suspend fun handlePost(call: ApplicationCall) {
        val sessionId = call.sessionIdHeader()
        if (sessionId == null) {
            initializeSession(call)
            return
        }
        val entry = sessions[sessionId] ?: return call.rejectUnknownSession()
        entry.lastActivityMs = clock()
        entry.transport.handlePostRequest(null, call)
    }

    private suspend fun initializeSession(call: ApplicationCall) {
        val transport = StreamableHttpServerTransport(
            enableJsonResponse = true,
            enableDnsRebindingProtection = false
        )
        transport.setOnSessionInitialized { id ->
            sessions[id] = Entry(transport).apply { lastActivityMs = clock() }
        }
        transport.setOnSessionClosed { id -> sessions.remove(id) }
        mcpServer.createSession(transport)
        transport.handlePostRequest(null, call)
        if (transport.sessionId == null) {
            // The SDK rejected the request (not an initialize, or malformed)
            // without minting a session id. Close the transport so the
            // ServerSession created above leaves the shared server's registry
            // instead of leaking one entry per stray POST.
            transport.close()
        }
    }

    /**
     * GET opens the spec's optional server→client SSE stream, which
     * JSON-response transports don't offer; 405 tells the client to carry on
     * with plain POSTs. Served as a regular GET route on purpose: a ktor
     * `sse()` handler has already committed a 200 event-stream response by the
     * time it runs, so the SDK's 405 reject inside one would strand the client
     * on an empty stream instead.
     */
    suspend fun handleGet(call: ApplicationCall) {
        call.response.header(HttpHeaders.Allow, "POST, DELETE")
        call.respondText(
            "Method Not Allowed: this endpoint serves JSON responses, not an SSE stream.",
            status = HttpStatusCode.MethodNotAllowed
        )
    }

    suspend fun handleDelete(call: ApplicationCall) {
        val sessionId = call.sessionIdHeader() ?: return call.respondText(
            "Missing $SESSION_ID_HEADER header.",
            status = HttpStatusCode.BadRequest
        )
        val entry = sessions[sessionId] ?: return call.rejectUnknownSession()
        // Fires onSessionClosed (drops the map entry) and closes the
        // transport, which cascades to ServerSession removal in [mcpServer].
        entry.transport.handleDeleteRequest(call)
    }

    /**
     * Close sessions with no activity for [idleTtlMs], reclaiming their
     * transports and `ServerSession`s. Returns how many were evicted.
     */
    suspend fun evictIdle(idleTtlMs: Long): Int {
        val cutoff = clock() - idleTtlMs
        var evicted = 0
        for ((id, entry) in sessions) {
            if (entry.lastActivityMs <= cutoff && sessions.remove(id, entry)) {
                entry.transport.close()
                evicted++
            }
        }
        return evicted
    }

    /** Engine stop: close every transport so nothing outlives the bind. */
    suspend fun closeAll() {
        for ((id, entry) in sessions) {
            if (sessions.remove(id, entry)) {
                entry.transport.close()
            }
        }
    }

    private fun ApplicationCall.sessionIdHeader(): String? =
        request.headers[SESSION_ID_HEADER]?.takeIf { it.isNotBlank() }

    private suspend fun ApplicationCall.rejectUnknownSession() {
        respondText("Session not found.", status = HttpStatusCode.NotFound)
    }

    companion object {
        /** Session id header defined by the MCP streamable HTTP transport. */
        const val SESSION_ID_HEADER = "mcp-session-id"
    }
}

/**
 * Mount the streamable HTTP endpoint on this route. Content negotiation is
 * route-scoped and wired to the SDK's own [McpJson]: the SDK's JSON-response
 * mode replies via `call.respond(JSONRPCMessage)`, which needs a server-side
 * converter — and must not leak onto the root SSE/identity routes.
 */
internal fun Route.mountStreamableMcp(sessions: StreamableMcpSessions) {
    install(ContentNegotiation) { json(McpJson) }
    post { sessions.handlePost(call) }
    get { sessions.handleGet(call) }
    delete { sessions.handleDelete(call) }
}
