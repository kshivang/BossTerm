package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.session.checkExternalTerminalUnloadCaller
import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.settings.TerminalSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class TerminalKillerCompletionTest {
    @Test
    fun `termination waiters leave IO permits available to the killer`(): Unit = runBlocking {
        val killStarted = CompletableDeferred<Unit>()
        val allowKill = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val abortProbe = AtomicBoolean()
        val state = EmbeddableTerminalState()
        val waiters = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val process = object : PlatformServices.ProcessService.ProcessHandle {
            override suspend fun kill() {
                assertFailsWith<IllegalStateException> { checkExternalTerminalUnloadCaller() }
                killStarted.complete(Unit)
                allowKill.await()
                if (abortProbe.get()) exited.complete(Unit)
                else withContext(Dispatchers.IO) { exited.complete(Unit) }
            }
            override suspend fun read(): String? { exited.await(); return null }
            override suspend fun waitFor(): Int { exited.await(); return 0 }
            override suspend fun write(data: String) = Unit
            override suspend fun writeBytes(data: ByteArray) = Unit
            override suspend fun resize(columns: Int, rows: Int) = Unit
            override fun isAlive() = !exited.isCompleted
            override fun getExitCode(): Int? = if (isAlive()) null else 0
            override fun getPid(): Long? = null
            override fun getWorkingDirectory(): String? = null
        }
        try {
            val services = object : PlatformServices by getPlatformServices() {
                override fun getProcessService() = object : PlatformServices.ProcessService {
                    override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) = process
                }
            }
            state.initializeSession(TerminalSettings(autoInjectShellIntegration = false), "fake-shell", null, null,
                null, null, null, null, services)
            withTimeout(5000) { while (!state.isConnected) delay(10) }
            val engine = state.session!!.sessionEngine!!
            engine.close()
            withTimeout(5000) { killStarted.await() }
            // Process exit and killer completion are separate events. Fully finish
            // every earlier awaitTermination gate while the actual kill stays held.
            exited.complete(Unit)
            withTimeout(5000) { awaitConsumersStopped(engine) }
            val ioParallelism = System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull()
                ?: maxOf(64, Runtime.getRuntime().availableProcessors())
            val completions = List(ioParallelism + 8) {
                // Since every earlier gate is complete, undispatched launch reaches
                // the killer wait before returning. The old IO join consumes/queues
                // all IO permits here; the completion Deferred suspends freely.
                waiters.async(start = CoroutineStart.UNDISPATCHED) { engine.awaitTermination() }
            }
            completions.forEach { assertFalse(it.isCompleted) }
            // Keep the probe outside the timeout's child scope: queued IO work must
            // not delay timeout cancellation and prevent releasing the held killer.
            val probe = waiters.async(start = CoroutineStart.UNDISPATCHED) { withContext(Dispatchers.IO) { Unit } }
            withTimeout(1500) { probe.await() }
            allowKill.complete(Unit)
            withTimeout(5000) { completions.awaitAll() }
        } finally {
            // Release an older implementation's parked IO joiners even if the probe
            // fails, so this regression reports a timeout without hanging the suite.
            abortProbe.set(true)
            allowKill.complete(Unit)
            state.disposeForUnload()
            waiters.cancel()
        }
    }

    private suspend fun awaitConsumersStopped(engine: TerminalSessionEngine) {
        fun field(name: String): Any? = TerminalSessionEngine::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(engine)
        (field("terminated") as CompletableDeferred<*>).await()
        (field("writeConsumer") as Job?)?.join()
        (field("resizeWorker") as Job?)?.join()
    }
}
