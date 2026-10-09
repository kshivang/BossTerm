package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * GUI control lanes for standalone or hosted daemon shares. Hosted surfaces aggregate their state,
 * deduplicate a shared pool's tokens, and route token actions through a live owning connection.
 * Removing one window never clears another window's controls.
 */
object DaemonShareClient {
    private val log = LoggerFactory.getLogger(DaemonShareClient::class.java)

    /** Sends a client message over the active attach socket; null when no bridge is attached. */
    fun interface Sender {
        fun send(message: DaemonAttachProtocol.Client)
    }

    @Volatile private var sender: Sender? = null
    private val sources = java.util.IdentityHashMap<Sender, DaemonAttachProtocol.Server.ShareState>()

    private fun publish() {
        _state.value = DaemonAttachProtocol.Server.ShareState(
            shares = sources.values.flatMap { it.shares }.distinctBy { it.token },
            pending = sources.values.flatMap { it.pending }.distinctBy { it.token to it.clientId },
        )
    }

    private val _state = MutableStateFlow(DaemonAttachProtocol.Server.ShareState())

    /** Latest daemon-hosted shares + pending approvals. Empty until a bridge pushes a [ShareState]. */
    val state: StateFlow<DaemonAttachProtocol.Server.ShareState> = _state.asStateFlow()

    /** The active bridge registers its outbox here on connect so UI calls reach this socket. */
    @Synchronized
    fun registerSender(sender: Sender, hosted: Boolean = false) {
        if (!hosted) sources.clear()
        sources.putIfAbsent(sender, DaemonAttachProtocol.Server.ShareState())
        this.sender = sender
        publish()
    }

    /** Clear the sender on disconnect/stop; UI calls become no-ops until a bridge reattaches. */
    @Synchronized
    fun clearSender(sender: Sender) {
        sources.remove(sender)
        if (this.sender === sender) this.sender = sources.keys.firstOrNull()
        publish()
    }

    /** Push the daemon's latest share state into the flow the UI observes. */
    @Synchronized
    fun update(state: DaemonAttachProtocol.Server.ShareState, sender: Sender) {
        if (sources.containsKey(sender)) {
            sources[sender] = state
            publish()
        }
    }

    private fun send(message: DaemonAttachProtocol.Client, token: String? = null) {
        val s = synchronized(this) {
            if (token == null) sender else sources.entries.firstOrNull { (_, state) ->
                state.shares.any { it.token == token } || state.pending.any { it.token == token }
            }?.key
        }
        if (s == null) {
            log.debug("daemon-share action dropped (no bridge attached): {}", message::class.simpleName)
            return
        }
        s.send(message)
    }

    fun startShare(scope: String, sessionId: String? = null, remoteMode: String? = null, groupId: String? = null) =
        send(DaemonAttachProtocol.Client.StartShare(scope, sessionId, remoteMode, groupId))

    fun stopShare(token: String) = send(DaemonAttachProtocol.Client.StopShare(token), token)

    fun setRemoteMode(token: String, mode: String) =
        send(DaemonAttachProtocol.Client.SetShareRemoteMode(token, mode), token)

    fun setName(token: String, name: String) =
        send(DaemonAttachProtocol.Client.SetShareName(token, name), token)

    fun approve(token: String, clientId: String, control: Boolean) =
        send(DaemonAttachProtocol.Client.ApproveViewer(token, clientId, control), token)

    fun deny(token: String, clientId: String) =
        send(DaemonAttachProtocol.Client.DenyViewer(token, clientId), token)
}
