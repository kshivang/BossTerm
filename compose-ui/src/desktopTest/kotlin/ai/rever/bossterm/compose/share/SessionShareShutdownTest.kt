package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shutdown contract for [SessionShareManager] — specifically the window in which work that was
 * already in flight when [SessionShareManager.shutdown] ran could come back and re-bind the share
 * server behind a manager that had already declared itself down.
 *
 * The vehicle is `prewarmRemote()`: the settings watcher launches it **on the manager's own scope**
 * (not as a child of the watcher job), and it parks for up to 5 seconds waiting for the BossTerm MCP
 * server to claim its port before binding. `shutdown()` used to cancel only the watcher, so a
 * pre-warm parked in that wait woke up afterwards and bound a fresh Ktor server — which, when
 * BossTerm is embedded in a plugin whose classloader is closing, runs against dead classes.
 *
 * ### How these tests are kept honest
 * - The share server binds by scanning `sessionSharingPort .. +9`, so every test reserves a run of
 *   **ten consecutive free ports** and asserts over the whole range. "Nothing bound" is therefore a
 *   real observation, not an artefact of the manager failing to find a port.
 * - Asserting "free" by probing (rather than by holding the ports) means a test cannot pass
 *   vacuously by making the bind impossible in the first place.
 * - [`pre-warm binds the share server once the MCP port is known`] is the positive control. It
 *   proves the pre-warm really is parked at the moment the other tests shut down (nothing is bound
 *   before the MCP port is published, something is bound right after), and it fails if a fix
 *   over-reaches and leaves the manager permanently inert.
 * - The remote-provider mode is [TEST_REMOTE_MODE]: any value other than `"off"` arms the pre-warm,
 *   and an unrecognised one makes `establishRemote`'s `when` fall through to `null`. So these tests
 *   exercise the bind without ever shelling out to `tailscale` or `cloudflared`.
 * - Settings come from a throwaway [SettingsManager] injected via
 *   [SessionShareManager.settingsManagerOverrideForTest], so nothing here can read or rewrite a
 *   developer's real `~/.bossterm/settings.json`.
 *
 * [SessionShareManager] is a singleton, so every test tears it back down in [tearDown]; the fact
 * that the tests pass in any order is itself coverage of `start()` re-arming after `shutdown()`.
 */
class SessionShareShutdownTest {

    private companion object {
        /** Not "off" (so the pre-warm arms) and not a provider `establishRemote` knows (so it
         *  resolves to "no remote link" without launching a process). */
        const val TEST_REMOTE_MODE = "test-no-provider"

        /** How long the manager's ports are scanned over — mirrors `MAX_PORT_FALLBACK`. */
        const val PORT_RANGE = 10

        /** Long enough for the settings watcher to collect and park the pre-warm in its MCP wait;
         *  far short of that wait's own 5s budget. Validated by the positive-control test. */
        const val PARK_MS = 400L

        /** Generous upper bound for "the pre-warm would have bound by now". */
        const val SETTLE_MS = 2_000L
    }

    private val settingsDir = createTempDirectory("session-share-shutdown-test").toFile()
        .apply { deleteOnExit() }

    private var basePort = 0
    private lateinit var settings: SettingsManager

    /** A non-null MCP port to publish; deliberately outside [basePort]'s range so the manager's
     *  "never take the MCP port" skip can't shift the bind out from under the assertions. */
    private val mcpPort: Int get() = basePort + 100

    @BeforeTest
    fun setUp() {
        McpTerminalRegistry.setStopped()
        basePort = reserveFreeRange()
        settings = SettingsManager(File(settingsDir, "settings.json").absolutePath)
        SessionShareManager.settingsManagerOverrideForTest = settings
        settings.updateSettings(
            TerminalSettings.DEFAULT.copy(
                sessionSharingEnabled = true,
                sessionSharingBind = "loopback",
                sessionSharingPort = basePort,
                shareTailscaleMode = TEST_REMOTE_MODE,
                // The pre-warm only waits when MCP is enabled — that wait is the window under test.
                mcpEnabled = true,
            )
        )
    }

    @AfterTest
    fun tearDown() {
        SessionShareManager.shutdown()
        SessionShareManager.settingsManagerOverrideForTest = null
        McpTerminalRegistry.setStopped()
    }

    // ---- positive control -------------------------------------------------------------------

