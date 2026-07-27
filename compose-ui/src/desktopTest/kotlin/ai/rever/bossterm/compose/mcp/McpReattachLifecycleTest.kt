package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.SettingsManager
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
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
    ).apply {
        // Redirected so a teardown that reaches the marker delete can't touch a developer's real
        // `~/.bossterm/mcp.port`.
        portMarkerFileOverrideForTest = File(settingsDir, "mcp.port")
    }

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

    /**
     * The tighter form of the first test: not just "gets cancelled eventually", but "is already
     * cancelled AND finished unwinding by the time [BossTermMcpManager.stop] returns, and never
     * resumes". That is the property an embedder needs — `dispose()` returning is the moment the
     * host is free to close this classloader, and anything that wakes up afterwards faults on it.
     *
     * Deliberately not wrapped in `runBlocking`: `stop()` is called from a plain thread, the way
     * an embedder's `dispose()` calls it.
     */
    @Test
    fun `in-flight work is cancelled and cannot resume after stop returns`() {
        val manager = newManager()
        McpTerminalRegistry.markAttached(McpAttachTarget.CLAUDE_CODE)
        val entered = CountDownLatch(1)
        val resumed = AtomicBoolean(false)
        manager.reattachBodyOverrideForTest = {
            entered.countDown()
            delay(30_000)     // parked; this is where cancellation lands
            resumed.set(true) // must never run
        }

        manager.launchAutoReattach(port = 7699)
        val job = assertNotNull(manager.reattachJob)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the fan-out never started")

        manager.stop()

        assertTrue(job.isCancelled, "stop() must cancel the in-flight fan-out")
        assertTrue(
            job.isCompleted,
            "stop() must not return while cancelled work is still unwinding — the host closes " +
                "the classloader the moment dispose() returns"
        )
        assertFalse(resumed.get(), "cancelled work must not resume after stop() returned")
    }

    /**
     * Pins the *ordering*, not just the fact: in-flight work is cancelled **before** the engine
     * teardown, never after. The engine stop is the widest part of `stop()` and it ends by freeing
     * the port, so cancelling after it would leave exactly the window being closed wide open — a
     * straggler waking during the stop could reconcile its way into a fresh bind behind a manager
     * that has already declared itself down.
     *
     * `closeAllOverrideForTest` is the observation point because it runs *inside*
     * `stopRunningEngineLocked`: whatever it can see about the fan-out is what the teardown saw.
     */
    @Test
    fun `in-flight work is cancelled before the engine teardown runs`() {
        val manager = newManager()
        McpTerminalRegistry.markAttached(McpAttachTarget.CLAUDE_CODE)
        val entered = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>() // never completed — holds the fan-out open
        manager.reattachBodyOverrideForTest = { entered.countDown(); gate.await() }

        manager.launchAutoReattach(port = 7699)
        val job = assertNotNull(manager.reattachJob)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the fan-out never started")

        val cancelledBeforeTeardown = AtomicBoolean(false)
        manager.runningEngine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {}
        manager.closeAllOverrideForTest = { cancelledBeforeTeardown.set(job.isCancelled) }

        manager.stop()

        assertTrue(
            cancelledBeforeTeardown.get(),
            "the fan-out was still live when the engine teardown ran — cancellation must come first"
        )
    }
}
