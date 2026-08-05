package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * - The share server binds by scanning forward from `sessionSharingPort`, so every test reserves a
 *   run of [portRange] consecutive free ports — the production constant, not a copy of it — and
 *   asserts over the whole range. "Nothing bound" is therefore a real observation, not an artefact
 *   of the manager failing to find a port.
 * - Asserting "free" by probing (rather than by holding the ports) means a test cannot pass
 *   vacuously by making the bind impossible in the first place.
 * - No test shuts down on a timer: [awaitPrewarmLaunched] waits for the pre-warm coroutine to
 *   actually exist on the manager's scope, so "shutdown during an in-flight pre-warm" cannot
 *   degrade into "shutdown before the pre-warm ever started".
 * - [`pre-warm binds the share server once the MCP port is known`] is the positive control: nothing
 *   is bound while the pre-warm is parked, something is bound once the MCP port is published. It
 *   fails if a fix over-reaches and leaves the manager permanently inert.
 * - The remote-provider mode is [TEST_REMOTE_MODE]: any value other than `"off"` arms the pre-warm,
 *   and an unrecognised one makes `establishRemote`'s `when` fall through to `null`. So these tests
 *   exercise the bind without ever shelling out to `tailscale` or `cloudflared`.
 * - Settings come from a throwaway [SettingsManager] injected via
 *   [SessionShareManager.settingsManagerOverrideForTest]. That covers every read the manager itself
 *   makes; `MirrorShare` still reads the process singleton directly, which no path here reaches
 *   because every share these tests attempt is refused before one is constructed.
 *
 * [SessionShareManager] is a singleton, so every test tears it back down in [tearDown] — including
 * clearing the shutdown latch, which is why re-arming gets its own test rather than riding on test
 * ordering. That, and the two process-wide singletons this class drives, mean these tests assume
 * sequential execution within the JVM; they would need rethinking under in-JVM parallelism.
 */
class SessionShareShutdownTest {

    private companion object {
        /** Not "off" (so the pre-warm arms) and not a provider `establishRemote` knows (so it
         *  resolves to "no remote link" without launching a process). */
        const val TEST_REMOTE_MODE = "test-no-provider"

        /** Generous upper bound for "the pre-warm would have bound by now". */
        const val SETTLE_MS = 2_000L

        /** Active jobs under the manager's lifecycle once the pre-warm is in flight: the settings
         *  watcher, the supervisorScope it collects inside, and the pre-warm nested in that. Counted
         *  transitively — the point is that waiting on it proves the *pre-warm* exists, not just the
         *  watcher, so this must track the real depth of the tree. */
        const val SCOPE_CHILDREN_WITH_PREWARM = 3
    }

    /**
     * How many ports the manager scans from `sessionSharingPort`. Read from the production constant
     * rather than duplicated, so widening the fallback can't silently leave these assertions
     * covering less than the manager can reach.
     */
    private val portRange: Int =
        SessionShareManager::class.java.getDeclaredField("MAX_PORT_FALLBACK")
            .apply { isAccessible = true }
            .getInt(SessionShareManager)

