package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless verification of the daemon MCP tool logic ([DaemonMcpTools]) against a real PTY-backed
 * [SessionHost]: read_scrollback returns rendered output, send_input round-trips, list/close work.
 * Proves "agent-driven daemon sessions" end to end without an MCP client or the GUI.
 */
class DaemonMcpToolsTest {

    @Test
    fun `read_scrollback returns rendered command output`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        val tools = DaemonMcpTools(host)
        try {
            val marker = "MCP_TOOLS_OK_5150"
            val id = host.openSession(command = "/bin/sh", arguments = listOf("-c", "printf '%s\\n' $marker; sleep 0.4"))

            assertTrue(awaitTool(timeoutMs = 8000) {
                tools.readScrollback(buildJsonObject { put("session_id", id) }).contains(marker)
            }, "read_scrollback should return the command output")

            assertTrue(tools.listSessions().contains(id), "list_sessions should include the session")
        } finally {
            host.shutdownAll()
        }
    }

    @Test
    fun `send_input round-trips through a cat session`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        val tools = DaemonMcpTools(host)
        try {
            val marker = "INPUT_RT_8042"
            val id = host.openSession(command = "/bin/cat", arguments = emptyList())
            // Wait until the PTY is actually spawned before writing (else early input is dropped).
            assertTrue(awaitTool(timeoutMs = 5000) { host.get(id)?.isAlive() == true }, "session should come alive")
            Thread.sleep(150)
            val resp = tools.sendInput(buildJsonObject { put("session_id", id); put("text", "$marker\n") })
            assertTrue(resp.contains("\"ok\":true"), "send_input should succeed: $resp")
            assertTrue(awaitTool(timeoutMs = 8000) {
                tools.readScrollback(buildJsonObject { put("session_id", id) }).contains(marker)
            }, "cat should echo the input back, visible in read_scrollback")

            // Closing removes it from list.
            tools.closeSession(buildJsonObject { put("session_id", id) })
            assertTrue(!tools.listSessions().contains(id), "closed session should be gone from list")
        } finally {
            host.shutdownAll()
        }
    }

    @Test
    fun `unknown session ids return an error payload`() {
        val tools = DaemonMcpTools(SessionHost(TerminalSettings.DEFAULT))
        val r = tools.readScrollback(buildJsonObject { put("session_id", "nope") })
        assertTrue(r.contains("\"error\""), "unknown session should yield an error: $r")
    }

    private fun awaitTool(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return false
    }
}
