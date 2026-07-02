package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.splits.SplitNode
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TabController
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import androidx.compose.runtime.snapshots.SnapshotStateMap
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
    private val splitStates: SnapshotStateMap<String, SplitViewState>,
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

    /** daemon sessionId → its GUI mirror tab. Every leaf of every group lives here too, keyed by
     *  its own session id — the same cache the flat (1-pane-group) path uses. */
    private val tabs = ConcurrentHashMap<String, TerminalTab>()
    /** daemon groupId -> local container tab. Only meaningful for >1-pane groups — a 1-pane
     *  group's "container" is just its own flat tab (already in [tabs], no wrapper needed). */
    private val groupTabs = ConcurrentHashMap<String, TerminalTab>()
    /** groupId -> last-applied tree, to skip redundant [SplitViewState.setTree] calls on a
     *  no-op GroupList resend. */
    private val lastTree = ConcurrentHashMap<String, GroupTreeDto>()
    /** Every session id that's a member of some group, as of the latest GroupList — lets
     *  [reconcile] tell a genuinely ungrouped session (MCP/CLI-created; daemon never wraps those
     *  in a group) apart from one whose tab/leaf is owned by [reconcileGroups]. */
    @Volatile private var allGroupedSessionIds: Set<String> = emptySet()
    @Volatile private var running = false
    // Auto-open bookkeeping for the "empty daemon → open one session" path. issuedAutoOpen: this bridge
    // enqueued the auto-open. sawAnySession: the daemon ever reported a non-empty list. If we issued the
    // open but never saw a session before the socket dropped, the Open was likely lost — release the
    // process-wide claim on disconnect so the reconnect retries instead of leaving the window tab-less.
    @Volatile private var issuedAutoOpen = false
    @Volatile private var sawAnySession = false

    private companion object {
        /** Min spacing between daemon-bound Resize sends per session. Auto-fit fires per layout
         *  tick during a live window drag, and every Resize reflows the daemon's full scrollback
         *  (then the Resized echo reflows the mirror's) — throttle to one grid per interval,
         *  first send immediate, final value always delivered. */
        const val RESIZE_MIN_INTERVAL_MS = 50L

        /** Home + clear screen + clear scrollback — prepended to a snapshot so a reattach repaint
         *  replaces (not appends below) the mirror tab's existing content. */
        const val SNAPSHOT_RESET = "\u001b[H\u001b[2J\u001b[3J"
    }

    /** sessionId → its pending grid + sampler job (see [sendResizeSampled]). */
    private class ResizeSampler(val grid: MutableStateFlow<Pair<Int, Int>?>, val job: Job)
    private val resizeSamplers = ConcurrentHashMap<String, ResizeSampler>()

    /**
     * Send a Resize for [id], sampled: the first request is forwarded as soon as the sampler's
     * collector starts (one dispatch hop onto [io] — the StateFlow retains the value, so nothing
     * is lost, just not strictly synchronous); while requests keep arriving faster than
     * [RESIZE_MIN_INTERVAL_MS] the StateFlow conflates them and the collector forwards only the
     * latest per interval — ending, once the burst stops, with the final grid (a StateFlow always
     * retains the last value). Equal grids dedup for free (StateFlow skips value-equal updates).
     * Sampler creation (layout callbacks) and removal ([closeMirror]/[closeMirrorContainer]) are
     * both Main-confined, so they can't interleave.
     */
    private fun sendResizeSampled(id: String, cols: Int, rows: Int) {
        val sampler = resizeSamplers.computeIfAbsent(id) {
            val grid = MutableStateFlow<Pair<Int, Int>?>(null)
            val job = io.launch {
                grid.filterNotNull().collect { (c, r) ->
                    send(DaemonAttachProtocol.Client.Resize(id, c, r))
                    delay(RESIZE_MIN_INTERVAL_MS) // conflation window: intermediate grids are skipped
                }
            }
            ResizeSampler(grid, job)
        }
        sampler.grid.value = cols to rows
    }

    private fun dropResizeSampler(id: String) {
        resizeSamplers.remove(id)?.job?.cancel()
    }

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
        io.cancel() // reaps the resize-sampler collectors too
        resizeSamplers.clear()
        runCatching { client.close() }
    }

    /** Ask the daemon to open a new session (the GUI's "new tab" when in daemon mode). Returns whether
     *  the request was actually enqueued (false → no live connection / outbox full). */
    fun openSession(cwd: String? = null): Boolean =
        send(DaemonAttachProtocol.Client.Open(cwd = cwd))

    /** Ask the daemon to split [sessionId] (a daemon-hosted pane) in [orientation] ("v"|"h"),
     *  inheriting [cwd] if known. Fire-and-forget like [openSession]; the new pane arrives via the
     *  next GroupList — no optimistic local splice. */
    fun splitPane(sessionId: String, orientation: String, cwd: String? = null): Boolean =
        send(DaemonAttachProtocol.Client.SplitPane(sessionId, orientation, cwd))

    /** Ask the daemon to close one pane (session) — collapses its group if it has siblings, or
     *  closes the whole (1-pane) group if it doesn't. Fire-and-forget. */
    fun closePane(sessionId: String): Boolean =
        send(DaemonAttachProtocol.Client.ClosePane(sessionId))

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
        // The secret travels in a header (not the query string) so it doesn't leak into request-line
        // logs/proxies; pid (for window activation) and v (protocol skew) stay in the query.
        val url = "ws://127.0.0.1:$attachPort/attach?pid=${ProcessHandle.current().pid()}" +
            "&v=${DaemonAttachProtocol.PROTOCOL_VERSION}"
        val out = Channel<String>(capacity = 1024)
        outbox = out
        // Route the daemon-share UI's start/stop/approve calls onto THIS connection's outbox, so a
        // window's Share controls reach whichever attach socket is currently live.
        val shareSender = DaemonShareClient.Sender { m -> send(m) }
        DaemonShareClient.registerSender(shareSender)
        try {
            client.webSocket(url, request = { header(DaemonAttachProtocol.TOKEN_HEADER, secret) }) {
                // Pump this connection's outbox → socket.
                val writer = launch {
                    try { for (text in out) send(Frame.Text(text)) } catch (_: Exception) {}
                }
                try {
                    for (frame in incoming) {
                        // v3: Output/Snapshot arrive as binary frames (raw UTF-8 payload, no JSON
                        // escaping on the hot path); everything else stays JSON text.
                        val msg = when (frame) {
                            is Frame.Text -> runCatching { DaemonAttachProtocol.decodeServer(frame.readText()) }.getOrNull()
                            is Frame.Binary -> DaemonAttachProtocol.BinaryFrame.decode(frame.data)
                            else -> null
                        } ?: continue
                        dispatch(msg)
                    }
                } finally {
                    writer.cancel()
                }
            }
        } finally {
            // Spans the whole connect: if webSocket() throws BEFORE entering its block (daemon down
            // during backoff, token/version rejected), the sender + outbox must still be cleared —
            // otherwise every failed reconnect leaks a stale sender pointing at a dead channel.
            DaemonShareClient.clearSender(shareSender)
            out.close()
            if (outbox === out) outbox = null
            // If we auto-opened a session for an empty daemon but the connection dropped before the
            // daemon ever reported it back, the Open was likely lost — release the one-shot claim so the
            // reconnect's reconcile can retry (it won't double-open: if the session actually exists, the
            // reconnect's reconcile sees a non-empty list and skips auto-open).
            if (running && issuedAutoOpen && !sawAnySession) {
                DaemonBridgeCoordinator.releaseAutoOpen()
                issuedAutoOpen = false
            }
        }
    }

    private suspend fun dispatch(msg: DaemonAttachProtocol.Server) {
        when (msg) {
            // Process SessionList first (drives tabs[id] title/cwd + vanish detection) so any leaf
            // GroupList references next already has fresh metadata.
            is DaemonAttachProtocol.Server.SessionList -> reconcile(msg.sessions)
            is DaemonAttachProtocol.Server.GroupList -> reconcileGroups(msg.groups)
            // Reset the mirror buffer before painting a snapshot. The snapshot has no clear sequence of
            // its own, so on a reconnect (the tab persists, only the socket blipped) it would paint a
            // SECOND full scrollback+screen below the existing content. Clear scrollback+screen+home
            // first — a no-op on a fresh tab, deduplicates on reattach.
            is DaemonAttachProtocol.Server.Snapshot -> tabs[msg.id]?.dataStream?.append(SNAPSHOT_RESET + msg.data)
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
            if (DaemonBridgeCoordinator.claimAutoOpen()) {
                if (openSession()) issuedAutoOpen = true else DaemonBridgeCoordinator.releaseAutoOpen()
            }
            return
        }
        sawAnySession = true
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
            // A session not yet in `tabs` and already known to be grouped (per the latest
            // GroupList) is owned by reconcileGroups — it'll be created there (1-pane groups too),
            // so don't race it into a duplicate flat tab here. Only a genuinely ungrouped session
            // (MCP/CLI-created; the daemon never wraps those in a group) falls through to today's
            // flat-tab creation below.
            if (meta.id in allGroupedSessionIds) continue
            // New session → create a mirror tab (on the UI thread — mutates the Compose tabs list).
            withContext(Dispatchers.Main) {
                val tab = controller.createRemoteSession(
                    title = meta.title,
                    remotePaneId = meta.id,
                    onUserInput = { data -> send(DaemonAttachProtocol.Client.Input(meta.id, data)) },
                )
                tab.onRemoteFit = { cols, rows -> sendResizeSampled(meta.id, cols, rows) }
                tab.workingDirectory.value = meta.cwd
                tabs[meta.id] = tab
                controller.createTabFromExistingSession(tab)
            }
        }
    }

    /**
     * Build/update local [SplitViewState] trees from the daemon's [groups]. Owns ALL tab/leaf
     * creation for grouped sessions (including 1-pane groups — an ordinary daemon tab is just a
     * 1-pane group), so a session whose SessionList frame raced ahead of its GroupList frame is
     * never left to [reconcile]'s ungrouped fallback. Mirrors the existing
     * `RemoteSessionManager.reconcile`/`buildTree` pattern (rebuild-by-id, not incremental patch).
     */
    private suspend fun reconcileGroups(groups: List<GroupView>) {
        val liveGroupIds = groups.map { it.groupId }.toSet()
        withContext(Dispatchers.Main) {
            // Drop containers for groups that vanished entirely (closed/merged away daemon-side).
            (groupTabs.keys - liveGroupIds).toList().forEach { gone ->
                groupTabs.remove(gone)?.let { closeMirrorContainer(it) }
                lastTree.remove(gone)
            }
            for (group in groups) {
                if (lastTree[group.groupId] == group.tree) continue // no-op resend — nothing changed
                val paneIds = collectPaneIds(group.tree)
                if (paneIds.size == 1) {
                    // 1-pane group == ordinary daemon tab. No SplitViewState wrapper — the lone
                    // TerminalTab IS the tab, exactly like today's flat daemon tabs.
                    val onlyId = paneIds.first()
                    val tab = tabs.getOrPut(onlyId) { createLeafMirror(onlyId) }
                    groupTabs[group.groupId] = tab
                    lastTree[group.groupId] = group.tree
                    if (controller.tabs.none { it.id == tab.id }) controller.createTabFromExistingSession(tab)
                    continue
                }
                // Multi-pane group. Any of its panes that currently sit as a TOP-LEVEL tab — either
                // because this group just grew 1->N (the original lone tab is paneIds.first()) or
                // because this pane's SessionList frame raced ahead of this GroupList frame and
                // `reconcile()` wrongly flat-created it — must be pulled out of the tab list (NOT
                // disposed) before it's folded into the split tree, so it doesn't render twice.
                paneIds.forEach { pid ->
                    tabs[pid]?.let { t ->
                        val idx = controller.tabs.indexOfFirst { it.id == t.id }
                        if (idx >= 0) controller.tabs.removeAt(idx)
                    }
                }
                val isNewGroup = !groupTabs.containsKey(group.groupId)
                val container = groupTabs.getOrPut(group.groupId) {
                    tabs[paneIds.first()] ?: controller.createRemoteSession(title = "Split", feedsStream = false)
                }
                val ss = splitStates.getOrPut(container.id) { SplitViewState(initialSession = container) }
                ss.onRemoteDividerDrag = { splitId, ratio, committed ->
                    if (committed) send(DaemonAttachProtocol.Client.UpdateSplitRatio(group.groupId, splitId, ratio))
                }
                ss.setTree(buildGroupTree(group.tree), ss.focusedPaneId)
                lastTree[group.groupId] = group.tree
                if (isNewGroup) controller.createTabFromExistingSession(container)
            }
            allGroupedSessionIds = groups.flatMap { collectPaneIds(it.tree) }.toSet()
        }
    }

    /** Create (not reuse) a leaf mirror session for [sessionId] — same shape as the flat-tab path,
     *  factored out so both the 1-pane and multi-pane branches of [reconcileGroups] share it. */
    private fun createLeafMirror(sessionId: String): TerminalTab =
        controller.createRemoteSession(
            title = "",
            remotePaneId = sessionId,
            onUserInput = { data -> send(DaemonAttachProtocol.Client.Input(sessionId, data)) },
        ).also { it.onRemoteFit = { cols, rows -> sendResizeSampled(sessionId, cols, rows) } }

    /** [GroupTreeDto] -> [SplitNode], reusing/creating leaf mirrors by session id via the same
     *  `tabs` cache the flat path uses. */
    private fun buildGroupTree(node: GroupTreeDto): SplitNode = when (node) {
        is GroupTreeDto.Pane -> SplitNode.Pane(id = node.paneId, session = tabs.getOrPut(node.sessionId) { createLeafMirror(node.sessionId) })
        is GroupTreeDto.Split -> if (node.dir == "h") {
            SplitNode.HorizontalSplit(id = node.id, top = buildGroupTree(node.a), bottom = buildGroupTree(node.b), ratio = node.ratio)
        } else {
            SplitNode.VerticalSplit(id = node.id, left = buildGroupTree(node.a), right = buildGroupTree(node.b), ratio = node.ratio)
        }
    }

    private fun collectPaneIds(node: GroupTreeDto): List<String> = when (node) {
        is GroupTreeDto.Pane -> listOf(node.sessionId)
        is GroupTreeDto.Split -> collectPaneIds(node.a) + collectPaneIds(node.b)
    }

    /** Tear down a multi-pane group's container: dispose every leaf, drop its split state, close
     *  the container tab. Mirrors `RemoteSessionManager.removeMirrorTab`. Must run on Main. */
    private fun closeMirrorContainer(container: TerminalTab) {
        splitStates[container.id]?.getAllSessions()?.filterIsInstance<TerminalTab>()?.forEach { mirror ->
            tabs.entries.filter { it.value === mirror }.forEach { dropResizeSampler(it.key) }
            tabs.entries.removeIf { it.value === mirror }
            runCatching { mirror.dispose() }
        }
        splitStates.remove(container.id)
        val idx = controller.tabs.indexOfFirst { it.id == container.id }
        if (idx >= 0) controller.closeTab(idx)
    }

    private fun resizeMirror(id: String, cols: Int, rows: Int) {
        val tab = tabs[id] ?: return
        if (cols < 1 || rows < 1) return
        runCatching { tab.terminal.resize(TermSize(cols, rows), RequestOrigin.User) }
    }

    private suspend fun closeMirror(id: String) {
        dropResizeSampler(id)
        val tab = tabs.remove(id) ?: return
        withContext(Dispatchers.Main) {
            // A leaf inside a multi-pane group's SplitViewState isn't in controller.tabs at all —
            // only its container is — so the old "assume it's a top-level tab" lookup would miss
            // it. Check every tracked container's split tree first; fall back to the flat-tab
            // removal only if it isn't a grouped leaf.
            val containerSplitState = groupTabs.values.firstNotNullOfOrNull { container ->
                splitStates[container.id]?.takeIf { ss -> ss.getAllSessions().any { it === tab } }
            }
            val pane = containerSplitState?.getAllPanes()?.firstOrNull { it.session === tab }
            if (containerSplitState != null && pane != null) {
                // If this was the group's last pane, the group is gone too — the next GroupList
                // drives closeMirrorContainer; nothing to collapse here.
                if (!containerSplitState.isSinglePane) containerSplitState.closePane(pane.id)
            } else {
                val idx = controller.tabs.indexOfFirst { it.id == tab.id }
                if (idx >= 0) controller.closeTab(idx)
            }
        }
    }
}
