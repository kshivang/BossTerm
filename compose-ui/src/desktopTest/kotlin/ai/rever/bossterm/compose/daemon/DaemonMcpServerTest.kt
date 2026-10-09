package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.mcp.InProcessClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transport-level guards for [DaemonMcpServer.start]: it binds a loopback SSE port, writes the bound
 * port atomically to `mcp.port` (the marker the Claude Code hook/CLI read), enforces the
 * DNS-rebinding Host guard (non-loopback Host → 403), lets loopback Hosts through, and tears the
 * marker down on stop. Tool LOGIC is covered separately by DaemonMcpToolsTest.
 */
class DaemonMcpServerTest {

    @Test
    fun `binds loopback, writes mcp_port atomically, guards Host header, cleans up on stop`() = withSettingsDir {
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonMcpServer(host)
        // A high base port unlikely to collide with a real dev daemon's default (7682).
        val port = server.start(desiredPort = 17_777)
        assertNotNull(port, "MCP server must bind a loopback port")
        assertEquals(port, server.boundPort)
        awaitAccepting(port)

        // mcp.port marker carries exactly the bound port.
        val marker = BossTermPaths.mcpPortFile()
        assertTrue(marker.exists(), "mcp.port marker must be written")
        assertEquals(port.toString(), marker.readText().trim())

        // DNS-rebinding defense: a non-loopback Host header is forbidden, regardless of path.
        assertEquals(403, httpStatus(port, "/__probe__", hostHeader = "evil.example.com"))

        // A loopback Host passes the guard (the route then 404s — the point is it is NOT 403).
        val loopbackStatus = httpStatus(port, "/__probe__", hostHeader = "127.0.0.1:$port")
        assertTrue(loopbackStatus != 403, "loopback Host must pass the guard (was $loopbackStatus)")

        server.stop()
        assertNull(server.boundPort)
        assertFalse(marker.exists(), "stop() must remove the mcp.port marker")
    }

    @Test
    fun `mcp_port marker is gated on shouldWriteMarker (preferred-shell opt-in)`() = withSettingsDir {
        val host = SessionHost(TerminalSettings.DEFAULT)
        var preferred = false
        val server = DaemonMcpServer(host, shouldWriteMarker = { preferred })
        val marker = BossTermPaths.mcpPortFile()
        try {
            val port = server.start(desiredPort = 17_797)
            assertNotNull(port, "MCP server must bind regardless of the marker gate")
            awaitAccepting(port)
            // Opt-in is OFF → the server is up but the PreToolUse-hook marker must NOT be written.
            assertFalse(marker.exists(), "marker must not be written when preferred-shell is off")

            // Toggle the opt-in ON and re-sync → marker appears, carrying the bound port.
            preferred = true
            server.syncPortMarker()
            assertTrue(marker.exists(), "syncPortMarker must write the marker once opted in")
            assertEquals(port.toString(), marker.readText().trim())

            // Toggle back OFF and re-sync → marker is removed.
            preferred = false
            server.syncPortMarker()
            assertFalse(marker.exists(), "syncPortMarker must remove the marker when opted out")
        } finally {
            server.stop()
        }
    }


    @Test
    fun `an unstarted or stopped server does not erase another servers marker`() = withSettingsDir {
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonMcpServer(host)
        val marker = BossTermPaths.mcpPortFile()
        marker.parentFile.mkdirs()
        marker.writeText("18999")
        server.syncPortMarker()
        server.stop()
        assertEquals("18999", marker.readText())
        try {
            val port = assertNotNull(server.start(18_777))
            assertEquals(port, server.start(18_797), "second start must reuse the current listener")
            marker.writeText("18999")
            server.stop()
            server.syncPortMarker()
            assertEquals("18999", marker.readText())
        } finally {
            server.stop()
            host.shutdownAll()
        }
    }

    @Test
    fun `invalid requested ports do not create an unusable marker`() = withSettingsDir {
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonMcpServer(host)
        try {
            assertNull(server.start(0))
            assertNull(server.start(-1))
            assertNull(server.start(65536))
            assertFalse(BossTermPaths.mcpPortFile().exists())
        } finally {
            server.stop()
            host.shutdownAll()
        }
    }


    @Test
    fun `disabled tools are omitted and already advertised handlers reject live disable`() = runBlocking {
        val host = SessionHost(TerminalSettings.DEFAULT)
        var disabled = emptySet<String>()
        val wrapper = DaemonMcpServer(host, disabledTools = { disabled })
        val server = wrapper.createServer()
        try {
            val tool = assertNotNull(server.tools["send_input"])
            val invalid = tool.handler(InProcessClientConnection,
                CallToolRequest(CallToolRequestParams(name = "send_input")))
            assertEquals(true, invalid.isError, "argument failures must be MCP errors")
            disabled = setOf("send_input", "open_session")
            val blocked = tool.handler(InProcessClientConnection,
                CallToolRequest(CallToolRequestParams(name = "send_input")))
            assertEquals(true, blocked.isError)
            val refreshed = wrapper.createServer()
            try {
                assertFalse(refreshed.tools.containsKey("send_input"))
                assertFalse(refreshed.tools.containsKey("open_session"))
                assertTrue(refreshed.tools.containsKey("list_sessions"))
            } finally {
                refreshed.close()
            }
        } finally {
            server.close()
            host.shutdownAll()
        }
    }

    // ---- helpers ----

    /** Wait until the server is actually accepting connections (start(wait=false) binds async). */
    private fun awaitAccepting(port: Int, timeoutMs: Long = 3_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val ok = runCatching { Socket(InetAddress.getLoopbackAddress(), port).close(); true }.getOrDefault(false)
            if (ok) return
            runCatching { Thread.sleep(25) }
        }
    }

    /** Raw HTTP/1.1 GET with an explicit Host header; returns the status code (or -1). */
    private fun httpStatus(port: Int, path: String, hostHeader: String): Int =
        runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3_000
                s.getOutputStream().apply {
                    write("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
                val statusLine = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine()
                    ?: return -1
                // e.g. "HTTP/1.1 403 Forbidden"
                statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            }
        }.getOrDefault(-1)

    private fun newTempDir(): String =
        java.nio.file.Files.createTempDirectory("bossterm-mcp").toFile().absolutePath

    private fun <T> withSettingsDir(block: () -> T): T {
        val prev = System.getProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, newTempDir())
        try {
            return block()
        } finally {
            if (prev != null) System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, prev)
            else System.clearProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        }
    }
}
