package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.ShareProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLDecoder

/** Lifecycle of a connection to a remote BossTerm share, surfaced in the UI. */
sealed class RemoteStatus {
    data object Connecting : RemoteStatus()
    /** Connected; awaiting the host's approval of this device. */
    data object Pending : RemoteStatus()
    /** Live; [canControl] = the host granted write access (else view-only). */
    data class Connected(val canControl: Boolean) : RemoteStatus()
    /** Host denied / the key expired. Terminal — no reconnect. */
    data class Denied(val reason: String?) : RemoteStatus()
    /** Could not connect / dropped after exhausting retries. */
    data class Failed(val message: String) : RemoteStatus()
    /** Closed by the user (remote removed). */
    data object Closed : RemoteStatus()
}

/**
 * The native counterpart of the browser `viewer.js`: a WebSocket client that connects to a
 * remote BossTerm shared session via its share **link** and streams [ServerMessage]s to a
 * handler (which mirrors the remote tabs locally). Reuses [ShareProtocol] for the wire format.
 *
 * Link `http(s)://host[:port]/?t=TOKEN` → WS `ws(s)://host[:port]/ws/TOKEN`. The handshake
 * sends [ClientMessage.Hello] (device name + stable clientId + any saved access key); the host
 * replies [ServerMessage.Pending] → [ServerMessage.Grant]/[ServerMessage.Denied], then streams
 * Theme/Layout/PaneSnapshot/Control and live PaneOutput/PaneResize. Reconnects (replaying the
 * saved key to skip re-approval) on a transient drop, up to [MAX_RECONNECTS].
 */
class RemoteSessionConnection(
    val link: String,
    private val clientId: String,
    private val deviceName: String,
    private val keyProvider: () -> String?,
    /** Persist a freshly granted key for this link (per the host's rolling 24h window). */
    private val onGrant: (key: String, expiresAt: Long) -> Unit,
    /** Every decoded server message (handshake handled here too, for status). */
    private val onServerMessage: (ServerMessage) -> Unit,
) {
    private val log = LoggerFactory.getLogger(RemoteSessionConnection::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) { install(WebSockets) }

    @Volatile private var session: DefaultClientWebSocketSession? = null
    @Volatile private var closedByUser = false
    @Volatile private var sawData = false // a Layout arrived this connection → it was healthy

    // Outbound messages go through a per-connection channel drained by ONE writer coroutine, so
    // frames reach the socket in submission order — independent `launch`-per-send would let two
    // rapid keystrokes race and arrive reordered (a terminal-input correctness bug).
    @Volatile private var outbox: kotlinx.coroutines.channels.SendChannel<ClientMessage>? = null

    // Best-effort: a malformed link must not crash construction — it just fails to connect.
    private val wsUrl: String = runCatching { toWsUrl(link) }.getOrDefault(link)
    // Host portion only — safe to log (the token, a bearer credential, must never hit logs).
    private val safeUrl: String = wsUrl.substringBefore("/ws/").substringBefore("?").ifBlank { "remote" } + "/…"

    private val _status = MutableStateFlow<RemoteStatus>(RemoteStatus.Connecting)
    val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    fun start() {
        scope.launch { runWithReconnect() }
    }

    /** Restart the loop after a terminal [RemoteStatus.Failed] (the disconnect dialog's Reconnect). */
    fun reconnect() {
        if (closedByUser || _status.value !is RemoteStatus.Failed) return
        _status.value = RemoteStatus.Connecting
        scope.launch { runWithReconnect() } // fresh retry budget (locals reset on entry)
    }

    private suspend fun runWithReconnect() {
        var attempt = 0
        while (scope.isActive && !closedByUser) {
            try {
                connectOnce()
            } catch (t: Throwable) {
                log.warn("remote session ({}) error: {}", safeUrl, t.message ?: t::class.simpleName)
            }
            if (closedByUser || _status.value is RemoteStatus.Denied) return
            // A connection that had actually loaded (received a Layout) gets a fresh retry
            // budget, so an occasional blip over a long session doesn't exhaust it.
            if (sawData) { attempt = 0; sawData = false }
            attempt++
            if (attempt > MAX_RECONNECTS) {
                _status.value = RemoteStatus.Failed("Lost connection to the remote session.")
                return
            }
            _status.value = RemoteStatus.Connecting
            delay((attempt * 1500L).coerceAtMost(8_000L))
        }
    }

    private suspend fun connectOnce() {
        client.webSocket(wsUrl) {
            session = this
            // Per-connection outbox + single writer → strict send ordering. Fresh each connection
            // so messages queued while disconnected aren't replayed stale on reconnect.
            val box = kotlinx.coroutines.channels.Channel<ClientMessage>(kotlinx.coroutines.channels.Channel.BUFFERED)
            outbox = box
            // Handshake first, ahead of any queued input.
            send(Frame.Text(ShareProtocol.encodeClient(
                ClientMessage.Hello(name = deviceName, clientId = clientId, key = keyProvider()))))
            val writer = launch {
                for (m in box) runCatching { send(Frame.Text(ShareProtocol.encodeClient(m))) }
                    .onFailure { log.debug("ws send failed: {}", it.message) }
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val msg = runCatching { ShareProtocol.decodeServer(frame.readText()) }.getOrNull() ?: continue
                        handle(msg)
                    }
                }
            } finally {
                outbox = null
                box.close()
                writer.cancel()
                session = null
            }
        }
    }

    private fun handle(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Pending -> _status.value = RemoteStatus.Pending
            // Defensive: mark Connected on Grant too, not just the Control that the host sends
            // right after — so a future reorder can't leave status stuck at Pending.
            is ServerMessage.Grant -> { onGrant(msg.key, msg.expiresAt); _status.value = RemoteStatus.Connected(msg.control) }
            is ServerMessage.Denied -> _status.value = RemoteStatus.Denied(msg.reason)
            is ServerMessage.Control -> _status.value = RemoteStatus.Connected(msg.granted)
            is ServerMessage.Layout -> sawData = true // session is up and rendering
            else -> {}
        }
        onServerMessage(msg)
    }

    /** Send a client message (Input / RequestControl / etc.). Queued in submission order; dropped if not connected. */
    fun send(msg: ClientMessage) {
        outbox?.trySend(msg)
    }

    /** Whether write/control was granted by the host. */
    val canControl: Boolean get() = (_status.value as? RemoteStatus.Connected)?.canControl == true

    fun close() {
        closedByUser = true
        _status.value = RemoteStatus.Closed
        scope.cancel()              // cancels the read loop → ktor closes the session
        runCatching { client.close() } // tear down the engine + any open socket
    }

    // Visible for tests. https→wss, anything else→ws; token taken from ?t= (URL-decoded).
    internal fun toWsUrl(link: String): String {
        val uri = URI(link.trim())
        val host = uri.host ?: throw IllegalArgumentException("share link has no host: $link")
        val rawToken = (uri.rawQuery ?: "").split("&")
            .firstOrNull { it.startsWith("t=") }?.substringAfter("t=").orEmpty()
        val token = runCatching { URLDecoder.decode(rawToken, "UTF-8") }.getOrDefault(rawToken)
        val wsScheme = if (uri.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$wsScheme://$host$port/ws/$token"
    }

    private companion object {
        const val MAX_RECONNECTS = 5
    }
}
