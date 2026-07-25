package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.ai.ToolCommandProvider
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.theme.ColorPalette
import ai.rever.bossterm.compose.settings.theme.ColorPaletteManager
import ai.rever.bossterm.compose.notification.NotificationService
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import ai.rever.bossterm.compose.splits.SplitNode
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.compose.voice.GuiVoiceToolExecutor
import ai.rever.bossterm.compose.voice.VoiceAgentStorage
import ai.rever.bossterm.compose.voice.VoiceCallService
import ai.rever.bossterm.compose.window.WindowManager
import ai.rever.bossterm.terminal.model.TerminalModelListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
    private var voiceJob: Job? = null

    // Boss Calling: one service per share; its executor limits the agent's tool targets to
    // this share's scope. Lazy — no MCP machinery spins up until a viewer actually calls.
    private val voiceService by lazy {
        VoiceCallService(
            executor = GuiVoiceToolExecutor(
                inScopeTabIds = { voiceScopeTabIds() },
                anchorTabId = { tabId },
            ),
            scope = coro,
            // In-process: answer from the reactive flow instead of re-reading + re-parsing
            // voice.json on every settings emission (status is recomputed per share).
            keyPresent = { VoiceAgentStorage.keyPresentFlow.value },
            sessionName = { sessionName.value },
            onCallActivity = ::notifyVoiceCall,
        )
    }

    /**
     * A voice call is remote hands on this machine, and the feature ships enabled — so the host
     * gets the same treatment as an approval request rather than only the terminal scrolling by
     * itself. Same channel as [SessionShareManager.notifyApprovalRequest].
     */
    private fun notifyVoiceCall(started: Boolean) {
        runCatching {
            NotificationService.showNotification(
                title = "BossTerm — Boss Calling",
                message = if (started) {
                    "A viewer started a voice call on \"${sessionName.value}\" — the agent can read and run commands here"
                } else {
                    "The voice call on \"${sessionName.value}\" ended"
                },
                withSound = started,
            )
        }
    }

    /** The tabs this share covers — the only ids the voice agent may ever target. */
    private fun voiceScopeTabIds(): Set<String> =
        inScopeStates().flatMap { st -> inScopeTabs(st).map { it.id } }.toSet()

    /**
     * Remember which tab a viewer is looking at, but only if the share actually covers it: this is
     * unvalidated viewer input that becomes the voice agent's default tool target, so a foreign id
     * must never be stored (the executor re-checks, and this keeps it from getting that far).
     */
    private fun rememberVoiceTab(vc: ViewerConnection, tabId: String?) {
        if (tabId != null && tabId in voiceScopeTabIds()) vc.voiceTabId = tabId
    }

    private class TapEntry(
        val tab: TerminalTab,
        val listener: (String) -> Unit,
        val modelListener: TerminalModelListener,
        val graphics: PaneGraphicsTracker,
        val monitoringGraphics: AtomicBoolean = AtomicBoolean(false),
        val graphicsSyncPending: AtomicBoolean = AtomicBoolean(false),
        val graphicsSyncAgain: AtomicBoolean = AtomicBoolean(false),
        val textSnapshotLimiter: GraphicsResyncLimiter = GraphicsResyncLimiter(1_000_000_000L),
    ) {
        @Volatile var graphicsSyncJob: Job? = null
        lateinit var repaintSender: DeferredPaneRepaint
    }
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
        // Push the Call pill's availability whenever the voice settings or the key change.
        voiceJob = coro.launch {
            combine(
                SettingsManager.instance.settings,
                VoiceAgentStorage.keyPresentFlow,
            ) { _, _ -> voiceService.status() }
                .distinctUntilChanged()
                .collect { status ->
                    // Server-side kill, same as DaemonShareServer's poller: the viewer ends its own
                    // call on this push, but a viewer that ignores it is exactly why the server
                    // check exists.
                    if (!status.available) voiceService.closeCalls()
                    // Per-viewer, not broadcast: only a controller may see WHY voice is unavailable.
                    val redacted = status.copy(reason = null)
                    for (vc in viewers) {
                        val msg = if (vc.canControl) status else redacted
                        vc.outbox.sendControl(ShareProtocol.encodeServer(msg))
                    }
                }
        }
    }

    fun stop() {
        observerJob?.cancel()
        mcpJob?.cancel()
        voiceJob?.cancel()
        // Tearing down the share ends any call riding it — including the host-side "ended" signal.
        runCatching { voiceService.closeCalls() }
        synchronized(taps) {
            taps.values.forEach(::disposeTap)
            taps.clear()
        }
        viewers.forEach { it.outbox.close() }
        viewers.clear()
        coro.cancel()
    }

    // ---- viewers ----
    fun addViewer(
        canControl: Boolean,
        name: String = "Viewer",
        supportsPaneGraphics: Boolean = false,
    ): ViewerConnection {
        val vc = ViewerConnection(viewerSeq.incrementAndGet(), canControl, name, supportsPaneGraphics)
        viewers.add(vc)
        if (supportsPaneGraphics) {
            // A graphics mutation may have landed after this viewer's initial full frames were
            // captured but before admission. Poll every pane once after registration so even a
            // quiet pane closes that window without registering early and replaying text twice.
            val entries = synchronized(taps) { taps.toList() }
            entries.forEach { (id, entry) ->
                entry.monitoringGraphics.set(true)
                scheduleGraphicsSync(id, entry)
            }
        }
        broadcast(ServerMessage.Presence(viewers.size))
        return vc
    }

    fun removeViewer(vc: ViewerConnection) {
        if (viewers.remove(vc)) {
            // A dropped connection can't hang up, so retire its call handle here or the token
            // stays usable for its whole TTL.
            voiceService.closeCall(vc.voiceCallToken)
            vc.voiceCallToken = null
            vc.outbox.close()
            broadcast(ServerMessage.Presence(viewers.size))
            if (viewers.none { it.supportsPaneGraphics }) {
                synchronized(taps) {
                    taps.values.forEach {
                        it.monitoringGraphics.set(false)
                        it.repaintSender.cancel()
                    }
                }
            }
            // Last viewer gone → undo any "fit host to my screen" resize.
            if (viewers.isEmpty()) SessionShareManager.releaseEmbeddedFit(tabId)
        }
    }

    val viewerCount: Int get() = viewers.size

    fun broadcast(msg: ServerMessage) {
        val text = ShareProtocol.encodeServer(msg)
        for (v in viewers) v.outbox.trySend(text)
    }

    private fun broadcastGraphics(msg: ServerMessage) {
        if (msg is ServerMessage.PaneGraphics) {
            for (viewer in viewers) {
                if (viewer.supportsPaneGraphics) enqueueGraphics(viewer, msg)
            }
        } else {
            val text = ShareProtocol.encodeServer(msg)
            for (viewer in viewers) {
                if (viewer.supportsPaneGraphics) viewer.outbox.trySend(text)
            }
        }
    }

    private fun enqueueGraphics(
        viewer: ViewerConnection,
        message: ServerMessage.PaneGraphics,
    ) {
        if (!viewer.outbox.trySendWithoutEviction(ShareProtocol.encodeServer(message))) {
            viewer.outbox.trySend(ShareProtocol.encodeServer(message.resyncSentinel()))
        }
    }

    private fun broadcastPaneOutput(
        paneId: String,
        raw: String,
        filtered: String,
    ) {
        var rawFrame: String? = null
        var filteredFrame: String? = null
        for (viewer in viewers) {
            val data = if (viewer.supportsPaneGraphics) filtered else raw
            if (data.isEmpty()) continue
            val frame = if (viewer.supportsPaneGraphics) {
                filteredFrame ?: ShareProtocol.encodeServer(ServerMessage.PaneOutput(paneId, data))
                    .also { filteredFrame = it }
            } else {
                rawFrame ?: ShareProtocol.encodeServer(ServerMessage.PaneOutput(paneId, data))
                    .also { rawFrame = it }
            }
            viewer.outbox.trySend(frame)
        }
    }

    /**
     * Theme + Layout + a PaneSnapshot per pane, for a newly-connected viewer. [canControl] gates the
     * voice-status reason, which is host configuration (see [VoiceCallService.status]).
     */
    fun initialMessages(includePaneGraphics: Boolean = false, canControl: Boolean = false): List<ServerMessage> {
        val sig = computeSignature()
        val out = ArrayList<ServerMessage>()
        out.add(themeMessage())
        out.add(mcpStatusMessage())
        out.add(voiceService.status(withReason = canControl))
        out.add(ServerMessage.Layout(sig.tabs, sig.activeTabId, sig.tabBarOnLeft, sig.summaryMode, sig.sessionName))
        for ((id, tab) in paneTabMap()) {
            val sz = sig.sizes[id] ?: listOf(80, 24)
            out.add(ServerMessage.PaneSnapshot(
                id, snapshotText(tab), sz[0], sz[1], webViewerScrollbackLines(tab.textBuffer)
            ))
            if (includePaneGraphics) {
                val entry = synchronized(taps) { taps[id] } ?: continue
                // The first graphics-capable viewer establishes the shared baseline. A later
                // viewer gets a private full frame without advancing revisions for existing peers.
                val full = entry.graphics.fullMessage(
                    commit = viewers.none { it.supportsPaneGraphics }
                )
                if (full.requiredImageIds.isNotEmpty()) entry.monitoringGraphics.set(true)
                out.add(full)
            }
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

    /** The tapped tab behind a pane id — [taps] is a plain HashMap, so never read it unlocked. */
    private fun tappedTab(paneId: String): TerminalTab? = synchronized(taps) { taps[paneId]?.tab }

    fun handleClient(vc: ViewerConnection, msg: ClientMessage) {
        if (msg is ClientMessage.GraphicsResync) {
            if (vc.supportsPaneGraphics) {
                val tracker = synchronized(taps) { taps[msg.paneId]?.graphics }
                if (tracker != null) {
                    val estimatedBytes = tracker.estimatedWireBytes()
                    if (vc.graphicsResyncLimiter.tryAcquire(msg.paneId, estimatedBytes)) {
                        enqueueGraphics(vc, tracker.fullMessage(commit = false))
                    } else {
                        vc.outbox.trySend(ShareProtocol.encodeServer(ServerMessage.GraphicsResyncDenied(
                            msg.paneId,
                            vc.graphicsResyncLimiter.retryAfterMillis(msg.paneId, estimatedBytes),
                        )))
                    }
                }
            }
            return
        }
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
        // Voice messages run BEFORE the control gate so a view-only caller gets an explicit
        // not_controller error (the service re-checks the role) instead of silence. Focus
        // rides along: it tracks which tab the viewer is looking at — the voice agent's
        // default tool target.
        when (msg) {
            is ClientMessage.Focus -> { rememberVoiceTab(vc, msg.tabId); return }
            is ClientMessage.VoiceStart -> {
                // One connection holds at most one call: redialling without a clean voiceEnd used to
                // orphan the previous token for the whole call-duration ceiling, and since openCall()
                // refuses rather than evicts, eight redials wedged the share at too_many_calls.
                voiceService.closeCall(vc.voiceCallToken)
                vc.voiceCallToken = null
                rememberVoiceTab(vc, msg.activeTabId)
                voiceService.handleStart(msg, vc.canControl) { m ->
                    // Record the token on the way OUT. Populating it from an inbound voiceToolCall
                    // instead meant a call where the agent never used a tool left nothing to retire
                    // on hangup — and an attacker could clobber the tracked value with one bogus
                    // token, defeating the disconnect cleanup outright.
                    if (m is ServerMessage.VoiceSession) vc.voiceCallToken = m.callToken
                    vc.outbox.sendControl(ShareProtocol.encodeServer(m))
                }
                return
            }
            is ClientMessage.VoiceEnd -> {
                voiceService.closeCall(vc.voiceCallToken)
                vc.voiceCallToken = null
                return
            }
            is ClientMessage.VoiceToolCall -> {
                // Defence in depth: the token must be the one THIS connection was issued, not merely
                // one live somewhere on the share — otherwise another viewer presenting it would
                // drive the read tools without ever passing handleStart's control gate.
                if (msg.callToken == null || msg.callToken != vc.voiceCallToken) {
                    vc.outbox.sendControl(
                        ShareProtocol.encodeServer(ServerMessage.VoiceError(code = "not_controller"))
                    )
                    return
                }
                voiceService.handleToolCall(msg, vc.canControl, vc.voiceTabId) { m ->
                    // Control lane: a tool result dropped under output back-pressure would leave
                    // the agent waiting on a reply that never comes.
                    vc.outbox.sendControl(ShareProtocol.encodeServer(m))
                }
                return
            }
            else -> {}
        }
        if (!vc.canControl) return // all mutating actions require the control role
        // Structural actions on a tab WE mirror from another session relay to that origin
        // (our RemoteSession methods no-op silently if we're view-only on it).
        when (msg) {
            is ClientMessage.Input ->
                (tappedTab(msg.paneId) ?: paneTabMap()[msg.paneId])?.writeUserInput(msg.data)
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
                val target = tappedTab(msg.paneId) ?: paneTabMap()[msg.paneId]
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
            // tabId naming an upstream-mirrored tab → relay to that origin's MCP; else our own.
            is ClientMessage.SetMcpEnabled -> {
                val up = upstreamSession(msg.tabId)
                if (up != null) up.setRemoteMcpEnabled(msg.enabled)
                else SettingsManager.instance.updateSetting { copy(mcpEnabled = msg.enabled) }
            }
            is ClientMessage.AttachMcp -> {
                val up = upstreamSession(msg.tabId)
                if (up != null) {
                    up.attachRemoteMcp(msg.target)
                } else {
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
            }
            else -> {} // Hello: no-op (Focus/voice/RequestControl handled above)
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
        private const val GRAPHICS_SYNC_DEBOUNCE_MS = 100L

        // MCP server name/label as the CLI attachers register it / as broadcast to viewers.
        // The embedder's BossTermMcpConfig is a Compose CompositionLocal (unavailable in this
        // non-Composable class), so BossTermMcpManager publishes the resolved values to the
        // registry — read here so a customized embedder isn't forced to the standalone defaults.
        private val MCP_SERVER_NAME get() = McpTerminalRegistry.mcpServerName
        private val MCP_SERVER_LABEL get() = McpTerminalRegistry.mcpServerLabel

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
            // Read INSIDE the lock: [addViewer] snapshots taps under the same lock, so either we
            // already see its viewer (and send the initial frame) or it sees this new tap (and
            // schedules its own sync). Reading outside would let a joining graphics viewer miss a
            // pane created in the same instant.
            val hasGraphicsViewer = viewers.any { it.supportsPaneGraphics }
            (taps.keys - paneMap.keys).toList().forEach { id ->
                taps.remove(id)?.let(::disposeTap)
                viewers.forEach { it.graphicsResyncLimiter.remove(id) }
            }
            for ((id, tab) in paneMap) {
                if (id !in taps) {
                    val graphics = PaneGraphicsTracker(id, tab.textBuffer, tab.terminal.getImageDataCache())
                    val graphicsOutputFilter = GraphicsOutputFilter()
                    lateinit var entry: TapEntry
                    val listener: (String) -> Unit = { d ->
                        // Filter unconditionally: parser state must stay coherent even with zero
                        // graphics viewers, so a viewer joining mid-payload never receives raw
                        // raster bytes as terminal text. Broadcast through broadcastPaneOutput for
                        // the same reason — it picks raw/filtered PER VIEWER off the live list, so a
                        // viewer [addViewer] registers between here and the send still gets the
                        // stream it can render. Branching on a `viewers.any { }` snapshot instead
                        // would hand that viewer the whole unfiltered payload as terminal text.
                        // (broadcastPaneOutput also encodes lazily per kind, so with no graphics
                        // viewer it costs exactly one encodeServer call, same as `broadcast`.)
                        val filtered = graphicsOutputFilter.filter(d)
                        if (filtered.detectedGraphics) {
                            entry.monitoringGraphics.set(true)
                            scheduleGraphicsSync(id, entry) // no-ops while no viewer wants graphics
                        }
                        broadcastPaneOutput(id, d, filtered.output)
                    }
                    val modelListener = object : TerminalModelListener {
                        override fun modelChanged() {
                            if (entry.monitoringGraphics.get()) scheduleGraphicsSync(id, entry)
                        }
                    }
                    entry = TapEntry(tab, listener, modelListener, graphics)
                    entry.repaintSender = DeferredPaneRepaint(
                        scope = coro,
                        admit = { repaint ->
                            val estimatedBytes = repaint.length.toLong() * 2
                            if (entry.textSnapshotLimiter.tryAcquire(id, estimatedBytes)) {
                                null
                            } else {
                                entry.textSnapshotLimiter.retryAfterMillis(id, estimatedBytes)
                            }
                        },
                        send = { repaint ->
                            if (synchronized(taps) { taps[id] } === entry) {
                                broadcastGraphics(ServerMessage.PaneRepaint(id, repaint))
                            }
                        },
                    )
                    taps[id] = entry
                    tab.dataStream.addRawOutputListener(listener)
                    tab.textBuffer.addModelListener(modelListener)
                    val sz = sig.sizes[id] ?: listOf(80, 24)
                    broadcast(ServerMessage.PaneSnapshot(
                        id, snapshotText(tab), sz[0], sz[1], webViewerScrollbackLines(tab.textBuffer)
                    ))
                    if (hasGraphicsViewer) {
                        val initialGraphics = graphics.fullMessage()
                        if (initialGraphics.requiredImageIds.isNotEmpty()) entry.monitoringGraphics.set(true)
                        broadcastGraphics(initialGraphics)
                    }
                }
            }
        }
        broadcast(ServerMessage.Layout(sig.tabs, sig.activeTabId, sig.tabBarOnLeft, sig.summaryMode, sig.sessionName))
        sig.sizes.forEach { (id, sz) -> broadcast(ServerMessage.PaneResize(id, sz[0], sz[1])) }
    }

    private fun scheduleGraphicsSync(id: String, entry: TapEntry) {
        if (viewers.none { it.supportsPaneGraphics }) return
        if (!entry.graphicsSyncPending.compareAndSet(false, true)) {
            entry.graphicsSyncAgain.set(true)
            return
        }
        val job = coro.launch(start = CoroutineStart.LAZY) {
            try {
                // Cap full-buffer graphics scans at 10 Hz while coalescing multipart transfers.
                delay(GRAPHICS_SYNC_DEBOUNCE_MS)
                if (synchronized(taps) { taps[id] } !== entry) return@launch
                val update = entry.graphics.pollUpdate()
                if (update != null) {
                    if (update.requiresTextSnapshot) {
                        // xterm.js does not emulate image-protocol cursor movement. Re-anchor text
                        // only for a real placement change, never for scrolling/raster animation.
                        // Repaint only the visible screen: retained browser scrollback stays intact
                        // and the payload remains bounded by the pane dimensions.
                        entry.repaintSender.offer {
                            TerminalSnapshotEncoder.encodeScreenRepaint(
                                entry.tab.textBuffer.createSnapshot(),
                                entry.tab.terminal.cursorX,
                                entry.tab.terminal.cursorY,
                            )
                        }
                    }
                    broadcastGraphics(update.message)
                }
                entry.monitoringGraphics.set(entry.graphics.hasVisibleGraphics())
            } finally {
                // Keep the pending flag set through capture + sends. A concurrent model change is
                // represented by graphicsSyncAgain and cannot launch an overlapping poll.
                entry.graphicsSyncPending.set(false)
                if (entry.graphicsSyncAgain.getAndSet(false) &&
                    synchronized(taps) { taps[id] } === entry
                ) {
                    entry.monitoringGraphics.set(true)
                    scheduleGraphicsSync(id, entry)
                }
            }
        }
        entry.graphicsSyncJob = job
        job.start()
    }

    private fun disposeTap(entry: TapEntry) {
        entry.graphicsSyncJob?.cancel()
        entry.repaintSender.cancel()
        runCatching { entry.tab.dataStream.removeRawOutputListener(entry.listener) }
        runCatching { entry.tab.textBuffer.removeModelListener(entry.modelListener) }
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
                // The upstream's MCP state — forward so OUR viewers can show + manage it on the
                // "via host" group (chain: viewer → us → upstream). Subscribed via the read.
                val upstreamMcp = upstream?.mcpStatus?.value
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
                        originMcpEnabled = upstreamMcp?.enabled,
                        originMcpRunning = upstreamMcp?.running,
                        originMcpAttached = upstreamMcp?.attached ?: emptyList(),
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
        return TerminalSnapshotEncoder.encode(
            snap,
            tab.terminal.cursorX,
            tab.terminal.cursorY,
            webViewerScrollbackLines(tab.textBuffer),
        )
    }

    // ---- theme (host palette → CSS) ----
    private fun themeMessage(): ServerMessage.Theme {
        val theme = ThemeManager.instance.currentTheme.value
        val palette = ColorPaletteManager.instance.currentPalette.value ?: ColorPalette.fromTheme(theme)
        val settings = SettingsManager.instance.settings.value
        return ServerMessage.Theme(
            background = hexToCss(theme.background),
            foreground = hexToCss(theme.foreground),
            cursor = hexToCss(theme.cursor),
            cursorAccent = hexToCss(theme.cursorText),
            selectionBackground = hexToCss(theme.selection),
            ansi = (0..15).map { hexToCss(palette.getAnsiColorHex(it)) },
            fontFamily = webTerminalFontFamily(settings.fontName),
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
 * the Ktor route drains it. The queue is bounded by both UTF-16 payload size and frame count, so a
 * slow viewer never stalls the terminal or retains gigabytes of large graphics frames.
 */
class ViewerConnection(
    val id: Int,
    /** Mutable: a view-only viewer can be upgraded mid-session via an approved RequestControl. */
    @Volatile var canControl: Boolean,
    /** Device name from the viewer's Hello — shown in the control-request approval prompt. */
    val name: String = "Viewer",
    /** True only for peers that render the host's normalized image-cell protocol. */
    val supportsPaneGraphics: Boolean = false,
) {
    internal val outbox = BoundedViewerOutbox()
    internal val graphicsResyncLimiter = GraphicsResyncLimiter()

    /**
     * The tab this viewer is looking at (from [ClientMessage.Focus] / [ClientMessage.VoiceStart])
     * — the voice agent's default tool target. Null until the viewer reports one.
     */
    @Volatile var voiceTabId: String? = null

    /** This connection's live call handle, so hanging up (or dropping) can retire exactly it. */
    @Volatile var voiceCallToken: String? = null

    /** A mid-session control request is awaiting the host's decision (dedupes re-requests). */
    @Volatile var controlRequestPending = false

    /**
     * This connection's access key — an approved mid-session control upgrade is written back
     * into its grant, so the device keeps control across silent reconnects (the client redials
     * the same view link + key; without this each blip demoted it to view-only).
     */
    @Volatile var grantKey: String? = null
}

/**
 * Desktop-hosted counterpart to the daemon's heap-bounded [FrameOutbox]. Frames are best effort,
 * matching the old `DROP_OLDEST` channel semantics, but the real bound is characters rather than
 * 2048 arbitrarily-sized Strings. A legal 16 MiB web-raster snapshot expands to ~22M base64 chars,
 * so the default admits one such frame while bounding the aggregate queue to ~64 MiB of UTF-16.
 */
internal class BoundedViewerOutbox(
    private val capacityChars: Int = DEFAULT_CAPACITY_CHARS,
    private val capacityFrames: Int = DEFAULT_CAPACITY_FRAMES,
    private val controlCapacityFrames: Int = CONTROL_CAPACITY_FRAMES,
    private val controlCapacityChars: Int = CONTROL_CAPACITY_CHARS,
) {
    private val lock = Any()
    private val frames = ArrayDeque<String>()

    /**
     * Frames that must never be dropped, drained ahead of pane output. [trySend] evicts the OLDEST
     * queued frames under back-pressure, which is exactly wrong for a voice tool result: the moment
     * that matters is a `run_command` flooding output, when the queued result is the oldest thing
     * there. The daemon side has always had this lane ([FrameOutbox.sendControl]); this is its
     * counterpart for in-app shares.
     */
    private val controlFrames = ArrayDeque<String>()
    private var controlChars = 0
    private var queuedChars = 0
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var closed = false

    fun trySend(text: String): Boolean {
        if (closed || text.isEmpty()) return false
        if (text.length > capacityChars) {
            log.warn(
                "Dropping viewer frame of {} chars above the {}-char queue capacity",
                text.length,
                capacityChars,
            )
            return false
        }
        var droppedFrames = 0
        synchronized(lock) {
            if (closed) return false
            frames.addLast(text)
            queuedChars += text.length
            while ((queuedChars > capacityChars || frames.size > capacityFrames) && frames.size > 1) {
                queuedChars -= frames.removeFirst().length
                droppedFrames++
            }
        }
        if (droppedFrames > 0) {
            log.warn("Dropped {} stale viewer frame(s) under back-pressure", droppedFrames)
        }
        wake.trySend(Unit)
        return true
    }

    /**
     * Enqueue a large recoverable frame only when it fits behind all existing output. Graphics
     * must never evict pane text; callers fall back to a small resync sentinel on false.
     */
    fun trySendWithoutEviction(text: String): Boolean {
        if (closed || text.isEmpty() || text.length > capacityChars) return false
        synchronized(lock) {
            if (closed ||
                queuedChars + text.length > capacityChars ||
                frames.size + 1 > capacityFrames
            ) {
                return false
            }
            frames.addLast(text)
            queuedChars += text.length
        }
        wake.trySend(Unit)
        return true
    }

    /**
     * Enqueue a frame that must survive back-pressure (voice control traffic).
     *
     * Bounded like [ai.rever.bossterm.compose.daemon.FrameOutbox.sendControl], and for the same
     * reason: a guaranteed lane with no ceiling is a memory hole. Any viewer holding a link can pump
     * `voiceToolCall` frames — the cheap refusal paths in `VoiceCallService.handleToolCall` reply
     * before the in-flight gate — while not reading its own socket. Past the bound the connection is
     * closed rather than grown, because a viewer this far behind is not going to catch up.
     */
    fun sendControl(text: String): Boolean {
        if (closed || text.isEmpty()) return false
        // Snapshot the numbers under the lock: they are only read in the bad case, and torn values
        // in the one diagnostic that fires there would be actively misleading.
        val overflow: Pair<Int, Int>? = synchronized(lock) {
            if (closed) return false
            if (controlFrames.size >= controlCapacityFrames ||
                controlChars + text.length > controlCapacityChars
            ) {
                controlFrames.size to controlChars
            } else {
                controlFrames.addLast(text)
                controlChars += text.length
                null
            }
        }
        if (overflow != null) {
            log.warn(
                "Closing stalled viewer outbox: control backlog at {} frames / {} chars",
                overflow.first,
                overflow.second,
            )
            close()
            return false
        }
        wake.trySend(Unit)
        return true
    }

    suspend fun drainTo(emit: suspend (String) -> Unit) {
        var controlBurst = 0
        while (true) {
            // Prefer control frames, but only [CONTROL_BURST] in a row: draining every one first
            // would let voice traffic starve pane output entirely.
            val next = synchronized(lock) {
                // …unless there is no output to be fair to: with the output queue empty the burst
                // cap would take nothing and park the loop on `wake` with control frames still
                // queued — on the one lane advertised as guaranteed.
                val takeControl = controlBurst < CONTROL_BURST || frames.isEmpty()
                val control = if (takeControl) {
                    controlFrames.removeFirstOrNull()?.also { controlChars -= it.length }
                } else null
                if (control != null) {
                    controlBurst++
                    control
                } else {
                    controlBurst = 0
                    frames.removeFirstOrNull()?.also { queuedChars -= it.length }
                }
            }
            if (next != null) {
                emit(next)
                continue
            }
            if (closed) return
            wake.receiveCatching()
        }
    }

    fun close() {
        closed = true
        wake.close()
    }

    internal companion object {
        val log = LoggerFactory.getLogger(BoundedViewerOutbox::class.java)
        const val DEFAULT_CAPACITY_CHARS = 32 * 1024 * 1024
        const val DEFAULT_CAPACITY_FRAMES = 2048

        /** Control-lane bounds — guaranteed delivery, but not at any price. */
        const val CONTROL_CAPACITY_FRAMES = 1024
        const val CONTROL_CAPACITY_CHARS = 8 * 1024 * 1024

        /** Control frames drained per pass before yielding to output, so neither starves. */
        const val CONTROL_BURST = 64
    }
}
