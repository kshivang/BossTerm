package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.tabs.TabController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TerminalRuntimeLifecycleTest {
    @Test
    fun `classloader shutdown rejects a remote parser callback`() {
        TerminalRuntimeLifecycle.activateHostLifetime()
        val remote = TabController(TerminalSettings(), {}).createRemoteSession("remote")
        val checked = CountDownLatch(1)
        val beginBatch = remote.dataStream.onChunkStart
        remote.dataStream.onChunkStart = {
            assertFailsWith<IllegalStateException> { TerminalRuntimeLifecycle.shutdownForUnload() }
            beginBatch?.invoke()
            checked.countDown()
        }
        try {
            remote.dataStream.append("remote callback\r\n")
            assertTrue(checked.await(5, TimeUnit.SECONDS))
        } finally {
            remote.dispose()
            TerminalRuntimeLifecycle.shutdownForUnload()
            TerminalRuntimeLifecycle.activateHostLifetime()
        }
    }

    @Test
    fun `shutdown retains a removed tab reader fences late starts and supports reactivation`() = runBlocking {
        TerminalRuntimeLifecycle.activateHostLifetime()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fenced = CountDownLatch(1)
        val retiredProcess = FakeProcess()
        val controlProcess = FakeProcess()
        val laterProcess = FakeProcess()
        val laterSpawns = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val retired = controller(object : PlatformServices.ProcessService.ProcessHandle by retiredProcess {
            override suspend fun read(): String? {
                reading.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return "你好👩🏽‍💻"
            }
        })
        val control = controller(controlProcess)
        val later = controller(laterProcess) { laterSpawns.incrementAndGet() }
        try {
            retired.createTab(command = "fake-shell")
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            retired.closeTab(0)
            assertTrue(retired.tabs.isEmpty())
            val live = control.createTab(command = "fake-shell")
            withTimeout(5000) { while (live.connectionState.value !is ConnectionState.Connected) delay(10) }
            live.sessionEngine!!.onExit = { fenced.countDown() }
            val shutdown = executor.submit { TerminalRuntimeLifecycle.shutdownForUnload() }
            assertTrue(fenced.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { shutdown.get(150, TimeUnit.MILLISECONDS) }
            val refused = later.createTab(command = "fake-shell")
            assertTrue(refused.connectionState.value is ConnectionState.Error)
            assertEquals(0, laterSpawns.get())
            release.countDown()
            shutdown.get(5, TimeUnit.SECONDS)
            assertFalse(retiredProcess.isAlive())
            TerminalRuntimeLifecycle.activateHostLifetime()
            val reopened = later.createTab(command = "fake-shell")
            withTimeout(5000) { while (reopened.connectionState.value !is ConnectionState.Connected) delay(10) }
            assertEquals(1, laterSpawns.get())
        } finally {
            release.countDown()
            retired.disposeAll()
            control.disposeAll()
            later.disposeAll()
            TerminalRuntimeLifecycle.shutdownForUnload()
            TerminalRuntimeLifecycle.activateHostLifetime()
            executor.shutdownNow()
        }
    }

    @Test
    fun `classloader shutdown rejects a synchronous engine close callback`() = runBlocking {
        TerminalRuntimeLifecycle.activateHostLifetime()
        val process = FakeProcess()
        val controller = controller(process)
        var checked = false
        try {
            val tab = controller.createTab(command = "fake-shell")
            withTimeout(5000) { while (tab.connectionState.value !is ConnectionState.Connected) delay(10) }
            tab.sessionEngine!!.onExit = {
                assertFailsWith<IllegalStateException> { TerminalRuntimeLifecycle.shutdownForUnload() }
                checked = true
            }
            controller.closeTab(0)
            assertTrue(checked)
        } finally {
            controller.disposeAll()
            TerminalRuntimeLifecycle.shutdownForUnload()
            TerminalRuntimeLifecycle.activateHostLifetime()
        }
    }

    private fun controller(process: PlatformServices.ProcessService.ProcessHandle, spawning: () -> Unit = {}): TabController =
        TabController(TerminalSettings(autoInjectShellIntegration = false), {}, platformServices =
            object : PlatformServices by getPlatformServices() {
                override fun getProcessService() = object : PlatformServices.ProcessService {
                    override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig): PlatformServices.ProcessService.ProcessHandle {
                        spawning()
                        return process
                    }
                }
            })

    private class FakeProcess : PlatformServices.ProcessService.ProcessHandle {
        private val exited = CompletableDeferred<Unit>()
        override suspend fun read(): String? { exited.await(); return null }
        override suspend fun write(data: String) = Unit
        override suspend fun writeBytes(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun kill() { exited.complete(Unit) }
        override suspend fun waitFor(): Int { exited.await(); return 0 }
        override fun isAlive() = !exited.isCompleted
        override fun getExitCode(): Int? = if (isAlive()) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String? = null
    }
}