    private val settingsDir = createTempDirectory("session-share-shutdown-test").toFile()

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
        SessionShareManager.afterBindBeforeRecheckForTest = null
        SessionShareManager.fitHostEmbedder = null
        SessionShareManager.shutdown()
        SessionShareManager.settingsManagerOverrideForTest = null
        // shutdown() latches the singleton shut and kills its lifecycle job, and the singleton
        // outlives this class: left that way, a later test's share() returns a silent null and its
        // launches go nowhere, both of which read as product bugs. Re-arm the way start() does.
        setShuttingDown(false)
        resetLifecycle()
        McpTerminalRegistry.setStopped()
        settingsDir.deleteRecursively()
    }

    // ---- positive control -------------------------------------------------------------------

    @Test
    fun `pre-warm binds the share server once the MCP port is known`() = runBlocking {
        SessionShareManager.start()
        awaitPrewarmLaunched()
        assertTrue(
            rangeIsFree(),
            "the pre-warm must still be parked in its MCP wait - nothing may bind before the " +
                "MCP port is published"
        )

        McpTerminalRegistry.setRunning(mcpPort) // release the wait

        assertTrue(
            awaitUntil(SETTLE_MS) { !rangeIsFree() },
            "the pre-warm should have bound the share server in $basePort..${basePort + portRange - 1}"
        )
    }

    // ---- the regression ---------------------------------------------------------------------

    @Test
    fun `shutdown during an in-flight pre-warm prevents a late re-bind`() = runBlocking {
        SessionShareManager.start()
        awaitPrewarmLaunched() // it exists and is waiting on the MCP port — not merely "soon"

        SessionShareManager.shutdown()

        // Complete the thing it was parked on. Before the fix the pre-warm woke here, took the
        // mutex and bound a brand-new Ktor server behind the shut-down manager.
        McpTerminalRegistry.setRunning(mcpPort)
        delay(SETTLE_MS)

        assertTrue(engineIsNull(), "the manager must hold no engine after shutdown()")
        assertTrue(
            rangeIsFree(),
            "nothing may bind after shutdown(): ports $basePort..${basePort + portRange - 1} " +
                "must all still be free"
        )
    }

    @Test
    fun `shutdown cancels the manager's own in-flight work`() = runBlocking {
        SessionShareManager.start()
        awaitPrewarmLaunched()

        SessionShareManager.shutdown()

        assertEquals(
            0,
            managerScopeChildren(),
            "shutdown() must leave no active work on the manager's scope - a straggler that " +
                "resumes inside a closing classloader is the whole bug"
        )
    }

    @Test
    fun `share is refused after shutdown`() = runBlocking {
        SessionShareManager.start()
        SessionShareManager.shutdown()

        // share() reaches the binder without suspending anywhere cancellation could catch it, so
        // only the shutdown latch can stop it. Called directly, not guarded: the point is a *clean*
        // refusal, so an exception here should fail rather than read as a pass.
        val info = SessionShareManager.share("tab-after-shutdown")

        assertNull(info, "share() must return null once the manager has been shut down")
        assertTrue(rangeIsFree(), "share() after shutdown() must not bind a server")
    }

    // ---- idempotency ------------------------------------------------------------------------

    @Test
    fun `shutdown is idempotent`() = runBlocking {
        SessionShareManager.start()
        awaitPrewarmLaunched()
        McpTerminalRegistry.setRunning(mcpPort)
        assertTrue(awaitUntil(SETTLE_MS) { !rangeIsFree() }, "expected a bound server to tear down")

        SessionShareManager.shutdown()
        SessionShareManager.shutdown() // must not throw, must not resurrect anything

        assertTrue(
            awaitUntil(SETTLE_MS) { engineIsNull() && rangeIsFree() },
            "a double shutdown() must leave the share server stopped"
        )
    }

    @Test
    fun `start re-arms the manager after shutdown`() = runBlocking {
        SessionShareManager.start()
        awaitPrewarmLaunched()
        SessionShareManager.shutdown()

        // The embedder loading BossTerm again — a plugin dispose/re-init cycle. If the latch stuck,
        // sharing would be inert for the rest of the host process with nothing logged anywhere.
        SessionShareManager.start()
        awaitPrewarmLaunched()
        McpTerminalRegistry.setRunning(mcpPort)

        assertTrue(
            awaitUntil(SETTLE_MS) { !rangeIsFree() },
            "start() must clear the shutdown latch so the manager binds again"
        )
    }

    @Test
    fun `a bind that loses to a shutdown plus re-init is undone`() = runBlocking {
        // Drive the one interleaving that the shutdown latch alone cannot survive: the shutdown
        // lands while the socket is listening but before `engine` is assigned — so it stops nothing
        // — and a re-init then clears the latch under the binder. Only the monotonic epoch can tell
        // the binder it has been superseded. Injected rather than raced, so there is no wall clock
        // in this test.
        val reachedRecheck = CountDownLatch(1)
        SessionShareManager.afterBindBeforeRecheckForTest = {
            SessionShareManager.afterBindBeforeRecheckForTest = null // fire once
            SessionShareManager.shutdown()
            setShuttingDown(false) // what a re-initialising embedder's start() does to the latch
            // CIO binds its listening socket asynchronously, so block here until the port is
            // demonstrably taken. Without this the end-state assertion below could be satisfied by
            // the pre-bind state and pass whatever the guard does.
            check(blockingAwaitUntil(SETTLE_MS) { !rangeIsFree() }) { "the listening socket never came up" }
            reachedRecheck.countDown()
        }
        SessionShareManager.start()
        awaitPrewarmLaunched()
        McpTerminalRegistry.setRunning(mcpPort)

        assertTrue(
            reachedRecheck.await(SETTLE_MS, TimeUnit.MILLISECONDS),
            "the pre-warm should have bound a server and reached the re-check"
        )
        // The port is bound as of this line. Only the guard can free it again.
        assertTrue(
            awaitUntil(SETTLE_MS) { engineIsNull() && rangeIsFree() },
            "a server bound across a shutdown must be stopped again, not left running with no " +
                "manager holding it"
        )
    }

    @Test
    fun `shutdown restores an active host fit and drops the embedder`() {
        var restored: String? = null
        SessionShareManager.fitHostEmbedder = object : SessionShareManager.FitHostEmbedder {
            override fun onFitHost(tabId: String, deltaWidthPx: Float, deltaHeightPx: Float) = Unit
            override fun onRestoreHostSize(tabId: String) { restored = tabId }
        }
        SessionShareManager.requestEmbeddedFit("fitted-tab", 120f, 80f)

        SessionShareManager.shutdown()

        assertEquals(
            "fitted-tab", restored,
            "shutdown must tell the embedder to undo a fit-resized host window"
        )
        assertNull(
            SessionShareManager.fitHostEmbedder,
            "shutdown must drop the embedder it was handed - holding it pins the host that is " +
                "disposing us"
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

    /**
     * Block until the settings watcher has launched the pre-warm, so a test that shuts down "during
     * an in-flight pre-warm" really does. The pre-warm cannot bind before the MCP port is published,
     * so once its coroutine exists it is either in that wait or about to enter it — either way it is
     * live work on the manager's scope, which is what the shutdown has to deal with.
     */
    private suspend fun awaitPrewarmLaunched() {
        assertTrue(
            awaitUntil(SETTLE_MS) { managerScopeChildren() >= SCOPE_CHILDREN_WITH_PREWARM },
            "the settings watcher should have launched the pre-warm onto the manager's scope " +
                "(saw ${managerScopeChildren()} active children, expected " +
                "$SCOPE_CHILDREN_WITH_PREWARM)"
        )
    }

    /**
     * Active jobs anywhere under the manager's lifecycle job. Counted transitively: the pre-warm is
     * launched as a child of the settings watcher, not of the lifecycle job directly. Reflection
     * because the job is an internal implementation detail we nevertheless need to assert about.
     */
    private fun managerScopeChildren(): Int {
        fun count(job: Job): Int = job.children.sumOf { (if (it.isActive) 1 else 0) + count(it) }
        return count(readPrivate("lifecycle") as Job)
    }

    /** True when the manager holds no engine — the deterministic counterpart to [rangeIsFree],
     *  which depends on nothing else on the machine grabbing one of the probed ports. */
    private fun engineIsNull(): Boolean = readPrivate("engine") == null

    private fun readPrivate(name: String): Any? =
        SessionShareManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(SessionShareManager)

    /** Install a fresh lifecycle job, as [SessionShareManager.start] would. */
    private fun resetLifecycle() {
        SessionShareManager::class.java.getDeclaredField("lifecycle")
            .apply { isAccessible = true }
            .set(SessionShareManager, SupervisorJob())
    }

    /** Clear the shutdown latch so the singleton isn't left inert for the rest of the JVM. */
    private fun setShuttingDown(value: Boolean) {
        SessionShareManager::class.java.getDeclaredField("shuttingDown")
            .apply { isAccessible = true }
            .setBoolean(SessionShareManager, value)
    }

    /** True while every port the manager could bind is still free. */
    private fun rangeIsFree(): Boolean = (basePort until basePort + portRange).all { portIsFree(it) }

    private fun portIsFree(port: Int): Boolean = runCatching {
        ServerSocket().use { ss ->
            ss.reuseAddress = false
            ss.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)

    /**
     * A base port with [portRange] consecutive free ports after it, so the manager's port-fallback
     * scan cannot land outside the range these tests assert on.
     *
     * Deliberately kept well below 32768 — and the scan below can only add ~2200 — so the window
     * never overlaps Linux's default ephemeral range (32768-60999). Otherwise an unrelated outbound
     * connection from the test JVM could occupy one of these ports mid-assertion and fail a test for
     * reasons that have nothing to do with session sharing.
     */
    private fun reserveFreeRange(): Int {
        // floorMod, not %: nanoTime's origin is arbitrary and it is allowed to be negative.
        var candidate = 20_000 + Math.floorMod(System.nanoTime(), 10_000L).toInt()
        repeat(200) {
            if ((candidate until candidate + portRange).all { portIsFree(it) } &&
                portIsFree(candidate + 100) // the stand-in MCP port
            ) {
                return candidate
            }
            candidate += portRange + 1
        }
        error("could not reserve $portRange consecutive free ports for the test")
    }

    /** [awaitUntil] for callers that cannot suspend — the production test seam runs on a plain
     *  binder thread. */
    private fun blockingAwaitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    private suspend fun awaitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000  // nanoTime: monotonic
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            delay(25)
        }
        return predicate()
    }
}
