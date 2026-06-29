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
 * Process-wide singleton (one daemon per settings dir). v1 attaches the FIRST window only; its
 * controller readiness and the daemon connection can arrive in either order, so [register] polls
 * briefly for both before starting the bridge. Entirely inert unless `daemonEnabled`.
 */
object DaemonBridgeCoordinator {
    private val log = LoggerFactory.getLogger(DaemonBridgeCoordinator::class.java)

    private data class Attach(val port: Int, val secret: String)

    @Volatile private var attach: Attach? = null
    @Volatile private var activeState: TabbedTerminalState? = null
    @Volatile private var bridge: DaemonSessionBridge? = null

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
     * controller are ready. v1: only the first window is attached. Safe to call from composition.
     */
    fun register(state: TabbedTerminalState, uiScope: CoroutineScope) {
        if (activeState != null) return
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
            if (bridge == null) log.warn("Daemon bridge not started (controller/endpoint not ready in time)")
        }
    }

    /** Detach when the attached window closes. */
    fun unregister(state: TabbedTerminalState) {
        if (activeState !== state) return
        bridge?.stop()
        bridge = null
        activeState = null
    }

    /** Open a new daemon-hosted session (the GUI's "new tab" when in daemon mode). No-op if unattached. */
    fun openSession(cwd: String? = null) {
        bridge?.openSession(cwd)
    }

    /** Route the GUI's MCP enable/disable to the daemon. No-op when no daemon is attached (the
     *  in-process MCP manager handles the toggle in non-daemon mode). */
    fun setMcpEnabled(enabled: Boolean) {
        bridge?.setMcpEnabled(enabled)
    }

    val isAttached: Boolean get() = bridge != null
}
