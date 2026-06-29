package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.tabs.TabController
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * GUI-side bridge that renders the daemon's sessions as local mirror tabs (Phase 4 thin-client).
 * Connects to the daemon's [DaemonAttachServer] over a loopback WebSocket and, per daemon session,
 * creates a PTY-less mirror tab via [TabController.createRemoteSession] fed by the session's byte
 * stream — the SAME rendering path proven by remote session sharing. Local keystrokes and canvas
 * resizes are routed back to the daemon, so the GUI is the real display and the daemon owns the PTY.
 *
 * One bridge per attached [TabController]. Tab-list mutations run on the UI dispatcher; byte feeding
 * is thread-safe and stays off it. Reconnects with backoff if the socket drops.
 */
class DaemonSessionBridge(
    private val controller: TabController,
    private val attachPort: Int,
    private val secret: String,
    private val uiScope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(DaemonSessionBridge::class.java)
    private val client = HttpClient(CIO) { install(WebSockets) }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Recreated per connection so messages queued during a dropped connection aren't replayed
    // (stale Input/Resize) against a fresh socket.
    @Volatile private var outbox: Channel<String>? = null

    /** daemon sessionId → its GUI mirror tab. */
    private val tabs = ConcurrentHashMap<String, TerminalTab>()
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        io.launch { runWithReconnect() }
        // Route MCP enable/disable to the daemon whenever the user changes the setting — from the
        // status pill, the Settings toggle, anywhere. This is the daemon-mode analog of how the
        // in-process BossTermMcpManager observes [TerminalSettings.mcpEnabled]; the daemon starts/stops
        // its MCP server and replies with McpState, which drives the status indicator.
        io.launch {
            SettingsManager.instance.settings
                .map { it.mcpEnabled }
                .distinctUntilChanged()
                .collect { setMcpEnabled(it) }
        }
    }

    fun stop() {
        running = false
        outbox?.close()
        io.cancel()
        runCatching { client.close() }
    }

    /** Ask the daemon to open a new session (the GUI's "new tab" when in daemon mode). Returns whether
     *  the request was actually enqueued (false → no live connection / outbox full). */
    fun openSession(cwd: String? = null): Boolean =
        send(DaemonAttachProtocol.Client.Open(cwd = cwd))

    /** Turn the daemon's MCP server on/off (the GUI's MCP settings toggle in daemon mode). The daemon
     *  replies with [DaemonAttachProtocol.Server.McpState], which updates the status indicator. */
    fun setMcpEnabled(enabled: Boolean) {
        send(DaemonAttachProtocol.Client.SetMcpEnabled(enabled))
    }

    /** Enqueue a client message; returns false if it couldn't be sent (no connection / outbox full). */
    private fun send(m: DaemonAttachProtocol.Client): Boolean {
        val box = outbox ?: run {
            // No live connection (reconnecting). Dropping queued input here is intentional — see the
            // per-connection outbox note — but make it visible rather than silent.
            log.debug("attach: dropped client msg {} — no live connection", m::class.simpleName)
            return false
        }
        if (box.trySend(DaemonAttachProtocol.encodeClient(m)).isFailure) {
            // Output drops are by design; a dropped *input*/control message is a correctness issue, so
            // surface it (the socket is wedged and will drop+reconnect).
            log.warn("attach: outbox full — dropped client msg {}", m::class.simpleName)
            return false
        }
        return true
    }

    private suspend fun runWithReconnect() {
        var backoff = 250L
        while (running) {
            try {
                connectOnce()
                backoff = 250L // reset after a clean session
            } catch (e: Exception) {
                if (running) log.debug("attach connection dropped: {}", e.message)
            }
            if (!running) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(4000)
        }
    }

    private suspend fun connectOnce() {
        // Report our pid so the daemon can activate this GUI window on "Open BossTerm".
        // URL-encode the token defensively — it's hex today, but a secret with URL-special chars would
        // otherwise break the query string (the server URL-decodes queryParameters["token"]).
        val encodedToken = java.net.URLEncoder.encode(secret, "UTF-8")
        val url = "ws://127.0.0.1:$attachPort/attach?token=$encodedToken&pid=${ProcessHandle.current().pid()}" +
            "&v=${DaemonAttachProtocol.PROTOCOL_VERSION}"
        val out = Channel<String>(capacity = 1024)
        outbox = out
        // Route the daemon-share UI's start/stop/approve calls onto THIS connection's outbox, so a
        // window's Share controls reach whichever attach socket is currently live.
        val shareSender = DaemonShareClient.Sender { m -> send(m) }
        DaemonShareClient.registerSender(shareSender)
        client.webSocket(url) {
            // Pump this connection's outbox → socket.
            val writer = launch {
                try { for (text in out) send(Frame.Text(text)) } catch (_: Exception) {}
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val msg = runCatching { DaemonAttachProtocol.decodeServer(frame.readText()) }.getOrNull() ?: continue
                    dispatch(msg)
                }
            } finally {
                writer.cancel()
                out.close()
                if (outbox === out) outbox = null
                DaemonShareClient.clearSender(shareSender)
            }
        }
    }

    private suspend fun dispatch(msg: DaemonAttachProtocol.Server) {
        when (msg) {
            is DaemonAttachProtocol.Server.SessionList -> reconcile(msg.sessions)
            is DaemonAttachProtocol.Server.Snapshot -> tabs[msg.id]?.dataStream?.append(msg.data)
            is DaemonAttachProtocol.Server.Output -> tabs[msg.id]?.dataStream?.append(msg.data)
            is DaemonAttachProtocol.Server.Resized -> resizeMirror(msg.id, msg.cols, msg.rows)
            is DaemonAttachProtocol.Server.Closed -> closeMirror(msg.id)
            is DaemonAttachProtocol.Server.Focus -> focusWindows()
            // Phase 2 daemon-share state — feed the process-wide hub the daemon-share window binds to.
            is DaemonAttachProtocol.Server.ShareState -> DaemonShareClient.update(msg)
            // Daemon MCP toggled on/off — reflect the bound port (or off) in the status indicator.
            is DaemonAttachProtocol.Server.McpState ->
                if (msg.port != null) ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setRunning(msg.port)
                else ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setStopped()
        }
    }

    /** Bring this GUI's window(s) to the front — daemon's "Open BossTerm" when a window is already open. */
    private suspend fun focusWindows() {
        withContext(Dispatchers.Main) {
            val windows = ai.rever.bossterm.compose.window.WindowManager.windows
            log.info("Focus requested by daemon; raising {} window(s)", windows.size)
            windows.forEach { w ->
                val win = w.awtWindow ?: return@forEach
                (win as? java.awt.Frame)?.let { f ->
                    if (f.extendedState and java.awt.Frame.ICONIFIED != 0) {
                        f.extendedState = f.extendedState and java.awt.Frame.ICONIFIED.inv() // de-minimize
                    }
                }
                win.isVisible = true
                // macOS won't let a background app activate itself (com.apple.eawt is gone in modern
                // JDKs), but momentarily floating the window above others raises it without focus-
                // stealing APIs; revert alwaysOnTop shortly after so it isn't pinned on top.
                val wasOnTop = win.isAlwaysOnTop
                runCatching { win.isAlwaysOnTop = true }
                win.toFront()
                win.requestFocus()
                if (!wasOnTop) {
                    javax.swing.Timer(450) { runCatching { win.isAlwaysOnTop = false } }
                        .apply { isRepeats = false; start() }
                }
            }
        }
    }

    /** Create mirror tabs for new daemon sessions; update existing ones; close vanished ones. */
    private suspend fun reconcile(sessions: List<DaemonAttachProtocol.SessionMeta>) {
        // First attach to an empty daemon → open one session so the user sees a daemon terminal.
        // The guard is process-wide (coordinator), so bridge churn can't accumulate sessions.
        if (sessions.isEmpty()) {
            // Release the process-wide claim if the Open couldn't be enqueued, so a later reconcile
            // retries instead of the empty daemon being stuck tab-less with the claim consumed.
            if (DaemonBridgeCoordinator.claimAutoOpen() && !openSession()) {
                DaemonBridgeCoordinator.releaseAutoOpen()
            }
            return
        }
        val live = sessions.associateBy { it.id }
        // Remove vanished sessions.
        (tabs.keys - live.keys).toList().forEach { closeMirror(it) }
        for (meta in sessions) {
            val existing = tabs[meta.id]
            if (existing != null) {
                // Reflect daemon-side title/cwd changes (the mirror tab has no local cwd/title
                // tracking of its own, so the tab-bar name + secondary cwd come from here).
                if (existing.title.value != meta.title || existing.workingDirectory.value != meta.cwd) {
                    withContext(Dispatchers.Main) {
                        existing.title.value = meta.title
                        existing.workingDirectory.value = meta.cwd
                    }
                }
                continue
            }
            // New session → create a mirror tab (on the UI thread — mutates the Compose tabs list).
            withContext(Dispatchers.Main) {
                val tab = controller.createRemoteSession(
                    title = meta.title,
                    remotePaneId = meta.id,
                    onUserInput = { data -> send(DaemonAttachProtocol.Client.Input(meta.id, data)) },
                )
                tab.onRemoteFit = { cols, rows -> send(DaemonAttachProtocol.Client.Resize(meta.id, cols, rows)) }
                tab.workingDirectory.value = meta.cwd
                tabs[meta.id] = tab
                controller.createTabFromExistingSession(tab)
            }
        }
    }

    private fun resizeMirror(id: String, cols: Int, rows: Int) {
        val tab = tabs[id] ?: return
        if (cols < 1 || rows < 1) return
        runCatching { tab.terminal.resize(TermSize(cols, rows), RequestOrigin.User) }
    }

    private suspend fun closeMirror(id: String) {
        val tab = tabs.remove(id) ?: return
        withContext(Dispatchers.Main) {
            val idx = controller.tabs.indexOfFirst { it.id == tab.id }
            if (idx >= 0) controller.closeTab(idx)
        }
    }
}
