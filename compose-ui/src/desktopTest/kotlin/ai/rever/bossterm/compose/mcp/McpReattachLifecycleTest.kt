package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the lifecycle contract of the auto-reattach fan-out job: it must not
 * outlive [BossTermMcpManager.stop] (an orphaned fan-out that keeps running
 * after an embedding host unloads this classloader — BOSS plugin hot-swap —
 * crashes with NoClassDefFoundError on its next lazy class load), and a rebind
 * must supersede the previous in-flight fan-out so two fan-outs never race
 * each other's CLI-config rewrites.
 *
 * The fan-out body is replaced via [BossTermMcpManager.reattachBodyOverrideForTest]:
 * the real body shells out `<cli> mcp add` against the developer's actual CLI
 * configs, which a unit test must never do.
 */
class McpReattachLifecycleTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val settingsDir = createTempDirectory("mcp-reattach-test").toFile().apply { deleteOnExit() }

    private fun newManager() = BossTermMcpManager(
        registry = McpTerminalRegistry,
        settingsManager = SettingsManager(File(settingsDir, "settings.json").absolutePath),
        parentScope = scope,
    )

    @AfterTest
    fun tearDown() {
        McpTerminalRegistry.markDetached(McpAttachTarget.CLAUDE_CODE)
        scope.cancel()
    }

    @Test
    fun `stop cancels an in-flight reattach fan-out`() = runBlocking {
        val manager = newManager()
        McpTerminalRegistry.markAttached(McpAttachTarget.CLAUDE_CODE)
        val gate = CompletableDeferred<Unit>() // never completed — holds the fan-out open
        manager.reattachBodyOverrideForTest = { gate.await() }

        manager.launchAutoReattach(port = 7699)
        val job = manager.reattachJob
        assertNotNull(job)
        assertTrue(job.isActive)

        manager.stop()

        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled, "stop() must cancel the in-flight fan-out")
    }

    @Test
    fun `a rebind supersedes the previous in-flight fan-out`() = runBlocking {
        val manager = newManager()
        McpTerminalRegistry.markAttached(McpAttachTarget.CLAUDE_CODE)
        val gate = CompletableDeferred<Unit>()
        manager.reattachBodyOverrideForTest = { gate.await() }

        manager.launchAutoReattach(port = 7699)
        val first = manager.reattachJob
        assertNotNull(first)

        manager.launchAutoReattach(port = 7700)
        val second = manager.reattachJob
        assertNotNull(second)

        withTimeout(5_000) { first.join() }
        assertTrue(first.isCancelled, "the newer fan-out must cancel the previous one")
        assertTrue(second.isActive)

        manager.stop()
        withTimeout(5_000) { second.join() }
        assertTrue(second.isCancelled)
    }
}
