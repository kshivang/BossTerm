package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.theme.ColorPalette
import ai.rever.bossterm.compose.settings.theme.ColorPaletteManager
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.CloudflaredExposer
import ai.rever.bossterm.compose.share.Kex
import ai.rever.bossterm.compose.share.PaneTreeNode
import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.SessionCrypto
import ai.rever.bossterm.compose.share.ShareProtocol
import ai.rever.bossterm.compose.share.TabNode
import ai.rever.bossterm.compose.share.TailscaleExposer
import ai.rever.bossterm.compose.share.TerminalSnapshotEncoder
import io.ktor.http.CacheControl
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.BindException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The **daemon's** session-sharing server (issue #276, Phase 2) — the headless, GUI-free analogue of
 * [ai.rever.bossterm.compose.share.SessionShareManager]. It hosts the public xterm.js web viewer over
 * an embedded Ktor (CIO) server, end-to-end encrypted ([SessionCrypto]), and streams daemon-hosted
 * [TerminalSessionCore]s instead of Compose tabs — so a share link survives the GUI closing.
 *
 * Faithfully ports SessionShareManager's wire behavior (the SAME bundled `viewer.js` connects, so the
 * frame sequence + E2E handshake match byte-for-byte): per-connection [Kex] handshake, [ClientMessage.Hello],
 * the approval workflow + 24h rolling access keys (honoring [TerminalSettings.sessionSharingApprovalScope]),
 * then [ServerMessage.Theme]/[ServerMessage.McpStatus]/[ServerMessage.Layout] + per-pane
 * [ServerMessage.PaneSnapshot]/[ServerMessage.PaneOutput]/[ServerMessage.PaneResize]/[ServerMessage.Presence].
 *
 * The source side is re-implemented headlessly: a daemon session maps to ONE [TabNode] holding a single
 * leaf [PaneTreeNode.Pane] (paneId == session id), output comes from [TerminalSessionCore.addRawOutputListener],
 * and the in-scope session set comes from [SessionHost.list] + [SessionHost.addChangeListener] (no
 * windows/splits — a daemon session is a flat PTY). Control routes [ClientMessage.Input] →
 * [SessionHost.get]/[TerminalSessionCore.writeInput]; everything window/split/AI/rename-shaped is a no-op.
 *
 * [state] mirrors the live share set + pending approvals for the attach channel to forward to GUIs.
 * The Ktor server starts lazily on the first [startShare] and is torn down when the last share stops
 * (or on [stop]). Approvals/streaming run with or without a GUI attached — a pending request simply
 * stays in [state] until [approveViewer]/[denyViewer] resolves it.
 */
