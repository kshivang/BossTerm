package ai.rever.bossterm.compose.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract test for the streamable HTTP MCP endpoint ([StreamableMcpSessions]
 * mounted via [mountStreamableMcp]) — the transport Codex speaks. Exercises
 * the real SDK transport + a real (tool-less) SDK [Server] through an
 * in-process ktor app, so it locks in the session lifecycle AND proves the
 * SDK-0.8.3 handler surface actually works end-to-end (the registration-URL
 * tests can't catch a handler-path or serialization regression).
 */
class StreamableMcpSessionsTest {

    private var now: Long = 0L
    private lateinit var server: Server
    private lateinit var sessions: StreamableMcpSessions

    private fun freshSessions(maxSessions: Int = StreamableMcpSessions.DEFAULT_MAX_SESSIONS): StreamableMcpSessions {
        now = 0L
        server = Server(
            serverInfo = Implementation(name = "test-server", version = "0.0.1"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
            )
        )
        sessions = StreamableMcpSessions(server, maxSessions) { now }
        return sessions
    }

    private fun ApplicationTestBuilder.mountEndpoint() {
        application {
            routing {
                route("/mcp") { mountStreamableMcp(sessions) }
            }
        }
    }

    private suspend fun HttpClient.mcpPost(body: String, sessionId: String? = null): HttpResponse =
        post("/mcp") {
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            sessionId?.let { header(StreamableMcpSessions.SESSION_ID_HEADER, it) }
            setBody(body)
        }

    private suspend fun HttpClient.initializeSession(): String {
        val response = mcpPost(INITIALIZE)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return assertNotNull(
            response.headers[StreamableMcpSessions.SESSION_ID_HEADER],
            "initialize response must carry a session id"
        )
    }

    @Test
    fun `initialize mints a session and returns its id`() = testApplication {
        freshSessions()
        mountEndpoint()

        val response = client.mcpPost(INITIALIZE)

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertNotNull(response.headers[StreamableMcpSessions.SESSION_ID_HEADER])
        assertTrue(response.bodyAsText().contains("serverInfo"))
        assertEquals(1, sessions.sessionCount)
        assertEquals(1, server.sessions.size)
    }

    @Test
    fun `established session serves requests`() = testApplication {
        freshSessions()
        mountEndpoint()
        val sessionId = client.initializeSession()

        val response = client.mcpPost(TOOLS_LIST, sessionId)

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("\"tools\""))
    }

    @Test
    fun `non-initialize post without session id is rejected and leaks no session`() = testApplication {
        freshSessions()
        mountEndpoint()

        val response = client.mcpPost(TOOLS_LIST)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, sessions.sessionCount)
        // The speculative transport's ServerSession must be torn down again —
        // otherwise every stray POST parks one in the shared Server forever.
        assertEquals(0, server.sessions.size)
    }

    @Test
    fun `malformed body without session id is rejected and leaks no session`() = testApplication {
        freshSessions()
        mountEndpoint()

        val response = client.mcpPost("""{"jsonrpc": garbage""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, sessions.sessionCount)
        assertEquals(0, server.sessions.size)
    }

    @Test
    fun `established session survives a failed request`() = testApplication {
        freshSessions()
        mountEndpoint()
        val sessionId = client.initializeSession()

        // Deliberate contract: a single bad request must not tear the
        // session down — only DELETE, idle eviction, or engine stop do.
        assertEquals(HttpStatusCode.BadRequest, client.mcpPost("""{"jsonrpc": garbage""", sessionId).status)

        assertEquals(1, sessions.sessionCount)
        assertEquals(1, server.sessions.size)
        assertEquals(HttpStatusCode.OK, client.mcpPost(TOOLS_LIST, sessionId).status)
    }

    @Test
    fun `unknown session id gets 404 so the client re-initializes`() = testApplication {
        freshSessions()
        mountEndpoint()

        assertEquals(HttpStatusCode.NotFound, client.mcpPost(TOOLS_LIST, "no-such-session").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/mcp") {
            header(StreamableMcpSessions.SESSION_ID_HEADER, "no-such-session")
        }.status)
    }

    @Test
    fun `get is answered with 405 not a hung stream`() = testApplication {
        freshSessions()
        mountEndpoint()

        val response = client.get("/mcp")

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals("POST, DELETE", response.headers[HttpHeaders.Allow])
    }

    @Test
    fun `delete without session id is a 400`() = testApplication {
        freshSessions()
        mountEndpoint()

        assertEquals(HttpStatusCode.BadRequest, client.delete("/mcp").status)
    }

    @Test
    fun `delete tears the session down everywhere`() = testApplication {
        freshSessions()
        mountEndpoint()
        val sessionId = client.initializeSession()

        val response = client.delete("/mcp") {
            header(StreamableMcpSessions.SESSION_ID_HEADER, sessionId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, sessions.sessionCount)
        assertEquals(0, server.sessions.size)
        // The id is gone for good — a returning client must re-initialize.
        assertEquals(HttpStatusCode.NotFound, client.mcpPost(TOOLS_LIST, sessionId).status)
    }

    @Test
    fun `idle sessions are evicted, active ones survive`() = testApplication {
        freshSessions()
        mountEndpoint()
        val stale = client.initializeSession()
        now = 10_000L
        val fresh = client.initializeSession()
        assertEquals(2, sessions.sessionCount)

        val evicted = sessions.evictIdle(idleTtlMs = 5_000L)

        assertEquals(1, evicted)
        assertEquals(1, sessions.sessionCount)
        assertEquals(1, server.sessions.size)
        assertEquals(HttpStatusCode.NotFound, client.mcpPost(TOOLS_LIST, stale).status)
        assertEquals(HttpStatusCode.OK, client.mcpPost(TOOLS_LIST, fresh).status)
    }

    @Test
    fun `session cap evicts the longest-idle session`() = testApplication {
        freshSessions(maxSessions = 2)
        mountEndpoint()
        val oldest = client.initializeSession()
        now = 1_000L
        val middle = client.initializeSession()
        now = 2_000L
        val newest = client.initializeSession()

        assertEquals(2, sessions.sessionCount)
        assertEquals(2, server.sessions.size)
        assertEquals(HttpStatusCode.NotFound, client.mcpPost(TOOLS_LIST, oldest).status)
        assertEquals(HttpStatusCode.OK, client.mcpPost(TOOLS_LIST, middle).status)
        assertEquals(HttpStatusCode.OK, client.mcpPost(TOOLS_LIST, newest).status)
    }

    @Test
    fun `session cap never evicts the just-initialized session on a timestamp tie`() = testApplication {
        freshSessions(maxSessions = 1)
        mountEndpoint()
        // Both sessions initialize at now = 0: with millisecond granularity a
        // burst produces ties, and the newcomer must win its own tie-break.
        val first = client.initializeSession()
        val second = client.initializeSession()

        assertEquals(1, sessions.sessionCount)
        assertEquals(HttpStatusCode.NotFound, client.mcpPost(TOOLS_LIST, first).status)
        assertEquals(HttpStatusCode.OK, client.mcpPost(TOOLS_LIST, second).status)
    }

    @Test
    fun `closeAll empties both registries`() = testApplication {
        freshSessions()
        mountEndpoint()
        client.initializeSession()
        client.initializeSession()
        assertEquals(2, sessions.sessionCount)

        sessions.closeAll()

        assertEquals(0, sessions.sessionCount)
        assertEquals(0, server.sessions.size)
    }

    private companion object {
        const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}"""
        const val TOOLS_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
    }
}
