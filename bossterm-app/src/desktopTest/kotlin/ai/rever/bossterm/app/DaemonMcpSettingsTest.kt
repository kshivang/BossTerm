package ai.rever.bossterm.app

import ai.rever.bossterm.compose.daemon.BossTermPaths
import ai.rever.bossterm.compose.daemon.DaemonColorSettings
import ai.rever.bossterm.compose.daemon.DaemonMcpServer
import ai.rever.bossterm.compose.daemon.SessionHost
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DaemonMcpSettingsTest {
    @Test
    fun persistedGuiChangesUpdateDaemonMarkerAndCatalogWithoutChangingDaemonFlow() = runBlocking {
        val property = BossTermPaths.SETTINGS_DIR_PROPERTY
        val previous = System.getProperty(property)
        val dir = Files.createTempDirectory("daemon-mcp-settings").toFile()
        System.setProperty(property, dir.absolutePath)
        val file = dir.resolve("settings.json")
        val gui = SettingsManager(file.path)
        gui.updateSettings(TerminalSettings.DEFAULT.copy(mcpRunCommandPreferredShell = false, disabledMcpTools = emptySet()))
        // Independent managers model the two processes: a GUI write cannot update the daemon flow.
        val daemon = SettingsManager(file.path)
        val persisted = DaemonColorSettings(daemon)
        val host = SessionHost(daemon.settings.value)
        val server = DaemonMcpServer(
            host,
            shouldWriteMarker = { persisted.current().mcpRunCommandPreferredShell },
            disabledTools = { persisted.current().disabledMcpTools },
        )
        val clientServer = server.createServer()
        val changes = CopyOnWriteArrayList<Pair<Boolean, Set<String>>>()
        var watcher: kotlinx.coroutines.Job? = null
        try {
            val port = assertNotNull(server.start(ServerSocket(0).use { it.localPort }))
            val marker = BossTermPaths.mcpPortFile()
            watcher = launch {
                daemonMcpSettingsChanges(persisted::current, pollIntervalMs = 20).collect {
                    server.syncPortMarker()
                    server.syncDisabledTools()
                    changes.add(it)
                }
            }
            await { changes.size == 1 }
            assertFalse(marker.exists())
            assertTrue(clientServer.tools.containsKey("send_input"))

            gui.updateSetting { copy(mcpRunCommandPreferredShell = true, disabledMcpTools = setOf("send_input")) }
            await { changes.lastOrNull() == (true to setOf("send_input")) }
            assertEquals(port.toString(), marker.readText())
            assertFalse(clientServer.tools.containsKey("send_input"), "already connected clients must lose disabled tools")
            assertFalse(daemon.settings.value.mcpRunCommandPreferredShell)
            assertTrue(daemon.settings.value.disabledMcpTools.isEmpty())

            file.writeText("temporarily unreadable settings")
            delay(80)
            assertEquals(2, changes.size, "a failed read must retain the last valid persisted settings")
            assertTrue(marker.exists())

            gui.updateSetting { copy(mcpRunCommandPreferredShell = false, disabledMcpTools = emptySet()) }
            await { changes.size == 3 }
            assertEquals(false to emptySet(), changes.last())
            assertFalse(marker.exists())
            assertTrue(clientServer.tools.containsKey("send_input"), "re-enabling must restore tools without reconnect")

            watcher.cancelAndJoin()
            val count = changes.size
            gui.updateSetting { copy(mcpRunCommandPreferredShell = true) }
            delay(80)
            assertEquals(count, changes.size, "daemon shutdown must cancel polling")
            assertFalse(marker.exists())
        } finally {
            watcher?.cancelAndJoin()
            clientServer.close()
            server.stop()
            host.close()
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.deleteRecursively()
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(5000) {
        while (!predicate()) delay(10)
    }
}