    @Test
    fun `pre-warm binds the share server once the MCP port is known`() = runBlocking {
        SessionShareManager.start()
        delay(PARK_MS)
        assertTrue(
            rangeIsFree(),
            "the pre-warm must still be parked in its MCP wait — nothing may bind before the " +
                "MCP port is published"
        )

        McpTerminalRegistry.setRunning(mcpPort) // release the wait

        assertTrue(
            awaitUntil(SETTLE_MS) { !rangeIsFree() },
            "the pre-warm should have bound the share server in $basePort..${basePort + PORT_RANGE - 1}"
        )
    }

    // ---- the regression ---------------------------------------------------------------------

    @Test
    fun `shutdown during an in-flight pre-warm prevents a late re-bind`() = runBlocking {
        SessionShareManager.start()
        delay(PARK_MS) // pre-warm is now parked in its MCP wait (see the positive control)

        SessionShareManager.shutdown()

        // Complete the thing it was parked on. Before the fix the pre-warm woke here, took the
        // mutex and bound a brand-new Ktor server behind the shut-down manager.
        McpTerminalRegistry.setRunning(mcpPort)
        delay(SETTLE_MS)

        assertTrue(
            rangeIsFree(),
            "nothing may bind after shutdown(): ports $basePort..${basePort + PORT_RANGE - 1} " +
                "must all still be free"
        )
    }

    @Test
    fun `shutdown cancels the manager's own in-flight work`() = runBlocking {
        SessionShareManager.start()
        delay(PARK_MS)
        assertTrue(
            managerScopeChildren() > 0,
            "expected in-flight work (the settings watcher and a parked pre-warm) on the " +
                "manager's scope"
        )

        SessionShareManager.shutdown()

        assertEquals(
            0,
            managerScopeChildren(),
            "shutdown() must leave no active work on the manager's scope — a straggler that " +
                "resumes inside a closing classloader is the whole bug"
        )
    }

    @Test
    fun `share is refused after shutdown`() = runBlocking {
        SessionShareManager.start()
        SessionShareManager.shutdown()

        // share() reaches the binder without suspending anywhere cancellation could catch it, so
        // only the shutdown latch can stop it. Guarded because an un-refused share would go on to
        // build a MirrorShare against terminal state this test never created.
        val info = runCatching { SessionShareManager.share("tab-after-shutdown") }.getOrNull()

        assertNull(info, "share() must return null once the manager has been shut down")
        assertTrue(rangeIsFree(), "share() after shutdown() must not bind a server")
    }

    // ---- idempotency ------------------------------------------------------------------------

    @Test
    fun `shutdown is idempotent`() = runBlocking {
        SessionShareManager.start()
        delay(PARK_MS)
        McpTerminalRegistry.setRunning(mcpPort)
        assertTrue(awaitUntil(SETTLE_MS) { !rangeIsFree() }, "expected a bound server to tear down")

        SessionShareManager.shutdown()
        SessionShareManager.shutdown() // must not throw, must not resurrect anything

        assertTrue(
            awaitUntil(SETTLE_MS) { rangeIsFree() },
            "a double shutdown() must leave the share server stopped"
        )
    }

    @Test
    fun `shutdown with nothing started is safe`() {
        // No start(), no share, no engine — the state an embedder is in when it disposes BossTerm
        // without the user ever having enabled sharing.
        SessionShareManager.shutdown()
        SessionShareManager.shutdown()

        assertTrue(rangeIsFree(), "shutting down an idle manager must not bind anything")
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Active children of the manager's private scope. Reflection because the scope is an internal
     *  implementation detail we nevertheless need to make an assertion about. */
    private fun managerScopeChildren(): Int {
        val field = SessionShareManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = field.get(SessionShareManager) as kotlinx.coroutines.CoroutineScope
        val job = scope.coroutineContext[Job] ?: return 0
        return job.children.count { it.isActive }
    }

    /** True while every port the manager could bind is still free. */
    private fun rangeIsFree(): Boolean = (basePort until basePort + PORT_RANGE).all { portIsFree(it) }

    private fun portIsFree(port: Int): Boolean = runCatching {
        ServerSocket().use { ss ->
            ss.reuseAddress = false
            ss.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)

    /** A base port with [PORT_RANGE] consecutive free ports after it, so the manager's port-fallback
     *  scan cannot land outside the range these tests assert on. */
    private fun reserveFreeRange(): Int {
        var candidate = 20_000 + (System.nanoTime() % 30_000).toInt()
        repeat(200) {
            if ((candidate until candidate + PORT_RANGE).all { portIsFree(it) } &&
                portIsFree(candidate + 100) // the stand-in MCP port
            ) {
                return candidate
            }
            candidate += PORT_RANGE + 1
        }
        error("could not reserve $PORT_RANGE consecutive free ports for the test")
    }

    private suspend fun awaitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(25)
        }
        return predicate()
    }
}
