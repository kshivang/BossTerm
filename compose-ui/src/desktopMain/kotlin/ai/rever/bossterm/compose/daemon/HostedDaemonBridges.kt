package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.TabbedTerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** UI-owned mirrors; registration itself queues no coroutine that could outlive UI unload. */
internal object HostedDaemonBridges {
    private class Entry(val scope: CoroutineScope, val port: Int, val token: String,
        val cwd: String?, val command: String?, var bridge: DaemonSessionBridge? = null)
    private val entries = java.util.concurrent.ConcurrentHashMap<TabbedTerminalState, Entry>()

    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retiring = java.util.concurrent.ConcurrentHashMap<DaemonSessionBridge, Job>()

    fun register(state: TabbedTerminalState, scope: CoroutineScope, port: Int, token: String, cwd: String?, command: String?) {
        entries.putIfAbsent(state, Entry(scope, port, token, cwd, command))
    }

    /** Called synchronously on the UI thread once TabbedTerminal has initialized its controller. */
    fun initialize(state: TabbedTerminalState) {
        val entry = entries[state] ?: return
        synchronized(entry) {
            if (entries[state] !== entry || entry.bridge != null) return
            val controller = state.tabController ?: return
            entry.bridge = DaemonSessionBridge(controller, state.splitStates, entry.port, entry.token, entry.scope,
                hosted = true, initialCwd = entry.cwd, initialCommand = entry.command).also { it.start() }
        }
    }

    fun unregister(state: TabbedTerminalState) {
        val entry = entries.remove(state) ?: return
        synchronized(entry) {
            entry.bridge?.let { bridge ->
                val retirement = retirementScope.launch(start = CoroutineStart.LAZY) {
                    try { bridge.awaitStopped() } finally { retiring.remove(bridge) }
                }
                retiring[bridge] = retirement
                bridge.stop()
                retirement.start()
            }
        }
    }

    /** External unload barrier. Ordinary disposal can be called inside a bridge's UI callback. */
    fun shutdownForUnload() {
        entries.keys.toList().forEach(::unregister)
        val jobs = retiring.values.toList()
        if (jobs.isEmpty()) return
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            // Pump cancellation of already-queued UI children while the external unload waits.
            // Ordinary unregister never joins its own in-flight UI callback.
            val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
            val remaining = java.util.concurrent.atomic.AtomicInteger(jobs.size)
            jobs.forEach { job -> job.invokeOnCompletion {
                if (remaining.decrementAndGet() == 0) java.awt.EventQueue.invokeLater { loop.exit() }
            } }
            if (remaining.get() != 0) check(loop.enter()) { "Could not drain terminal UI transports" }
        } else kotlinx.coroutines.runBlocking { jobs.forEach { it.join() } }
    }

    fun bridge(state: TabbedTerminalState?): DaemonSessionBridge? = state?.let { entries[it]?.bridge }
    fun contains(state: TabbedTerminalState?): Boolean = state != null && entries.containsKey(state)
    fun states(): List<TabbedTerminalState> = entries.keys.toList()
    fun bridges(): List<DaemonSessionBridge> = entries.values.mapNotNull { it.bridge }
}
