package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.ai.ToolCommandProvider
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.theme.ColorPalette
import ai.rever.bossterm.compose.settings.theme.ColorPaletteManager
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import ai.rever.bossterm.compose.splits.SplitNode
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.compose.window.WindowManager
import ai.rever.bossterm.terminal.TerminalColor
import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.TerminalLine
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Whether a share covers one tab (incl. its splits), the whole window (all tabs), or every window. */
enum class ShareScope { TAB, WINDOW, ALL }

/**
 * One active share (issue #276) mirroring a window→tabs→panes model to browser viewers.
 * TAB scope mirrors the single initiating tab (with its splits); WINDOW scope mirrors
 * every tab of the owning [TabbedTerminalState], reactive to tab add/remove; ALL scope
 * mirrors every tab of every registered window (reactive to window open/close too) and
 * stamps each [TabNode] with its window identity so viewers can group by window.
 *
 * A `snapshotFlow` over the window's structure (tabs, each tab's split `rootNode`,
 * focus, per-pane size, titles) drives: (re)installing a raw-output tap on every pane,
 * sending a one-time [ServerMessage.PaneSnapshot] for new panes, and broadcasting the
 * [ServerMessage.Layout] + per-pane [ServerMessage.PaneResize] on any structural change.
 * Output never goes through the flow — taps push [ServerMessage.PaneOutput] directly.
 */
