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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    }

    fun stop() {
        running = false
        outbox?.close()
        io.cancel()
        runCatching { client.close() }
    }

    /** Ask the daemon to open a new session (the GUI's "new tab" when in daemon mode). */
    fun openSession(cwd: String? = null) {
        send(DaemonAttachProtocol.Client.Open(cwd = cwd))
    }

    private fun send(m: DaemonAttachProtocol.Client) {
        outbox?.trySend(DaemonAttachProtocol.encodeClient(m))
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
        val url = "ws://127.0.0.1:$attachPort/attach?token=$secret"
        val out = Channel<String>(capacity = 1024)
        outbox = out
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
        }
    }

    /** Bring this GUI's window(s) to the front — daemon's "Open BossTerm" when a window is already open. */
    private suspend fun focusWindows() {
        withContext(Dispatchers.Main) {
            // macOS: best-effort app activation so the window actually comes forward from the
            // background (reflective — the class is absent on other platforms / future JDKs).
            runCatching {
                val appCls = Class.forName("com.apple.eawt.Application")
                val app = appCls.getMethod("getApplication").invoke(null)
                appCls.getMethod("requestForeground", java.lang.Boolean.TYPE).invoke(app, true)
            }
            ai.rever.bossterm.compose.window.WindowManager.windows.forEach { w ->
                w.awtWindow?.let { win ->
                    (win as? java.awt.Frame)?.let { if (it.state == java.awt.Frame.ICONIFIED) it.state = java.awt.Frame.NORMAL }
                    win.isVisible = true
                    win.toFront()
                    win.requestFocus()
                }
            }
        }
    }

    /** Create mirror tabs for new daemon sessions; update existing ones; close vanished ones. */
    private suspend fun reconcile(sessions: List<DaemonAttachProtocol.SessionMeta>) {
        // First attach to an empty daemon → open one session so the user sees a daemon terminal.
        // The guard is process-wide (coordinator), so bridge churn can't accumulate sessions.
        if (sessions.isEmpty()) {
            if (DaemonBridgeCoordinator.claimAutoOpen()) openSession()
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
