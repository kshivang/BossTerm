package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionEngineResizeTest {
    @Test
    fun `partial CSI cannot block layout or input and pending grids never overtake newer grids`() = runBlocking {
        val process = FakeProcess()
        val engine = engine(process)
        val waiting = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val grids = CopyOnWriteArrayList<Pair<Int, Int>>()
        engine.addResizeListener { cols, rows -> grids.add(cols to rows) }
        engine.dataStream.onChunkEnd = { waiting.countDown() }
        var layout: Thread? = null
        try {
            engine.start()
            process.output.send("\u001b[")
            assertTrue(waiting.await(3, TimeUnit.SECONDS), "parser must be waiting inside the incomplete CSI")
            process.grids.clear()
            grids.clear()
            layout = thread(isDaemon = true) {
                for (size in 90..99) engine.resize(size, size - 60)
                returned.countDown()
            }
            assertTrue(returned.await(500, TimeUnit.MILLISECONDS), "layout must return while the parser still waits")
            engine.writeInput("\u0003")
            withTimeout(3000) { while (process.writes.isEmpty()) delay(10) }
            assertEquals(listOf("\u0003"), process.writes.toList(), "Ctrl-C must bypass the deferred model resize")
            assertEquals(80, engine.textBuffer.width, "do not resize in the middle of an emulator operation")
            process.output.send("mAFTER")
            withTimeout(3000) {
                while (engine.textBuffer.width != 99 || process.grids.lastOrNull() != (99 to 39)) delay(10)
            }
            assertEquals(listOf(99 to 39), grids.toList(), "coalesce pending requests to the newest grid")
            assertEquals(listOf(99 to 39), process.grids.toList())
            engine.resize(100, 40)
            assertEquals(100, engine.textBuffer.width, "an uncontended layout resize remains synchronous")
            withTimeout(3000) { while (process.grids.lastOrNull() != (100 to 40)) delay(10) }
            assertEquals(listOf(99 to 39, 100 to 40), grids.toList())
            assertEquals(listOf(99 to 39, 100 to 40), process.grids.toList())
        } finally {
            // Also releases a blocked layout call if this regression reappears.
            process.output.trySend("m")
            layout?.join(3000)
            engine.close()
            withTimeout(3000) { engine.awaitTermination() }
        }
    }

    @Test
    fun `closing with a pending resize cancels it without changing the grid`() = runBlocking {
        val process = FakeProcess()
        val engine = engine(process)
        val waiting = CountDownLatch(1)
        engine.dataStream.onChunkEnd = { waiting.countDown() }
        try {
            engine.start()
            process.output.send("\u001b[")
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            engine.resize(120, 50)
            engine.close()
            withTimeout(3000) { engine.awaitTermination() }
            assertEquals(80, engine.textBuffer.width)
            assertEquals(24, engine.textBuffer.height)
        } finally {
            engine.close()
            withTimeout(3000) { engine.awaitTermination() }
        }
    }

    @Test
    fun `listener registration fails within a bounded wait on a partial CSI`() = runBlocking {
        val process = FakeProcess()
        val engine = engine(process)
        val waiting = CountDownLatch(1)
        engine.dataStream.onChunkEnd = { waiting.countDown() }
        try {
            engine.start()
            process.output.send("\u001b[")
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            val before = System.nanoTime()
            assertFailsWith<IllegalStateException> { engine.addRawOutputListener {} }
            assertFailsWith<IllegalStateException> { engine.addResizeListener { _, _ -> error("must not register") } }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 3000,
                "registration must time out rather than occupying a daemon connection indefinitely")
        } finally {
            engine.close()
            withTimeout(3000) { engine.awaitTermination() }
        }
    }

    private fun engine(process: FakeProcess): TerminalSessionEngine {
        val settings = TerminalSettings.DEFAULT.copy(autoInjectShellIntegration = false)
        return TerminalSessionEngine(
            settings = settings, workingDir = null, command = "fake-shell", reserveThreads = false,
            stack = TerminalSessionStack.create(settings, HeadlessTerminalDisplay()),
            platformServices = object : PlatformServices by getPlatformServices() {
                override fun getProcessService() = object : PlatformServices.ProcessService {
                    override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) = process
                }
            },
        )
    }

    private class FakeProcess : PlatformServices.ProcessService.ProcessHandle {
        val output = Channel<String>(Channel.UNLIMITED)
        val writes = CopyOnWriteArrayList<String>()
        val grids = CopyOnWriteArrayList<Pair<Int, Int>>()
        private val exited = CompletableDeferred<Unit>()
        override suspend fun write(data: String) { writes.add(data) }
        override suspend fun writeBytes(data: ByteArray) { writes.add(data.toString(Charsets.UTF_8)) }
        override suspend fun read(): String? = output.receiveCatching().getOrNull()
        override fun isAlive() = !exited.isCompleted
        override suspend fun kill() { output.close(); exited.complete(Unit) }
        override suspend fun waitFor(): Int { exited.await(); return 0 }
        override suspend fun resize(columns: Int, rows: Int) { grids.add(columns to rows) }
        override fun getExitCode(): Int? = if (isAlive()) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String? = null
    }
}
