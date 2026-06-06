package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.PaneTreeNode
import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.splits.SplitNode
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Per-window registry of connections to **remote** BossTerm shared sessions (the native
 * counterpart of the browser viewer). Each [connect] dials a share link and mirrors the
 * remote session's tabs (and their split layouts) into this window's tab list as read-or-write
 * mirror tabs ([TerminalTab.isRemote]).
 *
 * Owned by a [TabbedTerminalState] (the window whose left-bar "Add remote" was used).
 */
class RemoteSessionManager(private val state: TabbedTerminalState) {

    /** Stable per-install client id so the host recognizes this device across reconnects. */
    private val clientId: String = UUID.randomUUID().toString()

    /** Active remote sessions — observed by the UI to list them + show status. */
    val sessions = mutableStateListOf<RemoteSession>()

    fun connect(link: String, deviceName: String): RemoteSession {
        val session = RemoteSession(link, deviceName, state, clientId)
        sessions.add(session)
        session.start()
        return session
    }

    fun disconnect(session: RemoteSession) {
        session.close()
        sessions.remove(session)
    }

    /** The remote session that owns [tab] (a mirror tab), or null if it's a local tab. */
    fun sessionForTab(tab: TerminalTab): RemoteSession? = sessions.firstOrNull { it.containsTab(tab) }

    /** Tear down every remote session (e.g. when the window closes). */
    fun disconnectAll() {
        sessions.toList().forEach { it.close() }
        sessions.clear()
    }
}

/**
 * One live connection to a remote share + its mirrored local tabs. Decodes [ServerMessage]s
 * from the [RemoteSessionConnection] and reconciles them into [state]'s tab list: a `Layout`
 * (re)builds the mirror tabs and their split trees; `PaneSnapshot`/`PaneOutput` feed each
 * mirror pane's terminal; `PaneResize` resizes it; `Input` is sent back when control is granted.
 */
