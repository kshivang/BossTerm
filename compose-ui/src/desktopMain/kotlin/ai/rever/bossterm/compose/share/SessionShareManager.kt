package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import androidx.compose.runtime.snapshotFlow
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide lifecycle for **session sharing** (issue #276, Phase 1 — read-only).
 *
 * Self-hosted, no cloud relay: a dedicated embedded Ktor (CIO) server serves a
 * vendored/CDN xterm.js web viewer and a WebSocket per shared tab. The host
 * machine *is* the server; other devices reach it over LAN (or a tunnel/VPN).
 * Independent of [ai.rever.bossterm.compose.mcp.BossTermMcpManager] — that one
 * is loopback-only, whereas sharing needs a configurable bind scope.
 *
 * The engine runs **only while ≥1 tab is shared** (started on first [share],
 * stopped on the last [unshare]); [TerminalSettings.sessionSharingEnabled] gates
 * whether sharing is offered at all and, when toggled off, tears everything down.
 *
 * Singleton: [start] once from `fun main()`; UI actions call [share]/[unshare].
 */
object SessionShareManager {

    private val log = LoggerFactory.getLogger(SessionShareManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var boundPort: Int? = null
    private var boundHost: String? = null
    // Tailscale (Phase 3): published https://<host>.ts.net URL while a tunnel is up.
    private var tailscaleUrl: String? = null
    private var tailscaleMode: String = "off"

    /** A token resolves to a session and whether that token grants control (Phase 2). */
    private data class TokenRef(val session: SharedSession, val canControl: Boolean)

    private val sharesByToken = ConcurrentHashMap<String, TokenRef>()
    private val sharesByTab = ConcurrentHashMap<String, SharedSession>()
    private val resizeJobs = ConcurrentHashMap<String, Job>()

    private val _sharedTabIds = MutableStateFlow<Set<String>>(emptySet())
    /** Tab ids currently being shared — drives the UI indicator + Share/Stop menu state. */
    val sharedTabIds: StateFlow<Set<String>> = _sharedTabIds.asStateFlow()

    private var watcherJob: Job? = null

    /** Public info handed back to the UI when a share starts. */
    data class ShareInfo(
        val tabId: String,
        /** Read-only viewer URL. */
        val url: String,
        val token: String,
        /** Control URL (grants write access) — share deliberately. */
        val controlUrl: String,
        /** False when the link is plaintext over a non-private host (no TLS). */
        val secure: Boolean = true,
    )

    /** Begin observing settings. Idempotent. Call once from main(). */
    fun start() {
        if (watcherJob?.isActive == true) return
        watcherJob = scope.launch {
            SettingsManager.instance.settings
                .map { it.sessionSharingEnabled }
                .distinctUntilChanged()
                .collect { enabled -> if (!enabled) stopAll() }
        }
    }

    /** Is [tabId] currently shared? */
    fun isSharing(tabId: String): Boolean = sharesByTab.containsKey(tabId)

    /** The read-only share URL for an active share, or null if not shared / server down. */
    fun urlFor(tabId: String): String? {
        val token = sharesByTab[tabId]?.viewToken ?: return null
        return buildUrl(token)
    }

    /**
     * Start sharing [tabId] (read-only). Boots the share server if needed.
     * Returns null when sharing is disabled in settings or the server can't bind.
     * Safe to call repeatedly — returns the existing share if already sharing.
     */
    suspend fun share(tabId: String, title: String): ShareInfo? {
        val settings = SettingsManager.instance.settings.value
        if (!settings.sessionSharingEnabled) return null
        return mutex.withLock {
            if (!ensureEngineLocked(settings)) return@withLock null
            val existing = sharesByTab[tabId]
            val session = if (existing != null) existing else {
                SharedSession(tabId, title).also {
                    it.start()
                    sharesByToken[it.viewToken] = TokenRef(it, canControl = false)
                    sharesByToken[it.controlToken] = TokenRef(it, canControl = true)
                    sharesByTab[tabId] = it
                    _sharedTabIds.value = sharesByTab.keys.toSet()
                    launchResizeObserver(it)
                }
            }
            val url = buildUrl(session.viewToken) ?: return@withLock null
            val controlUrl = buildUrl(session.controlToken) ?: url
            val secure = isSecureUrl(url)
            if (!secure) {
                log.warn(
                    "Session-sharing link is plaintext over a non-private host ({}). " +
                        "Use https (Tailscale Funnel or a TLS tunnel) — input/output is not encrypted.",
                    url
                )
            }
            ShareInfo(tabId, url, session.viewToken, controlUrl, secure)
        }
    }

    /** A URL is "secure" if it's https or points at a loopback/private/`.local`/`.ts.net` host. */
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

    /** Stop sharing [tabId]; stops the server if it was the last share. */
    fun unshare(tabId: String) {
        scope.launch {
            mutex.withLock {
                val session = sharesByTab.remove(tabId) ?: return@withLock
                sharesByToken.remove(session.viewToken)
                sharesByToken.remove(session.controlToken)
                resizeJobs.remove(tabId)?.cancel()
                session.stop()
                _sharedTabIds.value = sharesByTab.keys.toSet()
                if (sharesByTab.isEmpty()) stopEngineLocked()
            }
        }
    }

    private fun stopAll() {
        scope.launch {
            mutex.withLock {
                sharesByTab.values.forEach { it.stop() }
                sharesByTab.clear()
                sharesByToken.clear()
                resizeJobs.values.forEach { it.cancel() }
                resizeJobs.clear()
                _sharedTabIds.value = emptySet()
                stopEngineLocked()
            }
        }
    }

    /**
     * Synchronous teardown for the JVM shutdown hook: stops all shares, the engine,
     * and any Tailscale serve/funnel mapping without the coroutine scope (which would
     * not drain at process exit). Crucially this tears down the Tailscale tunnel so it
     * isn't left published after the app quits. Best-effort; every step is guarded.
     */
    fun shutdown() {
        runCatching { watcherJob?.cancel() }
        resizeJobs.values.forEach { runCatching { it.cancel() } }
        resizeJobs.clear()
        sharesByTab.values.forEach { runCatching { it.stop() } }
        sharesByTab.clear()
        sharesByToken.clear()
        _sharedTabIds.value = emptySet()
        val e = engine ?: return
        val port = boundPort
        if (tailscaleMode != "off" && port != null) runCatching { TailscaleExposer.disable(tailscaleMode, port) }
        runCatching { e.stop(200, 800) }
        engine = null
        boundPort = null
        boundHost = null
        tailscaleUrl = null
        tailscaleMode = "off"
    }

    /** Mirror terminal-size changes to all viewers of [session]. */
    private fun launchResizeObserver(session: SharedSession) {
        val tab = McpTerminalRegistry.findTab(session.tabId) ?: return
        resizeJobs[session.tabId] = scope.launch {
            snapshotFlow { tab.display.termSize.value }
                .collect { size -> session.broadcast(ServerMessage.Resize(size.columns, size.rows)) }
        }
    }

    // ---- engine lifecycle (mutex-guarded) ----

    private fun resolveBindHost(settings: TerminalSettings): String = when (settings.sessionSharingBind) {
        "lan" -> "0.0.0.0"
        "custom" -> settings.sessionSharingBindHost.ifBlank { "127.0.0.1" }
        else -> "127.0.0.1"
    }

    /** Start the engine if not already running. Returns true if running afterwards. */
    private fun ensureEngineLocked(settings: TerminalSettings): Boolean {
        if (engine != null) return true
        val host = resolveBindHost(settings)
        val desiredPort = settings.sessionSharingPort
        for (offset in 0 until MAX_PORT_FALLBACK) {
            val port = desiredPort + offset
            if (port > 65535) break
            try {
                val started = embeddedServer(CIO, host = host, port = port) {
                    install(WebSockets)
                    routing {
                        webSocket("/ws/{token}") { serveViewer(this) }
                        // Static web viewer (index.html + viewer.js + css). Share URL:
                        // http://<host>:<port>/?t=<token>
                        staticResources("/", "share-viewer", index = "index.html")
                    }
                }
                started.start(wait = false)
                engine = started
                boundPort = port
                boundHost = host
                log.info("Session-sharing server bound on {}:{} (bind={})", host, port, settings.sessionSharingBind)
                // Phase 3: expose remotely via Tailscale if configured. Best-effort —
                // failure just leaves us on the LAN/loopback URL. Kept inline (under the
                // mutex) because the share dialog needs the published https URL the moment
                // share() returns; this only blocks once — on the FIRST share of a server
                // lifecycle when Tailscale is enabled (the engine is reused thereafter, so
                // ensureEngineLocked returns early and never re-runs this).
                if (settings.shareTailscaleMode != "off") {
                    tailscaleMode = settings.shareTailscaleMode
                    tailscaleUrl = TailscaleExposer.enable(tailscaleMode, port)
                }
                return true
            } catch (e: Throwable) {
                val bind = generateSequence(e as Throwable?) { it.cause }.filterIsInstance<BindException>().firstOrNull()
                if (bind != null) {
                    log.warn("Session-sharing port {}:{} in use, trying next", host, port)
                    continue
                }
                log.error("Session-sharing server failed to start on {}:{}", host, port, e)
                return false
            }
        }
        log.error("Session-sharing server could not bind any port from {}", desiredPort)
        return false
    }

    private suspend fun stopEngineLocked() {
        val e = engine ?: return
        // Tear down any Tailscale mapping first (best-effort).
        if (tailscaleMode != "off") {
            val port = boundPort
            val mode = tailscaleMode
            if (port != null) withContext(Dispatchers.IO) { TailscaleExposer.disable(mode, port) }
        }
        try {
            withContext(Dispatchers.IO) { e.stop(300, 1000) }
            log.info("Session-sharing server stopped (port {})", boundPort)
        } catch (t: Throwable) {
            log.warn("Error stopping session-sharing server: {}", t.message)
        } finally {
            engine = null
            boundPort = null
            boundHost = null
            tailscaleUrl = null
            tailscaleMode = "off"
        }
    }

    /** Handle one viewer WebSocket: snapshot, then drain its outbox to the socket. */
    private suspend fun serveViewer(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        val token = ws.call.parameters["token"]
        val ref = token?.let { sharesByToken[it] }
        if (ref == null) {
            ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unknown or expired share token"))
            return
        }
        val session = ref.session
        // Snapshot first (current state), THEN register so the outbox only carries
        // output produced after the snapshot — avoids double-rendering a chunk.
        ws.send(Frame.Text(ShareProtocol.encodeServer(session.snapshotMessage())))
        session.currentSize()?.let { (c, r) ->
            ws.send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Resize(c, r))))
        }
        // Control granted iff this connection used the control token (Phase 2).
        ws.send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Control(granted = ref.canControl))))
        val vc = session.addViewer(ref.canControl)
        val writer = ws.launch {
            for (text in vc.outbox) ws.send(Frame.Text(text))
        }
        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Text) {
                    val msg = runCatching { ShareProtocol.decodeClient(frame.readText()) }.getOrNull()
                    // Only control-token viewers can write; handleInput re-checks the role.
                    if (msg is ClientMessage.Input) session.handleInput(vc, msg.data)
                    // RequestControl from a view-only viewer is a no-op — they need the control link.
                }
            }
        } catch (_: Throwable) {
            // client gone
        } finally {
            writer.cancel()
            session.removeViewer(vc)
        }
    }

    /**
     * Build the user-facing share URL. Precedence: an active Tailscale tunnel
     * (https://<host>.ts.net) → a user-set public URL (their own proxy/cloudflared/
     * SSH tunnel) → the bound host (LAN-resolved when bound wide). https bases yield
     * wss automatically since the viewer derives the WS scheme from the page.
     */
    private fun buildUrl(token: String): String? {
        tailscaleUrl?.let { return "${it.trimEnd('/')}/?t=$token" }
        val publicUrl = SettingsManager.instance.settings.value.sessionSharingPublicUrl
        if (publicUrl.isNotBlank()) return "${publicUrl.trimEnd('/')}/?t=$token"
        val port = boundPort ?: return null
        return "http://${advertisedHost()}:$port/?t=$token"
    }

    /**
     * Host to put in the share URL. For a loopback bind it's 127.0.0.1 (this machine
     * only). For a wildcard/LAN bind, surface a site-local IPv4 so other devices can
     * actually reach it; fall back to the bound host.
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

    private const val MAX_PORT_FALLBACK = 10
}
