package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.ConnectionState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSessionEngineTest {
    @Test
    fun `dispose waits for local parser and permits synchronous close callback reentry`() = runBlocking {
        val process = FakeProcess()
        val controller = TabController(TerminalSettings(autoInjectShellIntegration = false), {}, platformServices = services { process })
        val tab = controller.createTab(command = "fake-shell")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposing = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            withTimeout(5000) { while (tab.connectionState.value !is ConnectionState.Connected) delay(10) }
            val beginBatch = tab.dataStream.onChunkStart
            tab.dataStream.onChunkStart = {
                assertFailsWith<IllegalStateException> { tab.dispose() }
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                beginBatch?.invoke()
            }
            tab.sessionEngine!!.onExit = { tab.dispose() }
            process.output.send("你好👩🏽‍💻\r\n")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val disposal = executor.submit { disposing.countDown(); tab.dispose() }
            assertTrue(disposing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { disposal.get(150, TimeUnit.MILLISECONDS) }
            release.countDown()
            disposal.get(5, TimeUnit.SECONDS)
            withTimeout(5000) { tab.sessionEngine!!.awaitTermination() }
            tab.dispose()
        } finally {
            release.countDown()
            tab.dispose()
            process.kill()
            executor.shutdownNow()
        }
    }

    @Test
    fun `input before spawn and initial command share one ordered CR submitting FIFO`() = runBlocking<Unit> {
        val spawning = CountDownLatch(1)
        val finishSpawn = CountDownLatch(1)
        val process = FakeProcess()
        val controller = TabController(TerminalSettings(autoInjectShellIntegration = false, initialCommandDelayMs = 2000), {},
            platformServices = services { spawning.countDown(); check(finishSpawn.await(5, TimeUnit.SECONDS)); process })
        try {
            val tab = controller.createTab(command = "fake-shell", initialCommand = "printf READY")
            assertTrue(spawning.await(5, TimeUnit.SECONDS))
            tab.writeUserInput("first")
            tab.writeRawBytes("second".toByteArray())
            finishSpawn.countDown()
            process.output.send("\u001b]133;A\u0007")
            withTimeout(5000) { while (process.writes.size < 3) delay(10) }
            assertEquals(listOf("first", "second", "printf READY\r"), process.writes.toList())
            assertNotNull(tab.sessionEngine)
        } finally {
            finishSpawn.countDown()
            controller.disposeAll()
            process.kill()
        }
    }

    @Test
    fun `preconnected session keeps custom environment title cwd and typeahead adapters`() = runBlocking<Unit> {
        val process = FakeProcess()
        val config = CompletableDeferred<PlatformServices.ProcessService.ProcessConfig>()
        val controller = TabController(TerminalSettings(autoInjectShellIntegration = false, typeAheadEnabled = true), {},
            platformServices = services { received -> config.complete(received); process })
        try {
            val tab = controller.createTabWithPreConnect {
                TabController.PreConnectConfig("/bin/bash", workingDir = "/configured", environment = mapOf("CUSTOM" to "kept", "TERM" to "custom-term"))
            }
            val spawned = withTimeout(5000) { config.await() }
            assertEquals(listOf("-l"), spawned.arguments)
            assertEquals("kept", spawned.environment["CUSTOM"])
            assertEquals("custom-term", spawned.environment["TERM"])
            assertEquals("/configured", spawned.workingDirectory)
            withTimeout(5000) { while (tab.connectionState.value !is ConnectionState.Connected) delay(10) }
            assertNotNull(tab.typeAheadManager)
            process.output.send("\u001b]7;file://localhost/changed\u0007\u001b]1;tab label\u0007\u001b]2;window label\u0007")
            withTimeout(5000) {
                while (tab.workingDirectory.value != "/changed" || tab.display.iconTitle != "tab label" || tab.display.windowTitle != "window label") delay(10)
            }
        } finally {
            controller.disposeAll()
            process.kill()
        }
    }

    @Test
    fun `split exit callback sees final tail from oversized output already parsed`() = runBlocking<Unit> {
        val process = FakeProcess()
        val finalText = CompletableDeferred<String>()
        val controller = TabController(TerminalSettings(autoInjectShellIntegration = false), {}, platformServices = services { process })
        lateinit var tab: TerminalTab
        try {
            tab = controller.createSessionForSplit(command = "fake-shell", onProcessExit = {
                finalText.complete(tab.textBuffer.getScreenLines())
            }) as TerminalTab
            withTimeout(5000) { while (tab.connectionState.value !is ConnectionState.Connected) delay(10) }
            process.output.send("x".repeat(70_000) + "FINAL_TAIL")
            process.finish()
            assertTrue(withTimeout(5000) { finalText.await() }.contains("FINAL_TAIL"))
        } finally {
            runCatching { tab.dispose() }
            controller.disposeAll()
            process.kill()
        }
    }

    @Test
    fun `parent cancellation joins shared engine teardown and kills only its process`() = runBlocking<Unit> {
        val parent = Job()
        val process = FakeProcess()
        val controller = TabController(TerminalSettings(autoInjectShellIntegration = false), {}, platformServices = services { process },
            parentScope = CoroutineScope(parent))
        try {
            val tab = controller.createTab(command = "fake-shell")
            withTimeout(5000) { while (tab.connectionState.value !is ConnectionState.Connected) delay(10) }
            parent.cancel()
            withTimeout(5000) { parent.join() }
            assertFalse(process.isAlive())
            assertEquals(1, process.kills)
        } finally {
            controller.disposeAll()
            process.kill()
            parent.cancel()
        }
    }


    @Test
    fun `preconnect input waits for configuration and preserves mutable byte payloads`() = runBlocking<Unit> {
        val answer = CompletableDeferred<Unit>()
        val process = FakeProcess()
        val controller = TabController(TerminalSettings(autoInjectShellIntegration = false), {}, platformServices = services { process })
        var createdTab: TerminalTab? = null
        try {
            val tab = controller.createTabWithPreConnect {
                answer.await()
                TabController.PreConnectConfig("fake-shell")
            }.also { createdTab = it }
            tab.writeUserInput("before-answer")
            val bytes = "original".toByteArray()
            tab.writeRawBytes(bytes)
            bytes.fill('X'.code.toByte())
            answer.complete(Unit)
            withTimeout(5000) { while (process.writes.size < 2) delay(10) }
            assertEquals(listOf("before-answer", "original"), process.writes.toList())
        } finally {
            answer.complete(Unit)
            controller.disposeAll()
            process.kill()
            createdTab?.sessionEngine?.let { withTimeout(5000) { it.awaitTermination() } }
        }
    }

    private fun services(spawn: (PlatformServices.ProcessService.ProcessConfig) -> PlatformServices.ProcessService.ProcessHandle): PlatformServices =
        object : PlatformServices by getPlatformServices() {
            override fun getProcessService() = object : PlatformServices.ProcessService {
                override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) = spawn(config)
            }
        }

    private class FakeProcess : PlatformServices.ProcessService.ProcessHandle {
        val output = Channel<String>(Channel.UNLIMITED)
        val writes = CopyOnWriteArrayList<String>()
        private val exited = CompletableDeferred<Unit>()
        @Volatile var kills = 0
        override suspend fun write(data: String) { writes.add(data) }
        override suspend fun writeBytes(data: ByteArray) { writes.add(data.toString(Charsets.UTF_8)) }
        override suspend fun read(): String? = output.receiveCatching().getOrNull()
        override fun isAlive() = !exited.isCompleted
        override suspend fun kill() { kills++; finish() }
        override suspend fun waitFor(): Int { exited.await(); return 0 }
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override fun getExitCode(): Int? = if (isAlive()) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String? = null
        fun finish() { output.close(); exited.complete(Unit) }
    }
}
