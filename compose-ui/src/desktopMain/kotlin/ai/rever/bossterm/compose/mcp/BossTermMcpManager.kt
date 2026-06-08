package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.SettingsManager
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifecycle wrapper that brings up the BossTerm in-process MCP server on a
 * loopback-only embedded Ktor (CIO) HTTP server, driven by user settings.
 *
 * The manager is meant to be constructed **once per JVM** in `fun main()` and
 * exposes tabs from every window via [McpTerminalRegistry]. Window-scoped
 * lifecycles only need to register/unregister their [TabbedTerminalState]
 * with the registry; they should not instantiate this class themselves.
 *
 * Behavior:
 *  - On [start] the manager begins observing [SettingsManager.settings] and
 *    reconciles the running server with the current `(mcpEnabled, mcpPort)`
 *    pair. Toggling `mcpEnabled` brings the server up/down; changing
 *    `mcpPort` while enabled performs a stop-then-start.
 *  - On [stop] the watcher is cancelled and any running Ktor engine is
 *    stopped asynchronously on a background coroutine. Caller does not
 *    block.
 *  - The server always binds to `127.0.0.1` — never `0.0.0.0`.
 *  - Every request must arrive with a `Host` header pointing at a loopback
 *    name (`127.0.0.1` or `localhost`, optionally with the bound port).
 *    Other Host values are rejected with 403 to defend against DNS
 *    rebinding from a browser tab.
 *  - The endpoint is logged at INFO with the full URL when the server
 *    comes up, so users can see where to point clients.
 *
 * Thread-safety: reconcile is serialized via a [Mutex] so concurrent
 * settings emissions cannot double-start the engine. A [BindException]
 * (e.g. port-in-use) is caught and logged; the engine reference stays null
 * and the next settings emission will trigger a new attempt.
 */
