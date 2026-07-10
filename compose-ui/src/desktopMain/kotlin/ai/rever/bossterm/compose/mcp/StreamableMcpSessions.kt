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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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
 *  - [maxSessions] bounds accretion between sweeps: steady state is roughly
 *    one abandoned session per codex invocation inside the idle TTL, so a
 *    burst of invocations could otherwise pile up unbounded until the next
 *    sweep. Initializing past the cap evicts the longest-idle session first
 *    (which, evicted, just re-initializes if it ever comes back).
 *
 * [clock] is injectable for tests; production uses wall time.
 */
internal class StreamableMcpSessions(
    private val mcpServer: Server,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val log = LoggerFactory.getLogger(StreamableMcpSessions::class.java)

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
        // Unlike initializeSession there is deliberately no teardown guard:
        // an established session must survive a single failed request; only
        // DELETE, idle eviction, or engine stop end it.
        entry.transport.handlePostRequest(null, call)
    }

    private suspend fun initializeSession(call: ApplicationCall) {
        val transport = StreamableHttpServerTransport(
            enableJsonResponse = true,
            // Not dropped, just not duplicated: the manager's app-level
            // intercept already 403s any non-loopback Host header before
            // routing, so it covers /mcp along with every other route.
            enableDnsRebindingProtection = false
        )
        transport.setOnSessionInitialized { id ->
            sessions[id] = Entry(transport).apply { lastActivityMs = clock() }
        }
        transport.setOnSessionClosed { id -> sessions.remove(id) }
        // The create-then-maybe-teardown shape (rather than checking the
        // method first) is forced by the SDK: it owns body parsing inside
        // handlePostRequest, so peeking at the method up front would need
        // double-receive plumbing. A stray anonymous POST therefore churns
        // one ServerSession create+close — loopback-gated, acceptable.
        mcpServer.createSession(transport)
        try {
            transport.handlePostRequest(null, call)
        } finally {
            if (transport.sessionId == null) {
                // No session id was minted: the SDK rejected the request (not
                // an initialize, or malformed), or the handler threw / the
                // call was cancelled mid-flight. Close the transport so the
                // ServerSession created above leaves the shared server's
                // registry instead of leaking one entry per stray POST.
                transport.closeQuietly()
            }
        }
        if (transport.sessionId != null) {
            enforceSessionCap()
        }
    }

    /**
     * Evict longest-idle sessions until the map is back within [maxSessions].
     * Runs after each successful initialize, so the map size is bounded even
     * when a burst of codex invocations lands between two idle sweeps.
     */
    private suspend fun enforceSessionCap() {
        while (sessions.size > maxSessions) {
            val oldest = sessions.entries.minByOrNull { it.value.lastActivityMs } ?: return
            if (sessions.remove(oldest.key, oldest.value)) {
                oldest.value.transport.closeQuietly()
                log.info(
                    "Streamable MCP session cap {} reached; evicted longest-idle session {}",
                    maxSessions, oldest.key
                )
            }
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
     *
     * Known benign race: a POST that stamps [Entry.lastActivityMs] while a
     * sweep is reading the pre-stamp value can have its transport closed
     * mid-request. Reaching it requires the request to land in the same
     * instant the session crosses the idle TTL (hours), and the cost is one
     * failed response on a session the client re-initializes anyway — not
     * worth a per-entry lock.
     */
    suspend fun evictIdle(idleTtlMs: Long): Int {
        val cutoff = clock() - idleTtlMs
        var evicted = 0
        for ((id, entry) in sessions) {
            if (entry.lastActivityMs <= cutoff && sessions.remove(id, entry)) {
                entry.transport.closeQuietly()
                evicted++
            }
        }
        return evicted
    }

    /** Engine stop: close every transport so nothing outlives the bind. */
    suspend fun closeAll() {
        for ((id, entry) in sessions) {
            if (sessions.remove(id, entry)) {
                entry.transport.closeQuietly()
            }
        }
    }

    /**
     * Close without letting the caller's context interfere: NonCancellable
     * because every call site can run on an already-cancelled job (request
     * finally, sweeper cancel, engine stop) and close() suspends on a mutex —
     * a cancelled caller would abort at that suspension point and skip the
     * cleanup. Failures are logged, not thrown: by the time this runs the map
     * entry is already gone, so one bad transport must not abort the rest of
     * a sweep/closeAll or mask a request's original exception.
     */
    private suspend fun StreamableHttpServerTransport.closeQuietly() {
        try {
            withContext(NonCancellable) { close() }
        } catch (e: Throwable) {
            log.warn("Failed to close streamable MCP transport (session {}): {}", sessionId, e.message)
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

        /**
         * Generous soft cap: real concurrent clients number in the single
         * digits; everything past that is abandoned codex sessions awaiting
         * the idle sweep, and evicting those early is harmless (see kdoc).
         */
        const val DEFAULT_MAX_SESSIONS = 256
    }
}

/**
 * Mount the streamable HTTP endpoint on this route. Content negotiation is
 * route-scoped and wired to the SDK's own [McpJson]: the SDK's JSON-response
 * mode replies via `call.respond(JSONRPCMessage)`, which needs a server-side
 * converter — and must not leak onto the root SSE/identity routes.
 *
 * Precondition: mount only inside an application that already rejects
 * non-loopback Host headers (BossTermMcpManager's app-level intercept).
 * The transports are built with `enableDnsRebindingProtection = false` on
 * that assumption — mounted elsewhere, /mcp would be open to DNS rebinding.
 */
internal fun Route.mountStreamableMcp(sessions: StreamableMcpSessions) {
    install(ContentNegotiation) { json(McpJson) }
    post { sessions.handlePost(call) }
    get { sessions.handleGet(call) }
    delete { sessions.handleDelete(call) }
}
