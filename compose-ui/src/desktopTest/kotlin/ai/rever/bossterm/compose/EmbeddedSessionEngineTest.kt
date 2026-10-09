package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbeddedSessionEngineTest {
    @Test
    fun `unload waits for a PTY reader that is already returning Unicode output`() = runBlocking {
        val process = FakeProcess()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposing = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val state = EmbeddableTerminalState()
        val heldReader = object : PlatformServices.ProcessService.ProcessHandle by process {
            override suspend fun read(): String? {
                reading.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return "你好👩🏽‍💻"
            }
        }
        try {
            state.initializeSession(TerminalSettings(autoInjectShellIntegration = false), "fake-shell", null, null,
                null, null, null, null, services { heldReader })
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val disposal = executor.submit { disposing.countDown(); state.disposeForUnload() }
            assertTrue(disposing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { disposal.get(150, TimeUnit.MILLISECONDS) }
            release.countDown()
            disposal.get(5, TimeUnit.SECONDS)
            assertFalse(process.isAlive())
        } finally {
            release.countDown()
            state.disposeForUnload()
            process.kill()
            executor.shutdownNow()
        }
    }

    @Test
    fun `unload waits for late spawn and kills its process before returning`() = runBlocking {
        val process = FakeProcess()
        val spawning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposing = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val state = EmbeddableTerminalState()
        try {
            state.initializeSession(TerminalSettings(autoInjectShellIntegration = false), "fake-shell", null, null,
                null, null, null, null, services {
                    spawning.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    process
                })
            assertTrue(spawning.await(5, TimeUnit.SECONDS))
            val disposal = executor.submit { disposing.countDown(); state.disposeForUnload() }
            assertTrue(disposing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { disposal.get(150, TimeUnit.MILLISECONDS) }
            release.countDown()
            disposal.get(5, TimeUnit.SECONDS)
            assertFalse(process.isAlive())
        } finally {
            release.countDown()
            state.disposeForUnload()
            process.kill()
            executor.shutdownNow()
        }
    }

    @Test
    fun `unload is rejected from an engine exit callback while ordinary disposal is safe`() = runBlocking {
        val process = FakeProcess()
        val state = EmbeddableTerminalState()
        val callback = CompletableDeferred<Unit>()
        try {
            state.initializeSession(TerminalSettings(autoInjectShellIntegration = false), "fake-shell", null, null,
                null, null, null, {
                    assertFailsWith<IllegalStateException> { state.disposeForUnload() }
                    state.dispose()
                    callback.complete(Unit)
                }, services { process })
            withTimeout(5000) { while (!state.isConnected) delay(10) }
            process.finish()
            withTimeout(5000) { callback.await() }
        } finally {
            state.disposeForUnload()
            process.kill()
        }
    }

    @Test
    fun `embedded dispose waits for its in flight parser`() = runBlocking {
        val process = FakeProcess()
        val state = EmbeddableTerminalState()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposing = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            state.initializeSession(TerminalSettings(autoInjectShellIntegration = false), "fake-shell", null, null,
                null, null, null, null, services { process })
            withTimeout(5000) { while (!state.isConnected) delay(10) }
            val tab = state.session!!
            val beginBatch = tab.dataStream.onChunkStart
            tab.dataStream.onChunkStart = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                beginBatch?.invoke()
            }
            process.output.send("你好👩🏽‍💻\r\n")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val disposal = executor.submit { disposing.countDown(); state.dispose() }
            assertTrue(disposing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { disposal.get(150, TimeUnit.MILLISECONDS) }
            release.countDown()
            disposal.get(5, TimeUnit.SECONDS)
            withTimeout(5000) { tab.sessionEngine!!.awaitTermination() }
        } finally {
            release.countDown()
            state.dispose()
            process.kill()
            executor.shutdownNow()
        }
    }

    @Test
    fun `input immediately after initialization is ordered before the CR submitted initial command`() = runBlocking {
        val spawned = CountDownLatch(1)
        val releaseSpawn = CountDownLatch(1)
        val process = FakeProcess()
        val state = EmbeddableTerminalState()
        val services = services {
            spawned.countDown()
            check(releaseSpawn.await(5, TimeUnit.SECONDS))
            process
        }
        try {
            state.initializeSession(
                TerminalSettings(autoInjectShellIntegration = false, initialCommandDelayMs = 2000),
                "fake-shell", null, null, "printf READY", null, null, null, services,
            )
            assertTrue(spawned.await(5, TimeUnit.SECONDS))
            state.writeVerbatim("first")
            state.sendInput("second".toByteArray())
            releaseSpawn.countDown()
            process.output.send("\u001b]133;A\u0007")
            withTimeout(5000) { while (process.writes.size < 3) delay(10) }
            assertEquals(listOf("first", "second", "printf READY\r"), process.writes.toList())
        } finally {
            releaseSpawn.countDown()
            val engine = state.session?.sessionEngine
            state.dispose()
            process.kill()
            withTimeout(5000) { engine?.awaitTermination() }
        }
    }

    @Test
    fun `embedded exit callback sees final output already parsed by the shared engine`() = runBlocking {
        val process = FakeProcess()
        val exited = CompletableDeferred<String>()
        val state = EmbeddableTerminalState()
        try {
            state.initializeSession(
                TerminalSettings(autoInjectShellIntegration = false), "fake-shell", null, null,
                null, null, null, { exited.complete(state.session!!.textBuffer.getScreenLines()) },
                services { process },
            )
            withTimeout(5000) { while (!state.isConnected) delay(10) }
            process.output.send("final output before EOF")
            process.finish()
            assertTrue(withTimeout(5000) { exited.await() }.contains("final output before EOF"))
            assertFalse(state.isConnected)
        } finally {
            val engine = state.session?.sessionEngine
            state.dispose()
            process.kill()
            withTimeout(5000) { engine?.awaitTermination() }
        }
    }

    private fun services(spawn: () -> PlatformServices.ProcessService.ProcessHandle): PlatformServices =
        object : PlatformServices by getPlatformServices() {
            override fun getProcessService() = object : PlatformServices.ProcessService {
                override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) = spawn()
            }
        }

    private class FakeProcess : PlatformServices.ProcessService.ProcessHandle {
        val output = Channel<String>(Channel.UNLIMITED)
        val writes = CopyOnWriteArrayList<String>()
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
