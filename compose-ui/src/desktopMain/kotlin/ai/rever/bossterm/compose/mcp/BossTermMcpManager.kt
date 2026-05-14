package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.SettingsManager
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.net.BindException

/**
 * Lifecycle wrapper that brings up the BossTerm in-process MCP server on a
 * loopback-only embedded Ktor (CIO) HTTP server, driven by user settings.
 *
 * Behavior:
 *  - On [start] the manager begins observing [SettingsManager.settings] and
 *    reconciles the running server with the current `(mcpEnabled, mcpPort)`
 *    pair. Toggling `mcpEnabled` brings the server up/down; changing
 *    `mcpPort` while enabled performs a stop-then-start. Unrelated setting
 *    changes are ignored.
 *  - On [stop] the watcher is cancelled and any running Ktor engine is
 *    stopped with a short grace period.
 *  - The server always binds to `127.0.0.1` — never `0.0.0.0` or a public
 *    interface. Path is `/mcp`.
 *
 * Thread-safety: reconcile is serialized via a [Mutex] so concurrent
 * settings emissions cannot double-start the engine. A [BindException]
 * (e.g. port-in-use) is caught and logged; the engine reference stays null
 * and the next settings emission will trigger a new attempt.
 *
 * This class does not touch the UI and must not be initialised from a
 * Composable.
 */
class BossTermMcpManager(
    private val state: TabbedTerminalState,
    private val settingsManager: SettingsManager,
    private val parentScope: CoroutineScope
) {

    private val log = LoggerFactory.getLogger(BossTermMcpManager::class.java)

    private val mutex = Mutex()

    // Guarded by [mutex] (and also by ordered emission from a single
    // collector coroutine, but the mutex covers stop() racing collect()).
    private var runningEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var runningPort: Int? = null

    private var watcherJob: Job? = null

    /** Begin observing settings. Idempotent. Safe to call multiple times. */
    fun start() {
        if (watcherJob?.isActive == true) return
        watcherJob = parentScope.launch {
            settingsManager.settings
                .map { McpConfig(enabled = it.mcpEnabled, port = it.mcpPort) }
                .distinctUntilChanged()
                .collect { config -> reconcile(config) }
        }
    }

    /**
     * Cancel the watcher and stop the Ktor engine (if running) with a short
     * grace period. Idempotent; blocks briefly while Ktor stops.
     */
    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
        runBlocking {
            mutex.withLock { stopRunningEngineLocked() }
        }
    }

    private suspend fun reconcile(desired: McpConfig) {
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
                    // No-op: either both disabled or already running on the
                    // requested port.
                }
            }
        }
    }

    private fun startEngineLocked(port: Int) {
        try {
            log.info("Starting BossTerm MCP server on http://{}:{}{}", HOST, port, PATH)
            val engine = embeddedServer(CIO, host = HOST, port = port) {
                install(SSE)
                routing {
                    route(PATH) {
                        mcp { BossTermMcpServer(state).createServer() }
                    }
                }
            }
            engine.start(wait = false)
            runningEngine = engine
            runningPort = port
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

    private fun stopRunningEngineLocked() {
        val engine = runningEngine ?: return
        val port = runningPort
        try {
            engine.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
            log.info("BossTerm MCP server stopped (port {})", port)
        } catch (e: Throwable) {
            log.warn("Error while stopping BossTerm MCP server on port {}: {}", port, e.message)
        } finally {
            runningEngine = null
            runningPort = null
        }
    }

    private data class McpConfig(val enabled: Boolean, val port: Int)

    private companion object {
        private const val HOST = "127.0.0.1"
        private const val PATH = "/mcp"
        private const val STOP_GRACE_MS = 500L
        private const val STOP_TIMEOUT_MS = 1500L
    }
}
