package ai.rever.bossterm.compose.mcp

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpPoliteReattachTest {

    // ------------------------------------------------------------------
    // Default-port parsing of registered URLs
    // ------------------------------------------------------------------

    @Test
    fun `defaultPortOf handles env-expanded plain and pathed urls`() {
        assertEquals(7677, McpRegistrationScanner.defaultPortOf("http://127.0.0.1:\${BOSS_MCP_PORT:-7677}"))
        assertEquals(7676, McpRegistrationScanner.defaultPortOf("http://127.0.0.1:7676"))
        assertEquals(7677, McpRegistrationScanner.defaultPortOf("http://localhost:7677/sse"))
        assertEquals(7677, McpRegistrationScanner.defaultPortOf("http://127.0.0.1:7677?session=1"))
        assertEquals(7677, McpRegistrationScanner.defaultPortOf("http://127.0.0.1:7677#frag"))
        assertNull(McpRegistrationScanner.defaultPortOf("http://127.0.0.1"))
    }

    @Test
    fun `registeredDefaultPort reads the claude entry from disk`() {
        val home = createTempDirectory("polite-test-home").toFile().apply { deleteOnExit() }
        val f = File(home, ".claude.json")
        f.writeText("""{"mcpServers": {"boss": {"type": "sse", "url": "http://127.0.0.1:${'$'}{BOSS_MCP_PORT:-7679}"}}}""")
        f.deleteOnExit()
        assertEquals(7679, McpRegistrationScanner.registeredDefaultPort(McpAttachTarget.CLAUDE_CODE, "boss", home))
        // No entry for a different name; no entry at all for other CLIs.
        assertNull(McpRegistrationScanner.registeredDefaultPort(McpAttachTarget.CLAUDE_CODE, "bossterm", home))
        assertNull(McpRegistrationScanner.registeredDefaultPort(McpAttachTarget.GEMINI, "boss", home))
    }

    // ------------------------------------------------------------------
    // Rewrite decision
    // ------------------------------------------------------------------

    @Test
    fun `shouldRewrite truth table`() {
        // No parseable registration — nothing to be polite to.
        assertTrue(McpInstanceProbe.shouldRewrite(null, 7677, "boss", null))
        // Registered port is ours — idempotent refresh.
        assertTrue(McpInstanceProbe.shouldRewrite(7677, 7677, "boss", null))
        // Live sibling with our name owns the registered port — keep it.
        assertFalse(McpInstanceProbe.shouldRewrite(7677, 7679, "boss", "boss"))
        // Dead port — claim.
        assertTrue(McpInstanceProbe.shouldRewrite(7677, 7679, "boss", null))
        // Foreign live server (different name) — claim; the entry is named
        // after US, so pointing it at another app's server is a misconfig.
        assertTrue(McpInstanceProbe.shouldRewrite(7676, 7679, "boss", "bossterm"))
    }

    // ------------------------------------------------------------------
    // Live identity probe against a real loopback HTTP server
    // ------------------------------------------------------------------

    @Test
    fun `liveServerName reads identity and fails safe`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext(McpInstanceProbe.IDENTITY_PATH) { exchange ->
                val body = """{"serverName":"boss","pid":12345}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.createContext("/other") { exchange ->
                exchange.sendResponseHeaders(404, -1)
            }
            server.start()
            val port = server.address.port

            assertEquals("boss", McpInstanceProbe.liveServerName(port))
        } finally {
            server.stop(0)
        }

        // After stop, the same port is dead — probe must return null, fast.
        assertNull(McpInstanceProbe.liveServerName(server.address.port, timeoutMs = 500))
    }

    @Test
    fun `a live foreign-named server results in a claim end-to-end`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext(McpInstanceProbe.IDENTITY_PATH) { exchange ->
                val body = """{"serverName":"bossterm","pid":99}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val registeredPort = server.address.port

            val owner = McpInstanceProbe.liveServerName(registeredPort)
            assertEquals("bossterm", owner)
            // A 'boss' instance probing a live 'bossterm' owner must still
            // claim the entry — it is named after US, not the other app.
            assertTrue(McpInstanceProbe.shouldRewrite(registeredPort, 7679, "boss", owner))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `oversized identity bodies are read bounded and rejected`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext(McpInstanceProbe.IDENTITY_PATH) { exchange ->
                // Chunked (length 0): stream far more than the probe's cap.
                // The probe must stop reading at its fixed buffer, not
                // materialize the whole body. Broken pipe when the client
                // bails early is expected — swallow it.
                try {
                    exchange.sendResponseHeaders(200, 0)
                    val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
                    exchange.responseBody.use { out ->
                        repeat(160) { out.write(chunk) } // ~10 MB offered
                    }
                } catch (_: Throwable) {
                    // client disconnected after its bounded read — fine
                }
            }
            server.start()
            assertNull(McpInstanceProbe.liveServerName(server.address.port))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `liveServerName rejects malformed and non-200 responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext(McpInstanceProbe.IDENTITY_PATH) { exchange ->
                val body = "not json at all".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            assertNull(McpInstanceProbe.liveServerName(server.address.port))
        } finally {
            server.stop(0)
        }
    }
}