class RemoteSession internal constructor(
    val link: String,
    val deviceName: String,
    private val state: TabbedTerminalState,
    clientId: String,
) {
    private val log = LoggerFactory.getLogger(RemoteSession::class.java)

    // In-memory access key for this link (persistence across restarts is a follow-up).
    @Volatile private var key: String? = null

    // While the user drags a split divider locally we stream the ratio to the host (throttled) and
    // ignore the host's Layout echoes (which carry the same ratio) so the divider doesn't fight the
    // drag — mirrors the browser viewer's `splitDragging` guard. Set on the UI thread, read by the
    // WS IO thread in reconcile().
    @Volatile private var draggingSplit = false
    @Volatile private var lastResizeSentAt = 0L

    private val conn = RemoteSessionConnection(
        link = link,
        clientId = clientId,
        deviceName = deviceName,
        keyProvider = { key },
        onGrant = { k, _ -> key = k },
        onServerMessage = ::onMessage,
    )

    val status: StateFlow<RemoteStatus> get() = conn.status
    val canControl: Boolean get() = conn.canControl

    // remote tabId → the local **container** tab in tabController.tabs. A container is a
    // PTY-less, stream-less shell that owns the chip + the split tree; it is never a pane itself.
    private val localTabByRemote = LinkedHashMap<String, TerminalTab>()
    // remote paneId → the mirror session feeding that pane's terminal. Holds ONLY pane mirrors
    // (every remote pane, even a single one), never a container — so the host closing any pane
    // (including a tab's first) can never orphan a survivor's output.
    private val sessionByPane = HashMap<String, TerminalTab>()

    fun start() = conn.start()

    /** Ask the host to grant write/control (when connected view-only). */
    fun requestControl() = conn.send(ClientMessage.RequestControl)

    /** True if [tab] is one of this session's mirror tabs. */
    fun containsTab(tab: TerminalTab): Boolean = localTabByRemote.values.any { it === tab }

    /** Open a new tab in the remote session (mirrors back as another tab). Control only. */
    fun newRemoteTab() = conn.send(ClientMessage.NewTab)

    /**
     * Mirror a local close of a remote chip back to the host (control only): close the whole
     * remote tab for a tab-level / single-pane chip, else just that pane. Structure is
     * host-driven — we never dispose a mirror locally (that would be resurrected by the next
     * Layout); the host's resulting Layout removes it here. No-op when view-only.
     */
    fun closeFromChip(localTabId: String, paneId: String) {
        if (!conn.canControl) return
        val remoteTabId = remoteTabIdFor(localTabId) ?: return
        val ss = state.splitStates[localTabId]
        val wholeTab = paneId == localTabId || ss == null || ss.isSinglePane
        conn.send(
            if (wholeTab) ClientMessage.CloseTab(remoteTabId)
            else ClientMessage.ClosePane(remoteTabId, paneId)
        )
    }

    // ---- chip context-menu actions (mirror the browser viewer's menu, routed to the host) ----
    // Each maps the local container id back to the remote tab id and forwards the host the same
    // ClientMessage the viewer would send. All are control-only (the host also re-checks control).

    /** Split [paneId] of [localTabId]'s tab on the host (mirrors back via Layout). */
    fun splitPane(localTabId: String, paneId: String, horizontal: Boolean) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        conn.send(
            if (horizontal) ClientMessage.SplitHorizontal(rid, paneId)
            else ClientMessage.SplitVertical(rid, paneId)
        )
    }

    /** Launch an AI assistant in [paneId] on the host (runs the host's configured command). */
    fun launchAI(localTabId: String, paneId: String, assistantId: String) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        conn.send(ClientMessage.LaunchAI(rid, paneId, assistantId))
    }

    /** Rename a tab/pane chip on the host (blank [title] clears the custom title). */
    fun renameTab(localTabId: String, paneId: String, title: String) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        conn.send(ClientMessage.RenameTab(rid, paneId, title))
    }

    /** Set/clear a chip's accent on the host. Native hex "0xFFRRGGBB" → host-expected CSS "#RRGGBB". */
    fun setTabColor(localTabId: String, paneId: String, nativeHex: String?) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        val css = nativeHex?.removePrefix("0x")?.removePrefix("0X")
            ?.let { if (it.length == 8) it.substring(2) else it }   // drop the FF alpha
            ?.let { "#$it" }
        conn.send(ClientMessage.SetTabColor(rid, paneId, css))
    }

    /** Duplicate the tab on the host. */
    fun duplicateTab(localTabId: String) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        conn.send(ClientMessage.DuplicateTab(rid))
    }

    /** Close every other tab of this remote session on the host. */
    fun closeOtherTabs(localTabId: String) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        conn.send(ClientMessage.CloseOtherTabs(rid))
    }

    /** Close all tabs after this one on the host. */
    fun closeTabsBelow(localTabId: String) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        conn.send(ClientMessage.CloseTabsBelow(rid))
    }

    /**
     * Drag a split divider: set split node [splitId] in [localTabId]'s tab to [ratio] on the host
     * (mirrors dragging the divider on the host). During the drag ([committed] = false) sends are
     * throttled and Layout echoes are ignored (see [draggingSplit]); on release ([committed] =
     * true) sends the final ratio and resumes reconciling.
     */
    fun resizeSplit(localTabId: String, splitId: String, ratio: Float, committed: Boolean) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        if (committed) {
            draggingSplit = false
            conn.send(ClientMessage.ResizeSplit(rid, splitId, ratio))
        } else {
            draggingSplit = true
            val now = System.currentTimeMillis()
            if (now - lastResizeSentAt >= 60L) { // ~16fps, matches the viewer's throttle
                lastResizeSentAt = now
                conn.send(ClientMessage.ResizeSplit(rid, splitId, ratio))
            }
        }
    }

    /**
     * "Fit host to my screen": ask the host to resize its window so [localTabId]'s grid becomes
     * about [cols]×[rows] (what fits this client's canvas). The host echoes PaneResize back.
     */
    fun resizeHost(localTabId: String, cols: Int, rows: Int) {
        if (!conn.canControl) return
        val rid = remoteTabIdFor(localTabId) ?: return
        conn.send(ClientMessage.ResizeHost(rid, cols, rows))
    }

    /** Map a local container tab id back to its remote tab id. */
    private fun remoteTabIdFor(localTabId: String): String? =
        localTabByRemote.entries.firstOrNull { it.value.id == localTabId }?.key

    /**
     * Split the remote session's current pane (the active mirror tab if it belongs to this
     * session, else this session's first tab) left/right or top/bottom — the host applies it
     * and it mirrors back. Control only.
     */
    fun splitFocused(horizontal: Boolean) {
        val controller = state.tabController ?: return
        // The container to split: the active tab if it's one of ours, else this session's first.
        val container = controller.tabs.getOrNull(controller.activeTabIndex)?.takeIf { containsTab(it) }
            ?: localTabByRemote.values.firstOrNull() ?: return
        val remoteTabId = localTabByRemote.entries.firstOrNull { it.value === container }?.key ?: return
        // The focused pane id IS a remote paneId (split-tree pane ids are the host's paneIds).
        val paneId = state.splitStates[container.id]?.focusedPaneId?.takeIf { sessionByPane.containsKey(it) } ?: return
        conn.send(
            if (horizontal) ClientMessage.SplitHorizontal(remoteTabId, paneId)
            else ClientMessage.SplitVertical(remoteTabId, paneId)
        )
    }

    fun close() {
        conn.close()
        localTabByRemote.values.toList().forEach { removeMirrorTab(it) }
        localTabByRemote.clear()
        sessionByPane.clear()
    }

    // ---- message handling ----
    private fun onMessage(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Layout -> runCatching { reconcile(msg) }
                .onFailure { log.warn("remote layout reconcile failed: {}", it.message) }
            is ServerMessage.PaneSnapshot -> {
                val s = sessionByPane[msg.paneId] ?: return
                if (msg.cols >= 2 && msg.rows >= 2) s.terminal.resize(TermSize(msg.cols, msg.rows), RequestOrigin.User)
                // Clear scrollback+screen IN-BAND (processed by the emulator loop, no cross-thread
                // buffer race) before painting the snapshot — so a reconnect's re-sent snapshot
                // replaces the old content instead of duplicating it.
                s.dataStream.append("[3J[2J[H" + msg.data)
            }
            is ServerMessage.PaneOutput -> sessionByPane[msg.paneId]?.dataStream?.append(msg.data)
            is ServerMessage.PaneResize -> {
                val s = sessionByPane[msg.paneId] ?: return
                if (msg.cols >= 2 && msg.rows >= 2) s.terminal.resize(TermSize(msg.cols, msg.rows), RequestOrigin.User)
            }
            else -> {} // Theme/Presence/Control/Pending/Grant/Denied handled in the connection
        }
    }

    /**
     * Rebuild the local mirror from the remote [layout]. Each remote tab maps to a local
     * **container** tab (chip + index only); every remote pane — even a lone one — is its own
     * mirror session living in the container's split tree. So when the host closes a tab's first
     * pane, the survivors keep streaming (no pane is special). Runs whenever the host's structure
     * changes — Layout is only resent then. Reuses containers/panes by remote id across rebuilds.
     */
    private fun reconcile(layout: ServerMessage.Layout) {
        // While a divider is being dragged locally, ignore the host's Layout echoes (they carry the
        // ratio we just sent) so the drag isn't interrupted — the post-release Layout reconciles.
        if (draggingSplit) return
        val controller = state.tabController ?: return
        val remoteIds = layout.tabs.map { it.id }.toSet()

        // Self-heal: a container the user closed via its chip (×) is gone from the controller but
        // still in our maps — forget it + dispose its pane mirrors so it can be rebuilt fresh.
        localTabByRemote.entries.toList().forEach { (remoteId, container) ->
            if (controller.tabs.none { it === container }) {
                localTabByRemote.remove(remoteId)
                // Owned paneIds: from the (maybe still-present) split tree, plus the incoming
                // layout — getAllPanes()/SplitNode.Pane.id are the remote paneIds.
                val owned = buildSet {
                    state.splitStates[container.id]?.getAllPanes()?.forEach { add(it.id) }
                    layout.tabs.firstOrNull { it.id == remoteId }?.let { addAll(paneIds(it.tree)) }
                }
                owned.forEach { pid -> sessionByPane.remove(pid)?.let { disposeSession(it) } }
                state.splitStates.remove(container.id)
            }
        }

        // Drop containers for remote tabs that no longer exist (host closed the whole tab).
        (localTabByRemote.keys - remoteIds).toList().forEach { gone ->
            localTabByRemote.remove(gone)?.let { removeMirrorTab(it) }
        }
        // Drop pane mirrors whose remote paneId vanished (host closed a split pane).
        val livePaneIds = layout.tabs.flatMap { paneIds(it.tree) }.toSet()
        (sessionByPane.keys - livePaneIds).toList().forEach { gone ->
            sessionByPane.remove(gone)?.let { disposeSession(it) }
        }

        for (tabNode in layout.tabs) {
            // The container — a PTY-less, stream-less tab that owns the chip and hosts the split
            // tree of pane mirrors. It is never itself a pane.
            val isNew = !localTabByRemote.containsKey(tabNode.id)
            val container = localTabByRemote.getOrPut(tabNode.id) {
                controller.createRemoteSession(title = tabNode.title.ifBlank { "remote" }, feedsStream = false)
            }
            container.title.value = tabNode.title.ifBlank { "remote" }
            container.workingDirectory.value = tabNode.cwd

            // (Re)build the split tree of pane mirrors — always present, even for a single pane.
            // Preserve the client's own focused pane across structural updates (setTree falls back
            // to the first pane if the focused one is gone) — client focus is independent of host.
            val ss = state.splitStates.getOrPut(container.id) { SplitViewState(initialSession = container) }
            // Route divider drags in this mirror's split tree to the host (idempotent each pass).
            ss.onRemoteDividerDrag = { splitId, ratio, committed -> resizeSplit(container.id, splitId, ratio, committed) }
            ss.setTree(buildTree(tabNode.tree), ss.focusedPaneId)

            // Add to the tab list only AFTER the split tree is populated — so the UI never renders
            // a not-yet-built container, and without switching focus (must not steal the local
            // active tab). New tabs append at the end.
            if (isNew) controller.tabs.add(container)
        }
    }

    /** Map a remote pane tree to a local [SplitNode] tree, reusing pane mirrors by remote paneId. */
    private fun buildTree(node: PaneTreeNode): SplitNode = when (node) {
        is PaneTreeNode.Pane -> {
            val session = sessionByPane.getOrPut(node.paneId) {
                state.tabController!!.createRemoteSession(
                    title = node.title,
                    remotePaneId = node.paneId,
                    onUserInput = { data -> if (conn.canControl) conn.send(ClientMessage.Input(node.paneId, data)) },
                )
            }
            session.title.value = node.title
            session.workingDirectory.value = node.cwd
            SplitNode.Pane(id = node.paneId, session = session)
        }
        is PaneTreeNode.Split -> if (node.dir == "h") {
            SplitNode.HorizontalSplit(
                id = node.id.ifBlank { node.paneIdKey() },
                top = buildTree(node.a),
                bottom = buildTree(node.b),
                ratio = node.ratio,
            )
        } else {
            SplitNode.VerticalSplit(
                id = node.id.ifBlank { node.paneIdKey() },
                left = buildTree(node.a),
                right = buildTree(node.b),
                ratio = node.ratio,
            )
        }
    }

    private fun removeMirrorTab(container: TerminalTab) {
        val controller = state.tabController
        // Dispose every pane mirror in this container's split tree, then the container itself
        // (closeTab disposes it). No PTY to kill — these are pure mirrors.
        state.splitStates[container.id]?.getAllSessions()?.filterIsInstance<TerminalTab>()?.forEach { mirror ->
            sessionByPane.entries.removeIf { it.value === mirror }
            disposeSession(mirror)
        }
        state.splitStates.remove(container.id)
        val idx = controller?.tabs?.indexOf(container) ?: -1
        if (idx >= 0) controller?.closeTab(idx) // index-safe removal; closeTab disposes the container
    }

    private fun disposeSession(s: TerminalTab) {
        runCatching { s.dataStream.close() } // unblocks the emulator loop (pane mirrors only)
        runCatching { s.dispose() }
    }

    private fun paneIds(node: PaneTreeNode): List<String> = when (node) {
        is PaneTreeNode.Pane -> listOf(node.paneId)
        is PaneTreeNode.Split -> paneIds(node.a) + paneIds(node.b)
    }

    // Fallback stable id for a split node when the host didn't send one (old peer).
    private fun PaneTreeNode.Split.paneIdKey(): String = "split:" + paneIds(this).joinToString(",")
}
