package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.session.checkExternalTerminalUnloadCaller
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Classloader-scoped lifecycle for embedders that unload the terminal library.
 * Standalone and daemon callers need not invoke this API.
 */
object TerminalRuntimeLifecycle {
    private val lock = Any()
    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engines = LinkedHashMap<TerminalSessionEngine, Job>()
    private var accepting = true
    private var shutdown: CountDownLatch? = null

    /** Reopen admission when the same plugin instance registers after completed shutdown. */
    fun activateHostLifetime() {
        checkExternalTerminalUnloadCaller()
        synchronized(lock) {
            if (accepting) return
            check(shutdown == null && engines.isEmpty()) { "Terminal runtime shutdown is still in progress" }
            accepting = true
        }
    }

    internal fun register(engine: TerminalSessionEngine): Boolean {
        val retirement = synchronized(lock) {
            if (!accepting) return false
            retirementScope.launch(start = CoroutineStart.LAZY) {
                try {
                    engine.awaitTermination()
                } finally {
                    synchronized(lock) { engines.remove(engine) }
                }
            }.also { engines[engine] = it }
        }
        retirement.start()
        return true
    }

    /**
     * Fence every later engine start, then stop and await all active and retiring engines.
     * Engines remain tracked after their tabs/states have disappeared until startup,
     * parsing, PTY reads/writes and process killing have all completed.
     *
     * Call from the external host after disposing its terminal states. Never call from
     * a terminal worker or callback. No registry monitor is held while stopping workers.
     * Caller interruption is restored only after shutdown completes.
     */
    fun shutdownForUnload() {
        checkExternalTerminalUnloadCaller()
        val completion: CountDownLatch
        val snapshot: List<Pair<TerminalSessionEngine, Job>>?
        synchronized(lock) {
            val existing = shutdown
            if (existing != null) {
                completion = existing
                snapshot = null
            } else {
                accepting = false
                completion = CountDownLatch(1)
                shutdown = completion
                snapshot = engines.entries.map { it.key to it.value }
            }
        }
        if (snapshot == null) {
            awaitUninterrupted(completion)
            return
        }
        try {
            snapshot.forEach { (engine, _) -> engine.close() }
            snapshot.forEach { (_, retirement) ->
                val finished = CountDownLatch(1)
                retirement.invokeOnCompletion { finished.countDown() }
                awaitUninterrupted(finished)
            }
        } finally {
            synchronized(lock) { shutdown = null }
            completion.countDown()
        }
    }

    private fun awaitUninterrupted(completion: CountDownLatch) {
        var interrupted = false
        try {
            while (true) {
                try {
                    completion.await()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
