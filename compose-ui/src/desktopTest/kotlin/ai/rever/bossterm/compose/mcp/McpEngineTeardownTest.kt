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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins two contracts of [BossTermMcpManager]'s engine teardown.
 *
 * **The guarded closeAll step in [BossTermMcpManager.stopRunningEngineLocked]**: when closing the
 * streamable sessions blows up (in production: NoClassDefFoundError, because the manager is being
 * torn down inside a classloader an embedding host is closing), the bookkeeping below the guard
 * must still run, so the registry never keeps reporting a running server that is gone.
 *
 * **[BossTermMcpManager.stop] is synchronous and bounded**: it must not return until the engine is
 * actually stopped — a fire-and-forget teardown is what let a `terminal-tab` hot-reload close the
 * plugin classloader out from under it — but it must never hang, because it runs from an
 * embedder's `dispose()`, possibly on the EDT.
 *
 * The real failure (a closed classloader) can't be produced in a unit test, so the close step is
 * substituted via [BossTermMcpManager.closeAllOverrideForTest] — which doubles as the seam for
 * making the teardown observably slow. The engine is a stub that was never started, so no port is
 * bound. The port marker is redirected into the temp settings dir
 * ([BossTermMcpManager.portMarkerFileOverrideForTest]) so the teardown's marker delete can't touch
 * a developer's real `~/.bossterm/mcp.port`.
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

    /** A never-started CIO server: enough to drive the teardown without binding a port. */
    private fun stubEngine() = embeddedServer(CIO, host = "127.0.0.1", port = 0) {}

    @AfterTest
    fun tearDown() {
        McpTerminalRegistry.setStopped()
        scope.cancel()
    }

    @Test
    fun `engine-stop bookkeeping survives a throwing closeAll`() = runBlocking {
        val manager = newManager()
        manager.runningEngine = stubEngine()
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

    /**
     * The regression. The override parks inside the teardown for [SLOW_TEARDOWN_MS]; a
     * fire-and-forget `stop()` cannot possibly have got past it by the time it returns, so the
     * flag is what distinguishes "teardown finished" from "teardown scheduled".
     */
    @Test
    fun `stop does not return until the engine teardown has finished`() {
        val manager = newManager()
        manager.runningEngine = stubEngine()
        McpTerminalRegistry.setRunning(7699)
        val teardownFinished = AtomicBoolean(false)
        manager.closeAllOverrideForTest = {
            delay(SLOW_TEARDOWN_MS)
            teardownFinished.set(true)
        }

        manager.stop()

        assertTrue(
            teardownFinished.get(),
            "stop() returned before the teardown finished - the embedder's classloader can " +
                "close under it"
        )
        assertNull(manager.runningEngine, "engine reference must be cleared by stop()")
        assertNull(McpTerminalRegistry.runningPort.value, "registry must report stopped after stop()")
    }

    /**
     * The other half of the contract: synchronous, but never a hang. A teardown that overruns is
     * abandoned (and logged at ERROR), because freezing an embedder's `dispose()` — on the EDT —
     * would be worse than the straggler it prevents.
     *
     * The teardown parks on a gate rather than a fixed `delay` for two reasons. The gate is
     * released in the `finally`, so the abandoned teardown finishes with the test instead of
     * lingering into the next class — this suite shares one JVM with PTY-backed daemon tests that
     * assert on output arriving within a poll window, and a straggler still holding the mutex and
     * doing registry/file bookkeeping several seconds later is enough to fail them. And the
     * watchdog releases it too, so a regression that drops the bound *fails* here rather than
     * hanging the build.
     */
    @Test
    fun `stop abandons a teardown that overruns its budget`() {
        val manager = newManager()
        manager.runningEngine = stubEngine()
        val release = CompletableDeferred<Unit>()
        manager.closeAllOverrideForTest = { release.await() }
        val watchdog = thread(isDaemon = true, name = "overrun-watchdog") {
            try {
                Thread.sleep(HANG_GUARD_MS)
                release.complete(Unit)
            } catch (_: InterruptedException) {
                // Released by the test instead; nothing to do.
            }
        }

        try {
            val elapsed = measureTimeMillis { manager.stop() }

            assertTrue(
                elapsed < ABANDON_CEILING_MS,
                "stop() must bound its wait, but blocked for ${elapsed}ms on a teardown that " +
                    "does not finish on its own"
            )
        } finally {
            release.complete(Unit)
            watchdog.interrupt()
        }
    }

    @Test
    fun `stop is idempotent`() {
        val manager = newManager()
        manager.runningEngine = stubEngine()
        McpTerminalRegistry.setRunning(7699)

        manager.stop()
        val secondCall = measureTimeMillis { manager.stop() } // must not throw

        assertNull(manager.runningEngine, "a second stop() must leave the manager torn down")
        assertNull(McpTerminalRegistry.runningPort.value, "a second stop() must leave the registry stopped")
        assertTrue(
            secondCall < IDLE_STOP_CEILING_MS,
            "a second stop() has nothing to do and must not wait for a budget, took ${secondCall}ms"
        )
    }

    @Test
    fun `stop on a manager that never started is a cheap no-op`() {
        val manager = newManager()

        val elapsed = measureTimeMillis { manager.stop() } // must not throw

        assertTrue(
            elapsed < IDLE_STOP_CEILING_MS,
            "stop() with nothing to tear down must not wait for a budget, took ${elapsed}ms"
        )
    }

    /**
     * Cancellation alone cannot close the window. `reconcile` reaches `startEngineLocked` through
     * `mutex.withLock`, whose uncontended fast path returns without checking cancellation, and
     * `startEngineLocked` then has no suspension point at all — so a cancelled collector can still
     * arrive holding a live intent to bind, right as the teardown frees the port. The latch is
     * what refuses it.
     *
     * Driven directly rather than through the settings watcher because that interleaving is a race
     * a unit test cannot schedule. [LATCH_TEST_PORT] is high and unregistered: if the latch ever
     * regresses, this binds a real loopback server there rather than colliding with a developer's
     * live BossTerm on 7676.
     */
    @Test
    fun `a bind is refused once stop has latched`() = runBlocking {
        val manager = newManager()
        manager.stop()

        try {
            manager.startEngineLocked(LATCH_TEST_PORT)

            assertNull(manager.runningEngine, "no engine may be bound after stop() has latched")
            assertNull(McpTerminalRegistry.runningPort.value, "a post-stop bind must not resurrect the registry")
        } finally {
            manager.stopRunningEngineLocked() // only reachable if the latch regressed
        }
    }

    private companion object {
        /** Long enough that a fire-and-forget teardown is unambiguously still in flight. */
        private const val SLOW_TEARDOWN_MS = 200L

        /**
         * Ceiling for a `stop()` whose teardown never finishes on its own. Comfortably above
         * `STOP_BUDGET_MS` so normal jitter can't fail it, and comfortably below [HANG_GUARD_MS]
         * so a dropped bound is unambiguous.
         */
        private const val ABANDON_CEILING_MS = 3_000L

        /** Backstop that releases a parked teardown, so a dropped bound fails instead of hanging. */
        private const val HANG_GUARD_MS = 6_000L

        /**
         * Ceiling for a `stop()` with nothing to do. Well under `STOP_BUDGET_MS`, so a regression
         * that makes the empty path wait out a budget fails; generous enough for a cold thread
         * spawn on a loaded CI box.
         */
        private const val IDLE_STOP_CEILING_MS = 700L

        /** Unassigned high port; only ever bound if the stop latch regresses. */
        private const val LATCH_TEST_PORT = 47811
    }
}
