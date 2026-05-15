package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.SettingsManager
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
import java.net.BindException

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

    private var watcherJob: Job? = null

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
            settingsManager.settings
                .map { McpRuntimeConfig(enabled = it.mcpEnabled, port = it.mcpPort) }
                .distinctUntilChanged()
                .collect { desired -> reconcile(desired) }
        }
    }

    /**
     * Cancel the watcher and stop the Ktor engine on a background coroutine.
     * Returns immediately — does not block the caller's thread. Idempotent.
     */
    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
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

    private fun startEngineLocked(port: Int) {
        val mcpServer = BossTermMcpServer(registry, config).createServer()
        val allowedHosts = setOf("127.0.0.1", "localhost", "127.0.0.1:$port", "localhost:$port")
        try {
            log.info("Starting BossTerm MCP server on http://{}:{}{}", HOST, port, PATH)
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
                }
                // Use the Routing.mcp(path, ...) overload, not Route.mcp{}.
                // SDK 0.8.3's Route.mcp ignores the parent route's path and
                // mounts at the application root; the Routing overload
                // honors the requested path correctly.
                routing {
                    mcp(path = PATH) { mcpServer }
                }
            }
            engine.start(wait = false)
            runningEngine = engine
            runningPort = port
            registry.setRunning(port)
            log.info(
                "BossTerm MCP server ready: http://{}:{}{} (SSE transport, {} state(s) registered)",
                HOST, port, PATH, registry.stateCount()
            )
            launchAutoReattach(port)
        } catch (e: BindException) {
            log.warn(
                "BossTerm MCP server failed to bind {}:{} (port in use?): {}",
                HOST, port, e.message
            )
            runningEngine = null
            runningPort = null
        } catch (e: Throwable) {
            log.error("BossTerm MCP server failed to start on {}:{}", HOST, port, e)
            runningEngine = null
            runningPort = null
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
            runningEngine = null
            runningPort = null
            registry.setStopped()
        }
    }

    private data class McpRuntimeConfig(val enabled: Boolean, val port: Int)

    private companion object {
        private const val HOST = "127.0.0.1"
        private const val PATH = "/mcp"
        private const val STOP_GRACE_MS = 500L
        private const val STOP_TIMEOUT_MS = 1500L
    }
}
