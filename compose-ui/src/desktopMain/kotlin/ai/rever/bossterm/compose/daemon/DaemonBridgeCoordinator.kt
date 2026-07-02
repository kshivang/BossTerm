package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.TabbedTerminalState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Wires the GUI thin-client to the daemon: discovers the daemon's attach port (via a control STATUS
 * after [DaemonClient] connects) and attaches a single window's [TabbedTerminalState] to it through
 * a [DaemonSessionBridge], so daemon-hosted sessions render as tabs in that window.
 *
 * Process-wide singleton (one daemon per settings dir). v1 attaches ONE window at a time; its
 * controller readiness and the daemon connection can arrive in either order, so [promote] awaits
 * both (event-driven — the endpoint via [attachState], the controller via [snapshotFlow]) before
 * starting the bridge. When the attached window closes, the bridge fails over to another still-open
 * window (if any) so daemon sessions keep rendering. Entirely inert unless `daemonEnabled`.
 */
object DaemonBridgeCoordinator {
    private val log = LoggerFactory.getLogger(DaemonBridgeCoordinator::class.java)

    /** The daemon's attach endpoint as this launch discovers it: unknown → ready | never-coming. */
    private sealed interface AttachEndpoint {
        /** Still discovering (daemon connect runs async at startup). */
        object Pending : AttachEndpoint
        data class Ready(val port: Int, val secret: String) : AttachEndpoint
        /** The daemon won't serve an attach endpoint this launch — unreachable, or reachable but
         *  its attach server failed to bind. Terminal state; windows fall back to local tabs. */
        object Unavailable : AttachEndpoint
    }

    // StateFlow (not a @Volatile var) so waiters wake the moment the endpoint resolves instead of
    // discovering it on a poll tick — the old 250ms poll granularity showed up directly in
    // time-to-first-tab on every warm start.
    private val attachState = MutableStateFlow<AttachEndpoint>(AttachEndpoint.Pending)
    @Volatile private var activeState: TabbedTerminalState? = null
    @Volatile private var bridge: DaemonSessionBridge? = null

    val isAttachUnavailable: Boolean get() = attachState.value is AttachEndpoint.Unavailable

    /** Mark the daemon as not serving an attach endpoint this launch (GUI falls back to local tabs).
     *  Only Pending → Unavailable: a late failure signal must not clobber a discovered endpoint. */
    fun markAttachUnavailable() { attachState.compareAndSet(AttachEndpoint.Pending, AttachEndpoint.Unavailable) }

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
        val resp = client.request(DaemonProtocol.STATUS)
            ?: run { log.warn("daemon STATUS failed"); markAttachUnavailable(); return }
        val payload = resp.removePrefix("OK ").trim()
        val status = runCatching {
            DaemonProtocol.json.decodeFromString(DaemonProtocol.Status.serializer(), payload)
        }.getOrNull() ?: run { log.warn("daemon STATUS unparseable: {}", resp); markAttachUnavailable(); return }
        // MCP is hosted by the daemon in daemon mode (the in-process BossTermMcpManager isn't
        // started), so the GUI's MCP status indicator — which reads McpTerminalRegistry.runningPort —
        // would otherwise show "not running" even though the daemon serves it. Reflect the daemon's
        // bound MCP port so the indicator is accurate.
        status.mcpPort?.let { ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setRunning(it) }
        val ap = status.attachPort
            ?: run { log.warn("daemon reported no attach port"); markAttachUnavailable(); return }
        attachState.value = AttachEndpoint.Ready(ap, ep.secret)
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
            // async. Await both event-driven (no poll granularity) under ONE deadline — it must
            // stay below the window's ~16s local-tab fallback grace (TabbedTerminal), which the
            // old 60×250ms poll also respected. The endpoint wait ends early on Unavailable — no
            // point sitting out the timeout for a daemon that will never serve one.
            val ready = withTimeoutOrNull(ATTACH_WAIT_MS) {
                val ep = attachState.first { it !is AttachEndpoint.Pending } as? AttachEndpoint.Ready
                    ?: return@withTimeoutOrNull null
                if (activeState !== state) return@withTimeoutOrNull null
                // The controller is Compose state set during composition — observe it, don't poll it.
                ep to snapshotFlow { state.tabController }.filterNotNull().first()
            }
            if (ready == null) {
                if (bridge == null && activeState === state) {
                    log.warn("Daemon bridge not started (controller/endpoint not ready in time)")
                }
                return@launch
            }
            val (endpoint, ctrl) = ready
            if (activeState === state && bridge == null) {
                bridge = DaemonSessionBridge(ctrl, state.splitStates, endpoint.port, endpoint.secret, uiScope).also { it.start() }
                log.info("Daemon session bridge attached (attachPort={})", endpoint.port)
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

    /**
     * Open a new daemon-hosted session (the GUI's "new tab" when in daemon mode). Returns whether the
     * request was actually enqueued onto a live bridge — false if unattached OR the socket is
     * mid-reconnect (outbox cleared), so the caller can fall back to a local tab instead of dropping
     * the request silently.
     */
    fun openSession(cwd: String? = null): Boolean = bridge?.openSession(cwd) ?: false

    /** Ask the daemon to split [sessionId] (a daemon-hosted pane) — the GUI's "split pane" when in
     *  daemon mode. Fire-and-forget like [openSession]; the new pane arrives via the next
     *  GroupList, no optimistic local splice. */
    fun splitPane(sessionId: String, orientation: String, cwd: String? = null): Boolean =
        bridge?.splitPane(sessionId, orientation, cwd) ?: false

    /** Ask the daemon to close one pane (session) — does not affect siblings. Fire-and-forget. */
    fun closePane(sessionId: String): Boolean = bridge?.closePane(sessionId) ?: false

    val isAttached: Boolean get() = bridge != null

    /** Single deadline for endpoint + controller readiness (matches the old 60×250ms poll window;
     *  cold daemon spawn + handshake fits well inside it, and it stays under the window's ~16s
     *  local-tab fallback grace). */
    private const val ATTACH_WAIT_MS = 15_000L
}