class MirrorShare(
    /** The tab the share was initiated from; also resolves the owning window. */
    val tabId: String,
    initialScope: ShareScope,
    /** Invoked when the share has no panes left (its tab/window closed) so the manager can drop it. */
    private val onEnded: () -> Unit,
) {
    val viewToken: String = secureToken()
    val controlToken: String = secureToken()

    // E2E session secret (issue: end-to-end encryption). One per share, shared by the view +
    // control links — it travels ONLY in the link's URL fragment (`#k=`), which the relay never
    // sees, and each connection derives fresh AES-GCM keys from it ([SessionCrypto]). Role
    // (view/control) stays enforced server-side by which token is presented.
    val sessionSecret: ByteArray = SessionCrypto.newSessionSecret()
    val sessionSecretB64: String = SessionCrypto.encodeSecretB64Url(sessionSecret)

    // Observable so the layout observer re-emits when the scope is toggled live
    // (Tab ↔ Window) — same tokens/viewers, just a different set of mirrored tabs.
    private var scopeVar by mutableStateOf(initialScope)
    val scope: ShareScope get() = scopeVar
    fun setScope(s: ShareScope) { scopeVar = s }

    private val viewers = CopyOnWriteArrayList<ViewerConnection>()
    private val viewerSeq = AtomicInteger(0)
    private val coro = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The host's name for this shared session, shown to viewers as the default group label
     * (defaults to `username_machine`; editable in the Share window). Compose state — read
     * inside computeSignature's snapshotFlow, so renames re-broadcast the Layout.
     */
    val sessionName = androidx.compose.runtime.mutableStateOf(SessionShareManager.defaultSessionName())
    private var observerJob: Job? = null
    private var mcpJob: Job? = null

    private class TapEntry(val tab: TerminalTab, val listener: (String) -> Unit)
    private val taps = HashMap<String, TapEntry>()

    fun start() {
        observerJob = coro.launch {
            snapshotFlow { computeSignature() }
                .distinctUntilChanged()
                .collect { sig -> reconcile(sig) }
        }
        // Push the host's MCP state (the "MCP" pill) to viewers whenever it changes —
        // server on/off, the user's enable toggle, or the set of attached CLIs.
        mcpJob = coro.launch {
            combine(
                SettingsManager.instance.settings,
                McpTerminalRegistry.runningPort,
                McpTerminalRegistry.attachedTargets,
            ) { settings, port, attached ->
                ServerMessage.McpStatus(
                    enabled = settings.mcpEnabled,
                    running = port != null,
                    attached = attached.map { it.persistenceKey },
                    serverLabel = MCP_SERVER_LABEL,
                )
            }.distinctUntilChanged().collect { broadcast(it) }
        }
    }

    fun stop() {
        observerJob?.cancel()
        mcpJob?.cancel()
        synchronized(taps) {
            taps.values.forEach { e -> runCatching { e.tab.dataStream.removeRawOutputListener(e.listener) } }
            taps.clear()
        }
        viewers.forEach { it.outbox.close() }
        viewers.clear()
        coro.cancel()
    }

    // ---- viewers ----
    fun addViewer(canControl: Boolean, name: String = "Viewer"): ViewerConnection {
        val vc = ViewerConnection(viewerSeq.incrementAndGet(), canControl, name)
        viewers.add(vc)
        broadcast(ServerMessage.Presence(viewers.size))
        return vc
    }

    fun removeViewer(vc: ViewerConnection) {
        if (viewers.remove(vc)) {
            vc.outbox.close()
            broadcast(ServerMessage.Presence(viewers.size))
            // Last viewer gone → undo any "fit host to my screen" resize.
            if (viewers.isEmpty()) SessionShareManager.releaseEmbeddedFit(tabId)
        }
    }

    val viewerCount: Int get() = viewers.size

    fun broadcast(msg: ServerMessage) {
        val text = ShareProtocol.encodeServer(msg)
        for (v in viewers) v.outbox.trySend(text)
    }

    /** Theme + Layout + a PaneSnapshot per pane, for a newly-connected viewer. */
    fun initialMessages(): List<ServerMessage> {
        val sig = computeSignature()
        val out = ArrayList<ServerMessage>()
        out.add(themeMessage())
        out.add(mcpStatusMessage())
        out.add(ServerMessage.Layout(sig.tabs, sig.activeTabId, sig.tabBarOnLeft, sig.summaryMode, sig.sessionName))
        for ((id, tab) in paneTabMap()) {
            val sz = sig.sizes[id] ?: listOf(80, 24)
            out.add(ServerMessage.PaneSnapshot(id, snapshotText(tab), sz[0], sz[1]))
        }
        return out
    }

    /** Current host MCP state as a [ServerMessage.McpStatus] (snapshot for a new viewer). */
    private fun mcpStatusMessage(): ServerMessage.McpStatus = ServerMessage.McpStatus(
        enabled = SettingsManager.instance.settings.value.mcpEnabled,
        running = McpTerminalRegistry.runningPort.value != null,
        attached = McpTerminalRegistry.attachedTargets.value.map { it.persistenceKey },
        serverLabel = MCP_SERVER_LABEL,
    )

    /** Route viewer messages — input + tab close/new — to the host (controller role only). */
    /**
     * The remote session a host tab itself mirrors (so a viewer's action on it can be relayed
     * to the origin instead of mutating the local mirror — which the next upstream Layout
     * would wipe). Null for the host's own tabs.
     */
    private fun upstreamSession(targetTabId: String?): ai.rever.bossterm.compose.remote.RemoteSession? {
        if (targetTabId == null) return null
        // Resolve by the TARGET tab's owning window (== the anchor's for TAB/WINDOW; any
        // registered window for ALL) so relays work on mirrors living in other windows.
        val st = McpTerminalRegistry.findState(targetTabId) ?: return null
        val tab = st.tabs.firstOrNull { it.id == targetTabId } ?: return null
        return st.remoteSessions.sessionForTab(tab)
    }

    /**
     * The window state a viewer action should mutate: the target tab's owner when the
     * action names a tab (it can live in any window under ALL scope), else the anchor's
     * (e.g. a bare NewTab), else — anchor window already closed under ALL — the first
     * remaining in-scope window.
     */
    private fun stateFor(targetTabId: String?): TabbedTerminalState? =
        targetTabId?.let { McpTerminalRegistry.findState(it) }
            ?: McpTerminalRegistry.findState(tabId)
            ?: inScopeStates().firstOrNull()

    fun handleClient(vc: ViewerConnection, msg: ClientMessage) {
        if (msg is ClientMessage.RequestControl) {
            val upstream = upstreamSession(msg.tabId)
            if (upstream != null) {
                // Relay an upstream control request (A→B→C: C asked us, we ask A — A's user
                // sees its approval toast with OUR device name). Needs control of this share.
                if (vc.canControl) upstream.requestControl()
                return
            }
            // A view-only viewer asking for an upgrade — must run BEFORE the control gate.
            // Surfaces the same approval toast as a join request; on approve, flip this live
            // connection's role, tell the viewer, AND persist the upgrade into its grant (below)
            // so a reconnect with the same key comes back WITH control rather than demoting.
            if (!vc.canControl && !vc.controlRequestPending) {
                vc.controlRequestPending = true
                coro.launch {
                    val approved = SessionShareManager.awaitApproval(tabId, vc.name, wantsControl = true)
                    vc.controlRequestPending = false
                    if (approved) {
                        vc.canControl = true
                        // Persist the upgrade into the grant so reconnects (same link + key)
                        // come back WITH control instead of silently demoting to view-only.
                        vc.grantKey?.let { SessionShareManager.upgradeGrantToControl(it) }
                        vc.outbox.trySend(ShareProtocol.encodeServer(ServerMessage.Control(granted = true)))
                    }
                }
            }
            return
        }
        if (!vc.canControl) return // all mutating actions require the control role
        // Structural actions on a tab WE mirror from another session relay to that origin
        // (our RemoteSession methods no-op silently if we're view-only on it).
        when (msg) {
            is ClientMessage.Input ->
                (taps[msg.paneId]?.tab ?: paneTabMap()[msg.paneId])?.writeUserInput(msg.data)
            is ClientMessage.CloseTab ->
                upstreamSession(msg.tabId)?.closeFromChip(msg.tabId, msg.tabId)
                    ?: stateFor(msg.tabId)?.closeTab(msg.tabId)
            is ClientMessage.NewTab ->
                // Background: a viewer creating a tab shouldn't switch the host user's active tab.
                // Relay TARGETED (newTabIn maps to the upstream tab id) so the new tab opens in
                // the origin window the viewer pointed at, not the upstream's anchor window.
                upstreamSession(msg.tabId)?.newTabIn(msg.tabId!!)
                    ?: stateFor(msg.tabId)?.createTab(activate = false)
            is ClientMessage.SplitVertical ->
                upstreamSession(msg.tabId)?.splitPane(msg.tabId, msg.paneId, horizontal = false)
                    ?: stateFor(msg.tabId)?.splitVerticalFromPane(msg.tabId, msg.paneId)
            is ClientMessage.SplitHorizontal ->
                upstreamSession(msg.tabId)?.splitPane(msg.tabId, msg.paneId, horizontal = true)
                    ?: stateFor(msg.tabId)?.splitHorizontalFromPane(msg.tabId, msg.paneId)
            is ClientMessage.ClosePane -> {
                val upstream = upstreamSession(msg.tabId)
                if (upstream != null) {
                    upstream.closeFromChip(msg.tabId, msg.paneId)
                } else {
                    // Target the clicked pane (focus it), then close; closeFocusedPane closes
                    // the whole tab when it's the only pane (matches the host's behavior).
                    val st = stateFor(msg.tabId)
                    st?.splitStates?.get(msg.tabId)?.setFocusedPane(msg.paneId)
                    st?.closeFocusedPane(msg.tabId)
                }
            }
            is ClientMessage.DisconnectUpstream ->
                upstreamSession(msg.tabId)?.let { up ->
                    stateFor(msg.tabId)?.remoteSessions?.disconnect(up)
                }
            is ClientMessage.CloseWindow ->
                // ALL scope only — windowIds are stamped by our own Layout, so the viewer can
                // only name windows this share covers. Closing every tab closes the window
                // (the last tab's close fires the window's onLastTabClosed).
                if (scope == ShareScope.ALL) {
                    McpTerminalRegistry.allStates().firstOrNull { it.windowTag == msg.windowId }
                        ?.let { st -> st.tabs.map { it.id }.forEach { id -> st.closeTab(id) } }
                }
            is ClientMessage.LaunchAI -> {
                val upstream = upstreamSession(msg.tabId)
                if (upstream != null) {
                    upstream.launchAI(msg.tabId, msg.paneId, msg.assistantId)
                    return
                }
                // Run the same launch command the host's AI menu would (honoring the
                // user's per-assistant YOLO/auto-mode config), in the clicked pane.
                val target = taps[msg.paneId]?.tab ?: paneTabMap()[msg.paneId]
                val assistant = AIAssistants.findById(msg.assistantId)
                if (target != null && assistant != null) {
                    val cfg = SettingsManager.instance.settings.value.aiAssistantConfigs[msg.assistantId]
                    target.writeUserInput(ToolCommandProvider().getLaunchCommand(assistant, cfg))
                }
            }
            // ---- tab-chip context menu (mirrors the host's chip menu) ----
            is ClientMessage.RenameTab ->
                stateFor(msg.tabId)?.renameChip(msg.tabId, msg.paneId, msg.title)
            is ClientMessage.SetTabColor ->
                stateFor(msg.tabId)?.setChipColor(msg.tabId, msg.paneId, msg.color)
            is ClientMessage.DuplicateTab ->
                stateFor(msg.tabId)?.duplicateTab(msg.tabId)
            is ClientMessage.CloseOtherTabs ->
                stateFor(msg.tabId)?.closeOtherTabs(msg.tabId)
            is ClientMessage.CloseTabsBelow ->
                stateFor(msg.tabId)?.closeTabsBelow(msg.tabId)
            is ClientMessage.ResizeHost -> resizeHostWindow(msg.tabId, msg.cols, msg.rows)
            is ClientMessage.ResizeSplit ->
                upstreamSession(msg.tabId)?.resizeSplit(msg.tabId, msg.splitId, msg.ratio, committed = true)
                    ?: stateFor(msg.tabId)?.splitStates?.get(msg.tabId)?.updateSplitRatio(msg.splitId, msg.ratio)
            is ClientMessage.OfferShare -> {
                // Two-way sharing: mirror the offering client's session back into this window.
                // NOTE (trust boundary): this makes the host open an OUTBOUND WebSocket to a URL
                // the peer supplies — a mild SSRF-flavored surface. It's gated on the control role
                // (the host explicitly approved this device), so granting control also implies
                // "this device may have my BossTerm dial a link it provides." connect() dedupes a
                // repeated offer (same token) and refuses our own links; origin tagging keeps
                // either side's tabs from bouncing back.
                val name = (System.getProperty("user.name")?.takeIf { it.isNotBlank() }
                    ?.let { "$it (BossTerm)" }) ?: "BossTerm"
                stateFor(null)?.remoteSessions?.connect(msg.link, name)
            }
            // ---- MCP pill (mirrors the host's MCP indicator menu) ----
            is ClientMessage.SetMcpEnabled ->
                SettingsManager.instance.updateSetting { copy(mcpEnabled = msg.enabled) }
            is ClientMessage.AttachMcp -> {
                val target = ai.rever.bossterm.compose.mcp.McpAttachTarget.fromPersistenceKey(msg.target)
                val port = McpTerminalRegistry.runningPort.value
                if (target != null && port != null) {
                    coro.launch {
                        runCatching {
                            val result = ai.rever.bossterm.compose.mcp.McpCliAttacher.attach(target, MCP_SERVER_NAME, port)
                            if (result is ai.rever.bossterm.compose.mcp.McpAttachResult.Success) {
                                McpTerminalRegistry.markAttached(target) // → re-broadcasts McpStatus
                            }
                        }
                    }
                }
            }
            else -> {} // Hello / Focus: no-op (focus is viewer-side; RequestControl handled above)
        }
    }

    /**
     * "Fit host to client": resize the host's OS window so its terminal grid lands near
     * [cols]×[rows]. The grid is slaved to the canvas, so we nudge the window by the pixel
     * delta for the column/row change — the per-cell pixel size comes from the host's own
     * measurement and the window chrome (left tab bar, title) cancels out of the delta.
     * Best-effort and single-pane-oriented; multi-window picks the focused (else first) window.
     */
    private fun resizeHostWindow(targetTabId: String?, cols: Int, rows: Int) {
        if (cols < 2 || rows < 2) return
        // The TAB THE VIEWER IS WATCHING (it names itself in the message), not the share's
        // anchor — its cell size / current grid drive the window delta. Fall back to anchor.
        val tab = targetTabId?.let { McpTerminalRegistry.findTab(it) }
            ?: McpTerminalRegistry.findTab(tabId) ?: return
        val cw = tab.terminal.cellWidthPx
        val ch = tab.terminal.cellHeightPx
        if (cw <= 0f || ch <= 0f) return
        val cur = tab.display.termSize.value
        // Embedded host (e.g. BossConsole): BossTerm doesn't own the OS window, so
        // hand the resize to the embedder as a physical-px delta. It converts to its
        // own units and resizes the window/pane (and restores it when the fit ends).
        val dWpx = (cols - cur.columns) * cw
        val dHpx = (rows - cur.rows) * ch
        if (SessionShareManager.requestEmbeddedFit(tab.id, dWpx, dHpx)) return
        val tw = WindowManager.windows.firstOrNull { it.isWindowFocused.value && it.awtWindow != null }
            ?: WindowManager.windows.firstOrNull { it.awtWindow != null }
            ?: return
        // A BACKGROUND tab's grid only re-measures when it becomes visible (the canvas
        // auto-fit runs for the composed tab only) — resizing just the window would leave
        // the viewer on the old grid until the host user switches to that tab. Resize the
        // target's terminal + PTY directly too (single-pane tabs; a split's panes share
        // the window area and re-measure on focus).
        val st = McpTerminalRegistry.findState(tab.id)
        if (st != null && st.activeTabId != tab.id) {
            val ss = st.splitStates[tab.id]
            val session = when {
                ss == null -> tab
                ss.getAllPanes().size == 1 -> ss.getAllPanes().first().session as? TerminalTab
                else -> null
            }
            if (session != null) {
                runCatching {
                    session.terminal.resize(
                        ai.rever.bossterm.core.util.TermSize(cols, rows),
                        ai.rever.bossterm.terminal.RequestOrigin.User
                    )
                }
                coro.launch { runCatching { session.processHandle.value?.resize(cols, rows) } }
            }
        }
        val win = tw.awtWindow ?: return
        // AWT window size is in points; cell px is physical — divide by the display scale.
        javax.swing.SwingUtilities.invokeLater {
            runCatching {
                val gc = win.graphicsConfiguration
                val sx = gc?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
                val sy = gc?.defaultTransform?.scaleY?.takeIf { it > 0 } ?: 1.0
                var newW = (win.width + (cols - cur.columns) * cw / sx).toInt()
                var newH = (win.height + (rows - cur.rows) * ch / sy).toInt()
                // Clamp to the screen, accounting for the window's position (setSize keeps the
                // top-left), so growing a window that isn't at the origin can't run off-screen.
                gc?.bounds?.let { b ->
                    val maxW = (b.x + b.width - win.x).coerceAtLeast(480)
                    val maxH = (b.y + b.height - win.y).coerceAtLeast(320)
                    newW = newW.coerceIn(480, maxW)
                    newH = newH.coerceIn(320, maxH)
                }
                if (newW != win.width || newH != win.height) {
                    applyWindowSize(tw, win, newW, newH)
                }
            }
        }
    }

    companion object {
        // MCP server name/label as the CLI attachers register it. The embedder's
        // BossTermMcpConfig is a Compose CompositionLocal (unavailable here); these match its
        // standalone-app defaults (BossTermMcpConfig.serverName / displayName fallback).
        private const val MCP_SERVER_NAME = "bossterm"
        private const val MCP_SERVER_LABEL = "BossTerm"

        /**
         * Resize a window programmatically. PREFER the Compose [WindowState] — Compose then
         * moves the AWT frame and its Skia surface together, so no unpainted strip can
         * appear. Poking awtWindow.setSize directly lets the surface (sometimes) lag one
         * resize event behind; for embedders without a wired state, fall back to setSize +
         * a delayed 1px nudge that replays the heal a real resize provides.
         */
        internal fun applyWindowSize(
            tw: ai.rever.bossterm.compose.window.TerminalWindow,
            win: java.awt.Window,
            newW: Int,
            newH: Int,
        ) {
            val cs = tw.composeWindowState
            if (cs != null) {
                cs.size = androidx.compose.ui.unit.DpSize(
                    androidx.compose.ui.unit.Dp(newW.toFloat()),
                    androidx.compose.ui.unit.Dp(newH.toFloat()),
                )
                return
            }
            win.setSize(newW, newH)
            win.validate()
            win.repaint()
            val nudge = javax.swing.Timer(100) {
                runCatching {
                    win.setSize(newW, newH + 1)
                    win.setSize(newW, newH)
                    win.validate()
                    win.repaint()
                }
            }
            nudge.isRepeats = false
            nudge.start()
        }
    }

    // ---- reconcile on structural change ----
    private fun reconcile(sig: WindowSig) {
        val paneMap = paneTabMap()
        if (paneMap.isEmpty()) { onEnded(); return }
        synchronized(taps) {
            (taps.keys - paneMap.keys).toList().forEach { id ->
                taps.remove(id)?.let { e -> runCatching { e.tab.dataStream.removeRawOutputListener(e.listener) } }
            }
            for ((id, tab) in paneMap) {
                if (id !in taps) {
                    val listener: (String) -> Unit = { d -> broadcast(ServerMessage.PaneOutput(id, d)) }
                    taps[id] = TapEntry(tab, listener)
                    tab.dataStream.addRawOutputListener(listener)
                    val sz = sig.sizes[id] ?: listOf(80, 24)
                    broadcast(ServerMessage.PaneSnapshot(id, snapshotText(tab), sz[0], sz[1]))
                }
            }
        }
        broadcast(ServerMessage.Layout(sig.tabs, sig.activeTabId, sig.tabBarOnLeft, sig.summaryMode, sig.sessionName))
        sig.sizes.forEach { (id, sz) -> broadcast(ServerMessage.PaneResize(id, sz[0], sz[1])) }
    }

    // ---- window-state signature (pure-serializable; drives distinctUntilChanged) ----
    private data class WindowSig(
        val tabs: List<TabNode>,
        val activeTabId: String?,
        val sizes: Map<String, List<Int>>,
        val tabBarOnLeft: Boolean,
        val summaryMode: Boolean,
        val sessionName: String? = null,
    )

    /** The windows a share covers: every registered one for ALL, else the anchor tab's. */
    private fun inScopeStates(): List<TabbedTerminalState> = when (scope) {
        ShareScope.ALL -> McpTerminalRegistry.allStates()
        else -> listOfNotNull(McpTerminalRegistry.findState(tabId))
    }

    private fun inScopeTabs(state: TabbedTerminalState): List<TerminalTab> = when (scope) {
        ShareScope.WINDOW, ShareScope.ALL -> state.tabs
        ShareScope.TAB -> state.tabs.filter { it.id == tabId }
    }

    private fun computeSignature(): WindowSig {
        if (scope == ShareScope.ALL) McpTerminalRegistry.statesRev.value // subscribe: window open/close re-emits
        val states = inScopeStates()
        if (states.isEmpty()) return WindowSig(emptyList(), null, emptyMap(), false, false)
        val tabNodes = ArrayList<TabNode>()
        val sizes = HashMap<String, List<Int>>()
        // ALL: the anchor window's active tab (falling back to the first window) — focus
        // isn't snapshot-observable across windows, and the anchor is where the share lives.
        val anchorState = McpTerminalRegistry.findState(tabId) ?: states.first()
        val activeId = if (scope == ShareScope.TAB) tabId else anchorState.activeTabId
        val settings = SettingsManager.instance.settings.value
        val onLeft = settings.tabBarPosition == "left"
        val summary = settings.tabBarSummaryMode
        // Stamp window identity only when the share actually spans windows — a single-window
        // ALL share stays frame-identical to WINDOW, and old viewers just ignore the fields.
        val multiWindow = scope == ShareScope.ALL && states.size > 1
        for ((wIdx, state) in states.withIndex()) {
            val windowId = if (multiWindow) state.windowTag else null
            val windowName = if (multiWindow) "Window ${wIdx + 1}" else null
            for (tab in inScopeTabs(state)) {
                val ss = state.splitStates[tab.id]
                val tree: PaneTreeNode = if (ss != null) {
                    sigNode(ss.rootNode, ss.focusedPaneId, sizes)
                } else {
                    val s = tab.display.termSize.value
                    sizes[tab.id] = listOf(s.columns, s.rows)
                    PaneTreeNode.Pane(
                        tab.id, tab.title.value, tab.workingDirectory.value, true,
                        color = tabColorCss(tab), branch = tab.gitBranch.value
                    )
                }
                // Mark tabs that themselves mirror another session with that share's token hash
                // (so a client connecting to us can skip the ones mirroring its own session), a
                // friendly label, and whether WE are view-only on it (then input can't flow
                // through us — nested viewers should mark those tabs read-only too).
                //
                // A mirrored tab may itself mirror a DEEPER session (A→B→C: our B-mirror of B's
                // C-mirror). Forward the DEEPEST origin — its hash keeps grouping/loop-guarding
                // correct at the true source — chain the label ("C · via B"), and degrade
                // read-only/offline if ANY hop along the path is degraded.
                val upstream = state.remoteSessions.sessionForTab(tab)
                upstream?.upstreamRev?.value // subscribe: deeper-origin changes re-broadcast too
                val deeper = upstream?.upstreamFor(tab.id)
                val upstreamName = upstream?.let {
                    it.customName.value ?: it.hostName.value
                        ?: runCatching { java.net.URI(it.link).host }.getOrNull() ?: it.link
                }
                // The upstream's own window for this tab (it shared ALL its windows) — forward
                // it so OUR viewers can section the "via host" group per origin window.
                val upstreamWindow = upstream?.windowFor(tab.id)
                tabNodes.add(
                    TabNode(
                        id = tab.id, title = tab.title.value, active = tab.id == activeId, tree = tree,
                        color = tabColorCss(tab), cwd = tab.workingDirectory.value, branch = tab.gitBranch.value,
                        origin = deeper?.key ?: upstream?.originHash,
                        originName = when {
                            deeper != null -> "${deeper.name ?: "remote"} · via $upstreamName"
                            else -> upstreamName
                        },
                        originReadOnly = upstream?.let { (deeper?.readOnly ?: false) || !it.canControlState.value },
                        originOffline = upstream?.let {
                            (deeper?.offline ?: false) ||
                                it.statusState.value !is ai.rever.bossterm.compose.remote.RemoteStatus.Connected
                        },
                        windowId = windowId,
                        windowName = windowName,
                        originWindowId = upstreamWindow?.key,
                        originWindowName = upstreamWindow?.name,
                    )
                )
            }
        }
        return WindowSig(tabNodes, activeId, sizes, onLeft, summary, sessionName.value)
    }

    /** Resolve a tab's accent as CSS (#RRGGBB), mirroring the host's chip color (manual or auto). */
    private fun tabColorCss(tab: TerminalTab): String? {
        val argb = tab.tabColor.value ?: run {
            val s = SettingsManager.instance.settings.value
            if (!s.tabColorByDirectory) return null
            val cwd = tab.workingDirectory.value
            if (cwd.isNullOrBlank()) return null
            val presets = ai.rever.bossterm.compose.tabs.TAB_COLOR_PRESETS
            presets[cwd.hashCode().mod(presets.size)].second
        }
        val h = argb.removePrefix("0x")
        return if (h.length >= 8) "#" + h.substring(2) else null // drop AA → #RRGGBB
    }

    private fun sigNode(node: SplitNode, focusedId: String, sizes: HashMap<String, List<Int>>): PaneTreeNode = when (node) {
        is SplitNode.Pane -> {
            val tab = node.session as TerminalTab
            val s = tab.display.termSize.value
            sizes[node.id] = listOf(s.columns, s.rows)
            PaneTreeNode.Pane(
                node.id, tab.title.value, tab.workingDirectory.value, node.id == focusedId,
                color = tabColorCss(tab), branch = tab.gitBranch.value
            )
        }
        is SplitNode.VerticalSplit ->
            PaneTreeNode.Split("v", node.ratio, sigNode(node.left, focusedId, sizes), sigNode(node.right, focusedId, sizes), node.id)
        is SplitNode.HorizontalSplit ->
            PaneTreeNode.Split("h", node.ratio, sigNode(node.top, focusedId, sizes), sigNode(node.bottom, focusedId, sizes), node.id)
    }

    /** Current paneId → owning session, across all in-scope tabs (all windows for ALL). */
    private fun paneTabMap(): Map<String, TerminalTab> {
        val m = LinkedHashMap<String, TerminalTab>()
        for (state in inScopeStates()) {
            for (tab in inScopeTabs(state)) {
                val ss = state.splitStates[tab.id]
                if (ss != null) ss.getAllPanes().forEach { p -> (p.session as? TerminalTab)?.let { m[p.id] = it } }
                else m[tab.id] = tab
            }
        }
        return m
    }

    /**
     * Build the initial-paint blob for a pane as **styled** text: each cell run is
     * prefixed with its SGR escape so the viewer's xterm.js paints the scrollback in
     * color, exactly like live output. (Previously this sent `line.text` — plain chars
     * with no styling — so the very first render was monochrome until new output arrived.)
     */
    private fun snapshotText(tab: TerminalTab): String {
        val snap = tab.textBuffer.createSnapshot()
        val sb = StringBuilder()
        var row = -snap.historyLinesCount
        while (row < snap.height) {
            appendStyledLine(sb, snap.getLine(row))
            if (row < snap.height - 1) sb.append("\r\n")
            row++
        }
        sb.append("[0m") // reset trailing style
        // Park the cursor at its real screen position (1-based row;col) — otherwise xterm.js
        // leaves it on the bottom row after we write the full-height blob (scrollback + screen).
        val cy = tab.terminal.cursorY.coerceIn(1, snap.height)
        val cx = tab.terminal.cursorX.coerceAtLeast(1)
        sb.append("[$cy;${cx}H")
        return sb.toString()
    }

    /** Append one buffer line as SGR-prefixed styled runs, trimming invisible trailing padding. */
    private fun appendStyledLine(sb: StringBuilder, line: TerminalLine) {
        // Collect runs, stopping at the first NUL entry (trailing unfilled cells), like TerminalLine.text.
        val runs = ArrayList<TerminalLine.TextEntry>()
        for (e in line.entries) {
            if (e == null) continue
            if (e.isNul) break
            runs.add(e)
        }
        // Drop trailing runs that are blank with no background — invisible padding (old .trimEnd()).
        var end = runs.size
        while (end > 0 && runs[end - 1].let { it.text.toString().isBlank() && it.style.background == null }) end--
        for (i in 0 until end) {
            sb.append(ansiForStyle(runs[i].style)).append(runs[i].text.toString())
        }
    }

    /** SGR escape that resets then applies [style]'s colors + attributes (256-color / truecolor). */
    private fun ansiForStyle(style: TextStyle): String {
        val codes = ArrayList<String>()
        codes.add("0") // reset first so each run is self-contained
        if (style.hasOption(TextStyle.Option.BOLD)) codes.add("1")
        if (style.hasOption(TextStyle.Option.DIM)) codes.add("2")
        if (style.hasOption(TextStyle.Option.ITALIC)) codes.add("3")
        if (style.hasOption(TextStyle.Option.UNDERLINED)) codes.add("4")
        if (style.hasOption(TextStyle.Option.SLOW_BLINK)) codes.add("5")
        if (style.hasOption(TextStyle.Option.RAPID_BLINK)) codes.add("6")
        if (style.hasOption(TextStyle.Option.INVERSE)) codes.add("7")
        if (style.hasOption(TextStyle.Option.HIDDEN)) codes.add("8")
        style.foreground?.let { codes.add(sgrColor(it, fg = true)) }
        style.background?.let { codes.add(sgrColor(it, fg = false)) }
        return "[" + codes.joinToString(";") + "m"
    }

    private fun sgrColor(c: TerminalColor, fg: Boolean): String {
        val base = if (fg) "38" else "48"
        return if (c.isIndexed) "$base;5;${c.colorIndex}"
        else c.toColor().let { "$base;2;${it.red};${it.green};${it.blue}" }
    }

    // ---- theme (host palette → CSS) ----
    private fun themeMessage(): ServerMessage.Theme {
        val theme = ThemeManager.instance.currentTheme.value
        val palette = ColorPaletteManager.instance.currentPalette.value ?: ColorPalette.fromTheme(theme)
        val settings = SettingsManager.instance.settings.value
        val font = settings.fontName
            ?.takeIf { it.isNotBlank() }
            ?.let { "\"$it\", Menlo, Monaco, monospace" }
            ?: "Menlo, Monaco, \"Courier New\", monospace"
        return ServerMessage.Theme(
            background = hexToCss(theme.background),
            foreground = hexToCss(theme.foreground),
            cursor = hexToCss(theme.cursor),
            cursorAccent = hexToCss(theme.cursorText),
            selectionBackground = hexToCss(theme.selection),
            ansi = (0..15).map { hexToCss(palette.getAnsiColorHex(it)) },
            fontFamily = font,
            fontSize = settings.fontSize.toInt(),
        )
    }

    private fun hexToCss(argb: String): String {
        val h = argb.removePrefix("0x").removePrefix("0X").removePrefix("#")
        val rrggbb = when {
            h.length >= 8 -> h.substring(h.length - 6)
            h.length == 6 -> h
            else -> "FFFFFF"
        }
        return "#$rrggbb"
    }

    private fun secureToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * A connected browser viewer. The host pushes pre-encoded JSON frames into [outbox];
 * the Ktor route drains it. Bounded + DROP_OLDEST so a slow viewer never stalls the
 * terminal — it just misses intermediate frames (the next snapshot heals it).
 */
class ViewerConnection(
    val id: Int,
    /** Mutable: a view-only viewer can be upgraded mid-session via an approved RequestControl. */
    @Volatile var canControl: Boolean,
    /** Device name from the viewer's Hello — shown in the control-request approval prompt. */
    val name: String = "Viewer",
) {
    val outbox: Channel<String> = Channel(capacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A mid-session control request is awaiting the host's decision (dedupes re-requests). */
    @Volatile var controlRequestPending = false

    /**
     * This connection's access key — an approved mid-session control upgrade is written back
     * into its grant, so the device keeps control across silent reconnects (the client redials
     * the same view link + key; without this each blip demoted it to view-only).
     */
    @Volatile var grantKey: String? = null
}
