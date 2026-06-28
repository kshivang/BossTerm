package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * Process-wide hub for the GUI's daemon-share control lane (Phase 2). Analogous to
 * [DaemonBridgeCoordinator]: the active [DaemonSessionBridge] feeds it the daemon's latest
 * [DaemonAttachProtocol.Server.ShareState] and registers a [sender] so the UI's start/stop/approve
 * calls reach that one attach socket. The daemon owns the real share server, so this is purely a
 * thin observe-and-steer surface that the daemon-share window binds to.
 *
 * Lives here (not on the bridge) because the share window is opened from the tab UI and must reach
 * whichever bridge is currently attached; a bridge can be recreated on window re-attach. Thread-safe:
 * a [@Volatile] sender ref plus a [MutableStateFlow] the UI collects via `collectAsState()`.
 */
object DaemonShareClient {
    private val log = LoggerFactory.getLogger(DaemonShareClient::class.java)

    /** Sends a client message over the active attach socket; null when no bridge is attached. */
    fun interface Sender {
        fun send(message: DaemonAttachProtocol.Client)
    }

    @Volatile private var sender: Sender? = null

    private val _state = MutableStateFlow(DaemonAttachProtocol.Server.ShareState())

    /** Latest daemon-hosted shares + pending approvals. Empty until a bridge pushes a [ShareState]. */
    val state: StateFlow<DaemonAttachProtocol.Server.ShareState> = _state.asStateFlow()

    /** The active bridge registers its outbox here on connect so UI calls reach this socket. */
    fun registerSender(sender: Sender) {
        this.sender = sender
    }

    /** Clear the sender on disconnect/stop; UI calls become no-ops until a bridge reattaches. */
    fun clearSender(sender: Sender) {
        // Only the registering bridge may clear, so a late teardown of an old bridge doesn't
        // wipe a freshly-attached one.
        if (this.sender === sender) this.sender = null
    }

    /** Push the daemon's latest share state into the flow the UI observes. */
    fun update(state: DaemonAttachProtocol.Server.ShareState) {
        _state.value = state
    }

    private fun send(message: DaemonAttachProtocol.Client) {
        val s = sender
        if (s == null) {
            log.debug("daemon-share action dropped (no bridge attached): {}", message::class.simpleName)
            return
        }
        s.send(message)
    }

    fun startShare(scope: String, sessionId: String?, remoteMode: String?) =
        send(DaemonAttachProtocol.Client.StartShare(scope, sessionId, remoteMode))

    fun stopShare(token: String) = send(DaemonAttachProtocol.Client.StopShare(token))

    fun setRemoteMode(token: String, mode: String) =
        send(DaemonAttachProtocol.Client.SetShareRemoteMode(token, mode))

    fun setName(token: String, name: String) =
        send(DaemonAttachProtocol.Client.SetShareName(token, name))

    fun approve(token: String, clientId: String, control: Boolean) =
        send(DaemonAttachProtocol.Client.ApproveViewer(token, clientId, control))

    fun deny(token: String, clientId: String) =
        send(DaemonAttachProtocol.Client.DenyViewer(token, clientId))
}
