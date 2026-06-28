package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.share.TerminalSnapshotEncoder
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest

/**
 * Serves the daemon's [SessionHost] sessions to attached GUI clients over a loopback WebSocket,
 * so the GUI can render and steer daemon-hosted terminals as if they were local. Each GUI mirrors
 * a session as a tab (`TabController.createRemoteSession`) fed by [DaemonAttachProtocol.Server.Output].
 *
 * Loopback + secret-gated (the daemon's control secret, presented as `?token=`), so no E2E/approval
 * is needed — that's only for untrusted remote viewers ([ai.rever.bossterm.compose.share.MirrorShare]).
 * Output is pushed via each session's raw-output tap; a per-connection bounded DROP_OLDEST outbox
 * means a stalled GUI never blocks the PTY (the next snapshot heals it).
 */
class DaemonAttachServer(
    private val host: SessionHost,
    private val secret: String,
    /** When sharing is enabled, the daemon's public-share server — the GUI starts/stops/approves
     *  shares over this attach socket and observes them via [DaemonAttachProtocol.Server.ShareState]. */
    private val shareServer: DaemonShareServer? = null,
) {
    private val log = LoggerFactory.getLogger(DaemonAttachServer::class.java)

    @Volatile private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile var boundPort: Int = -1
        private set

    // Attached GUIs: a push channel (for the Focus message) + the GUI's OS pid (for activation).
    private class Client(val pid: Long?, val send: (DaemonAttachProtocol.Server) -> Unit)
    private val clients = java.util.concurrent.CopyOnWriteArrayList<Client>()

    /**
     * Bring attached GUIs forward. Sends a Focus message (the GUI tries alwaysOnTop — no permission)
     * AND, on macOS, activates the GUI process by pid via [DaemonLauncher.activatePid] (a background
     * app can't self-activate on modern macOS, but the daemon activating *another* process works —
     * may prompt for Accessibility once). Returns the number of attached clients.
     */
    fun focusClients(): Int {
        clients.forEach { c ->
            runCatching { c.send(DaemonAttachProtocol.Server.Focus) }
            c.pid?.let { DaemonLauncher.activatePid(it) }
        }
        return clients.size
    }

    /** Bind on loopback, trying [desiredPort]..+9. Returns the bound port or -1. */
    fun start(desiredPort: Int = 7682): Int {
        for (offset in 0 until 10) {
            val port = desiredPort + offset
            if (port > 65535) break
            if (!portAvailable(port)) continue
            try {
                val srv = embeddedServer(CIO, host = HOST, port = port) {
                    install(WebSockets)
                    routing { webSocket("/attach") { serve(this) } }
                }
                srv.start(wait = false)
                engine = srv
                boundPort = port
                log.info("Daemon attach server on ws://{}:{}/attach", HOST, port)
                return port
            } catch (e: Throwable) {
                log.warn("attach bind {}:{} failed: {}", HOST, port, e.message)
            }
        }
        log.error("Daemon attach server could not bind in [{},{}]", desiredPort, desiredPort + 9)
        return -1
    }

    fun stop() {
        runCatching { engine?.stop(200, 600) }
        engine = null
        boundPort = -1
    }

    private suspend fun serve(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        // DNS-rebinding defense (parity with the MCP server): only accept Host headers naming a
        // loopback target, so a browser pointed at a name resolving to 127.0.0.1 can't connect.
        val hostHeader = (ws.call.request.headers["Host"] ?: "").substringBefore(':').lowercase()
        if (hostHeader.isNotEmpty() && hostHeader != "127.0.0.1" && hostHeader != "localhost") {
            log.warn("attach: rejected connection with non-loopback Host '{}'", hostHeader)
            runCatching { ws.close() }
            return
        }
        val presented = ws.call.request.queryParameters["token"] ?: ""
        if (!constantTimeEquals(presented, secret)) {
            log.warn("attach: rejected connection with bad token")
            runCatching { ws.close() }
            return
        }

        // Per-connection outbox: taps + layout pushes enqueue; this coroutine drains to the socket.
        val outbox = Channel<String>(capacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        // sessionId → its output tap + size-collector job. Mutated from BOTH the incoming-frame
        // coroutine and the SessionHost change listener (arbitrary threads), so guarded by [lock].
        val attachments = HashMap<String, Attachment>()
        val lock = Any()

        fun send(m: DaemonAttachProtocol.Server) { outbox.trySend(DaemonAttachProtocol.encodeServer(m)) }
        val client = Client(ws.call.request.queryParameters["pid"]?.toLongOrNull(), { send(it) })
        clients.add(client)

        fun sessionList(): DaemonAttachProtocol.Server.SessionList =
            DaemonAttachProtocol.Server.SessionList(host.list().map { info ->
                val sz = host.get(info.id)?.display?.termSizeFlow?.value
                DaemonAttachProtocol.SessionMeta(info.id, info.title, info.cwd, sz?.columns ?: 80, sz?.rows ?: 24)
            })

        // Attach output tap + size collector + send the initial snapshot. Caller holds [lock].
        fun beginLocked(core: TerminalSessionCore) {
            if (attachments.containsKey(core.id)) return
            val sz = core.display.termSizeFlow.value
            send(DaemonAttachProtocol.Server.Snapshot(
                core.id,
                TerminalSnapshotEncoder.encode(core.textBuffer.createSnapshot(), core.terminal.cursorX, core.terminal.cursorY),
                sz.columns, sz.rows,
            ))
            val tap: (String) -> Unit = { d -> send(DaemonAttachProtocol.Server.Output(core.id, d)) }
            core.addRawOutputListener(tap)
            // Push Resized whenever the grid changes (GUI-driven resize OR a TUI resizing it) so the
            // attached mirror's buffer follows — without this it stays stuck at the snapshot size.
            val sizeJob = ws.launch {
                core.display.termSizeFlow.collect { send(DaemonAttachProtocol.Server.Resized(core.id, it.columns, it.rows)) }
            }
            // Push an updated SessionList when this session's cwd or title changes (OSC 7 / OSC 0,2),
            // so the attached client's tab name + secondary cwd update live. drop(1) skips the
            // initial combined emit already conveyed by the SessionList/Snapshot above.
            val metaJob = ws.launch {
                combine(core.workingDirectory, core.windowTitle) { cwd, title -> cwd to title }
                    .drop(1)
                    .collect { send(sessionList()) }
            }
            attachments[core.id] = Attachment(tap, listOf(sizeJob, metaJob))
        }

        // Detach a session's tap + collector jobs. Caller holds [lock].
        fun endLocked(id: String) {
            attachments.remove(id)?.let { a ->
                host.get(id)?.removeRawOutputListener(a.tap)
                a.jobs.forEach { it.cancel() }
            }
        }

        // Re-sync the client's session set to the host's: announce the list, begin new ones,
        // close vanished ones.
        fun resync() {
            // All sends for one resync happen under the lock so concurrent resyncs (WS coroutine vs
            // SessionHost.notifyChanged from an exit/control thread) can't interleave their
            // SessionList/Snapshot/Closed frames.
            synchronized(lock) {
                send(sessionList())
                val liveIds = host.list().map { it.id }.toSet()
                (attachments.keys - liveIds).toList().forEach { id ->
                    endLocked(id)
                    send(DaemonAttachProtocol.Server.Closed(id))
                }
                host.list().forEach { info -> host.get(info.id)?.let { beginLocked(it) } }
            }
        }

        val onChange: () -> Unit = { resync() }
        host.addChangeListener(onChange)

        // Drain outbox to the socket.
        val writer = ws.launch {
            try { for (text in outbox) ws.send(Frame.Text(text)) } catch (_: Exception) {}
        }

        // Forward daemon-share state to this GUI (Phase 2): the StateFlow emits the current value
        // immediately (initial paint) and every change (share start/stop, viewer join/leave, remote
        // status, pending approvals). No-op when sharing is disabled (shareServer == null).
        val shareJob = shareServer?.let { ss ->
            ws.launch {
                ss.state.collect { snap ->
                    send(DaemonAttachProtocol.Server.ShareState(snap.shares, snap.pending))
                }
            }
        }

        try {
            resync() // initial paint
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching { DaemonAttachProtocol.decodeClient(frame.readText()) }.getOrNull() ?: continue
                when (msg) {
                    is DaemonAttachProtocol.Client.Input -> host.get(msg.id)?.writeInput(msg.data)
                    is DaemonAttachProtocol.Client.Resize -> host.get(msg.id)?.resize(msg.cols, msg.rows)
                    is DaemonAttachProtocol.Client.Open -> host.openSession(cwd = msg.cwd, cols = msg.cols, rows = msg.rows)
                    is DaemonAttachProtocol.Client.Close -> host.closeSession(msg.id)
                    // Phase 2 share management — delegate to the daemon's public-share server (no-op
                    // if sharing is disabled). startShare is non-blocking; results arrive via ShareState.
                    is DaemonAttachProtocol.Client.StartShare -> shareServer?.startShare(msg.scope, msg.sessionId, msg.remoteMode)
                    is DaemonAttachProtocol.Client.StopShare -> shareServer?.stopShare(msg.token)
                    is DaemonAttachProtocol.Client.SetShareRemoteMode -> shareServer?.setRemoteMode(msg.token, msg.mode)
                    is DaemonAttachProtocol.Client.SetShareName -> shareServer?.setName(msg.token, msg.name)
                    is DaemonAttachProtocol.Client.ApproveViewer -> shareServer?.approveViewer(msg.token, msg.clientId, msg.control)
                    is DaemonAttachProtocol.Client.DenyViewer -> shareServer?.denyViewer(msg.token, msg.clientId)
                }
            }
        } catch (e: Exception) {
            log.debug("attach connection ended: {}", e.message)
        } finally {
            clients.remove(client)
            shareJob?.cancel()
            host.removeChangeListener(onChange)
            synchronized(lock) {
                attachments.keys.toList().forEach { endLocked(it) }
            }
            outbox.close()
            writer.cancel()
        }
    }

    /** Per-connection, per-session attachment: the output tap + its collector jobs (size, meta). */
    private class Attachment(val tap: (String) -> Unit, val jobs: List<kotlinx.coroutines.Job>)

    private fun portAvailable(port: Int): Boolean =
        runCatching { ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress(HOST, port)); true } }.getOrDefault(false)

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object { const val HOST = "127.0.0.1" }
}
