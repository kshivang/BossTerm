package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.TerminalSessionSlots
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.PaneTreeNode
import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.TabNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemoteSessionShutdownTest {
    private val layout = ServerMessage.Layout(
        listOf(TabNode("tab", "remote", true, PaneTreeNode.Pane("pane", "remote", "/tmp", true))),
        "tab",
    )

    // Exercise the production rebuild without a socket, so the race is controlled by
    // the existing peer-filter callback rather than network timing or a test-only hook.
    private fun reconcile(session: RemoteSession) {
        RemoteSession::class.java.getDeclaredMethod("reconcile", ServerMessage.Layout::class.java)
            .apply { isAccessible = true }.invoke(session, layout)
    }

    @Test
    fun `off Main close waits for an in flight layout and disposes every new parser`(): Unit =
        closeDuringRebuild(resetFirst = false)

    @Test
    fun `disposal also waits for sessions already detached by an account reset`(): Unit =
        closeDuringRebuild(resetFirst = true)

    private fun closeDuringRebuild(resetFirst: Boolean): Unit = runBlocking {
        val state = TabbedTerminalState()
        withContext(Dispatchers.Main) { state.initialize(TerminalSettings(), {}, { true }) }
        val reservedBefore = TerminalSessionSlots.usedThreads
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(3)
        val session = RemoteSession("http://127.0.0.1/?t=test", "test", state, "test",
            peerOriginHashes = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                emptySet()
            },
        )
        val manager = state.remoteSessions
        manager.sessions.add(session)
        try {
            val rebuilding = workers.submit { runBlocking { withContext(Dispatchers.Main) { reconcile(session) } } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val accountReset = if (resetFirst) workers.submit { manager.disconnectAll() } else null
            if (resetFirst) withTimeout(5_000) { while (manager.sessions.isNotEmpty()) delay(5) }
            val disposal = workers.submit { closing.countDown(); state.dispose() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException>("close missed an executing layout rebuild") {
                disposal.get(150, TimeUnit.MILLISECONDS)
            }
            // The session has been claimed for disposal, but its rebuild is still held.
            // A host/MCP thread must not attach a replacement during that interval.
            withTimeout(5_000) { while (manager.sessions.isNotEmpty()) delay(5) }
            assertNull(manager.connect("http://127.0.0.1:1/?t=late", "late"))
            release.countDown()
            rebuilding.get(5, TimeUnit.SECONDS)
            accountReset?.get(5, TimeUnit.SECONDS)
            disposal.get(5, TimeUnit.SECONDS)
            withContext(Dispatchers.Main) {
                assertTrue(state.tabs.isEmpty())
                assertTrue(state.splitStates.isEmpty())
            }
            assertEquals(reservedBefore, TerminalSessionSlots.usedThreads, "a mirror parser survived close")
        } finally {
            release.countDown()
            session.close()
            withContext(Dispatchers.Main) { state.dispose() }
            workers.shutdownNow()
        }
    }

    @Test
    fun `Main close completes and a late layout cannot recreate mirrors`(): Unit = runBlocking {
        val state = TabbedTerminalState()
        val session = RemoteSession("http://127.0.0.1/?t=test", "test", state, "test")
        val reservedBefore = TerminalSessionSlots.usedThreads
        try {
            withContext(Dispatchers.Main) {
                state.initialize(TerminalSettings(), {}, { true })
                reconcile(session)
                assertEquals(1, state.tabs.size)
                assertEquals(reservedBefore + 1, TerminalSessionSlots.usedThreads)
                session.close()
                reconcile(session)
                session.close()
                assertTrue(state.tabs.isEmpty())
                assertTrue(state.splitStates.isEmpty())
            }
            assertEquals(reservedBefore, TerminalSessionSlots.usedThreads)
        } finally {
            session.close()
            withContext(Dispatchers.Main) { state.dispose() }
        }
    }

    @Test
    fun `a newer disposal keeps admission closed when an older reopen finishes waiting`(): Unit = runBlocking {
        val state = TabbedTerminalState()
        withContext(Dispatchers.Main) { state.initialize(TerminalSettings(), {}, { true }) }
        val manager = state.remoteSessions
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = RemoteSession("http://127.0.0.1/?t=test", "test", state, "test",
            peerOriginHashes = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                emptySet()
            },
        )
        manager.sessions.add(session)
        val workers = Executors.newFixedThreadPool(4)
        suspend fun awaitWaiting(thread: AtomicReference<Thread>) {
            withTimeout(5_000) { while (thread.get()?.state != Thread.State.WAITING) delay(5) }
        }
        try {
            val rebuilding = workers.submit { runBlocking { withContext(Dispatchers.Main) { reconcile(session) } } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val firstStop = workers.submit { manager.stopForDispose() }
            withTimeout(5_000) { while (manager.sessions.isNotEmpty()) delay(5) }
            val reopeningThread = AtomicReference<Thread>()
            val reopening = workers.submit { reopeningThread.set(Thread.currentThread()); manager.reopen() }
            awaitWaiting(reopeningThread)
            assertFalse(reopening.isDone, "reopening must wait for the original teardown")
            val stoppingThread = AtomicReference<Thread>()
            val latestStop = workers.submit { stoppingThread.set(Thread.currentThread()); manager.stopForDispose() }
            awaitWaiting(stoppingThread)
            assertFalse(latestStop.isDone, "repeated disposal must wait for the original teardown")
            release.countDown()
            rebuilding.get(5, TimeUnit.SECONDS)
            firstStop.get(5, TimeUnit.SECONDS)
            reopening.get(5, TimeUnit.SECONDS)
            latestStop.get(5, TimeUnit.SECONDS)
            assertNull(manager.connect("http://127.0.0.1:1/?t=late", "late"))
            withContext(Dispatchers.Main) {
                assertTrue(state.tabs.isEmpty())
                assertTrue(state.splitStates.isEmpty())
            }
        } finally {
            release.countDown()
            session.close()
            withContext(Dispatchers.Main) { state.dispose() }
            workers.shutdownNow()
        }
    }

    @Test
    fun `duplicate connect preserves the original session and its single set of UI workers`(): Unit = runBlocking {
        val state = TabbedTerminalState()
        try {
            withContext(Dispatchers.Main) {
                state.initialize(TerminalSettings(), {}, { true })
                val first = assertNotNull(state.remoteSessions.connect("http://127.0.0.1:1/?t=same", "first"))
                val uiScope = RemoteSession::class.java.getDeclaredField("uiScope")
                    .apply { isAccessible = true }.get(first) as CoroutineScope
                val workers = uiScope.coroutineContext[Job]!!.children.toSet()
                assertEquals(3, workers.size, "visibility, status, and inbox each need one worker")
                val second = state.remoteSessions.connect("http://127.0.0.1:1/?t=same", "duplicate")
                assertSame(first, second)
                first.start()
                assertEquals(workers, uiScope.coroutineContext[Job]!!.children.toSet())
                assertEquals(1, state.remoteSessions.sessions.size)
            }
        } finally {
            withContext(Dispatchers.Main) { state.dispose() }
        }
    }

    @Test
    fun `disconnect all stays reusable and initializing a disposed state reopens admission`(): Unit = runBlocking {
        val state = TabbedTerminalState()
        val manager = state.remoteSessions
        try {
            withContext(Dispatchers.Main) {
                state.initialize(TerminalSettings(), {}, { true })
                assertNotNull(manager.connect("http://127.0.0.1:1/?t=first", "test"))
                manager.disconnectAll()
                assertTrue(manager.sessions.isEmpty())
                assertNotNull(manager.connect("http://127.0.0.1:1/?t=second", "test"))
                state.dispose()
                assertNull(manager.connect("http://127.0.0.1:1/?t=closed", "test"))
                state.initialize(TerminalSettings(), {}, { true })
                assertNotNull(manager.connect("http://127.0.0.1:1/?t=reopened", "test"))
            }
        } finally {
            withContext(Dispatchers.Main) { state.dispose() }
        }
    }
}