class DaemonShareServer(
    private val host: SessionHost,
    private val settings: () -> TerminalSettings,
    private val mcpPort: () -> Int? = { null },
) {
    private val log = LoggerFactory.getLogger(DaemonShareServer::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Current shares + viewers awaiting approval. The attach server forwards this to GUIs. */
    data class Snapshot(
        val shares: List<DaemonAttachProtocol.ShareView> = emptyList(),
        val pending: List<DaemonAttachProtocol.PendingApproval> = emptyList(),
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    // ---- engine lifecycle ----
    @Volatile private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile private var boundPort: Int? = null
    @Volatile private var boundHost: String? = null
    @Volatile private var stopped = false

    // ---- approval grants (24h rolling access keys) ----
    private val grants = ConcurrentHashMap<String, Grant>()
    private val pending = CopyOnWriteArrayList<DaemonPendingViewer>()

    // ---- shares ----
    // Two tokens (view + control) resolve to the SAME ShareDef, differing only in granted control.
    private val sharesByToken = ConcurrentHashMap<String, TokenRef>()
    private val shares = CopyOnWriteArrayList<ShareDef>()
    private val mutex = Any() // guards engine start/stop + the shares list (start/stop are infrequent)

    internal companion object {
        /**
         * Whether a mutating viewer verb targeting session [targetId] is in scope for a share of
         * [scope]/[defSessionId]. SESSION shares write-isolate to their own session (an approved
         * controller must not type into / resize / close another session by id); ALL covers everything.
         * Extracted + internal so [handleClient]'s scope gate is unit-testable.
         */
        fun mutationInScope(scope: String, defSessionId: String?, targetId: String): Boolean =
            scope == DaemonAttachProtocol.ShareScopeKind.ALL || targetId == defSessionId

        const val MAX_PORT_FALLBACK = 10
        const val GRANT_TTL_MS = 24L * 60 * 60 * 1000
        // A Cloudflare quick tunnel serves an error page until cloudflared registers an edge
        // connection; spin a fresh one a couple of times before falling back to the LAN link.
        const val MAX_REFRESHES = 2
        // Cap unapproved viewers parked on the 2-minute approval window so a public share can't be
        // flooded with sockets (each holds a slot + bloats every publishState()).
        const val MAX_PENDING_VIEWERS = 32
        const val MAX_PENDING_PER_IP = 4
        // Public (funnel/cloudflare/public-URL) shares face the open internet: tighter pending cap and
        // a shorter approval-park window so an attacker can't tie up slots for long.
        const val MAX_PENDING_VIEWERS_PUBLIC = 8
        const val PENDING_PARK_MS = 2 * 60_000L
        const val PENDING_PARK_PUBLIC_MS = 45_000L
        /** Cap on concurrent approved/connected viewers per share. */
        const val MAX_CONNECTED_VIEWERS = 16
        /** Hard ceiling on a grant key's life, independent of the sliding 24h window. */
        const val GRANT_MAX_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
        /** Cap on the per-viewer prelude buffer (output racing a snapshot encode) — bounds heap. */
        const val MAX_PRELUDE_CHARS = 1_000_000
        /** Delay before re-snapshotting a pane whose output was dropped under back-pressure —
         *  long enough to coalesce a burst of drops into one heal, short enough to feel instant. */
        const val RESNAPSHOT_DELAY_MS = 500L
    }

    /** A granted device key: which share, its role, the client it was issued to, and its windows. */
    private class Grant(
        val key: String,
        val shareToken: String,
        @Volatile var canControl: Boolean,
        @Volatile var expiresAtMs: Long,
        /** The Hello.clientId this key was issued to. Best-effort binding only: clientId is an
         *  unauthenticated, client-supplied device label, so a determined attacker holding a leaked key
         *  can replay the matching id — this raises the bar but the E2E secret + approval are the real
         *  boundary. Combined with [issuedAtMs]'s hard cap it bounds a leaked key's blast radius. */
        val clientId: String,
        /** When the key was first issued — enforces [GRANT_MAX_LIFETIME_MS] regardless of sliding. */
        val issuedAtMs: Long,
    )

    /** A token resolves to a share and whether THAT token grants control (control vs view link). */
    private class TokenRef(val share: ShareDef, val canControl: Boolean)

    /** Lifecycle of a share's remote-access link — maps 1:1 to the `remoteStatus` wire string. */
    private enum class RemoteStatus(val wire: String) {
        Off("off"), Starting("starting"), Installing("installing"), Verifying("verifying"),
        Retrying("retrying"), Active("active"), FellBack("fellback")
    }

    /**
     * One active daemon share. [scope] is [DaemonAttachProtocol.ShareScopeKind]; [sessionId] is set only
     * for a SESSION-scoped share. The E2E [secret] travels ONLY in the link's `#k=` fragment and is shared
     * by the view + control links — role stays server-enforced by which token a viewer presents.
     */
    private inner class ShareDef(
        val scope: String,
        val sessionId: String?,
    ) {
        val viewToken: String = secureToken()
        val controlToken: String = secureToken()
        val secret: ByteArray = SessionCrypto.newSessionSecret()
        val secretB64: String = SessionCrypto.encodeSecretB64Url(secret)

        @Volatile var name: String = defaultSessionName()

        // Remote-access state (driven on [scope]'s coroutines; @Volatile — read from the WS threads).
        @Volatile var remoteMode: String = "off"
        @Volatile var remoteUrl: String? = null
        @Volatile var remoteStatus: RemoteStatus = RemoteStatus.Off
        @Volatile var remoteAttempt: Int = 0
        @Volatile var remoteMaxAttempts: Int = 0
        @Volatile var remoteProcess: Process? = null
        @Volatile var activeRemoteMode: String = "off"
        // Monotonic token serializing this share's remote ops (mirror SessionShareManager.remoteOp):
        // a long establish bails if a newer op (switch/refresh/stop) supersedes it.
        private val remoteOp = AtomicInteger(0)
        fun claimRemoteOp(): Int = remoteOp.incrementAndGet()
        fun isCurrentRemoteOp(op: Int): Boolean = remoteOp.get() == op

        val viewers = CopyOnWriteArrayList<DaemonShareConnection>()
        val viewerSeq = AtomicInteger(0)

        val viewerCount: Int get() = viewers.size

        fun broadcast(msg: ServerMessage) {
            val text = FrameOutbox.Frame.Text(ShareProtocol.encodeServer(msg))
            // Broadcasts (Layout / Presence / Theme / ...) are all control-lane (guaranteed) frames.
            for (v in viewers) v.outbox.sendControl(text)
        }

        /** Build this share's [DaemonAttachProtocol.ShareView] for [state]. */
        fun toView(): DaemonAttachProtocol.ShareView {
            val viewUrl = buildUrl(viewToken) ?: ""
            val controlUrl = buildUrl(controlToken) ?: viewUrl
            return DaemonAttachProtocol.ShareView(
                token = viewToken,
                scope = scope,
                sessionId = sessionId,
                url = viewUrl,
                controlUrl = controlUrl,
                secure = isSecureUrl(viewUrl),
                e2eCode = e2eCodeOf(viewUrl),
                viewers = viewerCount,
                sessionName = name,
                remoteMode = remoteMode,
                remoteStatus = remoteStatus.wire,
                remoteAttempt = remoteAttempt,
                remoteMaxAttempts = remoteMaxAttempts,
            )
        }
    }

    // ===================================================================================
    // Public API
    // ===================================================================================

    /**
     * Start (or return the existing) share for the scope. Binds the Ktor server lazily; tunnel setup
     * proceeds async and is reflected in [state]. Returns the share's VIEW token, or null on failure
     * (sharing disabled / server can't bind / SESSION scope without a session id).
     */
    fun startShare(scope: String, sessionId: String? = null, remoteMode: String? = null): String? {
        if (stopped) return null
        if (!settings().sessionSharingEnabled) return null
        val kind = if (scope == DaemonAttachProtocol.ShareScopeKind.SESSION)
            DaemonAttachProtocol.ShareScopeKind.SESSION else DaemonAttachProtocol.ShareScopeKind.ALL
        if (kind == DaemonAttachProtocol.ShareScopeKind.SESSION && sessionId.isNullOrBlank()) {
            log.warn("startShare(SESSION) requires a sessionId")
            return null
        }
        synchronized(mutex) {
            // Idempotent per scope: a whole-daemon (ALL) share and per-session shares coexist, but a
            // re-share of the same scope/session returns the existing one rather than minting a dupe.
            shares.firstOrNull { it.scope == kind && it.sessionId == sessionId }?.let { existing ->
                remoteMode?.let { setRemoteMode(existing.viewToken, it) }
                return existing.viewToken
            }
            if (!ensureEngineLocked()) return null
            val def = ShareDef(kind, if (kind == DaemonAttachProtocol.ShareScopeKind.SESSION) sessionId else null)
            sharesByToken[def.viewToken] = TokenRef(def, canControl = false)
            sharesByToken[def.controlToken] = TokenRef(def, canControl = true)
            shares.add(def)
            val mode = remoteMode ?: settings().shareTailscaleMode
            if (mode != "off") startRemote(def, mode)
            publishState()
            log.info("daemon share started (scope={}, session={})", kind, sessionId ?: "-")
            return def.viewToken
        }
    }

    fun stopShare(token: String) {
        synchronized(mutex) {
            val def = sharesByToken[token]?.share ?: return
            sharesByToken.remove(def.viewToken)
            sharesByToken.remove(def.controlToken)
            shares.remove(def)
            grants.values.removeIf { it.shareToken == def.viewToken }
            failPendingFor(def.viewToken)
            def.viewers.forEach { runCatching { it.outbox.close() } }
            def.viewers.clear()
            val op = def.claimRemoteOp() // supersede any in-flight establish so it self-cleans
            val port = boundPort // capture BEFORE stopEngineLocked() may null it (else serve/funnel teardown is skipped)
            scope.launch(Dispatchers.IO) { teardownRemote(def, port, op) }
            if (shares.isEmpty()) stopEngineLocked()
            publishState()
            log.info("daemon share stopped")
        }
    }

    fun setRemoteMode(token: String, mode: String) {
        // Under [mutex] like startShare/stopShare: it reads boundPort and mutates share state, which a
        // concurrent stopShare/stop (nulling boundPort via stopEngineLocked) would otherwise race.
        synchronized(mutex) {
            val def = sharesByToken[token]?.share ?: return
            def.remoteMode = mode
            // Tear down the current exposure and establish the new one in place (same server + viewers,
            // a fresh link). Bump the op so any in-flight establish bails + self-cleans before we
            // re-establish.
            val op = def.claimRemoteOp()
            val port = boundPort
            scope.launch(Dispatchers.IO) {
                teardownRemote(def, port, op)
                if (mode != "off") establishRemote(def, mode)
                publishState()
            }
            publishState()
        }
    }

    fun setName(token: String, name: String) {
        // Under [mutex] like the other mutators, so a concurrent stopShare (sharesByToken/shares
        // removal) can't have us broadcast against a half-removed share.
        synchronized(mutex) {
            val def = sharesByToken[token]?.share ?: return
            def.name = name.trim().ifBlank { defaultSessionName() }
            // Re-broadcast the Layout so connected viewers pick up the new group label.
            def.broadcast(layoutFor(def))
            publishState()
        }
    }

    fun approveViewer(token: String, clientId: String, control: Boolean) {
        // Honor the host's choice as a DOWNGRADE: a control-link viewer (req.control) can be approved
        // view-only by pressing "View"; a view-link viewer can never be upgraded to typing. serveViewer
        // reads req.grantedControl for the admitted role.
        synchronized(mutex) {
            val req = pending.firstOrNull { it.token == token && it.clientId == clientId } ?: return
            req.grantedControl = req.control && control
            req.decision.complete(true)
            pending.remove(req)
            publishState()
        }
    }

    fun denyViewer(token: String, clientId: String) {
        synchronized(mutex) {
            val req = pending.firstOrNull { it.token == token && it.clientId == clientId } ?: return
            req.decision.complete(false)
            pending.remove(req)
            publishState()
        }
    }

    /** Stop the Ktor server, all shares, and tunnels (daemon shutdown). Safe to call repeatedly. */
    fun stop() {
        synchronized(mutex) {
            if (stopped) return
            stopped = true
            shares.toList().forEach { def ->
                def.viewers.forEach { runCatching { it.outbox.close() } }
                def.viewers.clear()
                val op = def.claimRemoteOp()
                // Synchronous teardown — the coroutine scope won't drain at process exit. Engine is
                // still up here (stopEngineLocked runs after this loop), so boundPort is valid.
                runCatching { runBlocking { teardownRemote(def, boundPort, op) } }
            }
            shares.clear()
            sharesByToken.clear()
            grants.clear()
            failAllPending()
            stopEngineLocked()
            _state.value = Snapshot() // reflect the now-empty server to any attached GUI
        }
        runCatching { scope.cancel() }
    }

    // ===================================================================================
    // State publishing
    // ===================================================================================

    /** Recompute [state] from the live shares + pending approvals. Cheap; called on every change. */
    private fun publishState() {
        if (stopped) { _state.value = Snapshot(); return }
        _state.value = Snapshot(
            shares = shares.map { it.toView() },
            pending = pending.map {
                DaemonAttachProtocol.PendingApproval(it.token, it.clientId, it.name, it.control)
            },
        )
    }

    private fun failPendingFor(shareToken: String) {
        val drop = pending.filter { it.token == shareToken }
        if (drop.isEmpty()) return
        pending.removeAll(drop)
        drop.forEach { it.decision.complete(false) }
    }

    private fun failAllPending() {
        val all = pending.toList()
        pending.clear()
        all.forEach { it.decision.complete(false) }
    }

    // ===================================================================================
    // Engine lifecycle (mutex-guarded)
    // ===================================================================================

    private fun resolveBindHost(s: TerminalSettings): String = when (s.sessionSharingBind) {
        "lan" -> "0.0.0.0"
        "custom" -> s.sessionSharingBindHost.ifBlank { "127.0.0.1" }
        else -> "127.0.0.1"
    }

    /**
     * True if [port] is free for the share server. Probes BOTH the bind [host] and `127.0.0.1` (the
     * latter because Tailscale Serve / cloudflared proxy to 127.0.0.1:<port>, so we must own loopback,
     * not just the wildcard). reuseAddress = false so a loopback-specific listener a wildcard bind
     * would shadow still reports the port as taken. Mirrors SessionShareManager.portBindable +
     * DaemonMcpServer.portAvailable.
     */
    private fun portBindable(host: String, port: Int): Boolean {
        val probes = buildList {
            add("127.0.0.1")
            if (host != "127.0.0.1") add(host)
        }
        return probes.all { addr ->
            runCatching {
                ServerSocket().use { ss ->
                    ss.reuseAddress = false
                    ss.bind(InetSocketAddress(addr, port))
                }
                true
            }.getOrDefault(false)
        }
    }

    /** Start the engine if not already running. Returns true if running afterwards. Caller holds [mutex]. */
    private fun ensureEngineLocked(): Boolean {
        if (engine != null) return true
        val s = settings()
        val host = resolveBindHost(s)
        val desiredPort = s.sessionSharingPort
        for (offset in 0 until MAX_PORT_FALLBACK) {
            val port = desiredPort + offset
            if (port > 65535) break
            // Never take the daemon's MCP port. It's loopback-only in the same range; a wildcard bind
            // could coexist with its loopback bind on the same port, and cloudflared (dials 127.0.0.1)
            // would then hit the MCP server's rebinding guard instead of the viewer.
            if (port == mcpPort()) {
                log.warn("share port {} is the daemon MCP port, trying next", port)
                continue
            }
            // CIO binds asynchronously, so a taken port surfaces as an uncaught BindException — probe
            // synchronously first and skip it.
            if (!portBindable(host, port)) {
                log.warn("share port {}:{} in use, trying next", host, port)
                continue
            }
            try {
                val started = embeddedServer(CIO, host = host, port = port) {
                    install(WebSockets)
                    routing {
                        webSocket("/ws/{token}") { serveViewer(this) }
                        // Static web viewer (index.html + viewer.js + css). Share URL:
                        // http://<host>:<port>/?t=<token>. no-cache so a phone re-validates the
                        // viewer assets (filenames aren't content-hashed) — unchanged ones 304.
                        staticResources("/", "share-viewer", index = "index.html") {
                            cacheControl { listOf(CacheControl.NoCache(null)) }
                        }
                    }
                }
                started.start(wait = false)
                engine = started
                boundPort = port
                boundHost = host
                log.info("daemon share server bound on {}:{} (bind={})", host, port, s.sessionSharingBind)
                return true
            } catch (e: Throwable) {
                val bind = generateSequence(e as Throwable?) { it.cause }.filterIsInstance<BindException>().firstOrNull()
                if (bind != null) {
                    log.warn("share port {}:{} in use, trying next", host, port)
                    continue
                }
                log.error("daemon share server failed to start on {}:{}", host, port, e)
                return false
            }
        }
        log.error("daemon share server could not bind any port from {}", desiredPort)
        return false
    }

    /** Stop the engine. Caller holds [mutex]. */
    private fun stopEngineLocked() {
        val e = engine ?: return
        runCatching { e.stop(300, 1000) }
        engine = null
        boundPort = null
        boundHost = null
        log.info("daemon share server stopped")
    }

    // ===================================================================================
    // Per-connection viewer loop (ported from SessionShareManager.serveViewer)
    // ===================================================================================

    /** Handle one viewer WebSocket: E2E handshake / approval, snapshot, then drain its outbox. */
    private suspend fun serveViewer(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        // DNS-rebinding defense: a public link binds wildcard, so only enforce loopback when bound
        // loopback (parity with DaemonMcpServer); a LAN/funnel bind is reachable by design.
        val bHost = boundHost
        if (bHost == "127.0.0.1") {
            val hostHeader = (ws.call.request.headers["Host"] ?: "").substringBefore(':').lowercase()
            // Reject a non-loopback OR ABSENT Host (a legitimate loopback client always sends one) —
            // parity with DaemonAttachServer, which treats an empty Host as untrusted.
            if (hostHeader != "127.0.0.1" && hostHeader != "localhost") {
                log.warn("share: rejected connection with non-loopback Host '{}'", hostHeader)
                runCatching { ws.close() }
                return
            }
        }

        val token = ws.call.parameters["token"]
        val ref = token?.let { sharesByToken[it] }
        if (ref == null) {
            ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unknown or expired share token"))
            return
        }
        val def = ref.share
        val shareToken = def.viewToken

        // E2E handshake. The first frame is either a plaintext Kex (client holds the link's `#k`
        // secret → encrypt everything) or a plaintext Hello (plain-LAN client → plaintext path). The
        // host always knows the secret, so it follows whichever the client speaks; a relay never sees
        // `#k`, so it can't forge a Kex to force a downgrade.
        val first = withTimeoutOrNull(10_000L) { runCatching { ws.incoming.receive() }.getOrNull() }
        val kex = (first as? Frame.Text)?.let { ShareProtocol.decodeKex(it.readText()) }
        var serverCipher: SessionCrypto.FrameCipher? = null
        var clientCipher: SessionCrypto.FrameCipher? = null
        val hello: ClientMessage.Hello?
        if (kex != null) {
            if (kex.v != 1) { ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unsupported encryption version")); return }
            val saltC = runCatching { SessionCrypto.decodeSecretB64Url(kex.salt) }.getOrNull()
            if (saltC == null) { ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Bad handshake")); return }
            val saltS = SessionCrypto.randomSalt()
            val keys = SessionCrypto.deriveKeys(def.secret, saltC, saltS)
            serverCipher = SessionCrypto.FrameCipher(keys.kS2c, SessionCrypto.DIR_S2C)
            clientCipher = SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_C2S)
            ws.send(Frame.Text(ShareProtocol.encodeKex(
                Kex(v = 1, salt = SessionCrypto.encodeSecretB64Url(saltS), confirm = keys.confirmB64))))
            val helloFrame = withTimeoutOrNull(10_000L) { runCatching { ws.incoming.receive() }.getOrNull() }
            if (helloFrame == null) { ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Handshake timeout")); return }
            if (helloFrame !is Frame.Binary) {
                ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Expected an encrypted handshake")); return
            }
            val helloText = runCatching { clientCipher.decrypt(helloFrame.data) }.getOrNull()
            if (helloText == null) {
                ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Wrong or missing encryption key")); return
            }
            hello = runCatching { ShareProtocol.decodeClient(helloText) }.getOrNull() as? ClientMessage.Hello
        } else {
            // A plaintext first frame is allowed on LAN/loopback (no relay) but refused on a public
            // tunnel/Funnel share — there it'd stream unencrypted through the relay, so an old/keyless
            // client must update. Send a plaintext Denied first (old clients render its reason), then close.
            if (requireE2E(def)) {
                runCatching {
                    ws.send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Denied(
                        "This shared session is end-to-end encrypted. Update BossTerm to a version that supports it."))))
                }
                ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Encryption required"))
                return
            }
            hello = (first as? Frame.Text)?.let {
                runCatching { ShareProtocol.decodeClient(it.readText()) }.getOrNull()
            } as? ClientMessage.Hello
        }

        // Send helper: encrypted binary frames when a cipher was negotiated, else plaintext text frames.
        suspend fun send(m: ServerMessage) {
            val text = ShareProtocol.encodeServer(m)
            serverCipher?.let { ws.send(Frame.Binary(true, it.encrypt(text))) } ?: ws.send(Frame.Text(text))
        }
        fun decodeIncoming(frame: Frame): ClientMessage? = when {
            clientCipher != null && frame is Frame.Binary ->
                runCatching { ShareProtocol.decodeClient(clientCipher.decrypt(frame.data)) }.getOrNull()
            clientCipher == null && frame is Frame.Text ->
                runCatching { ShareProtocol.decodeClient(frame.readText()) }.getOrNull()
            else -> null
        }

        val clientId = hello?.clientId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        var canControl = ref.canControl

        // Approval workflow + 24h rolling keys. Honors sessionSharingApprovalScope: a previously
        // granted live key slides its window and skips re-approval; otherwise the viewer is added to
        // [pending] (surfaced in [state]) and we await approveViewer/denyViewer (2-min timeout).
        if (requiresApproval(def)) {
            val now = System.currentTimeMillis()
            val existing = hello?.key?.let { grants[it] }
            // Resume only if the key is for THIS share, matches the (best-effort, client-supplied)
            // clientId, isn't expired, AND is within the hard lifetime cap — bounding a leaked key's reach.
            if (existing != null && existing.shareToken == shareToken && existing.clientId == clientId &&
                existing.expiresAtMs > now && now < existing.issuedAtMs + GRANT_MAX_LIFETIME_MS) {
                existing.expiresAtMs = now + GRANT_TTL_MS
                canControl = existing.canControl
                send(ServerMessage.Grant(existing.key, existing.expiresAtMs, canControl))
            } else {
                hello?.key?.let { grants.remove(it) } // drop a stale/expired/foreign key
                val public = isPublicInternet(def)
                val pendingCap = if (public) MAX_PENDING_VIEWERS_PUBLIC else MAX_PENDING_VIEWERS
                // Cap parked viewers so a public share can't be flooded with sockets that each hold a
                // pending slot (and bloat every publishState()).
                val remoteHost = runCatching { ws.call.request.origin.remoteHost }.getOrNull() ?: "?"
                // The per-IP cap only makes sense for genuinely distinct sources. Behind a cloudflare/
                // tailscale tunnel every viewer connects from 127.0.0.1 (the local tunnel agent), so a
                // per-IP cap there would collapse all remote viewers into one bucket — rely on the
                // (tighter, public) total cap instead. Direct LAN viewers keep distinct IPs.
                val isLoopback = remoteHost == "127.0.0.1" || remoteHost == "::1" || remoteHost == "localhost"
                val req = DaemonPendingViewer(
                    token = shareToken,
                    clientId = clientId,
                    name = hello?.name?.takeIf { it.isNotBlank() } ?: "Browser (${clientId.take(6)})",
                    control = ref.canControl,
                    remoteHost = remoteHost,
                )
                // Cap-check + add atomically under [mutex]: pending is a CopyOnWriteArrayList, so a bare
                // check-then-add would let N simultaneous viewers all observe size<cap and overshoot it.
                val overCap = synchronized(mutex) {
                    val perIpExceeded = !isLoopback && pending.count { it.remoteHost == remoteHost } >= MAX_PENDING_PER_IP
                    if (pending.size >= pendingCap || perIpExceeded) true else { pending.add(req); false }
                }
                if (overCap) {
                    log.warn("share: too many pending viewers (cap={}, from {}); rejecting", pendingCap, remoteHost)
                    runCatching { send(ServerMessage.Denied("Too many pending requests; try again later")) }
                    ws.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many pending viewers"))
                    return
                }
                publishState()
                runCatching { send(ServerMessage.Pending) }
                val approved = withTimeoutOrNull(if (public) PENDING_PARK_PUBLIC_MS else PENDING_PARK_MS) {
                    req.decision.await()
                } ?: false
                if (pending.remove(req)) publishState() // timed out without a decision
                if (!approved) {
                    runCatching { send(ServerMessage.Denied("Not approved")) }
                    ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Not approved"))
                    return
                }
                // Honor the host's approve choice as a downgrade (set in approveViewer): a control-link
                // viewer can be approved view-only; a view-link viewer is never upgraded.
                canControl = req.grantedControl
                val key = newKey()
                val issuedAt = System.currentTimeMillis()
                grants[key] = Grant(key, shareToken, canControl, issuedAt + GRANT_TTL_MS, clientId, issuedAt)
                send(ServerMessage.Grant(key, issuedAt + GRANT_TTL_MS, canControl))
            }
        }

        // Admit: Theme + McpStatus + Layout + a PaneSnapshot per in-scope session, THEN register the
        // taps/collectors so the outbox only carries output produced AFTER the snapshot (no double paint).
        send(themeMessage())
        send(mcpStatusMessage())
        send(layoutFor(def))

        val vc = DaemonShareConnection(def.viewerSeq.incrementAndGet(), canControl,
            hello?.name?.takeIf { it.isNotBlank() } ?: "Viewer (${clientId.take(6)})")

        // Per-session attachment: the output tap + its size collector. Mutated from BOTH the change
        // listener (any thread) and this coroutine, so guarded by [attachLock].
        val attachments = HashMap<String, Attachment>()
        val attachLock = Any()

        fun beginLocked(core: TerminalSessionCore) {
            if (attachments.containsKey(core.id)) return
            val sz = core.display.termSizeFlow.value
            // Register the tap BEFORE encoding the snapshot so output produced during encode isn't lost
            // (old order — snapshot then listener — dropped that window). Output racing the snapshot is
            // buffered in [prelude] and flushed right after, so the viewer sees PaneSnapshot before
            // PaneOutput (at worst a small duplicated region, never a gap).
            val preludeLock = Any()
            var prelude: ArrayList<String>? = ArrayList()
            var preludeChars = 0
            val tap: (String) -> Unit = { d ->
                val held = synchronized(preludeLock) {
                    val p = prelude
                    when {
                        p == null -> false // snapshot already enqueued → send live
                        // Cap the buffer (bounds heap if a session floods output during a slow
                        // large-scrollback encode — the one path that bypasses outbox backpressure).
                        // Past the cap, drop; a small gap heals on the next resync.
                        preludeChars + d.length > MAX_PRELUDE_CHARS -> true
                        else -> { p.add(d); preludeChars += d.length; true }
                    }
                }
                // Raw chunk, not a pre-encoded PaneOutput: the writer encodes at drain time, so the
                // outbox can coalesce queued same-pane chunks into ONE PaneOutput (concatenated pane
                // bytes are protocol-equivalent, and a backlog collapses into few frames).
                if (!held) vc.outbox.sendOutput(core.id, d)
            }
            core.addRawOutputListener(tap)
            // One-time styled initial paint (identical encoder to the attach server / MirrorShare).
            vc.outbox.sendControl(FrameOutbox.Frame.Text(ShareProtocol.encodeServer(ServerMessage.PaneSnapshot(
                core.id,
                TerminalSnapshotEncoder.encode(core.textBuffer.createSnapshot(), core.terminal.cursorX, core.terminal.cursorY),
                sz.columns, sz.rows,
            ))))
            synchronized(preludeLock) {
                prelude?.forEach { vc.outbox.sendOutput(core.id, it) }
                prelude = null
            }
            // Push PaneResize whenever the grid changes (a TUI resizing it), so the viewer's xterm.js follows.
            val sizeJob = ws.launch {
                core.display.termSizeFlow.collect {
                    vc.outbox.sendControl(FrameOutbox.Frame.Text(ShareProtocol.encodeServer(ServerMessage.PaneResize(core.id, it.columns, it.rows))))
                }
            }
            attachments[core.id] = Attachment(core, tap, sizeJob)
        }
        fun endLocked(id: String) {
            attachments.remove(id)?.let { a ->
                // Remove the tap from the core directly — host.get(id) is null once a session exited.
                a.core.removeRawOutputListener(a.tap)
                a.sizeJob.cancel()
            }
        }

        // Set under [attachLock] in the finally — a resync/heal already dispatched on another thread
        // must not re-register taps after teardown (they'd fire forever on a dead connection).
        var viewerClosed = false

        // Re-sync taps + resend Layout when the host's session set (or this scope's session) changes.
        fun resync() {
            synchronized(attachLock) {
                if (viewerClosed) return
                val live = inScopeCores(def).associateBy { it.id }
                (attachments.keys - live.keys).toList().forEach { endLocked(it) }
                live.values.forEach { beginLocked(it) }
            }
            vc.outbox.sendControl(FrameOutbox.Frame.Text(ShareProtocol.encodeServer(layoutFor(def))))
        }

        val onChange: () -> Unit = { resync() }
        host.addChangeListener(onChange)

        // Heal a viewer whose incremental output was evicted under back-pressure (slow remote link):
        // re-snapshot the affected pane after a quiet delay — same pattern as the attach server.
        val resnapshotPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        vc.outbox.onOutputDropped = { pid ->
            if (resnapshotPending.add(pid)) {
                ws.launch {
                    delay(RESNAPSHOT_DELAY_MS)
                    resnapshotPending.remove(pid) // before healing: drops during the heal re-arm it
                    synchronized(attachLock) {
                        if (!viewerClosed) {
                            endLocked(pid)
                            // Tap is detached — purge the pane's still-queued output so the fresh
                            // snapshot isn't followed by older chunks it already contains.
                            vc.outbox.dropQueuedOutput(pid)
                            inScopeCores(def).firstOrNull { it.id == pid }?.let { beginLocked(it) }
                        }
                    }
                }
            }
        }

        // Register the viewer UNDER [mutex] and verify the share is still live + under the viewer cap.
        // stopShare clears def.viewers under the lock, so adding outside it could orphan a viewer that
        // keeps streaming a "stopped" share; and an uncapped connected count is a DoS on a public share.
        val rejection = synchronized(mutex) {
            when {
                def !in shares -> "stopped"
                def.viewers.size >= MAX_CONNECTED_VIEWERS -> "full"
                else -> { def.viewers.add(vc); null }
            }
        }
        if (rejection != null) {
            host.removeChangeListener(onChange)
            runCatching { vc.outbox.close() }
            runCatching {
                if (rejection == "full") {
                    send(ServerMessage.Denied("This share is at its viewer limit; try again later."))
                    ws.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Viewer limit reached"))
                } else {
                    ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Share stopped"))
                }
            }
            return
        }
        def.broadcast(ServerMessage.Presence(def.viewerCount))
        publishState() // viewer count changed

        send(ServerMessage.Control(granted = canControl))

        val sc = serverCipher
        val writer = ws.launch {
            try {
                vc.outbox.drainTo { f ->
                    val text = when (f) {
                        is FrameOutbox.Frame.Text -> f.text
                        // Coalesced pane bytes → one PaneOutput, encoded here at drain time.
                        is FrameOutbox.Frame.Output -> ShareProtocol.encodeServer(ServerMessage.PaneOutput(f.sessionId, f.data))
                        is FrameOutbox.Frame.Binary -> {
                            // Never enqueued on the share path — loud skip so a future mis-wiring
                            // surfaces instead of silently dropping a frame.
                            log.warn("share viewer {}: dropping unexpected binary control frame", vc.id)
                            return@drainTo
                        }
                    }
                    sc?.let { ws.send(Frame.Binary(true, it.encrypt(text))) } ?: ws.send(Frame.Text(text))
                }
            } catch (_: Throwable) { /* socket gone */ }
        }

        try {
            synchronized(attachLock) { inScopeCores(def).forEach { beginLocked(it) } } // initial paint
            for (frame in ws.incoming) {
                val msg = decodeIncoming(frame) ?: continue
                handleClient(def, vc, msg)
            }
        } catch (_: Throwable) {
            // client gone
        } finally {
            host.removeChangeListener(onChange)
            synchronized(attachLock) {
                viewerClosed = true
                attachments.keys.toList().forEach { endLocked(it) }
            }
            writer.cancel()
            if (def.viewers.remove(vc)) {
                runCatching { vc.outbox.close() }
                def.broadcast(ServerMessage.Presence(def.viewerCount))
                publishState()
            }
        }
    }

    /** Per-connection, per-session attachment: the core, its raw-output tap + size-collector job. */
    private class Attachment(val core: TerminalSessionCore, val tap: (String) -> Unit, val sizeJob: Job)

    /**
     * Route a viewer message to the daemon (controller role only for mutating actions). A daemon
     * session is a flat single-pane PTY, so window/split/AI/rename/color/MCP verbs are NO-OPs here
     * (they only make sense for the GUI's window→tabs→panes model in MirrorShare).
     */
    private fun handleClient(def: ShareDef, vc: DaemonShareConnection, msg: ClientMessage) {
        if (!vc.canControl) return // every mutating action requires the control role
        // A SESSION-scoped share must WRITE-isolate to its one session, not just render-isolate it. The
        // read/layout path (inScopeCores/layoutFor) is already scoped, but without this an approved
        // controller on a "This session" link could send Input/Resize/Close for ANOTHER session's id
        // (ids leak via MCP LIST_SESSIONS / attach layouts) or NewTab a brand-new shell — escaping the
        // share. Gate every mutating verb on scope; ALL covers every session, SESSION only its own.
        val isAll = def.scope == DaemonAttachProtocol.ShareScopeKind.ALL
        fun inScope(id: String): Boolean = mutationInScope(def.scope, def.sessionId, id)
        when (msg) {
            is ClientMessage.Input -> if (inScope(msg.paneId)) host.get(msg.paneId)?.writeInput(msg.data)
            is ClientMessage.ResizeHost -> if (inScope(msg.tabId)) host.get(msg.tabId)?.resize(msg.cols, msg.rows)
            // NewTab carries no id, so it can't be scoped — opening a fresh shell escapes a SESSION share.
            is ClientMessage.NewTab -> if (isAll) host.openSession()
            is ClientMessage.CloseTab -> if (inScope(msg.tabId)) host.closeSession(msg.tabId)
            is ClientMessage.ClosePane -> if (inScope(msg.paneId)) host.closeSession(msg.paneId)
            // SplitVertical/SplitHorizontal/LaunchAI/RenameTab/SetTabColor/DuplicateTab/CloseOtherTabs/
            // CloseTabsBelow/ResizeSplit/CloseWindow/DisconnectUpstream/OfferShare/SetMcpEnabled/
            // AttachMcp/Focus/RequestControl/Hello → no-op (daemon sessions are single-pane PTYs).
            else -> {}
        }
    }

    // ===================================================================================
    // Session → viewer mapping (each TerminalSessionCore → one single-leaf TabNode)
    // ===================================================================================

    /** The daemon sessions a share covers: every session for ALL, just the one for SESSION. */
    private fun inScopeCores(def: ShareDef): List<TerminalSessionCore> =
        if (def.scope == DaemonAttachProtocol.ShareScopeKind.SESSION) {
            listOfNotNull(def.sessionId?.let { host.get(it) })
        } else {
            host.list().mapNotNull { host.get(it.id) }
        }

    /**
     * Build the [ServerMessage.Layout] for a share: one [TabNode] per in-scope session, each a single
     * leaf [PaneTreeNode.Pane] (paneId == session id). summaryMode = true (one chip per tab); the
     * viewer renders single-leaf, no-split layouts fine.
     */
    private fun layoutFor(def: ShareDef): ServerMessage.Layout {
        val infos = if (def.scope == DaemonAttachProtocol.ShareScopeKind.SESSION)
            host.list().filter { it.id == def.sessionId } else host.list()
        val tabs = infos.mapNotNull { info ->
            val core = host.get(info.id) ?: return@mapNotNull null
            val title = core.windowTitle.value.ifBlank { info.title }
            val cwd = core.workingDirectory.value
            TabNode(
                id = info.id,
                title = title,
                active = false,
                tree = PaneTreeNode.Pane(paneId = info.id, title = title, cwd = cwd, focused = true),
                cwd = cwd,
            )
        }
        val activeId = tabs.firstOrNull()?.id
        return ServerMessage.Layout(
            tabs = tabs,
            activeTabId = activeId,
            // Mirror the host's tab-bar placement (like MirrorShare does) instead of hardcoding top.
            tabBarOnLeft = settings().tabBarPosition == "left",
            summaryMode = true, // one chip per tab
            sessionName = def.name,
        )
    }

    /** Host terminal [ServerMessage.Theme] — sourced exactly like MirrorShare so the viewer matches BossTerm. */
    private fun themeMessage(): ServerMessage.Theme {
        val theme = ThemeManager.instance.currentTheme.value
        val palette = ColorPaletteManager.instance.currentPalette.value ?: ColorPalette.fromTheme(theme)
        val s = settings()
        val font = s.fontName
            ?.takeIf { it.isNotBlank() }
            ?.let { "\"$it\", Menlo, Monaco, monospace" }
            ?: "Menlo, Monaco, \"Courier New\", monospace"
        return ServerMessage.Theme(
            background = hexToCss(theme.background),
            foreground = hexToCss(theme.foreground),
            cursor = hexToCss(theme.cursor),
            cursorAccent = hexToCss(theme.cursorText),
            selectionBackground = hexToCss(theme.selection),
            ansi = (0..15).map { hexToCss(palette.getAnsiColorHex(it)) },
            fontFamily = font,
            fontSize = s.fontSize.toInt(),
        )
    }

    /** Daemon MCP state for the viewer's MCP pill — running iff the daemon MCP port is bound. */
    private fun mcpStatusMessage(): ServerMessage.McpStatus {
        val running = mcpPort() != null
        return ServerMessage.McpStatus(enabled = running, running = running, attached = emptyList(), serverLabel = "BossTerm MCP")
    }

    private fun hexToCss(argb: String): String {
        val h = argb.removePrefix("0x").removePrefix("0X").removePrefix("#")
        val rrggbb = when {
            h.length >= 8 -> h.substring(h.length - 6)
            h.length == 6 -> h
            else -> "FFFFFF"
        }
        return "#$rrggbb"
    }

    // ===================================================================================
    // Approval / E2E policy (ported from SessionShareManager)
    // ===================================================================================

    /**
     * Whether a connecting device must be approved before it can view/control, per
     * [TerminalSettings.sessionSharingApprovalScope]: "all" = always; "off" = never; "funnel"
     * (default) = only when the share is publicly reachable (Funnel / Cloudflare / a custom public URL).
     */
    private fun requiresApproval(def: ShareDef): Boolean {
        val s = settings()
        return when (s.sessionSharingApprovalScope) {
            "all" -> true
            "off" -> false
            // "serve" is reachable by every node on the tailnet (not just this machine), so treat it
            // like funnel/cloudflare: require approval rather than auto-admitting tailnet viewers.
            else -> def.activeRemoteMode == "serve" || def.activeRemoteMode == "funnel" ||
                def.activeRemoteMode == "cloudflare" || s.sessionSharingPublicUrl.isNotBlank()
        }
    }

    /** True when this share is reachable from the open internet (vs LAN/loopback/tailnet only) — used
     *  to tighten the pending-viewer caps. (serve is tailnet-authenticated, so not counted here.) */
    private fun isPublicInternet(def: ShareDef): Boolean =
        def.activeRemoteMode == "funnel" || def.activeRemoteMode == "cloudflare" ||
            settings().sessionSharingPublicUrl.isNotBlank()

    /**
     * Whether the host should REQUIRE E2E (reject a plaintext handshake): true whenever a remote
     * provider is active (the relay is the threat and every link carries `#k`). Keyed off the active
     * MODE, not remoteUrl — remoteUrl stays null until verification completes, but a tunnel can be
     * reachable at the edge before then, so basing it on the URL leaves a plaintext-downgrade window.
     * Plain-LAN/loopback http has no relay, so plaintext stays allowed there.
     */
    private fun requireE2E(def: ShareDef): Boolean {
        if (def.activeRemoteMode == "serve" || def.activeRemoteMode == "funnel" || def.activeRemoteMode == "cloudflare") return true
        val url = def.remoteUrl
            ?: settings().sessionSharingPublicUrl.takeIf { it.isNotBlank() }
            ?: return false
        return e2eCapable(url)
    }

    // ===================================================================================
    // Remote access (Tailscale / Cloudflare) — per-share lifecycle
    // ===================================================================================

    private fun startRemote(def: ShareDef, mode: String) {
        def.remoteMode = mode
        scope.launch(Dispatchers.IO) {
            establishRemote(def, mode)
            publishState()
        }
    }

    /**
     * Bring up remote access for [mode] on this share's port, publishing the URL only once confirmed
     * working, and driving the share's RemoteStatus Starting → Verifying/Retrying → Active (or →
     * FellBack, leaving the LAN link). [op] gating: a newer remote op supersedes a slow establish.
     */
    private suspend fun establishRemote(def: ShareDef, mode: String) {
        val port = boundPort ?: return
        val op = def.claimRemoteOp()
        def.activeRemoteMode = mode
        def.remoteUrl = null
        def.remoteAttempt = 0
        def.remoteMaxAttempts = 0
        def.remoteStatus = RemoteStatus.Starting
        publishState()
        val url: String? = when (mode) {
            "cloudflare" -> establishCloudflareVerified(def, port, op)
            "serve", "funnel" -> TailscaleExposer.enable(mode, port)
            else -> null
        }
        if (!def.isCurrentRemoteOp(op)) return // superseded while establishing — don't publish
        if (url != null) {
            def.remoteUrl = url
            def.remoteStatus = RemoteStatus.Active
            log.info("daemon share reachable via {}", mode)
            if (mode == "cloudflare") def.remoteProcess?.let { registerRespawn(def, it, op) }
        } else {
            def.remoteStatus = RemoteStatus.FellBack
            log.warn("remote access ({}) yielded no working link; using the LAN link", mode)
        }
        publishState()
    }

    /**
     * Start a Cloudflare quick tunnel and return its URL only once cloudflared reports it routable.
     * Mirrors SessionShareManager.establishCloudflareVerified: install if needed, hold each tunnel in
     * a local until it's verified AND still the current [op], then adopt it; a failed/superseded
     * attempt destroys its own tunnel so nothing leaks. Returns null → caller falls back to LAN.
     */
    private suspend fun establishCloudflareVerified(def: ShareDef, port: Int, op: Int): String? {
        if (!CloudflaredExposer.isInstalled()) {
            if (!def.isCurrentRemoteOp(op)) return null
            if (!CloudflaredExposer.canAutoInstall()) return null
            def.remoteStatus = RemoteStatus.Installing
            publishState()
            val installed = withContext(Dispatchers.IO) { CloudflaredExposer.ensureInstalled() }
            if (!def.isCurrentRemoteOp(op)) return null
            if (!installed) { log.warn("cloudflared install failed; falling back to LAN link"); return null }
        }
        var refreshes = 0
        while (def.isCurrentRemoteOp(op)) {
            val tunnel = CloudflaredExposer.start(port) ?: return null
            val url = tunnel.awaitUrl()
            var ready = false
            if (url != null && def.isCurrentRemoteOp(op)) {
                def.remoteStatus = RemoteStatus.Verifying
                publishState()
                ready = tunnel.awaitReady()
            }
            if (ready && def.isCurrentRemoteOp(op)) {
                runCatching { def.remoteProcess?.destroyForcibly() }
                def.remoteProcess = tunnel.process
                if (def.isCurrentRemoteOp(op)) return url
                tunnel.destroy()
                if (def.remoteProcess === tunnel.process) def.remoteProcess = null
                return null
            }
            tunnel.destroy()
            if (!def.isCurrentRemoteOp(op) || refreshes >= MAX_REFRESHES) return null
            refreshes++
            def.remoteStatus = RemoteStatus.Retrying
            def.remoteAttempt = refreshes
            def.remoteMaxAttempts = MAX_REFRESHES
            publishState()
        }
        return null
    }

    /**
     * Re-establish the Cloudflare tunnel if it exits on its own (an idle tunnel can drop). The op-token
     * is the authority: a cooperative teardown bumps the op BEFORE killing the process, so a kill we
     * initiated leaves [op] stale and this self-cancels; only an unattended death respawns.
     */
    private fun registerRespawn(def: ShareDef, proc: Process, op: Int) {
        proc.onExit().thenAccept {
            scope.launch {
                if (!def.isCurrentRemoteOp(op)) return@launch
                if (def !in shares) return@launch
                if (def.remoteMode != "cloudflare") return@launch
                if (engine == null) return@launch
                delay(2000)
                if (!def.isCurrentRemoteOp(op)) return@launch
                log.info("cloudflared tunnel exited; re-establishing the share link")
                withContext(Dispatchers.IO) { establishRemote(def, "cloudflare") }
                publishState()
            }
        }
    }

    /**
     * Tear down whatever remote provider is live for [def] and reset its published URL. Idempotent.
     * [port] is passed in (not read from [boundPort]) because the caller may have already stopped the
     * engine — reading boundPort here could see null and skip the serve/funnel teardown.
     */
    private fun teardownRemote(def: ShareDef, port: Int?, @Suppress("UNUSED_PARAMETER") op: Int) {
        when (def.activeRemoteMode) {
            "serve", "funnel" -> if (port != null) runCatching { TailscaleExposer.disable(def.activeRemoteMode, port) }
            "cloudflare" -> runCatching { def.remoteProcess?.destroyForcibly() }
        }
        def.remoteProcess = null
        def.remoteUrl = null
        def.remoteStatus = RemoteStatus.Off
        def.remoteAttempt = 0
        def.remoteMaxAttempts = 0
        def.activeRemoteMode = "off"
    }

    // ===================================================================================
    // URL building (ported from SessionShareManager)
    // ===================================================================================

    /**
     * Build the user-facing share URL for [token]. Precedence: an active remote tunnel → a user-set
     * public URL → the bound host (LAN-resolved when bound wide). Appends the E2E `#k=` fragment when
     * the browser would have WebCrypto (https tunnel or loopback) — the fragment never reaches the relay.
     */
    private fun buildUrl(token: String): String? {
        val def = sharesByToken[token]?.share
        val base = def?.remoteUrl?.let { "${it.trimEnd('/')}/?t=$token" }
            ?: settings().sessionSharingPublicUrl.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/?t=$token" }
            ?: boundPort?.let { "http://${advertisedHost()}:$it/?t=$token" }
            ?: return null
        if (def != null && e2eCapable(base)) return "$base#k=${def.secretB64}"
        return base
    }

    /** The E2E verification code for a link that carries a `#k=` fragment, else null. */
    private fun e2eCodeOf(url: String): String? {
        val k = url.substringAfter("#k=", "").substringBefore('&').takeIf { it.isNotBlank() } ?: return null
        return runCatching { SessionCrypto.fingerprint(SessionCrypto.decodeSecretB64Url(k)) }.getOrNull()
    }

    /** True when a browser at this URL would have `crypto.subtle` (https or loopback). */
    private fun e2eCapable(url: String): Boolean {
        if (url.startsWith("https://", ignoreCase = true)) return true
        val h = hostOf(url).lowercase()
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }

    /** A URL is "secure" if it's https or points at a loopback/private/.local/.ts.net host. */
    private fun isSecureUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || hostIsPrivate(hostOf(url))

    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':')

    private fun hostIsPrivate(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".ts.net") ||
            h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
            h.startsWith("169.254.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h)
    }

    /**
     * Host to put in the share URL: 127.0.0.1 for a loopback bind (this machine only), else a
     * site-local IPv4 so other devices can reach a wildcard/LAN bind; falls back to the bound host.
     */
    private fun advertisedHost(): String {
        val bound = boundHost ?: "127.0.0.1"
        if (bound != "0.0.0.0") return bound
        return siteLocalIpv4() ?: "0.0.0.0"
    }

    private fun siteLocalIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    // ===================================================================================
    // misc helpers
    // ===================================================================================

    private fun newKey(): String = UUID.randomUUID().toString().replace("-", "")

    private fun secureToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // Cached — InetAddress.getLocalHost() can stall on bad DNS; compute once.
    private val cachedDefaultSessionName by lazy {
        val user = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "session"
        val machine = runCatching { java.net.InetAddress.getLocalHost().hostName }
            .getOrNull()?.takeIf { it.isNotBlank() }?.removeSuffix(".local")
        if (machine != null) "${user}_$machine" else user
    }

    /** Default share session name: `username_machine` (viewers' default group label). */
    private fun defaultSessionName(): String = cachedDefaultSessionName
}
