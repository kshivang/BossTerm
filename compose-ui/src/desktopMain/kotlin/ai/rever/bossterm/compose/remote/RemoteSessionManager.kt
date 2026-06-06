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

    // remote tabId → the local mirror tab placed in tabController.tabs.
    private val localTabByRemote = LinkedHashMap<String, TerminalTab>()
    // remote paneId → the mirror session feeding that pane's terminal.
    private val sessionByPane = HashMap<String, TerminalTab>()

    fun start() = conn.start()

    /** Ask the host to grant write/control (when connected view-only). */
    fun requestControl() = conn.send(ClientMessage.RequestControl)

    /** True if [tab] is one of this session's mirror tabs. */
    fun containsTab(tab: TerminalTab): Boolean = localTabByRemote.values.any { it === tab }

    /** Open a new tab in the remote session (mirrors back as another tab). Control only. */
    fun newRemoteTab() = conn.send(ClientMessage.NewTab)

    /**
     * Split the remote session's current pane (the active mirror tab if it belongs to this
     * session, else this session's first tab) left/right or top/bottom — the host applies it
     * and it mirrors back. Control only.
     */
    fun splitFocused(horizontal: Boolean) {
        val controller = state.tabController ?: return
        val tab = controller.tabs.getOrNull(controller.activeTabIndex)?.takeIf { containsTab(it) }
            ?: localTabByRemote.values.firstOrNull() ?: return
        val remoteTabId = localTabByRemote.entries.firstOrNull { it.value === tab }?.key ?: return
        val paneId = state.splitStates[tab.id]?.focusedPaneId?.takeIf { sessionByPane.containsKey(it) }
            ?: tab.remotePaneId ?: return
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
                s.dataStream.append(msg.data)
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
     * Rebuild the local mirror tabs from the remote [layout]: create tabs for new remote tabs,
     * remove vanished ones, and (re)build each tab's split tree (reusing mirror sessions by
     * remote paneId). Runs whenever the host's structure changes — Layout is only resent then.
     */
    private fun reconcile(layout: ServerMessage.Layout) {
        val controller = state.tabController ?: return
        val remoteIds = layout.tabs.map { it.id }.toSet()

        // Self-heal: a mirror tab the user closed via its chip (×) is gone from the controller
        // but still in our maps — forget it (+ its pane sessions) so it can be rebuilt fresh.
        localTabByRemote.entries.toList().forEach { (remoteId, t) ->
            if (controller.tabs.none { it === t }) {
                localTabByRemote.remove(remoteId)
                val panes = state.splitStates[t.id]?.getAllSessions()?.toList() ?: listOf(t)
                sessionByPane.entries.removeIf { e -> e.value === t || panes.any { it === e.value } }
                state.splitStates.remove(t.id)
            }
        }

        // Drop mirror tabs for remote tabs that no longer exist.
        (localTabByRemote.keys - remoteIds).toList().forEach { gone ->
            localTabByRemote.remove(gone)?.let { removeMirrorTab(it) }
        }
        // Drop pane sessions whose remote paneId vanished.
        val livePaneIds = layout.tabs.flatMap { paneIds(it.tree) }.toSet()
        (sessionByPane.keys - livePaneIds).toList().forEach { gone ->
            sessionByPane.remove(gone)?.let { s ->
                if (localTabByRemote.values.none { it === s }) disposeSession(s) // not a tab-level session
            }
        }

        for (tabNode in layout.tabs) {
            val firstPane = firstPaneId(tabNode.tree) ?: continue
            // Ensure the tab-level mirror session exists (it IS the first pane).
            val tab = localTabByRemote.getOrPut(tabNode.id) {
                val t = controller.createRemoteSession(
                    title = tabNode.title.ifBlank { "remote" },
                    remotePaneId = firstPane,
                    onUserInput = { data -> if (conn.canControl) conn.send(ClientMessage.Input(firstPane, data)) },
                )
                sessionByPane[firstPane] = t
                controller.tabs.add(t)
                t
            }
            tab.title.value = tabNode.title.ifBlank { "remote" }
            tab.workingDirectory.value = tabNode.cwd

            // Single pane → no split state; multi-pane → (re)build the split tree.
            if (tabNode.tree is PaneTreeNode.Split) {
                val ss = state.splitStates.getOrPut(tab.id) { SplitViewState(initialSession = tab) }
                ss.setTree(buildTree(tabNode.tree, tab, firstPane), firstPane)
            } else {
                state.splitStates.remove(tab.id)
            }
        }
    }

    /** Map a remote pane tree to a local [SplitNode] tree, reusing mirror sessions by paneId. */
    private fun buildTree(node: PaneTreeNode, tabSession: TerminalTab, firstPaneId: String): SplitNode = when (node) {
        is PaneTreeNode.Pane -> {
            val session = if (node.paneId == firstPaneId) tabSession else sessionByPane.getOrPut(node.paneId) {
                state.tabController!!.createRemoteSession(
                    title = node.title,
                    remotePaneId = node.paneId,
                    onUserInput = { data -> if (conn.canControl) conn.send(ClientMessage.Input(node.paneId, data)) },
                )
            }
            SplitNode.Pane(id = node.paneId, session = session)
        }
        is PaneTreeNode.Split -> if (node.dir == "h") {
            SplitNode.HorizontalSplit(
                id = node.id.ifBlank { node.paneIdKey() },
                top = buildTree(node.a, tabSession, firstPaneId),
                bottom = buildTree(node.b, tabSession, firstPaneId),
                ratio = node.ratio,
            )
        } else {
            SplitNode.VerticalSplit(
                id = node.id.ifBlank { node.paneIdKey() },
                left = buildTree(node.a, tabSession, firstPaneId),
                right = buildTree(node.b, tabSession, firstPaneId),
                ratio = node.ratio,
            )
        }
    }

    private fun removeMirrorTab(tab: TerminalTab) {
        val controller = state.tabController
        // Dispose every mirror session in the tab (the tab itself + any extra split panes).
        val sessions = state.splitStates[tab.id]?.getAllSessions()?.filterIsInstance<TerminalTab>() ?: listOf(tab)
        sessions.forEach { sessionByPane.entries.removeIf { e -> e.value === it } ; disposeSession(it) }
        state.splitStates.remove(tab.id)
        val idx = controller?.tabs?.indexOf(tab) ?: -1
        if (idx >= 0) controller?.closeTab(idx) // index-safe removal (no PTY to kill)
    }

    private fun disposeSession(s: TerminalTab) {
        runCatching { s.dataStream.close() } // unblocks the emulator loop
        runCatching { s.dispose() }
    }

    private fun paneIds(node: PaneTreeNode): List<String> = when (node) {
        is PaneTreeNode.Pane -> listOf(node.paneId)
        is PaneTreeNode.Split -> paneIds(node.a) + paneIds(node.b)
    }

    private fun firstPaneId(node: PaneTreeNode): String? = when (node) {
        is PaneTreeNode.Pane -> node.paneId
        is PaneTreeNode.Split -> firstPaneId(node.a) ?: firstPaneId(node.b)
    }

    // Fallback stable id for a split node when the host didn't send one (old peer).
    private fun PaneTreeNode.Split.paneIdKey(): String = "split:" + paneIds(this).joinToString(",")
}
