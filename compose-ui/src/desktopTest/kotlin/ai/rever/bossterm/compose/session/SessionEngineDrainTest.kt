package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionEngineDrainTest {
    @Test
    fun `closing during blocking spawn kills the late handle exactly once`() = runBlocking {
        val spawnEntered = CountDownLatch(1)
        val releaseSpawn = CountDownLatch(1)
        val process = FakeProcess()
        val engine = engine {
            spawnEntered.countDown()
            check(releaseSpawn.await(5, TimeUnit.SECONDS))
            process
        }
        engine.start()
        try {
            assertTrue(spawnEntered.await(5, TimeUnit.SECONDS))
            val killer = engine.close()
            assertSame(killer, engine.close(), "repeat close joins the same process teardown")
            releaseSpawn.countDown()
            withTimeout(5000) { engine.awaitTermination() }
            assertEquals(1, process.kills.get())
            assertTrue(!process.isAlive())
        } finally {
            releaseSpawn.countDown()
            engine.close()?.join(3000)
        }
    }

    @Test
    fun `natural exit waits for final parsing longer than the former two second deadline`() = runBlocking {
        val process = FakeProcess()
        val expected = "x".repeat(70_000) + "FINAL_TAIL\r\n"
        process.output.trySend(expected)
        process.finish()
        val engine = engine { process }
        val first = AtomicBoolean(true)
        val received = StringBuilder()
        engine.addRawOutputListener { chunk ->
            if (first.compareAndSet(true, false)) Thread.sleep(2500)
            synchronized(received) { received.append(chunk) }
        }
        val completed = CompletableDeferred<String>()
        engine.onExit = { completed.complete(synchronized(received) { received.toString() }) }
        engine.start()
        try {
            assertEquals(expected, withTimeout(8000) { completed.await() })
            withTimeout(5000) { engine.awaitTermination() }
            assertTrue(engine.textBuffer.getScreenLines().contains("FINAL_TAIL"))
        } finally { engine.close()?.join(3000) }
    }

    private fun engine(spawn: () -> PlatformServices.ProcessService.ProcessHandle): TerminalSessionEngine {
        val settings = TerminalSettings.DEFAULT.copy(autoInjectShellIntegration = false)
        return TerminalSessionEngine(
            settings = settings, workingDir = null, command = "fake-shell",
            stack = TerminalSessionStack.create(settings, HeadlessTerminalDisplay()),
            platformServices = object : PlatformServices by getPlatformServices() {
                override fun getProcessService() = object : PlatformServices.ProcessService {
                    override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) = spawn()
                }
            },
        )
    }

    private class FakeProcess : PlatformServices.ProcessService.ProcessHandle {
        val output = Channel<String>(Channel.UNLIMITED)
        val kills = AtomicInteger()
        private val exited = CompletableDeferred<Unit>()
        override suspend fun write(data: String) = Unit
        override suspend fun writeBytes(data: ByteArray) = Unit
        override suspend fun read(): String? = output.receiveCatching().getOrNull()
        override fun isAlive() = !exited.isCompleted
        override suspend fun kill() { kills.incrementAndGet(); finish() }
        override suspend fun waitFor(): Int { exited.await(); return 0 }
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override fun getExitCode(): Int? = if (isAlive()) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String? = null
        fun finish() { output.close(); exited.complete(Unit) }
    }
}
