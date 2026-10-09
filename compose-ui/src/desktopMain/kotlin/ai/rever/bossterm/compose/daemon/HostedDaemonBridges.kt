package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.TabbedTerminalState
import kotlinx.coroutines.CoroutineScope

/** UI-owned mirrors; registration itself queues no coroutine that could outlive UI unload. */
internal object HostedDaemonBridges {
    private class Entry(val scope: CoroutineScope, val port: Int, val token: String,
        val cwd: String?, val command: String?, var bridge: DaemonSessionBridge? = null)
    private val entries = java.util.concurrent.ConcurrentHashMap<TabbedTerminalState, Entry>()

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
        synchronized(entry) { entry.bridge?.stopForUnload() }
    }

    fun bridge(state: TabbedTerminalState?): DaemonSessionBridge? = state?.let { entries[it]?.bridge }
    fun contains(state: TabbedTerminalState?): Boolean = state != null && entries.containsKey(state)
    fun states(): List<TabbedTerminalState> = entries.keys.toList()
    fun bridges(): List<DaemonSessionBridge> = entries.values.mapNotNull { it.bridge }
}
