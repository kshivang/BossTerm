package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.TerminalSessionSlots
import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.util.CharUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Adapter parity and lifecycle regressions exercised without an app or a real shell. */
class SharedSessionEngineParityTest {
    private val settings = TerminalSettings.DEFAULT.copy(autoInjectShellIntegration = false)

    @Test
    fun `parsed and existing display stacks preserve EOF output and OSC metadata equally`() = runBlocking {
        val output = "\u001b]1;short tab\u0007\u001b]2;long window\u0007" +
            "\u001b]7;file://localhost/tmp/shared-engine\u0007\u001b]133;A\u0007\u001b[31mfinal 😀 你好\u001b[0m\r\n"
        val results = mutableListOf<List<String?>>()
        for (parsed in listOf(true, false)) {
            val handle = FakeHandle().apply {
                alive = false // waitFor may finish before readers run; final output must still drain.
                chunks.trySend(output.take(9))
                chunks.trySend(output.drop(9))
                chunks.close()
                exit.complete(7)
            }
            val engine = engine(handle, parsed = parsed, reserveThreads = false)
            val exits = AtomicInteger()
            engine.onExit = { exits.incrementAndGet() }
            try {
                engine.start()
                withTimeout(5_000) { engine.awaitTermination() }
                val screen = engine.textBuffer.getScreenLines()
                // Raw screen lines carry U+E000 continuation-cell markers after wide glyphs.
                assertTrue(screen.replace(CharUtils.DWC.toString(), "").contains("final 😀 你好"),
                    "final Unicode text must survive EOF (parsed=$parsed): $screen")
                assertEquals(7, engine.exitCode)
                assertEquals(1, exits.get())
                assertEquals(TerminalSessionEngine.State.Exited, engine.state.value)
                results.add(listOf(engine.textBuffer.getScreenLines(), engine.windowTitle.value,
                    engine.iconTitle.value, engine.workingDirectory.value))
            } finally {
                engine.close()?.join(1_000)
            }
        }
        assertEquals(results[0], results[1], "display wiring must not change parser, EOF or metadata behavior")
        assertEquals("", results[0][1], "a fresh prompt clears OSC 2 window title")
        assertEquals("short tab", results[0][2], "a fresh prompt retains the separate OSC 1 icon title")
        assertEquals("/tmp/shared-engine", results[0][3])
    }

    @Test
    fun `a canceled owner cannot start a new PTY`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        owner.cancel()
        val spawned = AtomicInteger()
        val handle = FakeHandle()
        val engine = engine(handle, owner = owner, reserveThreads = false, spawned = spawned)
        try {
            engine.start()
            withTimeout(2_000) { engine.awaitTermination() }
            assertEquals(0, spawned.get())
            assertFalse(engine.isAlive())
        } finally {
            engine.close()?.join(1_000)
        }
    }

    @Test
    fun `closing retains slots until an occupied reader thread actually finishes`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handle = FakeHandle(entered, release)
        val before = TerminalSessionSlots.usedThreads
        val engine = engine(handle, reserveThreads = true)
        try {
            engine.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS), "reader should enter the blocking PTY read")
            engine.close()?.join(1_000)
            assertEquals(before + TerminalSessionSlots.THREADS_PER_SESSION, TerminalSessionSlots.usedThreads,
                "closed session still owns the blocking reader permit")
            release.countDown()
            withTimeout(3_000) { engine.awaitTermination() }
            assertEquals(before, TerminalSessionSlots.usedThreads)
        } finally {
            release.countDown()
            engine.close()?.join(1_000)
            withTimeout(3_000) { engine.awaitTermination() }
        }
    }


    @Test
    fun `termination waits for an occupied write consumer as well as read lanes`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handle = FakeHandle(writeEntered = entered, writeRelease = release)
        val engine = engine(handle, reserveThreads = false)
        try {
            engine.start()
            engine.writeInput("queued input")
            assertTrue(entered.await(3, TimeUnit.SECONDS), "writer should enter the blocking PTY write")
            engine.close()?.join(1_000)
            val finished = async { engine.awaitTermination() }
            delay(100)
            assertFalse(finished.isCompleted, "engine still owns the blocked write consumer")
            release.countDown()
            withTimeout(3_000) { finished.await() }
        } finally {
            release.countDown()
            engine.close()?.join(1_000)
            withTimeout(3_000) { engine.awaitTermination() }
        }
    }


    @Test
    fun `a failed write cannot strand a connected engine with a dead consumer`() = runBlocking {
        val handle = FakeHandle(failWrites = true)
        val engine = engine(handle, reserveThreads = false)
        try {
            engine.start()
            engine.writeInput("trigger failure")
            withTimeout(3_000) { engine.awaitTermination() }
            assertTrue(handle.killed.await(1, TimeUnit.SECONDS), "failed writer must terminate its owned PTY")
            assertTrue(engine.state.value is TerminalSessionEngine.State.Error)
            assertFalse(engine.isAlive())
        } finally {
            engine.close()?.join(1_000)
            withTimeout(3_000) { engine.awaitTermination() }
        }
    }

    private fun engine(
        handle: FakeHandle,
        parsed: Boolean = true,
        reserveThreads: Boolean,
        owner: CoroutineScope? = null,
        spawned: AtomicInteger = AtomicInteger(),
    ): TerminalSessionEngine {
        val display = HeadlessTerminalDisplay(initialCols = 80, initialRows = 24)
        val stack = if (parsed) TerminalSessionStack.create(settings, display) else {
            // Public callers can retain their established display/model wiring.
            val style = StyleState()
            val buffer = TerminalTextBuffer(80, 24, style, settings.bufferMaxLines)
            val terminal = BossTerminal(display, buffer, style)
            val stream = BlockingTerminalDataStream()
            TerminalSessionStack(display, buffer, terminal, stream, BossEmulator(stream, terminal))
        }
        val services = object : PlatformServices by getPlatformServices() {
            override fun getProcessService(): PlatformServices.ProcessService = object : PlatformServices.ProcessService {
                override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig): PlatformServices.ProcessService.ProcessHandle {
                    spawned.incrementAndGet()
                    return handle
                }
            }
        }
        return TerminalSessionEngine(
            settings = settings, workingDir = "/tmp", command = "fake-terminal", stack = stack,
            platformServices = services, reserveThreads = reserveThreads, parentScope = owner,
        )
    }

    private class FakeHandle(
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
        private val writeEntered: CountDownLatch? = null,
        private val writeRelease: CountDownLatch? = null,
        private val failWrites: Boolean = false,
    ) : PlatformServices.ProcessService.ProcessHandle {
        val killed = CountDownLatch(1)
        val chunks = Channel<String>(Channel.UNLIMITED)
        val exit = CompletableDeferred<Int>()
        @Volatile var alive = true
        override suspend fun read(): String? {
            entered?.countDown()
            if (release != null) { release.await(); return null }
            return chunks.receiveCatching().getOrNull()
        }
        override suspend fun waitFor(): Int = exit.await()
        override suspend fun kill() { alive = false; chunks.close(); exit.complete(0); killed.countDown() }
        override suspend fun write(data: String) {
            if (failWrites) error("fake PTY write failed")
            writeEntered?.countDown()
            writeRelease?.await()
        }
        override suspend fun writeBytes(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override fun isAlive(): Boolean = alive
        override fun getExitCode(): Int? = if (alive) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String = "/tmp"
    }
}
