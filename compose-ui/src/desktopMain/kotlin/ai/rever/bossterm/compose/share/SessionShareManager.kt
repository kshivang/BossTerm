package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
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
    @Volatile private var boundPort: Int? = null
    private var boundHost: String? = null
    // Remote access (Phase 3): the published public/tunnel URL + which provider is live.
    // [activeRemoteMode] mirrors a TerminalSettings.shareTailscaleMode value:
    // "off" | "serve" | "funnel" (Tailscale) | "cloudflare" (Cloudflare Quick Tunnel).
    // @Volatile: read/written from multiple Dispatchers.IO threads (establish / teardown).
    @Volatile private var remoteUrl: String? = null
    @Volatile private var activeRemoteMode: String = "off"
    // For long-lived tunnels (cloudflared): the running process to kill on teardown.
    @Volatile private var remoteProcess: Process? = null
    // Monotonic token serializing remote-access operations WITHOUT a long-held lock: each
    // establish/teardown/stop "claims" the next value; a long-running establish (Cloudflare
    // verify can take tens of seconds) checks it's still current before publishing state or
    // adopting its tunnel, and otherwise bails and kills its own tunnel — so a concurrent
    // mode-switch / refresh / unshare can't leak a cloudflared process or stomp shared state.
    private val remoteOp = java.util.concurrent.atomic.AtomicInteger(0)
    private fun claimRemoteOp(): Int = remoteOp.incrementAndGet()
    private fun isCurrentRemoteOp(op: Int): Boolean = remoteOp.get() == op
    private val _remoteUrlFlow = MutableStateFlow<String?>(null)
    /**
     * The published remote-access URL (Tailscale `https://<host>.ts.net` or a Cloudflare
     * `https://<rand>.trycloudflare.com`) once exposure resolves, else null. Exposure runs
     * asynchronously (off the UI thread), so the share dialog opens with the LAN URL first;
     * the UI observes this to refresh the dialog to the public link when it's ready.
     */
    val remoteUrlFlow: StateFlow<String?> = _remoteUrlFlow.asStateFlow()

    /** Lifecycle of the remote-access link, surfaced in the share dialog. */
    enum class RemoteStatus { Off, Starting, Verifying, Active, Retrying, FellBack }

    /** Remote-access status snapshot: phase + provider [mode] + retry progress. */
    data class RemoteState(
        val status: RemoteStatus,
        val mode: String,
        val attempt: Int = 0,
        val maxAttempts: Int = 0,
    )

    private val _remoteStateFlow = MutableStateFlow(RemoteState(RemoteStatus.Off, "off"))
    /** Observed by the share dialog to show "starting / verifying / retrying / active / fell back". */
    val remoteStateFlow: StateFlow<RemoteState> = _remoteStateFlow.asStateFlow()

    // A Cloudflare quick tunnel serves a "tunnel error" page until cloudflared registers an
    // edge connection, so we wait for that readiness signal before publishing the URL; if it
    // never comes we spin a fresh tunnel a couple of times, else fall back to the LAN link.
    // (We can't HTTP-probe the public URL from the host: a Tailscale-MagicDNS resolver returns
    // NXDOMAIN for *.trycloudflare.com, so a host-side check would falsely fail working links.)
    private const val MAX_REFRESHES = 2

    /** A token resolves to a share and whether that token grants control. */
    private data class TokenRef(val share: MirrorShare, val canControl: Boolean)

    private val sharesByToken = ConcurrentHashMap<String, TokenRef>()
    private val sharesByTab = ConcurrentHashMap<String, MirrorShare>()

    private val _sharedTabIds = MutableStateFlow<Set<String>>(emptySet())
    /** Tab ids currently being shared — drives the UI indicator + Share/Stop menu state. */
    val sharedTabIds: StateFlow<Set<String>> = _sharedTabIds.asStateFlow()

    private var watcherJob: Job? = null

    // ---- Approval handshake + 24h rolling access keys (issue #276) ----

    private const val GRANT_TTL_MS = 24L * 60 * 60 * 1000

    /** A granted device key: which share it's for, its role, and when it lapses. */
    private data class Grant(
        val key: String,
        val shareId: String,
        val clientId: String,
        val canControl: Boolean,
        @Volatile var expiresAtMs: Long,
    )

    /** Access key → grant. Lazily expired on use; cleared when the share ends. */
    private val grants = ConcurrentHashMap<String, Grant>()

    /** A viewer connection awaiting the host's approve/deny decision. */
    data class PendingShareRequest(
        val id: String,
        val tabId: String,
        val deviceName: String,
        /** True when the device used the control link (one approve grants control). */
        val wantsControl: Boolean,
    ) {
        internal val decision = CompletableDeferred<Boolean>()
    }

    private val _pendingRequests = MutableStateFlow<List<PendingShareRequest>>(emptyList())
    /** Connections waiting for host approval — drives the approval toast + dialog list. */
    val pendingRequests: StateFlow<List<PendingShareRequest>> = _pendingRequests.asStateFlow()

    /** Host approved a pending request (admits the viewer + mints a 24h key). */
    fun approveRequest(id: String) = decideRequest(id, true)
    /** Host denied a pending request (closes the viewer socket). */
    fun denyRequest(id: String) = decideRequest(id, false)
    private fun decideRequest(id: String, approve: Boolean) {
        val req = _pendingRequests.value.firstOrNull { it.id == id } ?: return
        _pendingRequests.value = _pendingRequests.value.filterNot { it.id == id }
        req.decision.complete(approve)
    }

    /** Deny + drop any pending requests for [tabId] (its share is ending). */
    private fun failPendingFor(tabId: String) {
        val (drop, keep) = _pendingRequests.value.partition { it.tabId == tabId }
        if (drop.isEmpty()) return
        _pendingRequests.value = keep
        drop.forEach { it.decision.complete(false) }
    }

    /** Deny + drop all pending requests (sharing is shutting down). */
    private fun failAllPending() {
        val all = _pendingRequests.value
        if (all.isNotEmpty()) _pendingRequests.value = emptyList()
        all.forEach { it.decision.complete(false) }
    }

    /**
     * Whether a newly connecting device must be approved before it can view/control,
     * per [TerminalSettings.sessionSharingApprovalScope]: "all" = always; "off" = never;
     * "funnel" (default) = only when the share is publicly reachable (Tailscale Funnel,
     * Cloudflare Quick Tunnel, or a custom public URL), since LAN/Serve reach is trusted.
     */
    private fun requiresApproval(): Boolean {
        val s = SettingsManager.instance.settings.value
        return when (s.sessionSharingApprovalScope) {
            "all" -> true
            "off" -> false
            else -> activeRemoteMode == "funnel" || activeRemoteMode == "cloudflare" || s.sessionSharingPublicUrl.isNotBlank()
        }
    }

    private fun newKey(): String = UUID.randomUUID().toString().replace("-", "")

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
        /** Whether this share covers one tab or the whole window. */
        val scope: ShareScope = ShareScope.TAB,
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

    /**
     * Is [token] one of THIS instance's active share tokens (view or control)? Used to refuse
     * adding our own share link as a "remote" session — mirroring a session into itself loops.
     */
    fun ownsToken(token: String): Boolean = sharesByToken.containsKey(token)

    /**
     * Is [hash] the SHA-256 of one of THIS instance's active share tokens? Matches
     * [TabNode.origin] stamped by a host on tabs that mirror a remote session, so a client
     * connecting to that host can skip the tabs that mirror its own session.
     */
    fun ownsTokenHash(hash: String): Boolean =
        sharesByToken.keys.any { ShareProtocol.sha256Hex(it) == hash }

    /** The read-only share URL for an active share, or null if not shared / server down. */
    fun urlFor(tabId: String): String? {
        val token = sharesByTab[tabId]?.viewToken ?: return null
        return buildUrl(token)
    }

    /** Rebuild the [ShareInfo] for an already-shared tab (e.g. to reopen the QR dialog). */
    fun infoFor(tabId: String): ShareInfo? {
        val share = sharesByTab[tabId] ?: return null
        val url = buildUrl(share.viewToken) ?: return null
        val controlUrl = buildUrl(share.controlToken) ?: url
        return ShareInfo(tabId, url, share.viewToken, controlUrl, isSecureUrl(url), share.scope)
    }

    /**
     * Start sharing — [ShareScope.TAB] (this tab + its splits) or [ShareScope.WINDOW]
     * (all tabs of the owning window). Boots the share server if needed. Returns null
     * when sharing is disabled or the server can't bind. Idempotent per [tabId].
     */
    suspend fun share(tabId: String, scope: ShareScope = ShareScope.TAB): ShareInfo? {
        val settings = SettingsManager.instance.settings.value
        if (!settings.sessionSharingEnabled) return null
        return mutex.withLock {
            if (!ensureEngineLocked(settings)) return@withLock null
            val share = sharesByTab[tabId] ?: MirrorShare(tabId, scope, onEnded = { unshare(tabId) }).also {
                it.start()
                sharesByToken[it.viewToken] = TokenRef(it, canControl = false)
                sharesByToken[it.controlToken] = TokenRef(it, canControl = true)
                sharesByTab[tabId] = it
                _sharedTabIds.value = sharesByTab.keys.toSet()
            }
            val url = buildUrl(share.viewToken) ?: return@withLock null
            val controlUrl = buildUrl(share.controlToken) ?: url
            val secure = isSecureUrl(url)
            if (!secure) {
                log.warn(
                    "Session-sharing link is plaintext over a non-private host ({}). " +
                        "Use https (Tailscale Funnel or a TLS tunnel) — input/output is not encrypted.",
                    url
                )
            }
            ShareInfo(tabId, url, share.viewToken, controlUrl, secure, share.scope)
        }
    }

    /**
     * Change an active share's scope (Tab ↔ Window) in place — same tokens/viewers,
     * just a different set of mirrored tabs. Returns the refreshed [ShareInfo], or null
     * if the tab isn't currently shared (caller should [share] instead).
     */
    fun reshare(tabId: String, scope: ShareScope): ShareInfo? {
        val share = sharesByTab[tabId] ?: return null
        share.setScope(scope)
        return infoFor(tabId)
    }

    /**
     * Regenerate the remote-access link in place — the "give me a new link" action in the
     * share dialog (Cloudflare quick-tunnel URLs are ephemeral and can drop). Re-runs the
     * configured provider through the verified establish path (so the new Cloudflare link
     * is health-checked before it's published) and drives [remoteStateFlow]. No-op when
     * remote access is off. Runs off the UI thread.
     */
    fun refreshRemoteLink() {
        scope.launch {
            val port = boundPort ?: return@launch
            val mode = SettingsManager.instance.settings.value.shareTailscaleMode
            if (mode == "off") return@launch
            val op = claimRemoteOp()
            withContext(Dispatchers.IO) { establishRemote(mode, port, op) }
        }
    }

    /**
     * Switch the **live** remote-access provider (the share dialog's Off/Serve/Funnel/
     * Cloudflare picker, after the user confirms). Tears down the current exposure and
     * establishes the new one in place — same server + viewers, a new link. When sharing
     * isn't active (no bound port) this is a no-op; the persisted setting applies next share.
     */
    fun applyRemoteMode(mode: String) {
        scope.launch {
            val port = boundPort ?: return@launch
            val op = claimRemoteOp() // supersede any in-flight establish (it will bail + self-clean)
            withContext(Dispatchers.IO) {
                teardownRemoteAccess(port)
                if (mode != "off") establishRemote(mode, port, op)
                else if (isCurrentRemoteOp(op)) _remoteStateFlow.value = RemoteState(RemoteStatus.Off, "off")
            }
        }
    }

    /**
     * Bring up remote access for [mode] on [port], publishing the URL only once it's
     * confirmed working, and driving [remoteStateFlow] through Starting → Verifying/
     * Retrying → Active, or → FellBack (URL stays unpublished, so [buildUrl] uses the LAN
     * link). Shared by the initial share, manual refresh, and live mode switch.
     *
     * [op] is this operation's token: if a newer remote op is claimed while we're working,
     * we stop publishing state and don't adopt our tunnel (Cloudflare self-cleans), so a
     * concurrent switch / refresh / stop wins cleanly.
     */
    private suspend fun establishRemote(mode: String, port: Int, op: Int) {
        if (!isCurrentRemoteOp(op)) return
        activeRemoteMode = mode
        // Drop any stale link while we (re)establish, so the dialog shows the LAN link +
        // progress rather than a soon-to-be-dead URL.
        remoteUrl = null
        _remoteUrlFlow.value = null
        _remoteStateFlow.value = RemoteState(RemoteStatus.Starting, mode)
        val url: String? = when (mode) {
            "cloudflare" -> establishCloudflareVerified(port, op)
            "serve", "funnel" -> TailscaleExposer.enable(mode, port) // stable URL; published as-is
            else -> null
        }
        if (!isCurrentRemoteOp(op)) return // superseded while establishing — don't publish
        if (url != null) {
            remoteUrl = url
            _remoteUrlFlow.value = url
            _remoteStateFlow.value = RemoteState(RemoteStatus.Active, mode)
            log.info("Session-sharing reachable via {}: {}", mode, url)
        } else {
            _remoteStateFlow.value = RemoteState(RemoteStatus.FellBack, mode)
            log.warn(
                "Remote access ({}) did not yield a working link; using the LAN link. " +
                    "For tailscale check `tailscale status`; for cloudflare ensure `cloudflared` is installed.",
                mode
            )
        }
    }

    /**
     * Start a Cloudflare quick tunnel and return its URL only once cloudflared reports the
     * tunnel is routable (an edge connection registered) — so we never hand out a link that
     * still serves the Cloudflare error page. If a tunnel prints a URL but never becomes
     * ready, spin a brand-new one up to [MAX_REFRESHES] times. Returns null (→ caller falls
     * back to LAN) if all attempts fail.
     *
     * Each attempt holds its tunnel in a local until it's verified AND still the current
     * [op]; only then does it kill the prior tunnel and adopt itself as [remoteProcess]. A
     * failed attempt or a superseded op destroys its own tunnel, so nothing is ever leaked.
     */
    private suspend fun establishCloudflareVerified(port: Int, op: Int): String? {
        var refreshes = 0
        // Already runs on Dispatchers.IO (manager scope / caller's withContext), so the
        // blocking awaitUrl/awaitReady don't need their own withContext.
        while (isCurrentRemoteOp(op)) {
            val tunnel = CloudflaredExposer.start(port) ?: return null
            val url = tunnel.awaitUrl()
            var ready = false
            if (url != null && isCurrentRemoteOp(op)) {
                _remoteStateFlow.value = RemoteState(RemoteStatus.Verifying, "cloudflare")
                ready = tunnel.awaitReady()
            }
            if (ready && isCurrentRemoteOp(op)) {
                runCatching { remoteProcess?.destroyForcibly() } // replace any prior tunnel
                remoteProcess = tunnel.process
                // Re-check after adopting: a teardown/newer op could have slipped in between
                // the guard above and this assignment (and walked past our not-yet-set tunnel).
                // If so, reclaim our tunnel rather than leak it — the winner owns remoteProcess.
                if (isCurrentRemoteOp(op)) return url
                tunnel.destroy()
                if (remoteProcess === tunnel.process) remoteProcess = null
                return null
            }
            tunnel.destroy() // failed / superseded — never leak this tunnel
            if (!isCurrentRemoteOp(op) || refreshes >= MAX_REFRESHES) return null
            refreshes++
            _remoteStateFlow.value = RemoteState(RemoteStatus.Retrying, "cloudflare", refreshes, MAX_REFRESHES)
        }
        return null
    }

    /**
     * Window's onTabClose hook: stop only a TAB-scope share keyed by the closed tab.
     * WINDOW shares clean up via [MirrorShare]'s own observer when their window empties.
     */
    fun onTabClosed(tabId: String) {
        if (sharesByTab[tabId]?.scope == ShareScope.TAB) unshare(tabId)
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
                val share = sharesByTab.remove(tabId) ?: return@withLock
                sharesByToken.remove(share.viewToken)
                sharesByToken.remove(share.controlToken)
                grants.values.removeIf { it.shareId == share.viewToken }
                failPendingFor(tabId)
                share.stop()
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
                grants.clear()
                failAllPending()
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
        sharesByTab.values.forEach { runCatching { it.stop() } }
        sharesByTab.clear()
        sharesByToken.clear()
        grants.clear()
        failAllPending()
        _sharedTabIds.value = emptySet()
        claimRemoteOp() // cancel any in-flight establish before tearing down
        teardownRemoteAccess(boundPort)
        val e = engine ?: return
        runCatching { e.stop(200, 800) }
        engine = null
        boundPort = null
        boundHost = null
    }

    // ---- engine lifecycle (mutex-guarded) ----

    private fun resolveBindHost(settings: TerminalSettings): String = when (settings.sessionSharingBind) {
        "lan" -> "0.0.0.0"
        "custom" -> settings.sessionSharingBindHost.ifBlank { "127.0.0.1" }
        else -> "127.0.0.1"
    }

    /**
     * True if [port] is free for the share server to take. Probes both the bind
     * [host] (so we don't double-bind our own wildcard) AND `127.0.0.1` — the latter
     * because Tailscale Serve proxies to `127.0.0.1:<port>`, so we must OWN the loopback
     * address, not just the wildcard. With `reuseAddress = false` the probe reports a
     * port as taken when ANY other process holds it, including a loopback-specific
     * listener (e.g. BossConsole on 127.0.0.1) that a wildcard bind would otherwise
     * silently shadow — which is exactly how `serve` ended up hitting the wrong server.
     * Used to pick a free port before starting Ktor (CIO binds asynchronously, so a
     * collision would otherwise surface as an uncaught BindException or a mis-proxy).
     */
    private fun portBindable(host: String, port: Int): Boolean {
        val probes = buildList {
            add("127.0.0.1")
            if (host != "127.0.0.1") add(host)
        }
        return probes.all { addr ->
            runCatching {
                java.net.ServerSocket().use { ss ->
                    ss.reuseAddress = false
                    ss.bind(java.net.InetSocketAddress(addr, port))
                }
                true
            }.getOrDefault(false)
        }
    }

    /** Start the engine if not already running. Returns true if running afterwards. */
    private fun ensureEngineLocked(settings: TerminalSettings): Boolean {
        if (engine != null) return true
        val host = resolveBindHost(settings)
        val desiredPort = settings.sessionSharingPort
        for (offset in 0 until MAX_PORT_FALLBACK) {
            val port = desiredPort + offset
            if (port > 65535) break
            // CIO binds its listening socket asynchronously (in the acceptJob coroutine),
            // so a port-already-in-use surfaces as an UNCAUGHT BindException instead of
            // throwing from start(). Probe the port synchronously first and skip it if
            // taken — e.g. a previous instance still shutting down on relaunch.
            if (!portBindable(host, port)) {
                log.warn("Session-sharing port {}:{} in use, trying next", host, port)
                continue
            }
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
                // Phase 3: expose remotely via the configured provider, if any. Best-effort —
                // failure just leaves us on the LAN/loopback URL. Run it OFF the share path
                // (manager scope, Dispatchers.IO): shelling out to `tailscale`/`cloudflared`
                // can be slow or hang, and ensureEngineLocked runs synchronously on the
                // caller's (UI) thread — doing it inline would freeze the app. The dialog
                // opens immediately with the LAN URL; the public URL is picked up via
                // remoteUrlFlow when it resolves. Guarded so it only fires once per lifecycle.
                if (settings.shareTailscaleMode != "off" && activeRemoteMode == "off") {
                    val mode = settings.shareTailscaleMode
                    val rPort = port
                    activeRemoteMode = mode // claim it now so this fires once per lifecycle
                    val op = claimRemoteOp()
                    scope.launch { establishRemote(mode, rPort, op) }
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
        // Tear down any active remote-access exposure first (best-effort, off the UI thread).
        // Bump the op so an in-flight establish bails + self-cleans instead of racing teardown.
        claimRemoteOp()
        withContext(Dispatchers.IO) { teardownRemoteAccess(boundPort) }
        try {
            withContext(Dispatchers.IO) { e.stop(300, 1000) }
            log.info("Session-sharing server stopped (port {})", boundPort)
        } catch (t: Throwable) {
            log.warn("Error stopping session-sharing server: {}", t.message)
        } finally {
            engine = null
            boundPort = null
            boundHost = null
        }
    }

    /**
     * Tear down whatever remote-access provider is live and reset the published URL.
     * Tailscale serve/funnel is unmapped via the CLI; a Cloudflare quick tunnel is a
     * long-lived process, so it's killed. Idempotent / safe when nothing is active.
     */
    private fun teardownRemoteAccess(port: Int?) {
        when (activeRemoteMode) {
            "serve", "funnel" -> if (port != null) runCatching { TailscaleExposer.disable(activeRemoteMode, port) }
            "cloudflare" -> runCatching { remoteProcess?.destroyForcibly() }
        }
        remoteProcess = null
        remoteUrl = null
        _remoteUrlFlow.value = null
        _remoteStateFlow.value = RemoteState(RemoteStatus.Off, "off")
        activeRemoteMode = "off"
    }

    /** Handle one viewer WebSocket: handshake/approval, snapshot, then drain its outbox. */
    private suspend fun serveViewer(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        val token = ws.call.parameters["token"]
        val ref = token?.let { sharesByToken[it] }
        if (ref == null) {
            ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unknown or expired share token"))
            return
        }
        val share = ref.share
        val shareId = share.viewToken

        // Handshake: the viewer's first frame carries its clientId + any prior access key.
        val hello = readHello(ws)
        val clientId = hello?.clientId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        var canControl = ref.canControl

        if (requiresApproval()) {
            val now = System.currentTimeMillis()
            val existing = hello?.key?.let { grants[it] }
            if (existing != null && existing.shareId == shareId && existing.expiresAtMs > now) {
                // Known device with a live key → slide the 24h window, skip re-approval.
                existing.expiresAtMs = now + GRANT_TTL_MS
                canControl = existing.canControl
                ws.send(Frame.Text(ShareProtocol.encodeServer(
                    ServerMessage.Grant(existing.key, existing.expiresAtMs, canControl))))
            } else {
                hello?.key?.let { grants.remove(it) } // drop a stale/expired key
                val req = PendingShareRequest(
                    id = UUID.randomUUID().toString(),
                    tabId = share.tabId,
                    deviceName = hello?.name?.takeIf { it.isNotBlank() } ?: "Browser (${clientId.take(6)})",
                    wantsControl = ref.canControl,
                )
                _pendingRequests.value = _pendingRequests.value + req
                runCatching { ws.send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Pending))) }
                val approved = withTimeoutOrNull(2 * 60_000L) { req.decision.await() } ?: false
                _pendingRequests.value = _pendingRequests.value.filterNot { it.id == req.id }
                if (!approved) {
                    runCatching { ws.send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Denied("Not approved")))) }
                    ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Not approved"))
                    return
                }
                val key = newKey()
                val exp = System.currentTimeMillis() + GRANT_TTL_MS
                grants[key] = Grant(key, shareId, clientId, ref.canControl, exp)
                canControl = ref.canControl
                ws.send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Grant(key, exp, canControl))))
            }
        }

        // Admit: Theme + Layout + a PaneSnapshot per pane, THEN register so the outbox
        // only carries output produced after the snapshot (avoids double-rendering).
        share.initialMessages().forEach { ws.send(Frame.Text(ShareProtocol.encodeServer(it))) }
        ws.send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Control(granted = canControl))))
        val vc = share.addViewer(canControl)
        val writer = ws.launch {
            for (text in vc.outbox) ws.send(Frame.Text(text))
        }
        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Text) {
                    val msg = runCatching { ShareProtocol.decodeClient(frame.readText()) }.getOrNull()
                    if (msg != null) share.handleClient(vc, msg)  // input gated by role inside
                }
            }
        } catch (_: Throwable) {
            // client gone
        } finally {
            writer.cancel()
            share.removeViewer(vc)
        }
    }

    /** Read the first frame as a [ClientMessage.Hello] (best-effort, short timeout). */
    private suspend fun readHello(
        ws: io.ktor.server.websocket.DefaultWebSocketServerSession
    ): ClientMessage.Hello? {
        val frame = withTimeoutOrNull(10_000L) { runCatching { ws.incoming.receive() }.getOrNull() } ?: return null
        if (frame !is Frame.Text) return null
        return runCatching { ShareProtocol.decodeClient(frame.readText()) }.getOrNull() as? ClientMessage.Hello
    }

    /**
     * Build the user-facing share URL. Precedence: an active remote-access tunnel
     * (Tailscale `https://<host>.ts.net` or Cloudflare `https://<rand>.trycloudflare.com`)
     * → a user-set public URL (their own proxy/SSH tunnel) → the bound host (LAN-resolved
     * when bound wide). https bases yield wss automatically since the viewer derives the
     * WS scheme from the page.
     */
    private fun buildUrl(token: String): String? {
        remoteUrl?.let { return "${it.trimEnd('/')}/?t=$token" }
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
