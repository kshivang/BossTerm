package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.TabbedTerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Wires the GUI thin-client to the daemon: discovers the daemon's attach port (via a control STATUS
 * after [DaemonClient] connects) and attaches a single window's [TabbedTerminalState] to it through
 * a [DaemonSessionBridge], so daemon-hosted sessions render as tabs in that window.
 *
 * Process-wide singleton (one daemon per settings dir). v1 attaches ONE window at a time; its
 * controller readiness and the daemon connection can arrive in either order, so [register] polls
 * briefly for both before starting the bridge. When the attached window closes, the bridge fails
 * over to another still-open window (if any) so daemon sessions keep rendering. Entirely inert
 * unless `daemonEnabled`.
 */
object DaemonBridgeCoordinator {
    private val log = LoggerFactory.getLogger(DaemonBridgeCoordinator::class.java)

    private data class Attach(val port: Int, val secret: String)

    @Volatile private var attach: Attach? = null
    @Volatile private var activeState: TabbedTerminalState? = null
    @Volatile private var bridge: DaemonSessionBridge? = null

    // Every open window that has registered, in registration order — so when the attached window
    // closes we can fail the bridge over to a survivor instead of stranding it tab-less. Guarded by
    // its own monitor (register/unregister run on the UI thread, but be explicit).
    private val registered = LinkedHashMap<TabbedTerminalState, CoroutineScope>()

    // Process-wide one-shot guard for "auto-open a session when attaching to an empty daemon".
    // Must live here, NOT on the bridge: a bridge can be recreated (window re-attach), and a
    // stopped bridge's daemon session persists, so a per-bridge flag would auto-open again and
    // accumulate sessions. CAS so exactly one auto-open ever happens per GUI process.
    private val autoOpenClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Returns true to exactly one caller — the first bridge that finds the daemon empty. */
    fun claimAutoOpen(): Boolean = autoOpenClaimed.compareAndSet(false, true)

    /** Release a claim whose auto-open couldn't be issued, so a later reconcile can retry. */
    fun releaseAutoOpen() { autoOpenClaimed.set(false) }

    /** Record the daemon's attach endpoint after [DaemonClient.ensureConnected] succeeds (blocking STATUS). */
    fun onConnected(client: DaemonClient) {
        val ep = client.current ?: return
        val resp = client.request(DaemonProtocol.STATUS) ?: run { log.warn("daemon STATUS failed"); return }
        val payload = resp.removePrefix("OK ").trim()
        val status = runCatching {
            DaemonProtocol.json.decodeFromString(DaemonProtocol.Status.serializer(), payload)
        }.getOrNull() ?: run { log.warn("daemon STATUS unparseable: {}", resp); return }
        // MCP is hosted by the daemon in daemon mode (the in-process BossTermMcpManager isn't
        // started), so the GUI's MCP status indicator — which reads McpTerminalRegistry.runningPort —
        // would otherwise show "not running" even though the daemon serves it. Reflect the daemon's
        // bound MCP port so the indicator is accurate.
        status.mcpPort?.let { ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setRunning(it) }
        val ap = status.attachPort ?: run { log.warn("daemon reported no attach port"); return }
        attach = Attach(ap, ep.secret)
        log.info("Daemon attach endpoint: ws://127.0.0.1:{}/attach", ap)
    }

    /**
     * Attach [state]'s window to the daemon once both the daemon endpoint and the window's
     * controller are ready. v1 attaches one window at a time; additional windows are recorded so a
     * survivor can take over if the attached one closes. Safe to call from composition.
     */
    fun register(state: TabbedTerminalState, uiScope: CoroutineScope) {
        synchronized(registered) {
            registered[state] = uiScope
            if (activeState == null) promote(state, uiScope)
        }
    }

    /** Make [state] the attached window and start its bridge once endpoint + controller are ready. */
    private fun promote(state: TabbedTerminalState, uiScope: CoroutineScope) {
        activeState = state
        uiScope.launch {
            // Either ordering: the controller initializes during composition, the daemon connects
            // async. Poll briefly for both, then start the bridge.
            var tries = 0
            while (isActive && activeState === state && tries < 60) {
                val ctrl = state.tabController
                val a = attach
                if (ctrl != null && a != null) {
                    if (bridge == null) {
                        bridge = DaemonSessionBridge(ctrl, a.port, a.secret, uiScope).also { it.start() }
                        log.info("Daemon session bridge attached (attachPort={})", a.port)
                    }
                    return@launch
                }
                delay(250)
                tries++
            }
            if (bridge == null && activeState === state) {
                log.warn("Daemon bridge not started (controller/endpoint not ready in time)")
            }
        }
    }

    /** Detach when a window closes; if it was the attached one, fail over to another open window. */
    fun unregister(state: TabbedTerminalState) {
        synchronized(registered) {
            registered.remove(state)
            if (activeState !== state) return
            bridge?.stop()
            bridge = null
            activeState = null
            // Hand the bridge to any still-open window so daemon sessions keep rendering.
            registered.entries.firstOrNull()?.let { (next, scope) ->
                log.info("Attached window closed; failing daemon bridge over to another open window")
                promote(next, scope)
            }
        }
    }

    /** Open a new daemon-hosted session (the GUI's "new tab" when in daemon mode). No-op if unattached. */
    fun openSession(cwd: String? = null) {
        bridge?.openSession(cwd)
    }

    val isAttached: Boolean get() = bridge != null
}
