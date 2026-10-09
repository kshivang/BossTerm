package ai.rever.bossterm.app

import ai.rever.bossterm.compose.daemon.BossTermPaths
import ai.rever.bossterm.compose.daemon.DaemonAttachProtocol
import ai.rever.bossterm.compose.daemon.DaemonClient
import ai.rever.bossterm.compose.daemon.DaemonControlChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files

class DaemonConnectionTest {
    @Test
    fun olderDaemonWithCompatibleControlStartsLocalMcpFallback() {
        // Older releases have the same control version but omit attachProtocolVersion.
        withDaemonStatus("OK {\"pid\":1,\"version\":\"old\",\"protocolVersion\":1," +
            "\"uptimeMs\":0,\"sessionCount\":1,\"attachPort\":7682}") { client ->
            var localStarts = 0
            assertFalse(configureDaemonConnection(client) { localStarts++ })
            assertEquals(1, localStarts)
            assertEquals("PONG", client.request("PING"), "fallback must preserve the old daemon and its sessions")
        }
    }

    @Test
    fun unavailableAttachStartsLocalMcpFallback() {
        val version = DaemonAttachProtocol.PROTOCOL_VERSION
        withDaemonStatus("OK {\"pid\":1,\"version\":\"current\",\"protocolVersion\":1," +
            "\"uptimeMs\":0,\"sessionCount\":0,\"attachProtocolVersion\":$version}") { client ->
            var localStarts = 0
            assertFalse(configureDaemonConnection(client) { localStarts++ })
            assertEquals(1, localStarts)
        }
    }

    @Test
    fun compatibleAttachKeepsMcpInDaemon() {
        val version = DaemonAttachProtocol.PROTOCOL_VERSION
        withDaemonStatus("OK {\"pid\":1,\"version\":\"current\",\"protocolVersion\":1," +
            "\"uptimeMs\":0,\"sessionCount\":0,\"attachPort\":7682,\"attachProtocolVersion\":$version}") { client ->
            var localStarts = 0
            assertTrue(configureDaemonConnection(client) { localStarts++ })
            assertEquals(0, localStarts)
        }
    }

    @Test
    fun missingControlStartsLocalMcpFallback() {
        var localStarts = 0
        assertFalse(configureDaemonConnection(DaemonClient()) { localStarts++ })
        assertEquals(1, localStarts)
    }

    private fun withDaemonStatus(status: String, block: (DaemonClient) -> Unit) {
        val property = BossTermPaths.SETTINGS_DIR_PROPERTY
        val previous = System.getProperty(property)
        val dir = Files.createTempDirectory("bossterm-app-connection").toFile()
        System.setProperty(property, dir.absolutePath)
        val channel = DaemonControlChannel("test", DaemonControlChannel.PROTOCOL_VERSION) { _, _ -> status }
        try {
            channel.start()
            val client = DaemonClient()
            assertNotNull(client.ensureConnected(spawnIfAbsent = false))
            block(client)
        } finally {
            channel.stop()
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.deleteRecursively()
        }
    }
}