class BossTermMcpManager(
    private val registry: McpTerminalRegistry,
    private val settingsManager: SettingsManager,
    private val parentScope: CoroutineScope,
    private val config: BossTermMcpConfig = BossTermMcpConfig()
) {

    private val log = LoggerFactory.getLogger(BossTermMcpManager::class.java)

    private val mutex = Mutex()

    // Guarded by [mutex]. The Server itself is hoisted to a class-level field
    // and re-bound to a transport each time the engine restarts.
    private var runningEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var runningPort: Int? = null

    // Holds the live BossTermMcpServer wrapper so the disabled-tools watcher
    // can call applyDisabledSet() against the same instance the transport is bound to.
    // Null when no engine is running.
    private var runningServer: BossTermMcpServer? = null

    private var watcherJob: Job? = null
    private var disabledToolsWatcherJob: Job? = null
    private var preferredShellWatcherJob: Job? = null

    // Caches caller-window resolution by the client's ephemeral TCP port. That
    // port is stable for a connection's lifetime and its owning PID can't
    // change without reconnecting, so one (possibly expensive) ProcessAncestry
    // resolution per socket suffices instead of one per request. Optional wraps
    // the nullable result (a client outside any pane resolves to "no window")
    // since ConcurrentHashMap forbids null values. Cleared wholesale past
    // CLIENT_WINDOW_CACHE_MAX so recycled ephemeral ports can't accrete entries
    // — a recycled port could briefly return a stale window, an acceptable
    // last-writer-wins miss already inherent to the multi-client design.
    private val clientWindowByPort = ConcurrentHashMap<Int, Optional<TabbedTerminalState>>()

    /** Begin observing settings. Idempotent. Safe to call multiple times. */
    fun start() {
        if (watcherJob?.isActive == true) return

        // Publish the embedder's server name/label to the registry so non-Compose readers
        // (MirrorShare relaying a remote client's MCP attach/toggle) use the right values
        // instead of the standalone defaults — matching the Compose LocalBossTermMcpConfig path.
        registry.setServerInfo(config.serverName, config.displayName ?: "BossTerm")

        // Hydrate the runtime attached-targets set from persisted settings so
        // the indicator/menu reflect prior-session state immediately, and
        // the auto-reattach loop below has the right targets to refresh.
        registry.hydrate(settingsManager.settings.value.mcpAttachedTo)

        // First-launch reconciliation. Two distinct cases share the
        // `mcpConfigured = false` state:
        //   1) Brand-new install — apply the embedder's defaults.
        //   2) Existing user upgrading from a build that didn't yet have
        //      the mcpConfigured field — preserve their saved mcpEnabled /
        //      mcpPort and just flip the marker so future launches no-op.
        // We tell the two apart via SettingsManager.wasFreshInstall, which
        // is true only when no settings.json existed at load time.
        val current = settingsManager.settings.value
        if (!current.mcpConfigured) {
            if (settingsManager.wasFreshInstall) {
                log.info(
                    "MCP first-launch defaults applied: enabled={}, port={}",
                    config.defaultEnabled, config.defaultPort
                )
                settingsManager.updateSetting {
                    copy(
                        mcpEnabled = config.defaultEnabled,
                        mcpPort = config.defaultPort,
                        mcpConfigured = true
                    )
                }
            } else {
                // Upgrade path: do not touch the user's mcpEnabled / mcpPort.
                log.info("MCP marker absent on existing install; preserving user settings")
                settingsManager.updateSetting { copy(mcpConfigured = true) }
            }
        }

        watcherJob = parentScope.launch {
            settingsManager.settings
                .map { McpRuntimeConfig(enabled = it.mcpEnabled, port = it.mcpPort) }
                .distinctUntilChanged()
                .collect { desired -> reconcile(desired) }
        }

        // Independent watcher: settings-UI toggles of disabledMcpTools push add/remove
        // to whatever live server is currently bound. No-op when the engine is stopped
        // (applyDisabledSet returns early if no server has been built).
        //
        // `mutex.withLock` here only guards the read of [runningServer] against engine
        // start/stop (which also holds the mutex). The actual SDK-mutation race is
        // serialized internally by BossTermMcpServer.applyDisabledSet via its own
        // toolsLock — that's what makes the concurrent manage_tools-handler path safe.
        disabledToolsWatcherJob = parentScope.launch {
            settingsManager.settings
                .map { it.disabledMcpTools }
                .distinctUntilChanged()
                .collect { disabled ->
                    mutex.withLock {
                        runningServer?.applyDisabledSet(disabled)
                    }
                }
        }

        // Marker reflects the "use run_command as default shell" setting so the
        // user-global PreToolUse hook (which keys off ~/.bossterm/mcp.port)
        // turns on/off the instant, per-Bash-call enforcement the moment the
        // setting changes — no client restart. Write when opted in AND an
        // engine is bound; delete otherwise. Also clears any stale marker left
        // by a prior kill -9 when the setting is (or has become) off.
        preferredShellWatcherJob = parentScope.launch {
            settingsManager.settings
                .map { it.mcpRunCommandPreferredShell }
                .distinctUntilChanged()
                .collect { preferred ->
                    mutex.withLock {
                        val port = runningPort
                        if (preferred && port != null) writePortMarker(port)
                        else deletePortMarker()
                    }
                }
        }
    }

    /**
     * Cancel the watcher and stop the Ktor engine on a background coroutine.
     * Returns immediately — does not block the caller's thread. Idempotent.
     */
    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
        disabledToolsWatcherJob?.cancel()
        disabledToolsWatcherJob = null
        preferredShellWatcherJob?.cancel()
        preferredShellWatcherJob = null
        // Async shutdown so callers (including Compose onDispose on the UI
        // thread) don't block waiting for Ktor's grace period.
        parentScope.launch(Dispatchers.IO) {
            mutex.withLock { stopRunningEngineLocked() }
        }
    }

    private suspend fun reconcile(desired: McpRuntimeConfig) {
        mutex.withLock {
            val currentPort = runningPort
            val currentlyRunning = runningEngine != null

            when {
                !desired.enabled && currentlyRunning -> {
                    stopRunningEngineLocked()
                }
                desired.enabled && !currentlyRunning -> {
                    startEngineLocked(desired.port)
                }
                desired.enabled && currentlyRunning && currentPort != desired.port -> {
                    stopRunningEngineLocked()
                    startEngineLocked(desired.port)
                }
                else -> {
                    // No-op.
                }
            }
        }
    }

    /**
     * Outcome of a single bind attempt. Distinguishes "port is busy, try the
     * next one" from "stop trying, something is structurally wrong" so the
     * caller doesn't have to know which return value means what.
     */
    private enum class StartOutcome {
        /** Bound successfully; manager state fields are set. */
        Started,

        /** Port was in use (EADDRINUSE). State is cleared; caller should try the next port. */
        PortBusy,

        /**
         * Bind failed for a reason that won't be fixed by trying another port —
         * EACCES on a privileged port, an SDK initialization error, etc. State is
         * cleared; caller should stop the fallback loop.
         */
        HardFailed,
    }

    private fun startEngineLocked(desiredPort: Int) {
        // Try the user's configured port first, then walk sequential ports up to
        // MAX_PORT_FALLBACK_ATTEMPTS - 1 more. EADDRINUSE triggers fallback;
        // EACCES (permission denied — privileged ports on Linux/macOS), other
        // BindException causes, and unrelated Throwables stay hard failures so
        // config bugs aren't silently masked by walking up a privileged range.
        // The persisted `mcpPort` is NOT updated — the next restart still
        // tries the original first in case the conflicting process exited.
        //
        // Each iteration pre-flights the port with a synchronous ServerSocket
        // bind. Ktor CIO's `engine.start(wait = false)` returns immediately
        // (success) and the real accept-loop bind runs asynchronously — when
        // that async bind fails it wraps the BindException in a
        // JobCancellationException which our synchronous try/catch can't see
        // reliably. Pre-flighting moves the fallback decision out of the async
        // path and avoids the wrap-in-coroutine problem entirely.
        for (offset in 0 until MAX_PORT_FALLBACK_ATTEMPTS) {
            val port = desiredPort + offset
            if (port > MAX_TCP_PORT) {
                log.error(
                    "BossTerm MCP port fallback exhausted (would-be next port {} exceeds {}); giving up",
                    port, MAX_TCP_PORT
                )
                return
            }
            val probe = preflightPort(port)
            when (probe) {
                PortProbe.Available -> { /* fall through to tryStartOnPort */ }
                PortProbe.InUse -> {
                    log.warn(
                        "Port {}:{} appears in use (pre-flight); trying next",
                        HOST, port
                    )
                    continue
                }
                PortProbe.PermissionDenied -> {
                    log.error(
                        "BossTerm MCP server cannot bind {}:{} (permission denied at pre-flight); giving up",
                        HOST, port
                    )
                    return
                }
            }
            when (tryStartOnPort(port, desiredPort)) {
                StartOutcome.Started, StartOutcome.HardFailed -> return
                StartOutcome.PortBusy -> continue
            }
        }
        log.error(
            "BossTerm MCP server failed to bind any port in [{},{}]; giving up",
            desiredPort, desiredPort + MAX_PORT_FALLBACK_ATTEMPTS - 1
        )
    }

    /**
     * Synchronous pre-flight: try to bind a ServerSocket on the loopback
     * address and immediately close it. Lets us classify the port before
     * handing it to Ktor's async accept loop, where a real bind failure
     * gets wrapped in a JobCancellationException and is awkward to catch.
     *
     * Has a small TOCTOU window (the port could become busy between the
     * probe close and Ktor's bind). Acceptable: we're talking about
     * loopback in a single-user session, and `tryStartOnPort`'s catch
     * still handles whatever Ktor surfaces.
     */
    private fun preflightPort(port: Int): PortProbe {
        return try {
            ServerSocket().use { sock ->
                sock.reuseAddress = false  // mirror Ktor; don't quietly steal a TIME_WAIT
                sock.bind(InetSocketAddress(HOST, port))
                PortProbe.Available
            }
        } catch (e: BindException) {
            val msg = e.message.orEmpty()
            if (looksLikePermissionDenied(msg)) PortProbe.PermissionDenied
            else PortProbe.InUse
        } catch (e: IOException) {
            // Other I/O — treat as "in use" so the loop tries the next port.
            // If it's a config-level problem (e.g. binding outside loopback),
            // the user will hit the same error on every port and we'll
            // eventually run out and give up cleanly.
            PortProbe.InUse
        }
    }

    private enum class PortProbe { Available, InUse, PermissionDenied }

    private fun looksLikePermissionDenied(msg: String): Boolean =
        msg.contains("permission", ignoreCase = true) ||
            msg.contains("denied", ignoreCase = true) ||
            msg.contains("not permitted", ignoreCase = true) ||
            // The JDK sometimes includes the raw errno code in the message,
            // which is locale-independent — match it as a backstop.
            msg.contains("EACCES", ignoreCase = true)

    /**
     * One bind attempt. See [StartOutcome] for the return semantics.
     *
     * The EACCES detection inspects [BindException.message] rather than a more
     * specific exception type because OpenJDK throws plain `BindException` for
     * both EADDRINUSE and EACCES; only the message string distinguishes them
     * (see `sun.nio.ch.Net.bind0` → `handleSocketError`). It's brittle — a
     * non-English locale or a future JDK could rephrase the text — but the
     * worst-case failure is "fall back through a privileged range and waste
     * ~10 quick binds before giving up," which is the pre-fix behavior.
     */
    private fun tryStartOnPort(port: Int, desiredPort: Int): StartOutcome {
        val mcpServerWrapper = BossTermMcpServer(registry, config, settingsManager)
        val mcpServer = mcpServerWrapper.createServer()
        val allowedHosts = setOf("127.0.0.1", "localhost", "127.0.0.1:$port", "localhost:$port")
        try {
            if (port == desiredPort) {
                log.info("Starting BossTerm MCP server on http://{}:{}{}", HOST, port, PATH)
            } else {
                log.info(
                    "Starting BossTerm MCP server on http://{}:{}{} (fallback from configured port {})",
                    HOST, port, PATH, desiredPort
                )
            }
            val engine = embeddedServer(CIO, host = HOST, port = port) {
                install(SSE)
                // DNS-rebinding defense: only accept Host headers that name a
                // loopback target. Anything else (e.g. attacker.example
                // resolving to 127.0.0.1 in a victim browser) gets 403.
                intercept(ApplicationCallPipeline.Plugins) {
                    val host = call.request.host()
                    val hostHeader = call.request.headers["Host"]?.lowercase() ?: host.lowercase()
                    if (hostHeader !in allowedHosts) {
                        call.respondText(
                            "Forbidden: Host header '$hostHeader' is not a loopback target.",
                            status = HttpStatusCode.Forbidden
                        )
                        finish()
                        return@intercept
                    }

                    // Resolve which BossTerm window the calling client lives in
                    // (process-tree walk from the client's PID) and record it
                    // so tools that default to "primary window" target the
                    // caller's window rather than first-registered. Failure
                    // here is silent — the resolver returns null and the
                    // server keeps using the prior resolution (or
                    // primaryState() if there is none). This runs only AFTER
                    // the rebinding check passes, so we never spawn lsof for a
                    // hostile request.
                    //
                    // Gated to POST (the JSON-RPC path) so the long-lived SSE
                    // GET and Ktor housekeeping never trigger an lsof/ps walk,
                    // and cached per client port so repeated POSTs on one
                    // connection resolve once. Together these cut the shell-out
                    // cost (~50-150ms on macOS) from per-request to roughly
                    // once per client connection.
                    if (call.request.httpMethod == HttpMethod.Post) {
                        val remotePort = call.request.local.remotePort
                        val cached = clientWindowByPort[remotePort]
                        val resolved = if (cached != null) {
                            cached.orElse(null)
                        } else {
                            // Cache miss: the resolver shells out to lsof/ps
                            // (~50-150ms on macOS). Run it on Dispatchers.IO so
                            // it never stalls a CIO selector thread. The small
                            // race where two first-POSTs on one port both
                            // resolve is harmless (same result, idempotent).
                            if (clientWindowByPort.size > CLIENT_WINDOW_CACHE_MAX) {
                                clientWindowByPort.clear()
                            }
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    ProcessAncestry.resolveClientWindow(remotePort, registry)
                                }.getOrNull()
                            }
                            clientWindowByPort[remotePort] = Optional.ofNullable(r)
                            r
                        }
                        registry.setLastResolvedClientWindow(resolved)
                    }
                }
                // SDK 0.8.3 quirk: both `Route.mcp { ... }` and
                // `Routing.mcp(path, ...) { ... }` end up mounting SSE +
                // POST at the application root regardless of any wrapping
                // route, because the path overload's inner lambda re-invokes
                // mcp(routing, block) against the original Routing. So we
                // mount at root directly and advertise the URL as
                // http://127.0.0.1:<port>/ — clients register that URL.
                routing {
                    mcp { mcpServer }
                }
            }
            engine.start(wait = false)
            runningEngine = engine
            runningPort = port
            runningServer = mcpServerWrapper
            registry.setRunning(port)
            // The marker is gated on the "use run_command as default shell"
            // setting: it exists ONLY while the user has opted in, so the
            // PreToolUse hook (which keys off it) enforces run_command exactly
            // when desired. Live toggles are handled by preferredShellWatcherJob.
            if (settingsManager.settings.value.mcpRunCommandPreferredShell) {
                writePortMarker(port)
            }
            log.info(
                "BossTerm MCP server ready: http://{}:{}{} (SSE transport, {} state(s) registered)",
                HOST, port, PATH, registry.stateCount()
            )
            launchAutoReattach(port)
            return StartOutcome.Started
        } catch (e: Throwable) {
            mcpServerWrapper.detachServer()
            runningEngine = null
            runningPort = null
            runningServer = null

            // Ktor CIO wraps the bind-time BindException in a
            // JobCancellationException (the async accept loop's parent
            // coroutine gets cancelled). Unwrap the cause chain so the
            // fallback decision is right whether the JDK threw bind directly
            // or it bubbled through a coroutine. The pre-flight in
            // startEngineLocked usually catches this earlier, but a TOCTOU
            // window remains.
            val bind = generateSequence(e as Throwable?) { it.cause }
                .filterIsInstance<BindException>()
                .firstOrNull()
            if (bind != null) {
                val msg = bind.message.orEmpty()
                return if (looksLikePermissionDenied(msg)) {
                    log.error(
                        "BossTerm MCP server cannot bind {}:{} (permission denied); giving up: {}",
                        HOST, port, msg
                    )
                    StartOutcome.HardFailed
                } else {
                    log.warn(
                        "BossTerm MCP server failed to bind {}:{} (port in use?): {}",
                        HOST, port, msg
                    )
                    StartOutcome.PortBusy
                }
            }

            log.error("BossTerm MCP server failed to start on {}:{}", HOST, port, e)
            return StartOutcome.HardFailed
        }
    }

    /**
     * Re-run `<cli> mcp add` (quiet mode) for every CLI in the persisted
     * attached-targets set. Lets a user's saved attachments survive port
     * changes between launches — and fixes them silently. If a CLI's
     * binary is missing now (uninstalled since last run), we drop it from
     * the persisted set instead of polluting the clipboard.
     */
    private fun launchAutoReattach(port: Int) {
        val targets = registry.attachedTargets.value
        if (targets.isEmpty()) return
        log.info("Auto-reattaching {} CLI(s) to new endpoint…", targets.size)
        parentScope.launch(Dispatchers.IO) {
            // Fan out: each CLI's mcp add/remove operates on its own config
            // file, so they're independent. Running sequentially used to add
            // up to ~5-10s for four targets; parallel keeps total time bound
            // by the slowest one (~1-2s).
            val outcomes = coroutineScope {
                targets.map { target ->
                    async {
                        target to McpCliAttacher.attach(
                            target, config.serverName, port, quiet = true
                        )
                    }
                }.awaitAll()
            }
            outcomes.forEach { (target, result) ->
                if (result is McpAttachResult.CopiedToClipboard) {
                    log.warn(
                        "Auto-reattach failed for {}: {}; dropping from persisted set",
                        target.displayName, result.reason
                    )
                    registry.markDetached(target)
                }
            }
        }
    }

    private suspend fun stopRunningEngineLocked() {
        val engine = runningEngine ?: return
        val port = runningPort
        try {
            withContext(Dispatchers.IO) {
                engine.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
            }
            log.info("BossTerm MCP server stopped (port {})", port)
        } catch (e: Throwable) {
            log.warn("Error while stopping BossTerm MCP server on port {}: {}", port, e.message)
        } finally {
            // Detach the wrapper first so any in-flight manage_tools handler
            // (or a stray applyDisabledSet from the watcher) becomes a no-op
            // instead of mutating a Server that's no longer bound to any
            // transport.
            runningServer?.detachServer()
            runningEngine = null
            runningPort = null
            runningServer = null
            registry.setStopped()
            deletePortMarker()
        }
    }

    /**
     * Atomic write of the bound port to `~/.bossterm/mcp.port` so the user-global
     * Claude Code `PreToolUse` hook can decide whether to route `Bash` through
     * `mcp__bossterm__run_command` with a single stat + `nc -z` instead of an
     * HTTP probe (~5ms vs ~300ms worst case per Bash call).
     *
     * The marker is GATED on [TerminalSettings.mcpRunCommandPreferredShell]: it
     * is present only while the user has opted into run_command as the default
     * shell, so the hook enforces exactly when desired and toggling the setting
     * flips enforcement instantly (the marker is written/deleted live by
     * `preferredShellWatcherJob`). Callers must check the setting before calling
     * this; the bind path and the watcher both do.
     *
     * Reflects the *actual* bound port, including the 7676→7685 fallback range,
     * so the hook doesn't need to know about fallback. Best-effort: any I/O
     * failure is logged at WARN and ignored — the marker is an optimization,
     * not a correctness lever.
     */
    private fun writePortMarker(port: Int) {
        try {
            val target = mcpPortMarkerFile()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".mcp.port.tmp")
            tmp.writeText(port.toString())
            // ATOMIC_MOVE so concurrent hook reads never see a partial file.
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Layered filesystems (NFS $HOME with a tmpfs override, some
                // container mounts) can't do atomic rename. Fall back to a
                // plain replace. The only race is a hook reading the marker
                // mid-write on this fallback path — and its `case "$port" in '')`
                // guard degrades that to "no routing this call" (a missed hook
                // fire on the transition), never a stale/wrong port number.
                // Far better than never writing the marker at all.
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Throwable) {
            log.warn("Failed to write MCP port marker: {}", e.message)
        }
    }

    private fun deletePortMarker() {
        try {
            val target = mcpPortMarkerFile()
            if (target.exists() && !target.delete()) {
                log.warn("Failed to delete MCP port marker at {}", target)
            }
        } catch (e: Throwable) {
            log.warn("Error while deleting MCP port marker: {}", e.message)
        }
    }

    private fun mcpPortMarkerFile(): File =
        File(System.getProperty("user.home"), ".bossterm/mcp.port")

    private data class McpRuntimeConfig(val enabled: Boolean, val port: Int)

    private companion object {
        private const val HOST = "127.0.0.1"
        /** Path the SSE/POST endpoints are reachable at. SDK 0.8.3 only honors root. */
        private const val PATH = "/"
        private const val STOP_GRACE_MS = 500L
        private const val STOP_TIMEOUT_MS = 1500L

        /**
         * How many sequential ports to try when the user's configured `mcpPort`
         * is busy. The first attempt is the configured port; subsequent attempts
         * walk +1, +2, ... A small range so we don't wander off into ephemeral
         * port territory or run for too long on each (re)start. Each attempt
         * is a separate Ktor bind, so this also bounds startup latency in the
         * worst case (~10 × the bind timeout).
         */
        private const val MAX_PORT_FALLBACK_ATTEMPTS = 10

        /** Upper bound on TCP port numbers; we never wrap past this. */
        private const val MAX_TCP_PORT = 65535

        /**
         * Size at which [clientWindowByPort] is cleared wholesale. Caller-window
         * resolutions are keyed by ephemeral client port; this bounds the map so
         * recycled ports over a long-lived server can't accrete entries. Well
         * above the realistic concurrent-client count.
         */
        private const val CLIENT_WINDOW_CACHE_MAX = 256
    }
}
