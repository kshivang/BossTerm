package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.EmbeddableTerminalState
import ai.rever.bossterm.compose.resolveTerminalParentScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies the structured concurrency contract between a host-provided parent
 * scope and the terminal tab scopes created by [TabController].
 *
 * The key invariants under test:
 * 1. Parent cancellation propagates to every child tab scope.
 * 2. A single tab's cancellation does not propagate to the parent or siblings.
 * 3. Without a parent scope, existing standalone behavior is preserved.
 */
class TabControllerLifecycleTest {

    private class BlockingProcess : PlatformServices.ProcessService.ProcessHandle {
        val reading = CountDownLatch(1)
        val killed = CountDownLatch(1)
        override suspend fun read(): String? {
            reading.countDown()
            check(killed.await(5, TimeUnit.SECONDS)) { "Reader was not unblocked by cleanup" }
            return null
        }
        override suspend fun kill() { killed.countDown() }
        override fun isAlive() = killed.count > 0
        override suspend fun write(data: String) {}
        override suspend fun writeBytes(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun waitFor(): Int { killed.await(); return 0 }
        override fun getExitCode(): Int? = if (isAlive()) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String? = null
    }

    @Test
    fun `parent cancellation kills process and joins blocking reader`() = runBlocking {
        val parent = Job()
        val controller = TabController(TerminalSettings(), {}, parentScope = CoroutineScope(parent))
        val tab = controller.createRemoteSession("blocking", feedsStream = false)
        val process = BlockingProcess()
        tab.attachProcess(process)
        tab.coroutineScope.launch(Dispatchers.IO) { process.read() }
        try {
            assertTrue(process.reading.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { parent.cancelAndJoin() }
            assertFalse(process.isAlive())
        } finally {
            process.kill()
            controller.disposeAll()
            parent.cancel()
        }
    }

    @Test
    fun `process arriving after cancellation is still killed and joined`() = runBlocking {
        val parent = Job()
        val controller = TabController(TerminalSettings(), {}, parentScope = CoroutineScope(parent))
        val tab = controller.createRemoteSession("late spawn", feedsStream = false)
        val spawning = CountDownLatch(1)
        val finishSpawn = CountDownLatch(1)
        val process = BlockingProcess()
        tab.coroutineScope.launch(Dispatchers.IO) {
            spawning.countDown()
            check(finishSpawn.await(5, TimeUnit.SECONDS))
            tab.attachProcess(process)
        }
        try {
            assertTrue(spawning.await(5, TimeUnit.SECONDS))
            parent.cancel()
            finishSpawn.countDown()
            withTimeout(5000) { parent.join() }
            assertFalse(process.isAlive())
        } finally {
            finishSpawn.countDown()
            process.kill()
            controller.disposeAll()
            parent.cancel()
        }
    }

    @Test
    fun `external tabbed state adopts supplied parent`() = runBlocking {
        val parent = Job()
        val state = TabbedTerminalState()
        try {
            state.initialize(TerminalSettings(), {}, { true }, parentScope = CoroutineScope(parent))
            val tab = state.tabController!!.createRemoteSession("external", feedsStream = false)
            withTimeout(5000) { parent.cancelAndJoin() }
            assertFalse(tab.coroutineScope.isActive)
        } finally {
            state.dispose()
            parent.cancel()
        }
    }

    @Test
    fun `external embedded state adopts supplied cancelled parent without spawning`() = runBlocking {
        val parent = Job().also { it.cancel() }
        val state = EmbeddableTerminalState()
        try {
            state.initializeSession(TerminalSettings(), "unused", null, null, null, null, null, null,
                parentScope = CoroutineScope(parent))
            assertFalse(state.session!!.coroutineScope.isActive)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `conflicting state and composable owners are rejected`() {
        val first = CoroutineScope(Job())
        val second = CoroutineScope(Job())
        try {
            assertFailsWith<IllegalArgumentException> { resolveTerminalParentScope(first, second) }
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    @Test
    fun `parent scope cancellation propagates to tab scopes`() = runBlocking {
        val parentJob = Job()
        val parentScope = CoroutineScope(parentJob)

        val controller = TabController(
            settings = TerminalSettings(),
            onLastTabClosed = {},
            parentScope = parentScope
        )

        val tab = controller.createRemoteSession(title = "Test", feedsStream = false)
        assertTrue(tab.coroutineScope.isActive, "Tab scope should be active after creation")

        // Cancel the host-provided parent scope
        parentScope.cancel()

        // The child scope should now be cancelled through structured concurrency
        assertFalse(tab.coroutineScope.isActive, "Tab scope must be cancelled when parent is cancelled")
        controller.disposeAll()
        parentJob.join()
    }

    @Test
    fun `cancelling one tab does not cancel sibling tabs or parent scope`() = runBlocking {
        val parentJob = Job()
        val parentScope = CoroutineScope(parentJob)

        val controller = TabController(
            settings = TerminalSettings(),
            onLastTabClosed = {},
            parentScope = parentScope
        )

        val tab1 = controller.createRemoteSession(title = "Tab 1", feedsStream = false)
        val tab2 = controller.createRemoteSession(title = "Tab 2", feedsStream = false)

        assertTrue(tab1.coroutineScope.isActive)
        assertTrue(tab2.coroutineScope.isActive)
        assertTrue(parentScope.isActive)

        // Cancel just the first tab
        tab1.coroutineScope.cancel()

        // Tab 1 should be cancelled
        assertFalse(tab1.coroutineScope.isActive, "Tab 1 scope was not cancelled")

        // Sibling tab and parent should remain active because of SupervisorJob
        assertTrue(tab2.coroutineScope.isActive, "Sibling tab was incorrectly cancelled")
        assertTrue(parentScope.isActive, "Parent scope was incorrectly cancelled")
        controller.disposeAll()
        parentJob.cancelAndJoin()
    }

    @Test
    fun `controller functions normally without a parent scope`() = runBlocking {
        // Backward compatibility: no parentScope means standalone behavior
        val controller = TabController(
            settings = TerminalSettings(),
            onLastTabClosed = {},
            parentScope = null
        )

        val tab = controller.createRemoteSession(title = "Test", feedsStream = false)
        assertTrue(tab.coroutineScope.isActive, "Tab scope should be active after creation")

        // Manually cancel the tab (standalone lifecycle)
        tab.coroutineScope.cancel()
        assertFalse(tab.coroutineScope.isActive, "Tab scope should be cancelled after manual cancel")
        controller.disposeAll()
    }
}
