package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase-1 verification, fully headless: the daemon's [SessionHost] + [DaemonControlHandler] can open
 * a real PTY session, run a command, list/close it; and [DaemonClient] performs discovery + the HELLO
 * handshake against a live in-process [DaemonControlChannel] (no JVM spawn, no GUI).
 */
class DaemonSessionHostTest {

    @Test
    fun `handler opens, lists, and closes a real session`() {
        if (ShellCustomizationUtils.isWindows()) return

        val host = SessionHost(TerminalSettings.DEFAULT)
        val handler = DaemonControlHandler(
            sessionHost = host, version = "9.9.9", protocolVersion = 1,
            uptimeMs = { 0 }, mcpPort = { null }, onShutdown = { },
        )
        try {
            val marker = "HOST_OK_7777"
            val req = DaemonProtocol.OpenSessionRequest(
                command = "/bin/sh",
                arguments = listOf("-c", "printf '%s\\n' $marker; sleep 0.3"),
            )
            val resp = handler.handle(DaemonProtocol.OPEN_SESSION, DaemonProtocol.json.encodeToString(DaemonProtocol.OpenSessionRequest.serializer(), req))
            assertTrue(resp.startsWith("OK "), "open should succeed: $resp")
            val id = DaemonProtocol.json.decodeFromString(
                DaemonProtocol.OpenSessionResponse.serializer(), resp.removePrefix("OK ")
            ).id

            val core = host.get(id)
            assertNotNull(core, "session should be registered")
            assertTrue(awaitContains(core) { it.contains(marker) }, "command output should reach the buffer")

            // LIST_SESSIONS includes the session.
            val list = handler.handle(DaemonProtocol.LIST_SESSIONS, "")
            assertTrue(list.contains(id), "list should include the session id")

            assertEquals("OK", handler.handle(DaemonProtocol.CLOSE_SESSION, id))
            assertEquals(0, host.count(), "closed session is removed")
        } finally {
            host.shutdownAll()
        }
    }

    @Test
    fun `DaemonClient discovers and handshakes a live in-process daemon`() {
        val prev = System.getProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        val dir = java.nio.file.Files.createTempDirectory("bossterm-daemon-client").toFile().absolutePath
        System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, dir)

        val host = SessionHost(TerminalSettings.DEFAULT)
        val handler = DaemonControlHandler(
            sessionHost = host, version = "9.9.9",
            protocolVersion = DaemonControlChannel.PROTOCOL_VERSION,
            uptimeMs = { 123 }, mcpPort = { 7676 }, onShutdown = { },
        )
        val channel = DaemonControlChannel("9.9.9", DaemonControlChannel.PROTOCOL_VERSION, handler::handle)
        try {
            channel.start()

            val client = DaemonClient()
            // Must NOT spawn — a live daemon (our channel) is already discoverable.
            val ep = client.ensureConnected(spawnIfAbsent = false)
            assertNotNull(ep, "client should discover the running in-process daemon")
            assertEquals(DaemonControlChannel.PROTOCOL_VERSION, ep.protocolVersion)

            val status = client.request(DaemonProtocol.STATUS)
            assertNotNull(status)
            assertTrue(status.startsWith("OK "), "STATUS should return OK json: $status")
            assertTrue(status.contains("\"mcpPort\":7676"), "status payload should carry mcpPort")
        } finally {
            channel.stop()
            host.shutdownAll()
            if (prev != null) System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, prev)
            else System.clearProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        }
    }

    private fun awaitContains(
        core: TerminalSessionCore,
        timeoutMs: Long = 8000,
        predicate: (String) -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate(core.textBuffer.getScreenLines())) return true
            Thread.sleep(50)
        }
        return false
    }
}
