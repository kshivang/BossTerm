package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.SettingsManager
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Pins the contract of the guarded closeAll step in
 * [BossTermMcpManager.stopRunningEngineLocked]: when closing the streamable
 * sessions blows up (in production: NoClassDefFoundError, because the engine
 * teardown runs on a background coroutine after an embedding host's plugin
 * hot-swap has closed this classloader — see [BossTermMcpManager.stop]), the
 * bookkeeping below the guard must still run, so the registry never keeps
 * reporting a running server that is gone.
 *
 * The real failure (a closed classloader) can't be produced in a unit test,
 * so the close step is substituted via
 * [BossTermMcpManager.closeAllOverrideForTest], and the engine is a stub that
 * was never started so no port is bound. The port marker is redirected into
 * the temp settings dir ([BossTermMcpManager.portMarkerFileOverrideForTest])
 * so the teardown's marker delete can't touch a developer's real
 * `~/.bossterm/mcp.port`.
 */
class McpEngineTeardownTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val settingsDir = createTempDirectory("mcp-teardown-test").toFile().apply { deleteOnExit() }

    private fun newManager() = BossTermMcpManager(
        registry = McpTerminalRegistry,
        settingsManager = SettingsManager(File(settingsDir, "settings.json").absolutePath),
        parentScope = scope,
    ).apply {
        portMarkerFileOverrideForTest = File(settingsDir, "mcp.port")
    }

    @AfterTest
    fun tearDown() {
        McpTerminalRegistry.setStopped()
        scope.cancel()
    }

    @Test
    fun `engine-stop bookkeeping survives a throwing closeAll`() = runBlocking {
        val manager = newManager()
        manager.runningEngine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {}
        McpTerminalRegistry.setRunning(7699)
        manager.closeAllOverrideForTest = {
            throw NoClassDefFoundError("ai/rever/bossterm/compose/mcp/StreamableMcpSessions\$closeAll\$1")
        }

        manager.stopRunningEngineLocked() // must not throw

        assertNull(
            manager.runningEngine,
            "engine reference must be cleared even when closeAll throws"
        )
        assertNull(
            McpTerminalRegistry.runningPort.value,
            "registry must report stopped even when closeAll throws"
        )
    }
}
