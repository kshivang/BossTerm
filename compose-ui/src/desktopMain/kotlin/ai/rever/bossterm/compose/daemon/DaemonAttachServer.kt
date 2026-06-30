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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
    /** Starts/stops the daemon's MCP server live (GUI MCP toggle); returns the bound port or null. */
    private val setMcpEnabled: ((Boolean) -> Int?)? = null,
) {
    private val log = LoggerFactory.getLogger(DaemonAttachServer::class.java)

    @Volatile private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile var boundPort: Int = -1
        private set

    // Attached GUIs: a push channel (for the Focus message) + the GUI's OS pid (for activation).
    private class Client(val pid: Long?, val send: (DaemonAttachProtocol.Server) -> Unit)
    private val clients = java.util.concurrent.CopyOnWriteArrayList<Client>()

    /** Number of currently-attached GUI clients (diagnostics + tests assert this drops on disconnect). */
    val clientCount: Int get() = clients.size

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

    /** Push the daemon's MCP state (bound port, or null = off) to every attached GUI. */
    private fun broadcastMcpState(port: Int?) {
        val msg = DaemonAttachProtocol.Server.McpState(port)
        clients.forEach { c -> runCatching { c.send(msg) } }
    }

    /** Bind on loopback, trying [desiredPort]..+9. Returns the bound port or -1. */
    fun start(desiredPort: Int = 7682): Int {
        // Refuse to start without a real secret — constantTimeEquals("", "") is true, so an empty
        // secret would accept every (token-less) client. Not reachable today (secret is 32-byte
        // SecureRandom), but fail loud rather than serve unauthenticated.
        if (secret.isEmpty()) {
            log.error("attach: refusing to start with an empty secret")
            return -1
        }
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

    @OptIn(FlowPreview::class) // Flow.debounce (stable behavior; still annotated preview in this version)
    private suspend fun serve(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        // DNS-rebinding defense (parity with the MCP server): only accept Host headers naming a
        // loopback target, so a browser pointed at a name resolving to 127.0.0.1 can't connect. A
        // legitimate WS client always sends a loopback Host, so treat an absent/empty Host as untrusted.
        val hostHeader = (ws.call.request.headers["Host"] ?: "").substringBefore(':').lowercase()
        if (hostHeader != "127.0.0.1" && hostHeader != "localhost") {
            log.warn("attach: rejected connection with non-loopback Host '{}'", hostHeader)
            runCatching { ws.close() }
            return
        }
        // Secret presented via a header (not a ?query= param) so it doesn't leak into request-line
        // logging / proxies / the daemon's redirected stdout. Loopback-only either way.
        val presented = ws.call.request.headers[DaemonAttachProtocol.TOKEN_HEADER] ?: ""
        if (!constantTimeEquals(presented, secret)) {
            log.warn("attach: rejected connection with bad token")
            runCatching { ws.close() }
            return
        }
        // Attach-protocol skew check: a client sends ?v=<version>. Absent (older client) is tolerated;
        // a present-but-mismatched version means the GUI and daemon disagree on the wire shape — refuse
        // rather than silently mis-render (the control channel has its own HELLO version handshake).
        val attachVer = ws.call.request.queryParameters["v"]
        if (attachVer != null && attachVer != DaemonAttachProtocol.PROTOCOL_VERSION.toString()) {
            log.warn("attach: rejected connection with incompatible protocol v{} (want {})", attachVer, DaemonAttachProtocol.PROTOCOL_VERSION)
            runCatching { ws.close() }
            return
        }

        // Per-connection outbox: taps + layout pushes enqueue; this coroutine drains to the socket.
        // Two lanes — control frames (snapshot/list/closed/resized/shareState) are guaranteed; only
        // incremental Output is droppable under back-pressure (the next snapshot/resync heals it).
        val outbox = FrameOutbox()
        // sessionId → its output tap + size-collector job. Mutated from BOTH the incoming-frame
        // coroutine and the SessionHost change listener (arbitrary threads), so guarded by [lock].
        val attachments = HashMap<String, Attachment>()
        val lock = Any()
        // Set under [lock] in the finally. removeChangeListener (CopyOnWriteArrayList.remove) does NOT
        // wait for an in-flight resync already dispatched on the notify thread; without this flag that
        // late resync could beginLocked() again AFTER teardown, re-registering an output tap (which
        // fires forever capturing the dead connection's send) with no pairing endLocked.
        var closed = false

        fun send(m: DaemonAttachProtocol.Server) {
            val text = DaemonAttachProtocol.encodeServer(m)
            // Only incremental Output rides the droppable lane; every other frame is guaranteed.
            if (m is DaemonAttachProtocol.Server.Output) outbox.sendOutput(text) else outbox.sendControl(text)
        }
        val client = Client(ws.call.request.queryParameters["pid"]?.toLongOrNull(), { send(it) })
        clients.add(client)

        fun sessionList(): DaemonAttachProtocol.Server.SessionList =
            DaemonAttachProtocol.Server.SessionList(host.list().map { info ->
                val sz = host.get(info.id)?.display?.termSizeFlow?.value
                DaemonAttachProtocol.SessionMeta(info.id, info.title, info.cwd, sz?.columns ?: 80, sz?.rows ?: 24)
            })

        fun groupList(): DaemonAttachProtocol.Server.GroupList =
            DaemonAttachProtocol.Server.GroupList(host.listGroups().map { GroupView(it.groupId, it.tree) })

        // Attach output tap + size collector + send the initial snapshot. Caller holds [lock].
        fun beginLocked(core: TerminalSessionCore) {
            if (attachments.containsKey(core.id)) return
            val sz = core.display.termSizeFlow.value
            // Register the output tap BEFORE encoding the snapshot, so PTY output produced while we
            // snapshot isn't lost (the old order — snapshot then listener — silently dropped that
            // window). Output that races the snapshot is held in [prelude] and flushed right after the
            // Snapshot frame is enqueued, so the client still sees Snapshot before Output — at worst a
            // small duplicated region instead of a gap.
            val preludeLock = Any()
            var prelude: ArrayList<String>? = ArrayList()
            var preludeChars = 0
            val tap: (String) -> Unit = { d ->
                val held = synchronized(preludeLock) {
                    val p = prelude
                    when {
                        p == null -> false // snapshot already enqueued → send live
                        // Cap the buffer: a session flooding output (e.g. `cat largefile`) during a
                        // slow large-scrollback encode is the one path that bypasses FrameOutbox
                        // backpressure. Past the cap, drop (a small gap heals on the next resync).
                        preludeChars + d.length > MAX_PRELUDE_CHARS -> true
                        else -> { p.add(d); preludeChars += d.length; true }
                    }
                }
                if (!held) send(DaemonAttachProtocol.Server.Output(core.id, d))
            }
            core.addRawOutputListener(tap)
            // From here the tap is live but NOT yet recorded in [attachments]; if the snapshot encode
            // throws, endLocked would never see it and the tap would fire forever on a dead connection.
            // Remove it on any failure before the attachment is recorded.
            try {
                send(DaemonAttachProtocol.Server.Snapshot(
                    core.id,
                    TerminalSnapshotEncoder.encode(core.textBuffer.createSnapshot(), core.terminal.cursorX, core.terminal.cursorY),
                    sz.columns, sz.rows,
                ))
                synchronized(preludeLock) {
                    prelude?.forEach { send(DaemonAttachProtocol.Server.Output(core.id, it)) }
                    prelude = null
                }
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
                        // Debounce: a TUI that sets its title to the running command (shells, vim, htop,
                        // progress spinners) churns this rapidly, and each emit rebuilds the WHOLE session
                        // list + per-session sizes and pushes it on the control lane. Coalesce bursts so we
                        // send at most one refresh per quiet window instead of flooding control (see #4).
                        .debounce(META_DEBOUNCE_MS)
                        .collect { send(sessionList()) }
                }
                attachments[core.id] = Attachment(core, tap, listOf(sizeJob, metaJob))
            } catch (e: Throwable) {
                core.removeRawOutputListener(tap)
                log.warn("beginLocked({}) failed before recording the attachment; removed orphan tap: {}", core.id, e.message)
            }
        }

        // Detach a session's tap + collector jobs. Caller holds [lock].
        fun endLocked(id: String) {
            attachments.remove(id)?.let { a ->
                // Remove the tap from the core DIRECTLY — host.get(id) is already null once a session
                // has exited and been reaped, which would otherwise skip the removal.
                a.core.removeRawOutputListener(a.tap)
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
                if (closed) return // connection torn down — don't re-attach taps/jobs after endLocked
                send(sessionList())
                send(groupList())
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

        // Drain outbox to the socket (control frames first, then output).
        val writer = ws.launch {
            try { outbox.drainTo { text -> ws.send(Frame.Text(text)) } } catch (_: Exception) {}
            // drainTo returns when the outbox closes — including the control-lane-saturation hard close
            // for a read-wedged client. Closing the socket here unblocks the `for (frame in incoming)`
            // loop below so serve()'s finally runs the cleanup promptly, instead of the client + taps +
            // change-listener lingering until the socket-level timeout.
            runCatching { ws.close() }
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
                    // openWindow() additionally registers a fresh single-pane group — every
                    // GUI-attach session is grouped, even alone, so reconcile logic never needs a
                    // grouped-vs-flat distinction. Return value (sessionId, groupId) was already
                    // discarded for openSession() too; the GUI learns the result via SessionList/
                    // GroupList, not a reply.
                    is DaemonAttachProtocol.Client.Open -> host.openWindow(cwd = msg.cwd, cols = msg.cols, rows = msg.rows)
                    is DaemonAttachProtocol.Client.Close -> host.closeSession(msg.id)
                    is DaemonAttachProtocol.Client.SplitPane -> host.splitPane(
                        sessionId = msg.sessionId,
                        orientation = if (msg.orientation == "h") SplitOrientation.HORIZONTAL else SplitOrientation.VERTICAL,
                        cwd = msg.cwd,
                        ratio = msg.ratio,
                    )
                    is DaemonAttachProtocol.Client.ClosePane -> host.closeGroupedSession(msg.sessionId)
                    is DaemonAttachProtocol.Client.UpdateSplitRatio -> host.updateSplitRatio(msg.groupId, msg.splitId, msg.ratio)
                    // Phase 2 share management — delegate to the daemon's public-share server (no-op
                    // if sharing is disabled). startShare is non-blocking; results arrive via ShareState.
                    is DaemonAttachProtocol.Client.StartShare -> shareServer?.startShare(msg.scope, msg.sessionId, msg.remoteMode)
                    is DaemonAttachProtocol.Client.StopShare -> shareServer?.stopShare(msg.token)
                    is DaemonAttachProtocol.Client.SetShareRemoteMode -> shareServer?.setRemoteMode(msg.token, msg.mode)
                    is DaemonAttachProtocol.Client.SetShareName -> shareServer?.setName(msg.token, msg.name)
                    is DaemonAttachProtocol.Client.ApproveViewer -> shareServer?.approveViewer(msg.token, msg.clientId, msg.control)
                    is DaemonAttachProtocol.Client.DenyViewer -> shareServer?.denyViewer(msg.token, msg.clientId)
                    // Live MCP enable/disable from the GUI's settings toggle; broadcast the new state
                    // so every attached GUI's MCP indicator updates (no-op if no controller wired).
                    is DaemonAttachProtocol.Client.SetMcpEnabled ->
                        setMcpEnabled?.let { broadcastMcpState(it.invoke(msg.enabled)) }
                }
            }
        } catch (e: Exception) {
            log.debug("attach connection ended: {}", e.message)
        } finally {
            clients.remove(client)
            shareJob?.cancel()
            host.removeChangeListener(onChange)
            // Flip `closed` and tear down under the same lock resync() takes, so a resync already
            // dispatched on the notify thread either ran fully before this (its taps get ended here)
            // or sees `closed` and no-ops — it can never re-attach after teardown.
            synchronized(lock) {
                closed = true
                attachments.keys.toList().forEach { endLocked(it) }
            }
            outbox.close()
            writer.cancel()
        }
    }

    /** Per-connection, per-session attachment: the core, its output tap + collector jobs (size, meta). */
    private class Attachment(val core: TerminalSessionCore, val tap: (String) -> Unit, val jobs: List<kotlinx.coroutines.Job>)

    private fun portAvailable(port: Int): Boolean =
        runCatching { ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress(HOST, port)); true } }.getOrDefault(false)

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        const val HOST = "127.0.0.1"
        /** Cap on the per-session prelude buffer (output racing a snapshot encode) — bounds heap. */
        const val MAX_PRELUDE_CHARS = 1_000_000
        /** Quiet window for coalescing title/cwd-driven session-list refreshes (ms). */
        const val META_DEBOUNCE_MS = 200L
    }
}
