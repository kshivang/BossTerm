package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.splits.SplitNode
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TabController
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.close
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
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
    private val splitStates: SnapshotStateMap<String, SplitViewState>,
    private val attachPort: Int,
    private val secret: String,
    private val uiScope: CoroutineScope,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val hosted: Boolean = false,
    private val initialCwd: String? = null,
    private val initialCommand: String? = null,
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
    private var sessionMetadata: Map<String, DaemonAttachProtocol.SessionMeta> = emptyMap()
    @Volatile private var running = false
    var hasReceivedState by mutableStateOf(false)
        private set
    private val pendingSplits = ConcurrentHashMap<String, DaemonAttachProtocol.Client.SplitPane>()
    private val pendingOpens = ConcurrentHashMap<String, DaemonAttachProtocol.Client.Open>()
    private val exitCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val hostedFactory: (ai.rever.bossterm.compose.tabs.TerminalLaunchRequest) -> TerminalTab = { launch ->
        val id = launch.tabId ?: java.util.UUID.randomUUID().toString()
        require(controller.tabs.none { it.id == id }) { "Duplicate terminal ID: $id" }
        val tab = tabs.getOrPut(id) { createLeafMirror(id) }
        launch.onProcessExit?.let { exitCallbacks[id] = it }
        launch.onInitialCommandComplete?.let { callback ->
            val listener = object : ai.rever.bossterm.terminal.model.CommandStateListener {
                private var started = false
                override fun onCommandStarted() { started = true }
                override fun onCommandFinished(exitCode: Int) {
                    if (started) {
                        tab.terminal.removeCommandStateListener(this)
                        callback(exitCode == 0, exitCode)
                    }
                }
            }
            tab.terminal.addCommandStateListener(listener)
            tab.commandStateListeners.add(listener)
        }
        pendingOpens[id] = DaemonAttachProtocol.Client.Open(cwd = launch.workingDir, id = id,
            command = launch.command, arguments = launch.arguments, initialCommand = launch.initialCommand,
            requestId = java.util.UUID.randomUUID().toString())
        val selected = controller.activeTabIndex
        controller.createTabFromExistingSession(tab)
        if (!launch.activate && selected >= 0) controller.switchToTab(selected)
        send(pendingOpens.getValue(id))
        tab
    }
    private val pendingGroupCloses = ConcurrentHashMap.newKeySet<String>()
    private val pendingPaneCloses = ConcurrentHashMap.newKeySet<String>()
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

        /** Full terminal reset — prepended to a snapshot so a reattach repaint
         *  replaces (not appends below) the mirror tab's existing content. */
        const val SNAPSHOT_RESET = "\u001bc"
    }

    /**
     * Per-session conflated Resize forwarding (see [ResizeSampler] for the invariants: first send
     * near-immediate, at most one per interval during a burst, final grid always delivered).
     * Creation (layout callbacks) and removal ([closeMirror]/[closeMirrorContainer]) are both
     * Main-confined, so they can't interleave.
     */
    private val resizeSamplers = ResizeSampler(io, RESIZE_MIN_INTERVAL_MS) { id, c, r ->
        send(DaemonAttachProtocol.Client.Resize(id, c, r))
    }

    private fun sendResizeSampled(id: String, cols: Int, rows: Int) = resizeSamplers.request(id, cols, rows)

    private fun dropResizeSampler(id: String) = resizeSamplers.drop(id)

    fun start() {
        if (running) return
        running = true
        if (hosted) controller.daemonTabFactory = hostedFactory
        io.launch { runWithReconnect() }
        // Route MCP enable/disable to the daemon whenever the user changes the setting — from the
        // status pill, the Settings toggle, anywhere. This is the daemon-mode analog of how the
        // in-process BossTermMcpManager observes [TerminalSettings.mcpEnabled]; the daemon starts/stops
        // its MCP server and replies with McpState, which drives the status indicator.
        if (!hosted) io.launch {
            SettingsManager.instance.settings
                .map { it.mcpEnabled }
                .distinctUntilChanged()
                .collect { setMcpEnabled(it) }
        }
    }

    fun stop() {
        running = false
        if (controller.daemonTabFactory === hostedFactory) controller.daemonTabFactory = null
        outbox?.close()
        outbox = null
        io.cancel() // reaps the resize-sampler collectors too
        resizeSamplers.clear()
        runCatching { client.close() }
    }

    /** External UI-owner barrier, used before a plugin loader is closed. */
    fun stopForUnload() {
        stop()
        kotlinx.coroutines.runBlocking { awaitStopped() }
    }

    internal suspend fun awaitStopped() {
        io.coroutineContext[kotlinx.coroutines.Job]?.join()
        client.coroutineContext[kotlinx.coroutines.Job]?.join()
    }

    /** Ask the daemon to open a new session (the GUI's "new tab" when in daemon mode). Returns whether
     *  the request was actually enqueued (false → no live connection / outbox full). */
    fun openSession(cwd: String? = null): Boolean {
        if (hosted) {
            hostedFactory(ai.rever.bossterm.compose.tabs.TerminalLaunchRequest(cwd, null, emptyList(), null, null, null, null, true))
            return true
        }
        return send(DaemonAttachProtocol.Client.Open(cwd = cwd))
    }

    /** Ask the daemon to split [sessionId] (a daemon-hosted pane) in [orientation] ("v"|"h"),
     *  inheriting [cwd] if known. Fire-and-forget like [openSession]; the new pane arrives via the
     *  next GroupList — no optimistic local splice. */
    fun splitPane(sessionId: String, orientation: String, cwd: String? = null): Boolean =
        send(DaemonAttachProtocol.Client.SplitPane(sessionId, orientation, cwd))

    internal fun createHostedPane(anchor: String, orientation: String, cwd: String?, ratio: Float, command: String?): TerminalTab {
        check(hosted && ownsSession(anchor))
        val id = java.util.UUID.randomUUID().toString()
        val tab = tabs.getOrPut(id) { createLeafMirror(id) }
        pendingSplits[id] = DaemonAttachProtocol.Client.SplitPane(anchor, orientation, cwd, ratio, id, command)
        send(pendingSplits.getValue(id))
        return tab
    }

    /** Ask the daemon to close one pane (session) — collapses its group if it has siblings, or
     *  closes the whole (1-pane) group if it doesn't. Fire-and-forget. */
    fun closePane(sessionId: String): Boolean {
        if (!tabs.containsKey(sessionId)) return false
        pendingPaneCloses.add(sessionId)
        send(DaemonAttachProtocol.Client.ClosePane(sessionId))
        return true
    }

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
            log.debug("attach: dropped client msg {} - no live connection", m::class.simpleName)
            return false
        }
        if (box.trySend(DaemonAttachProtocol.encodeClient(m)).isFailure) {
            // Output drops are by design; a dropped *input*/control message is a correctness issue, so
            // surface it (the socket is wedged and will drop+reconnect).
            log.warn("attach: outbox full - dropped client msg {}", m::class.simpleName)
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
        // Route the daemon-share UI's start/stop/approve calls onto THIS connection's outbox, so a
        // window's Share controls reach whichever attach socket is currently live.
        val shareSender = DaemonShareClient.Sender { m -> send(m) }
        try {
            client.webSocket(url, request = { header(DaemonAttachProtocol.TOKEN_HEADER, secret) }) {
                outbox = out
                DaemonShareClient.registerSender(shareSender, hosted)
                // Settings observed while disconnected must be replayed on every live socket.
                setMcpEnabled(SettingsManager.instance.settings.value.mcpEnabled)
                resizeSamplers.replay()
                pendingOpens.values.forEach { send(it) }
                pendingSplits.values.forEach { send(it) }
                pendingGroupCloses.forEach { send(DaemonAttachProtocol.Client.CloseGroup(it)) }
                pendingPaneCloses.forEach { send(DaemonAttachProtocol.Client.ClosePane(it)) }
                // Pump this connection's outbox → socket.
                val writer = launch {
                    try {
                        for (text in out) send(Frame.Text(text))
                    } finally {
                        // A failed writer must end the reader too; otherwise inputs disappear into
                        // an outbox whose consumer has died while the bridge looks connected.
                        runCatching { this@webSocket.close() }
                    }
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
                        dispatch(msg, shareSender)
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
            if (!hosted && running && issuedAutoOpen && !sawAnySession) {
                DaemonBridgeCoordinator.releaseAutoOpen()
                issuedAutoOpen = false
            }
        }
    }

    internal suspend fun dispatch(msg: DaemonAttachProtocol.Server, sender: DaemonShareClient.Sender? = null) {
        when (msg) {
            // Process SessionList first (drives tabs[id] title/cwd + vanish detection) so any leaf
            // GroupList references next already has fresh metadata.
            is DaemonAttachProtocol.Server.SessionList -> reconcile(msg.sessions)
            is DaemonAttachProtocol.Server.GroupList -> reconcileGroups(msg.groups)
            // Reset the mirror buffer before painting a snapshot. The snapshot has no clear sequence of
            // its own, so on a reconnect (the tab persists, only the socket blipped) it would paint a
            // SECOND full scrollback+screen below the existing content. Reset parser/model modes and clear both buffers
            // first — a no-op on a fresh tab, deduplicates on reattach.
            is DaemonAttachProtocol.Server.Snapshot -> {
                resizeMirror(msg.id, msg.cols, msg.rows)
                tabs[msg.id]?.dataStream?.append(SNAPSHOT_RESET + msg.data)
            }
            is DaemonAttachProtocol.Server.Output -> tabs[msg.id]?.dataStream?.append(msg.data)
            is DaemonAttachProtocol.Server.Resized -> resizeMirror(msg.id, msg.cols, msg.rows)
            is DaemonAttachProtocol.Server.Opened -> { pendingOpens.remove(msg.id); pendingSplits.remove(msg.id) }
            is DaemonAttachProtocol.Server.Closed -> {
                pendingOpens.remove(msg.id)
                pendingSplits.remove(msg.id)
                closeMirror(msg.id)
                exitCallbacks.remove(msg.id)?.let { callback -> withContext(uiDispatcher) { callback() } }
            }
            is DaemonAttachProtocol.Server.Focus -> focusWindows()
            // Phase 2 daemon-share state — feed the process-wide hub the daemon-share window binds to.
            is DaemonAttachProtocol.Server.ShareState -> if (sender != null) DaemonShareClient.update(msg, sender)
            // Daemon MCP toggled on/off — reflect the bound port (or off) in the status indicator.
            is DaemonAttachProtocol.Server.McpState -> if (!hosted) {
                if (msg.port != null) ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setRunning(msg.port)
                else ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setStopped()
            }
        }
    }

    /** Bring this GUI's window(s) to the front — daemon's "Open BossTerm" when a window is already open. */
    private suspend fun focusWindows() {
        withContext(uiDispatcher) {
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

    /** Session lists also arrive independently for title/cwd updates. Structural changes are
     * applied with the following GroupList, avoiding temporary flat tabs and last-tab close
     * callbacks while a split changes shape. */
    private suspend fun reconcile(sessions: List<DaemonAttachProtocol.SessionMeta>) {
        sessionMetadata = sessions.associateBy { it.id }
        if (sessions.isNotEmpty()) sawAnySession = true
        withContext(uiDispatcher) {
            sessions.forEach { meta -> tabs[meta.id]?.let { applyMetadata(it, meta) } }
        }
    }

    private fun applyMetadata(tab: TerminalTab, meta: DaemonAttachProtocol.SessionMeta) {
        tab.title.value = meta.title
        tab.workingDirectory.value = meta.cwd
    }

    /** Apply an authoritative topology with stable leaf identities. Multi-pane containers own no
     * leaf stream, so replacing/collapsing a container can never dispose a surviving pane. */
    private suspend fun reconcileGroups(groups: List<GroupView>) {
        pendingGroupCloses.removeAll { pending -> groups.none { it.groupId == pending } }
        pendingPaneCloses.removeAll { it !in sessionMetadata }
        val closingSessions = groups.filter { it.groupId in pendingGroupCloses }
            .flatMap { collectPaneIds(it.tree) }.toSet()
        val groupedSessionIds = groups.flatMap { collectPaneIds(it.tree) }.toSet()
        // A flat MCP/CLI-created session has no group-close acknowledgement. Hide its
        // pending pane close until SessionList confirms removal, including stale reconnect state.
        val visibleMetadata = sessionMetadata.filterKeys {
            it !in closingSessions && !(it in pendingPaneCloses && it !in groupedSessionIds)
        }
        withContext(uiDispatcher) {
            val selected = controller.activeTab?.id
            val selectedGroup = groupTabs.entries.firstOrNull { it.value.id == selected }?.key
            val liveGroupIds = groups.filter { it.groupId !in pendingGroupCloses }.map { it.groupId }.toSet()
            allGroupedSessionIds = groups.flatMap { collectPaneIds(it.tree) }.toSet()
            for (group in groups) {
                if (group.groupId in pendingGroupCloses) continue
                val ids = collectPaneIds(group.tree)
                if (ids.isEmpty() || ids.any { it !in sessionMetadata }) continue
                ids.forEach { id ->
                    tabs.getOrPut(id) { createLeafMirror(id) }.let { applyMetadata(it, sessionMetadata.getValue(id)) }
                }
                val old = groupTabs[group.groupId]
                // Hosted callers (runners/MCP) retain their tab ID through split/collapse,
                // including when the original pane exits and a sibling survives.
                val useLeaf = ids.size == 1 && (!hosted || ids.single() == group.groupId)
                val container = if (useLeaf) tabs.getValue(ids.single()) else {
                    old?.takeIf { it.remotePaneId == null }
                        ?: controller.createRemoteSession(title = tabs.getValue(ids.first()).title.value,
                            feedsStream = false, tabId = if (hosted) group.groupId else null)
                }
                if (useLeaf) {
                    splitStates.remove(container.id)
                } else {
                    val ss = splitStates.getOrPut(container.id) { SplitViewState(initialSession = container) }
                    ss.onRemoteDividerDrag = { splitId, ratio, committed ->
                        if (committed) send(DaemonAttachProtocol.Client.UpdateSplitRatio(group.groupId, splitId, ratio))
                    }
                    if (lastTree[group.groupId] != group.tree) ss.setTree(buildGroupTree(group.tree), ss.focusedPaneId)
                }
                // Replace in place so a topology update never leaves the controller temporarily
                // empty or changes the selected group. The old leaf remains alive in the tree.
                val oldIndex = old?.let { t -> controller.tabs.indexOfFirst { it === t } } ?: -1
                if (old !== container && oldIndex >= 0) controller.replaceTabAtIndex(oldIndex, container)
                else if (controller.tabs.none { it === container }) controller.createTabFromExistingSession(container)
                groupTabs[group.groupId] = container
                lastTree[group.groupId] = group.tree
                if (old !== container && old != null) {
                    splitStates.remove(old.id)
                    if (old.remotePaneId == null) old.dispose()
                }
                // A legacy/ungrouped flat tab may now be owned by this tree. Remove its duplicate
                // list entry only after the replacement container exists.
                ids.forEach { id ->
                    val leaf = tabs.getValue(id)
                    if (leaf !== container) controller.tabs.removeAll { it === leaf }
                }
            }
            (groupTabs.keys - liveGroupIds).toList().forEach { gone ->
                groupTabs.remove(gone)?.let { container ->
                    splitStates.remove(container.id)
                    val index = controller.tabs.indexOfFirst { it === container }
                    if (index >= 0) controller.closeTab(index)
                    else if (container.remotePaneId == null) container.dispose()
                }
                lastTree.remove(gone)
            }
            // MCP/CLI-created sessions can be ungrouped; add them only after group membership is
            // known, with the exact same metadata/resize/input hooks as grouped leaves.
            visibleMetadata.values.filter { it.id !in allGroupedSessionIds }.forEach { meta ->
                val tab = tabs.getOrPut(meta.id) { createLeafMirror(meta.id) }
                applyMetadata(tab, meta)
                if (controller.tabs.none { it === tab }) controller.createTabFromExistingSession(tab)
            }
            (tabs.keys - visibleMetadata.keys - pendingOpens.keys - pendingSplits.keys).toList().forEach { id ->
                dropResizeSampler(id)
                tabs.remove(id)?.let { tab ->
                    val index = controller.tabs.indexOfFirst { it === tab }
                    if (index >= 0) controller.closeTab(index) else tab.dispose()
                }
            }
            if (groupTabs.isNotEmpty() || tabs.isNotEmpty()) hasReceivedState = true
            val targetId = selectedGroup?.let { groupTabs[it]?.id } ?: selected
            controller.tabs.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { controller.switchToTab(it) }
        }
        if (sessionMetadata.isEmpty() && pendingOpens.isEmpty() &&
            (if (hosted) !issuedAutoOpen && !sawAnySession else DaemonBridgeCoordinator.claimAutoOpen())) {
            if (hosted) withContext(uiDispatcher) {
                hostedFactory(ai.rever.bossterm.compose.tabs.TerminalLaunchRequest(initialCwd, null, emptyList(), null, initialCommand, null, null, true))
                issuedAutoOpen = true
            } else if (openSession()) issuedAutoOpen = true else DaemonBridgeCoordinator.releaseAutoOpen()
        }
    }

    fun groupIdForTab(tabId: String): String? = groupTabs.entries.firstOrNull { it.value.id == tabId }?.key

    fun isDaemonSession(tab: TerminalTab): Boolean = tabs.values.any { it === tab }

    fun ownsSession(id: String): Boolean = tabs.containsKey(id)

    fun startShare(scope: String, groupId: String?) { send(DaemonAttachProtocol.Client.StartShare(scope = scope, groupId = groupId)) }

    /** Closing a GUI tab owns either a whole group or one ungrouped daemon session. */
    fun closeTab(tabId: String): Boolean {
        if (closeGroupForTab(tabId)) return true
        val sessionId = tabs.entries.firstOrNull { it.value.id == tabId }?.key ?: return false
        return closePane(sessionId)
    }

    fun closeGroupForTab(tabId: String): Boolean {
        val groupId = groupIdForTab(tabId) ?: return false
        if (lastTree[groupId] == null) return false
        // Queue a single atomic group-close command; closing panes independently can interleave
        // with a split and accidentally leave a just-created sibling alive.
        pendingGroupCloses.add(groupId)
        send(DaemonAttachProtocol.Client.CloseGroup(groupId))
        return true
    }

    /** Create (not reuse) a leaf mirror session for [sessionId] — same shape as the flat-tab path,
     *  factored out so both the 1-pane and multi-pane branches of [reconcileGroups] share it. */
    private fun createLeafMirror(sessionId: String): TerminalTab =
        controller.createRemoteSession(
            title = "",
            remotePaneId = sessionId,
            tabId = if (hosted) sessionId else null,
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

    private fun resizeMirror(id: String, cols: Int, rows: Int) {
        val tab = tabs[id] ?: return
        if (cols < 1 || rows < 1) return
        tab.dataStream.appendAction {
            runCatching { tab.terminal.resize(TermSize(cols, rows), RequestOrigin.User) }
        }
    }

    private suspend fun closeMirror(id: String) {
        dropResizeSampler(id)
        val tab = tabs.remove(id) ?: return
        withContext(uiDispatcher) {
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
