package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.TerminalSessionSlots
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonLifecycleRegressionTest {
    @Test
    fun `close before start cannot spawn or reserve slots`() {
        val spawns = AtomicInteger()
        val reserved = TerminalSessionSlots.usedThreads
        val core = core(services { spawns.incrementAndGet(); null })
        core.close()
        core.start()
        assertEquals(0, spawns.get())
        assertEquals(reserved, TerminalSessionSlots.usedThreads)
    }

    @Test
    fun `failed spawn reaps its host entry and releases slots`() {
        val reserved = TerminalSessionSlots.usedThreads
        val host = SessionHost(TerminalSettings.DEFAULT, platformServices = services { null })
        try {
            host.openSession(command = "fake-shell")
            assertTrue(await { host.count() == 0 && TerminalSessionSlots.usedThreads == reserved })
            assertTrue(host.listGroups().isEmpty())
        } finally { host.close() }
    }

    @Test
    fun `final large PTY read is preserved after the process exits`() {
        val process = FakeProcess()
        val expected = "x".repeat(70_000) + "TAIL_777\r\n"
        process.output.trySend(expected)
        process.finish()
        val core = core(services { process })
        val output = StringBuilder()
        val done = CountDownLatch(1)
        core.addRawOutputListener { synchronized(output) { output.append(it) } }
        core.onExit = { done.countDown() }
        core.start()
        try {
            assertTrue(done.await(8, TimeUnit.SECONDS))
            assertEquals(expected, synchronized(output) { output.toString() })
            assertTrue(core.textBuffer.getScreenLines().contains("TAIL_777"))
        } finally { core.close() }
    }

    @Test
    fun `control close removes grouped pane before PTY exit`() {
        val host = SessionHost(TerminalSettings.DEFAULT, platformServices = services { FakeProcess() })
        try {
            val (session, _) = host.openWindow(command = "fake-shell")
            assertEquals(1, host.listGroups().size)
            host.closeSession(session)
            assertEquals(0, host.count())
            assertTrue(host.listGroups().isEmpty())
        } finally { host.close() }
    }

    @Test
    fun `closing a split group removes every session atomically`() {
        val host = SessionHost(TerminalSettings.DEFAULT, platformServices = services { FakeProcess() })
        try {
            val (session, group) = host.openWindow(command = "fake-shell")
            assertNotNull(host.splitPane(session, SplitOrientation.VERTICAL))
            assertEquals(2, host.count())
            assertTrue(host.closeGroup(group))
            assertEquals(0, host.count())
            assertTrue(host.listGroups().isEmpty())
            assertNull(host.splitPane(session, SplitOrientation.VERTICAL))
        } finally { host.close() }
    }

    @Test
    fun `non-destructive shutdown refuses active sessions and admission closes atomically`() {
        val host = SessionHost(TerminalSettings.DEFAULT, platformServices = services { FakeProcess() })
        var requested = false
        val handler = DaemonControlHandler(host, "1", 1, { 0 }, { null }, onShutdown = { requested = true })
        try {
            host.openSession(command = "fake-shell")
            assertTrue(handler.handle(DaemonProtocol.SHUTDOWN, "").startsWith("ERR "))
            assertFalse(requested)
            assertEquals("OK stopping", handler.handle(DaemonProtocol.SHUTDOWN, "{\"killSessions\":true}"))
            assertTrue(requested)
            assertFailsWith<IllegalStateException> { host.openSession(command = "fake-shell") }
        } finally { host.close() }
    }

    @Test
    fun `OSC 1 and OSC 2 stay separate and window title resets on shell prompt`() {
        val process = FakeProcess()
        val core = core(services { process })
        core.start()
        try {
            process.output.trySend("\u001b]1;TAB_NAME\u0007\u001b]2;WINDOW_NAME\u0007")
            assertTrue(await { core.iconTitle.value == "TAB_NAME" && core.windowTitle.value == "WINDOW_NAME" })
            process.output.trySend("\u001b]133;A\u0007")
            assertTrue(await { core.iconTitle.value == "TAB_NAME" && core.windowTitle.value.isEmpty() })
        } finally { core.close()?.join(2000) }
    }

    @Test
    fun `initial command waits for prompt and sends carriage return`() {
        val process = FakeProcess()
        val core = TerminalSessionCore(
            settings = TerminalSettings.DEFAULT.copy(initialCommandDelayMs = 10_000),
            workingDir = null, command = "fake-shell", platformServices = services { process },
            initialCommand = "printf READY",
        )
        core.start()
        try {
            assertTrue(await { core.state.value == TerminalSessionCore.State.Connected })
            assertTrue(process.writes.isEmpty())
            process.output.trySend("\u001b]133;A\u0007")
            assertTrue(await { process.writes.isNotEmpty() })
            assertEquals(listOf("printf READY\r"), process.writes.toList())
        } finally { core.close()?.join(2000) }
    }

    @Test
    fun `non-finite split weights produce serializable finite ratios`() {
        val split = GroupNode.VerticalSplit(left = GroupNode.Pane(sessionId = "a"), right = GroupNode.Pane(sessionId = "b"))
        assertEquals(0.5f, (split.updateRatio(split.id, Float.NaN) as GroupNode.VerticalSplit).ratio)
        assertEquals(0.5f, (split.updateRatio(split.id, Float.POSITIVE_INFINITY) as GroupNode.VerticalSplit).ratio)
    }

    private fun core(platformServices: PlatformServices) = TerminalSessionCore(
        settings = TerminalSettings.DEFAULT, workingDir = null, command = "fake-shell", platformServices = platformServices,
    )

    private fun services(spawn: () -> PlatformServices.ProcessService.ProcessHandle?): PlatformServices =
        object : PlatformServices by getPlatformServices() {
            override fun getProcessService() = object : PlatformServices.ProcessService {
                override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) = spawn()
            }
        }

    private fun await(predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    private class FakeProcess : PlatformServices.ProcessService.ProcessHandle {
        val output = Channel<String>(Channel.UNLIMITED)
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val exited = CompletableDeferred<Unit>()
        override suspend fun write(data: String) { writes.add(data) }
        override suspend fun writeBytes(data: ByteArray) { writes.add(data.toString(Charsets.UTF_8)) }
        override suspend fun read(): String? = output.receiveCatching().getOrNull()
        override fun isAlive() = !exited.isCompleted
        override suspend fun kill() { finish() }
        override suspend fun waitFor(): Int { exited.await(); return 0 }
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override fun getExitCode(): Int? = if (isAlive()) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String? = null
        fun finish() { output.close(); exited.complete(Unit) }
    }
}
