package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.SettingsManager
import io.ktor.http.ContentType
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
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * lifecycles only need to register/unregister their window state with the
 * registry; they should not instantiate this class themselves.
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
    // internal (not private) so McpEngineTeardownTest can install a stub
    // engine and exercise stopRunningEngineLocked without binding a port.
    internal var runningEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var runningPort: Int? = null

    // Holds the live BossTermMcpServer wrapper so the disabled-tools watcher
    // can call applyDisabledSet() against the same instance the transport is bound to.
    // Null when no engine is running.
    private var runningServer: BossTermMcpServer? = null

    private var watcherJob: Job? = null
    private var disabledToolsWatcherJob: Job? = null
    private var preferredShellWatcherJob: Job? = null

    // In-flight auto-reattach fan-out (see launchAutoReattach). Tracked so stop()
    // can cancel it: when an embedding host unloads this instance's classloader
    // (BOSS plugin hot-swap/update), an orphaned reattach coroutine that keeps
    // running past dispose crashes with NoClassDefFoundError on its next lazy
    // class load. Also cancelled by the next launchAutoReattach so two fan-outs
    // never race each other's CLI-config rewrites. Unlike the watcher jobs above
    // (written only from single-threaded start()), this is written under [mutex]
    // from reconcile — so stop() cancels it inside the same lock, giving the
    // read a happens-before edge and closing the assign-vs-cancel race.
    // Internal (not private) for the lifecycle tests.
    internal var reattachJob: Job? = null

    // Test seam: replaces the reattach fan-out body so lifecycle tests can hold
    // the job open without shelling out to real CLIs (which would rewrite the
    // developer's actual CLI configs) or probing real registration files.
    // Null in production.
    internal var reattachBodyOverrideForTest: (suspend (Int) -> Unit)? = null

    // Streamable HTTP (Codex) session bookkeeping for the running engine.
    // Guarded by [mutex] like the engine fields; null while stopped.
    private var streamableSessions: StreamableMcpSessions? = null
    private var streamableSweeperJob: Job? = null

    // Substitutes the closeAll step in [stopRunningEngineLocked]. The failure
    // that step guards against (NoClassDefFoundError from a classloader the
    // host closed after dispose — see [stop]) can't be produced in a unit
    // test, so McpEngineTeardownTest injects a throwing close here to pin the
    // contract that the bookkeeping below the guard still runs.
    internal var closeAllOverrideForTest: (suspend () -> Unit)? = null

    // Caches caller-tab resolution by the client's ephemeral TCP port. That
    // port is stable for a connection's lifetime and its owning PID can't
    // change without reconnecting, so one (possibly expensive) ProcessAncestry
    // resolution per socket suffices instead of one per request. Optional wraps
    // the nullable result (a client outside any pane resolves to "no tab")
    // since ConcurrentHashMap forbids null values. Cleared wholesale past
    // CLIENT_WINDOW_CACHE_MAX so recycled ephemeral ports can't accrete entries
    // — a recycled port could briefly return a stale tab, an acceptable
    // last-writer-wins miss already inherent to the multi-client design.
    private val clientTabByPort = ConcurrentHashMap<Int, Optional<String>>()

    init {
        // Publish the embedder's server name/label to the registry at CONSTRUCTION
        // (not in start()): TabController / EmbeddableTerminal read
        // McpTerminalRegistry.mcpServerName when spawning a PTY to set the shell's
        // BOSS_MCP_SERVER env var, so in-shell agents (e.g. Claude Code) pick the
        // matching mcp__<name>__* toolset instead of a sibling app's. Setting it
        // here means merely constructing the manager before creating terminals is
        // enough — start() and port-bind timing are irrelevant. Also consumed by
        // non-Compose readers like MirrorShare (relaying a remote client's MCP
        // attach/toggle) instead of the Compose-only LocalBossTermMcpConfig.
        registry.setServerInfo(config.serverName, config.displayName ?: "BossTerm")
    }

    /** Begin observing settings. Idempotent. Safe to call multiple times. */
    fun start() {
        if (watcherJob?.isActive == true) return

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
            // Before the first bind (and thus the first auto-reattach),
            // reconcile the persisted set against the CLIs' own config files
            // (adopt entries we're missing, prune ones the user removed) —
            // the CLI config is the canonical record. Runs inside this job so
            // it is guaranteed to complete before the first reconcile/bind
            // kicks off the reattach fan-out.
            reconcileTargetsWithCliConfigs()
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
        // thread) don't block waiting for Ktor's grace period. The reattach
        // fan-out is cancelled inside the lock — its writer (launchAutoReattach,
        // reached from reconcile) assigns under the same mutex, so cancelling
        // here can't race a concurrent assignment or read a stale reference.
        // No new fan-out can start after this block: watcherJob (the only
        // reconcile trigger) was cancelled synchronously above.
        // Nothing in this coroutine may escape uncaught: it runs AFTER stop()
        // (and, in an embedding host, dispose()) has returned, and a plugin
        // hot-swap closes this instance's classloader at that point — so any
        // first-time lazy class load in the teardown path throws
        // NoClassDefFoundError from the closed loader and would crash the host
        // (this killed BOSS via StreamableMcpSessions.closeAll's state-machine
        // class; same failure mode as the #331 reattach orphan). Plain
        // try/catch on purpose: it compiles inline and needs no class of its
        // own. Cleanup past the failure point is forfeited, which is fine —
        // the engine stop has already been requested and the instance is dying.
        parentScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    reattachJob?.cancel()
                    reattachJob = null
                    stopRunningEngineLocked()
                }
            } catch (e: CancellationException) {
                // parentScope cancellation, not a teardown failure — let the
                // coroutine complete as cancelled rather than mis-logging it
                // below. Rethrowing needs no lazy load: CancellationException
                // aliases the (parent-loader) JDK class.
                throw e
            } catch (t: Throwable) {
                // Full stack on purpose: anything landing here is an unknown
                // failure mode, and the trace is what identifies the next
                // StreamableMcpSessions-style lazy-load site.
                log.warn("MCP engine teardown after stop() failed (classloader likely closed)", t)
            }
        }
    }

    private suspend fun reconcile(desired: McpRuntimeConfig) {
        // Publish the configured (pre-fallback-walk) port so registration-URL
        // builders bake a stable default instead of a walked bound port. See
        // McpTerminalRegistry.configuredMcpPort for why baking the bound port
        // cross-wires CLI registrations after this instance quits.
        registry.setConfiguredPort(desired.port)
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
        val streamable = StreamableMcpSessions(mcpServer)
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
                        val cached = clientTabByPort[remotePort]
                        val resolved = if (cached != null) {
                            cached.orElse(null)
                        } else {
                            // Cache miss: the resolver shells out to lsof/ps
                            // (~50-150ms on macOS). Run it on Dispatchers.IO so
                            // it never stalls a CIO selector thread. The small
                            // race where two first-POSTs on one port both
                            // resolve is harmless (same result, idempotent).
                            if (clientTabByPort.size > CLIENT_WINDOW_CACHE_MAX) {
                                clientTabByPort.clear()
                            }
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    ProcessAncestry.resolveClientTabId(remotePort, registry)
                                }.getOrNull()
                            }
                            clientTabByPort[remotePort] = Optional.ofNullable(r)
                            r
                        }
                        registry.setLastResolvedClientTab(resolved)
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
                    // Identity for sibling instances: a starting instance's
                    // polite auto-reattach probes this to learn whether the
                    // port a CLI registration points at is owned by a LIVE
                    // server of the same name (keep it) or is stale/foreign
                    // (claim it). See McpInstanceProbe. Sits behind the
                    // loopback bind + Host-header check like everything else.
                    get(McpInstanceProbe.IDENTITY_PATH) {
                        call.respondText(
                            buildJsonObject {
                                put("serverName", config.serverName)
                                put("pid", ProcessHandle.current().pid())
                            }.toString(),
                            ContentType.Application.Json
                        )
                    }
                    route(STREAMABLE_PATH) { mountStreamableMcp(streamable) }
                    mcp { mcpServer }
                }
            }
            // Warm closeAll's suspend state-machine class while this
            // classloader can still load classes (a no-op on the empty session
            // map). The engine teardown in stop() runs on a background
            // coroutine after dispose() has returned, and a plugin hot-swap in
            // an embedding host closes the classloader at that point — a
            // first-time load of the class there crashed the host with
            // NoClassDefFoundError. Same eager-preload pattern as
            // McpCliAttacher (#331); calling it IS the intended side effect —
            // launched, not awaited, because warming a suspend fun's class
            // requires invoking it. Scheduled before the engine accepts
            // connections: the session map is empty here and a client needs a
            // connect + initialize round-trip to mint a session, so the no-op
            // close finishing first is practically certain. If it ever did
            // observe a freshly-minted session, closing it is the same benign
            // eviction the idle sweeper and session cap already impose — the
            // client sees 404 and re-initializes.
            parentScope.launch {
                // On the fresh empty map closeAll cannot throw — this guard
                // exists so no future change to closeAll can ever cancel
                // parentScope (not guaranteed to be a SupervisorJob) from a
                // fire-and-forget warm-up.
                try {
                    streamable.closeAll()
                } catch (t: Throwable) {
                    log.warn("Streamable MCP closeAll warm-up failed: {}", t.toString())
                }
            }
            engine.start(wait = false)
            runningEngine = engine
            runningPort = port
            runningServer = mcpServerWrapper
            streamableSessions = streamable
            // Streamable HTTP clients that vanish without DELETE (crashed or
            // just exited, as codex does per invocation) leave sessions behind;
            // sweep them so a long-running app doesn't accrete one per run.
            streamableSweeperJob = parentScope.launch {
                while (true) {
                    delay(STREAMABLE_SWEEP_INTERVAL_MS)
                    // evictIdle contains its own per-transport failure handling;
                    // this guard is belt-and-suspenders so no future exception
                    // can kill the sweeper for the life of the engine and
                    // silently reintroduce the leak it exists to prevent.
                    // Cancellation must still propagate or stop() would hang
                    // the loop forever.
                    try {
                        val evicted = streamable.evictIdle(STREAMABLE_IDLE_TTL_MS)
                        if (evicted > 0) {
                            log.info("Evicted {} idle streamable MCP session(s)", evicted)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.warn("Streamable MCP sweep failed; retrying next interval: {}", e.message)
                    }
                }
            }
            registry.setRunning(port)
            // The marker is gated on the "use run_command as default shell"
            // setting: it exists ONLY while the user has opted in, so the
            // PreToolUse hook (which keys off it) enforces run_command exactly
            // when desired. Live toggles are handled by preferredShellWatcherJob.
            if (settingsManager.settings.value.mcpRunCommandPreferredShell) {
                writePortMarker(port)
            }
            log.info(
                "BossTerm MCP server ready: http://{}:{}{} (SSE), http://{}:{}{} (streamable HTTP, {} state(s) registered)",
                HOST, port, PATH, HOST, port, STREAMABLE_PATH, registry.stateCount()
            )
            launchAutoReattach(port)
            return StartOutcome.Started
        } catch (e: Throwable) {
            mcpServerWrapper.detachServer()
            streamableSweeperJob?.cancel()
            streamableSweeperJob = null
            streamableSessions = null
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
     * Reconcile the persisted attached-targets set against the CLIs' own
     * config files — the canonical record. Two directions:
     *  - ADOPT targets whose config carries our loopback entry but that are
     *    missing from the persisted set (heals the historical drop-on-failure
     *    bug with zero clicks).
     *  - PRUNE persisted targets whose config cleanly lacks the entry: the
     *    user removed it (e.g. `claude mcp remove boss`) and auto-reattach
     *    must not keep resurrecting it. UNKNOWN (unreadable config right now)
     *    never prunes — see [McpRegistrationScanner.Presence].
     * Both mark* calls persist, so the outcome survives to future launches.
     */
    private suspend fun reconcileTargetsWithCliConfigs() {
        val presence = try {
            withContext(Dispatchers.IO) {
                McpRegistrationScanner.scan(config.serverName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Registration scan failed: {}", t.message)
            return
        }
        val persisted = registry.attachedTargets.value
        val adopt = presence.filterValues { it == McpRegistrationScanner.Presence.PRESENT }.keys - persisted
        val prune = persisted.filter { presence[it] == McpRegistrationScanner.Presence.ABSENT }
        if (adopt.isNotEmpty()) {
            log.info(
                "Adopting existing MCP registration(s) from CLI config: {}",
                adopt.joinToString { it.displayName }
            )
            adopt.forEach { registry.markAttached(it) }
        }
        if (prune.isNotEmpty()) {
            log.info(
                "Pruning persisted MCP target(s) no longer registered in their CLI config: {}",
                prune.joinToString { it.displayName }
            )
            prune.forEach { registry.markDetached(it) }
        }
    }

    /**
     * Re-run `<cli> mcp add` (quiet mode) for every CLI in the persisted
     * attached-targets set. Lets a user's saved attachments survive port
     * changes between launches — and fixes them silently. A failure keeps
     * the target persisted: attach failures are usually transient (missing
     * binary on a packaged app's bare PATH, a timeout), and dropping the
     * target here is what used to freeze the CLI's registered endpoint on a
     * stale port forever. A genuinely uninstalled CLI costs one quiet
     * background retry per bind, which is harmless.
     *
     * Polite to siblings: before rewriting, each target's currently
     * registered default port is probed ([McpInstanceProbe]) — when a LIVE
     * server with the same name owns it (e.g. the packaged app, while this
     * is a dev instance on a fallback port), the registration is left alone
     * instead of last-writer-wins clobbered. Our own terminals reach this
     * instance via the injected port env var regardless. Explicit attach
     * (the Settings/Toolbox button) stays impolite on purpose: the user
     * asked THIS instance to own the registration.
     */
    internal fun launchAutoReattach(port: Int) {
        val targets = registry.attachedTargets.value
        if (targets.isEmpty()) return
        log.info("Auto-reattaching {} CLI(s) to new endpoint…", targets.size)
        // A rebind supersedes any still-running fan-out from the previous bind —
        // let the newer port win instead of racing two writers over CLI configs.
        reattachJob?.cancel()
        reattachJob = parentScope.launch(Dispatchers.IO) {
            reattachBodyOverrideForTest?.let { it(port); return@launch }
            // One identity probe per distinct registered port (several CLIs
            // usually point at the same default), before the fan-out.
            val registeredPorts = targets.associateWith { target ->
                runCatching {
                    McpRegistrationScanner.registeredDefaultPort(target, config.serverName)
                }.getOrNull()
            }
            val liveOwnerByPort = registeredPorts.values.filterNotNull().distinct()
                .filter { it != port }
                .associateWith { McpInstanceProbe.liveServerName(it) }

            // Fan out: each CLI's mcp add/remove operates on its own config
            // file, so they're independent. Running sequentially used to add
            // up to ~5-10s for four targets; parallel keeps total time bound
            // by the slowest one (~1-2s).
            val outcomes = coroutineScope {
                targets.mapNotNull { target ->
                    val registered = registeredPorts[target]
                    val rewrite = McpInstanceProbe.shouldRewrite(
                        registeredDefaultPort = registered,
                        ourPort = port,
                        ourServerName = config.serverName,
                        liveOwnerName = registered?.let { liveOwnerByPort[it] }
                    )
                    if (!rewrite) {
                        log.info(
                            "Skipping reattach for {} — a live '{}' instance already owns registered port {}",
                            target.displayName, config.serverName, registered
                        )
                        return@mapNotNull null
                    }
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
                        "Auto-reattach failed for {}: {} — keeping it persisted; will retry on next bind",
                        target.displayName, result.reason
                    )
                }
            }
        }
    }

    // internal (not private) for McpEngineTeardownTest; production callers
    // hold [mutex].
    internal suspend fun stopRunningEngineLocked() {
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
            streamableSweeperJob?.cancel()
            streamableSweeperJob = null
            // Close streamable transports so their ServerSessions leave the
            // (about-to-be-detached) Server rather than lingering until GC.
            // Guarded so the bookkeeping below still runs when this teardown
            // executes after the host closed our classloader (post-hot-swap,
            // see stop()): closing live sessions can need a first-time class
            // load the warm-up at bind time couldn't reach (closeQuietly).
            try {
                val closeOverride = closeAllOverrideForTest
                if (closeOverride != null) closeOverride() else streamableSessions?.closeAll()
            } catch (e: CancellationException) {
                // Deliberately NOT rethrown: everything below is plain
                // non-suspending bookkeeping that must still run, and the
                // cancelled job still completes as cancelled once this
                // function returns. Logged as what it is, not as a failure.
                log.info("Streamable MCP session close cancelled during engine stop; finishing bookkeeping")
            } catch (t: Throwable) {
                log.warn("Failed to close streamable MCP sessions during engine stop: {}", t.toString())
            }
            streamableSessions = null
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

    // The production path is fixed at ~/.bossterm/mcp.port on purpose: the
    // PreToolUse hook reads exactly that file, and embedders that relocate
    // the settings dir (bossterm.settings.dir) must still publish the marker
    // where the hook looks. The override exists so tests exercising
    // stopRunningEngineLocked can't delete a developer's real marker.
    internal var portMarkerFileOverrideForTest: File? = null

    private fun mcpPortMarkerFile(): File =
        portMarkerFileOverrideForTest
            ?: File(System.getProperty("user.home"), ".bossterm/mcp.port")

    private data class McpRuntimeConfig(val enabled: Boolean, val port: Int)

    private companion object {
        private const val HOST = "127.0.0.1"
        /** Path the SSE/POST endpoints are reachable at. SDK 0.8.3 only honors root. */
        private const val PATH = "/"
        /** Streamable HTTP endpoint for clients such as Codex. */
        private const val STREAMABLE_PATH = "/mcp"

        /**
         * Idle TTL for streamable sessions. Generous on purpose: an evicted
         * client just gets 404 and re-initializes (the spec requires it), so
         * the only cost of a long TTL is a small lingering session object.
         */
        private const val STREAMABLE_IDLE_TTL_MS = 2 * 60 * 60 * 1000L
        private const val STREAMABLE_SWEEP_INTERVAL_MS = 15 * 60 * 1000L
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
         * Size at which [clientTabByPort] is cleared wholesale. Caller-tab
         * resolutions are keyed by ephemeral client port; this bounds the map so
         * recycled ports over a long-lived server can't accrete entries. Well
         * above the realistic concurrent-client count.
         */
        private const val CLIENT_WINDOW_CACHE_MAX = 256
    }
}
