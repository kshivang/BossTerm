package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.PaneTreeNode
import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.SessionShareManager
import ai.rever.bossterm.compose.share.ShareProtocol
import ai.rever.bossterm.compose.share.ShareScope
import ai.rever.bossterm.compose.splits.SplitNode
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TabController
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    /**
     * Client id the host uses to recognize this device across reconnects WITHIN a session. It's a
     * fresh UUID per window/launch (not persisted), so a host re-approval is needed after restart
     * — consistent with the in-memory access keys. Persisting it is a follow-up.
     */
    private val clientId: String = UUID.randomUUID().toString()

    /** Active remote sessions — observed by the UI to list them + show status. */
    val sessions = mutableStateListOf<RemoteSession>()

    /**
     * Dial [link] and mirror its tabs here. Returns null (refusing to connect) when [link] is one
     * of THIS instance's own share links — mirroring a session into itself would feed the mirror
     * tabs back into the share in a loop. Reconnecting to a share we're already mirroring (same
     * token — e.g. a repeated two-way offer or a double-pasted link) returns the existing session.
     * [shareBack] = two-way: once the host grants control, offer it this window's own share link
     * so it mirrors our tabs too.
     */
    @Synchronized
    fun connect(link: String, deviceName: String, shareBack: Boolean = false): RemoteSession? {
        if (isOwnShareLink(link)) return null
        // @Synchronized makes the lookup-then-add atomic — connect() is called from the UI thread
        // (AddRemoteDialog) and from the host's OfferShare handler (a server coroutine).
        tokenOf(link)?.let { token ->
            sessions.firstOrNull { tokenOf(it.link) == token }?.let { existing ->
                if (shareBack) existing.enableShareBack() // honor a newly-ticked two-way box
                return existing
            }
        }
        val session = RemoteSession(link, deviceName, state, clientId, shareBack)
        sessions.add(session)
        session.start()
        return session
    }

    /** True if [link]'s token belongs to a session this instance is hosting. */
    fun isOwnShareLink(link: String): Boolean =
        tokenOf(link)?.let { ai.rever.bossterm.compose.share.SessionShareManager.ownsToken(it) } == true

    fun disconnect(session: RemoteSession) {
        session.close()
        sessions.remove(session)
    }

    /** The remote session that owns [tab] (a mirror tab), or null if it's a local tab. */
    fun sessionForTab(tab: TerminalTab): RemoteSession? = sessions.firstOrNull { it.containsTab(tab) }

    /** Like [sessionForTab] but also matches pane mirrors inside a container's split tree. */
    fun sessionForMirror(s: ai.rever.bossterm.compose.TerminalSession): RemoteSession? =
        sessions.firstOrNull { it.ownsMirror(s) }

    /** Tear down every remote session (e.g. when the window closes). */
    fun disconnectAll() {
        sessions.toList().forEach { it.close() }
        sessions.clear()
    }

    /** A blocked-typing event awaiting the user's decision ([tabId] != null → relay upstream). */
    data class BlockedInput(val session: RemoteSession, val tabId: String?)

    /** Non-null while the "typing needs control" prompt should show; cleared by the dialog. */
    val blockedInput = androidx.compose.runtime.mutableStateOf<BlockedInput?>(null)
    @Volatile private var inputPromptQuietUntil = 0L

    /** Typing hit a read-only path — surface the request-control prompt (throttled). */
    internal fun notifyBlockedInput(session: RemoteSession, upstreamTabId: String?) {
        val now = System.currentTimeMillis()
        if (now < inputPromptQuietUntil) return
        inputPromptQuietUntil = now + 2_000 // buffered keystrokes → one prompt
        blockedInput.value = BlockedInput(session, upstreamTabId)
    }

    /** The user dismissed the typing prompt — stay quiet a while instead of nagging per key. */
    internal fun snoozeBlockedInputPrompt() {
        inputPromptQuietUntil = System.currentTimeMillis() + 30_000
    }
}

/** The `t=` token in a share [link], or null if absent/malformed. Visible for tests. */
internal fun tokenOf(link: String): String? = runCatching {
    val raw = java.net.URI(link.trim()).rawQuery ?: return@runCatching null
    raw.split("&").firstOrNull { it.startsWith("t=") }?.substringAfter("t=")
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
}.getOrNull()

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
    /** Two-way: once the host grants control, offer it this window's own share link. */
    shareBack: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(RemoteSession::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val offeredShareBack = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var shareBackEnabled = shareBack

    /** Turn on two-way share-back after construction (a re-paste with the box newly ticked). */
    fun enableShareBack() {
        shareBackEnabled = true
        if (conn.canControl) maybeOfferShareBack()
    }

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

    // Local-only customization of this remote's group box in the left bar (right-click the
    // box header). Not sent to the host; lives as long as the connection.
    /** Custom group name; null → the link's host. */
    val customName = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Custom group accent ("0xFFRRGGBB"); null → the default remote cyan. */
    val accent = androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * The host's own name for its session (Layout.sessionName — defaults to its username).
     * Used as the group label when no local [customName] is set; falls back to the link host.
     */
    val hostName = androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * [canControl] as Compose state — updated on the host's Control messages, so the tab bar's
     * remote menus rebuild the moment control is granted/revoked (a StateFlow read wouldn't
     * trigger recomposition).
     */
    val canControlState = androidx.compose.runtime.mutableStateOf(false)

    /**
     * Connection status as Compose state — drives the group box's header indicator
     * (connecting…/disconnected) and, read inside the share signature's snapshotFlow, the
     * originOffline flag forwarded to nested viewers when WE are someone's upstream host.
     */
    val statusState = androidx.compose.runtime.mutableStateOf<RemoteStatus>(RemoteStatus.Connecting)

    /**
     * The remote host's BossTerm-MCP state (from [ServerMessage.McpStatus]), or null until it
     * arrives. Drives the "MCP" pill in this remote's tab-group header (rendered by
     * [ai.rever.bossterm.compose.tabs.RemoteTabGroup] in the tab bar).
     */
    val mcpStatus = androidx.compose.runtime.mutableStateOf<RemoteMcpStatus?>(null)

    /** Toggle the remote host's MCP server (the pill's on/off). No-op when view-only. */
    fun setRemoteMcpEnabled(enabled: Boolean) {
        if (canControlState.value) conn.send(ClientMessage.SetMcpEnabled(enabled))
    }

    /** Attach a CLI on the remote host ([McpAttachTarget] persistence key). No-op when view-only. */
    fun attachRemoteMcp(targetKey: String) {
        if (canControlState.value) conn.send(ClientMessage.AttachMcp(targetKey))
    }

    /** True if [s] is any of this session's mirrors — the containers or their pane mirrors. */
    fun ownsMirror(s: ai.rever.bossterm.compose.TerminalSession): Boolean =
        localTabByRemote.values.any { it === s } || sessionByPane.values.any { it === s }

    /**
     * Upstream-origin info for a host tab that itself mirrors ANOTHER session (the host's
     * remote): [key] groups tabs of the same upstream, [name] labels the subsection, and
     * [readOnly] means the host can't type into it either — so neither can we through it.
     * [window] is the ORIGIN's window for this tab when the upstream shared all its windows
     * (forwarded by the host) — sections the nested box per origin window.
     */
    data class UpstreamOrigin(
        val key: String,
        val name: String?,
        val readOnly: Boolean,
        val offline: Boolean = false,
        val window: HostWindow? = null,
    )

    // localTabId (container) → upstream-origin info; bumping [upstreamRev] re-renders the bar
    // when only this map changed (e.g. the host's upstream control was granted mid-session).
    // Concurrent: written on Main (the inbox drain), read on host threads (MirrorShare).
    private val upstreamByTab = java.util.concurrent.ConcurrentHashMap<String, UpstreamOrigin>()
    val upstreamRev = androidx.compose.runtime.mutableStateOf(0)

    /** The upstream origin of [localTabId]'s host tab, or null if it's the host's own tab. */
    fun upstreamFor(localTabId: String): UpstreamOrigin? = upstreamByTab[localTabId]

    /**
     * The host window a tab belongs to, for a host sharing ALL its windows: [key] groups
     * tabs of the same window ([TabNode.windowId][ai.rever.bossterm.compose.share.TabNode]),
     * [name] labels the section ("Window 2"). Absent for single-window hosts.
     */
    data class HostWindow(val key: String, val name: String?)

    /** The remote host's MCP state for its group-header "MCP" pill. [attached] = persistence keys. */
    data class RemoteMcpStatus(
        val enabled: Boolean,
        val running: Boolean,
        val attached: List<String>,
    )

    // localTabId (container) → host-window identity; changes bump [upstreamRev] (the bar's
    // metadata-revision tick). Concurrent for the same reason as [upstreamByTab].
    private val windowByTab = java.util.concurrent.ConcurrentHashMap<String, HostWindow>()

    /** The host window of [localTabId]'s tab, or null when the host didn't stamp one. */
    fun windowFor(localTabId: String): HostWindow? = windowByTab[localTabId]

    /**
     * Identifies which share this session mirrors: SHA-256 of [link]'s token. Stamped on this
     * session's container tabs in OUR outgoing Layout ([TabNode.origin][ai.rever.bossterm.compose.share.TabNode]),
     * so a client connecting to US can skip tabs that mirror its own session. Null if the link
     * carries no token.
     */
    val originHash: String? = tokenOf(link)?.let { ShareProtocol.sha256Hex(it) }

    // remote tabId → the local **container** tab in tabController.tabs. A container is a
    // PTY-less, stream-less shell that owns the chip + the split tree; it is never a pane itself.
    // Concurrent maps: mutated only on Main (the inbox drain / disconnect), but read on the
    // Compose thread (sessionForTab during composition) and host threads (MirrorShare). Insertion
    // order isn't relied on — tab display order comes from controller.tabs.
    private val localTabByRemote = java.util.concurrent.ConcurrentHashMap<String, TerminalTab>()
    // remote paneId → the mirror session feeding that pane's terminal. Holds ONLY pane mirrors
    // (every remote pane, even a single one), never a container — so the host closing any pane
    // (including a tab's first) can never orphan a survivor's output. Read on the WS IO thread
    // (PaneOutput hot path) and host threads, hence concurrent.
    private val sessionByPane = java.util.concurrent.ConcurrentHashMap<String, TerminalTab>()

    // Inbound server messages are drained by ONE coroutine on Main, in wire order — so reconcile's
    // tab-list/splitState mutation and the snapshot-after-layout ordering both run on the UI thread,
    // matching TabController's convention and removing the cross-thread tab-mutation races.
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val inbox = kotlinx.coroutines.channels.Channel<ServerMessage>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    fun start() {
        uiScope.launch {
            // Mirror the connection's status into Compose state (read in composition).
            conn.status.collect { statusState.value = it }
        }
        uiScope.launch {
            for (msg in inbox) runCatching { handleMessage(msg) }
                .onFailure { log.warn("remote message handling failed: {}", it.message) }
        }
        conn.start()
    }

    /**
     * Don't re-show the disconnect dialog for this failure (its dismiss); reset by [reconnect],
     * so a later failure prompts again.
     */
    val failureDismissed = androidx.compose.runtime.mutableStateOf(false)

    /** Restart a failed connection (the disconnect dialog's Reconnect). */
    fun reconnect() {
        failureDismissed.value = false
        conn.reconnect()
    }

    /** Ask the host to grant write/control (when connected view-only). */
    fun requestControl() = conn.send(ClientMessage.RequestControl())

    // An upstream control request queued until OUR control of the host is granted — the host
    // only relays upstream requests from controlling clients, so (A view-only on B, B view-only
    // on C) chains as: ask B for control first, then automatically ask C through it.
    @Volatile private var pendingUpstreamControlTab: String? = null

    /**
     * Control request targeted at [localTabId]'s tab: when that tab mirrors an upstream session
     * on the host (A→B→C), the host relays the request to the origin. If we don't control the
     * host yet, first request that (its user approves), then the upstream request fires
     * automatically when the grant arrives.
     */
    fun requestControlFor(localTabId: String) {
        if (!conn.canControl) {
            pendingUpstreamControlTab = localTabId
            conn.send(ClientMessage.RequestControl())
            return
        }
        conn.send(ClientMessage.RequestControl(remoteTabIdFor(localTabId)))
    }

    /** New tab in [localTabId]'s session — the host relays upstream for mirrored tabs. */
    fun newTabIn(localTabId: String) {
        if (!conn.canControl) return
        conn.send(ClientMessage.NewTab(remoteTabIdFor(localTabId) ?: return))
    }

    /** Ask the host to disconnect from the upstream session that [localTabId] mirrors. */
    fun disconnectUpstream(localTabId: String) {
        if (!conn.canControl) return
        conn.send(ClientMessage.DisconnectUpstream(remoteTabIdFor(localTabId) ?: return))
    }

    /** True if [tab] is one of this session's mirror tabs. */
    fun containsTab(tab: TerminalTab): Boolean = localTabByRemote.values.any { it === tab }

    /** Open a new tab in the remote session (mirrors back as another tab). Control only. */
    fun newRemoteTab() = conn.send(ClientMessage.NewTab())

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
        runCatching { scope.cancel() }   // status collector + share-back offer
        runCatching { uiScope.cancel() } // the inbox drain
        conn.close()
        localTabByRemote.values.toList().forEach { removeMirrorTab(it) }
        localTabByRemote.clear()
        sessionByPane.clear()
        upstreamByTab.clear()
        windowByTab.clear()
    }

    // ---- message handling ----
    // Called on the WS IO thread — just hand off to the ordered Main drain (see [inbox]).
    private fun onMessage(msg: ServerMessage) { inbox.trySend(msg) }

    // Runs on Main, in wire order.
    private fun handleMessage(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Layout -> {
                msg.sessionName?.takeIf { it.isNotBlank() }?.let { hostName.value = it }
                runCatching { reconcile(msg) }
                    .onFailure { log.warn("remote layout reconcile failed: {}", it.message) }
            }
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
            is ServerMessage.Control -> {
                canControlState.value = msg.granted // recompose the tab bar's remote menus
                if (msg.granted) {
                    // Two-way sharing offers only matter once the host trusts us with control —
                    // it ignores OfferShare from view-only clients anyway.
                    maybeOfferShareBack()
                    // Fire a queued upstream control request (the second hop of the chain):
                    // now that the host trusts us, it will relay it to the origin.
                    pendingUpstreamControlTab?.let { tabId ->
                        pendingUpstreamControlTab = null
                        conn.send(ClientMessage.RequestControl(remoteTabIdFor(tabId)))
                    }
                }
            }
            is ServerMessage.McpStatus -> {
                mcpStatus.value = RemoteMcpStatus(
                    enabled = msg.enabled, running = msg.running, attached = msg.attached,
                )
            }
            else -> {} // Theme/Presence/Pending/Grant/Denied handled in the connection
        }
    }

    /**
     * Two-way sharing: offer this window's own share link to the host (once per session) so it
     * mirrors our tabs back. Reuses an active share of this window, else starts a WINDOW share
     * (booting the share server/tunnel as needed — hence async).
     */
    private fun maybeOfferShareBack() {
        if (!shareBackEnabled || !offeredShareBack.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                val ownLink = ensureOwnShareLink() ?: return@launch
                conn.send(ClientMessage.OfferShare(ownLink))
            }.onFailure { log.warn("two-way share-back failed: {}", it.message) }
        }
    }

    /** This window's own view link — an active share if present, else a fresh WINDOW share. */
    private suspend fun ensureOwnShareLink(): String? {
        state.tabs.firstOrNull { SessionShareManager.isSharing(it.id) }
            ?.let { SessionShareManager.urlFor(it.id) }?.let { return it }
        val tabId = state.activeTabId ?: state.tabs.firstOrNull()?.id ?: return null
        if (!SettingsManager.instance.settings.value.sessionSharingEnabled) {
            SettingsManager.instance.updateSetting { copy(sessionSharingEnabled = true) }
        }
        return SessionShareManager.share(tabId, ShareScope.WINDOW)?.url
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
        // Skip the host's tabs that mirror OUR OWN session (their origin is the hash of one of
        // this instance's share tokens) — mirroring them back here would loop our tabs through
        // the peer. The host's own tabs, and its mirrors of third sessions, come through as-is.
        val tabs = layout.tabs.filter { node ->
            val o = node.origin
            o == null || !ai.rever.bossterm.compose.share.SessionShareManager.ownsTokenHash(o)
        }
        val remoteIds = tabs.map { it.id }.toSet()

        // Self-heal: a container the user closed via its chip (×) is gone from the controller but
        // still in our maps — forget it + dispose its pane mirrors so it can be rebuilt fresh.
        localTabByRemote.entries.toList().forEach { (remoteId, container) ->
            if (controller.tabs.none { it === container }) {
                localTabByRemote.remove(remoteId)
                // Owned paneIds: from the (maybe still-present) split tree, plus the incoming
                // layout — getAllPanes()/SplitNode.Pane.id are the remote paneIds.
                val owned = buildSet {
                    state.splitStates[container.id]?.getAllPanes()?.forEach { add(it.id) }
                    tabs.firstOrNull { it.id == remoteId }?.let { addAll(paneIds(it.tree)) }
                }
                owned.forEach { pid -> sessionByPane.remove(pid)?.let { disposeSession(it) } }
                state.splitStates.remove(container.id)
                upstreamByTab.remove(container.id)
                windowByTab.remove(container.id)
            }
        }

        // Drop containers for remote tabs that no longer exist (host closed the whole tab).
        (localTabByRemote.keys - remoteIds).toList().forEach { gone ->
            localTabByRemote.remove(gone)?.let { removeMirrorTab(it) }
        }
        // Drop pane mirrors whose remote paneId vanished (host closed a split pane).
        val livePaneIds = tabs.flatMap { paneIds(it.tree) }.toSet()
        (sessionByPane.keys - livePaneIds).toList().forEach { gone ->
            sessionByPane.remove(gone)?.let { disposeSession(it) }
        }

        for (tabNode in tabs) {
            // The container — a PTY-less, stream-less tab that owns the chip and hosts the split
            // tree of pane mirrors. It is never itself a pane.
            val isNew = !localTabByRemote.containsKey(tabNode.id)
            val container = localTabByRemote.getOrPut(tabNode.id) {
                controller.createRemoteSession(title = tabNode.title.ifBlank { "remote" }, feedsStream = false)
            }
            container.title.value = tabNode.title.ifBlank { "remote" }
            container.workingDirectory.value = tabNode.cwd

            // Track whether this host tab itself mirrors ANOTHER session (and our effective
            // writability through it) — the tab bar nests those under a labeled subsection.
            val upstream = tabNode.origin?.let {
                UpstreamOrigin(
                    it, tabNode.originName, tabNode.originReadOnly == true, tabNode.originOffline == true,
                    window = tabNode.originWindowId?.let { w -> HostWindow(w, tabNode.originWindowName) },
                )
            }
            if (upstreamByTab[container.id] != upstream) {
                if (upstream != null) upstreamByTab[container.id] = upstream else upstreamByTab.remove(container.id)
                upstreamRev.value++
            }

            // Track which host WINDOW the tab belongs to (stamped by ALL-scope hosts) — the
            // tab bar sections the group's own tabs per window, like the web viewer's boxes.
            val win = tabNode.windowId?.let { HostWindow(it, tabNode.windowName) }
            if (windowByTab[container.id] != win) {
                if (win != null) windowByTab[container.id] = win else windowByTab.remove(container.id)
                upstreamRev.value++
            }

            // (Re)build the split tree of pane mirrors — always present, even for a single pane.
            // Preserve the client's own focused pane across structural updates (setTree falls back
            // to the first pane if the focused one is gone) — client focus is independent of host.
            val ss = state.splitStates.getOrPut(container.id) { SplitViewState(initialSession = container) }
            // Route divider drags in this mirror's split tree to the host (idempotent each pass).
            ss.onRemoteDividerDrag = { splitId, ratio, committed -> resizeSplit(container.id, splitId, ratio, committed) }
            ss.setTree(buildTree(tabNode.tree, container.id, controller), ss.focusedPaneId)

            // Add to the tab list only AFTER the split tree is populated — so the UI never renders
            // a not-yet-built container, and without switching focus (must not steal the local
            // active tab). New tabs append at the end.
            if (isNew) controller.tabs.add(container)
        }
    }

    /** Map a remote pane tree to a local [SplitNode] tree, reusing pane mirrors by remote paneId. */
    private fun buildTree(node: PaneTreeNode, containerId: String, controller: TabController): SplitNode = when (node) {
        is PaneTreeNode.Pane -> {
            val session = sessionByPane.getOrPut(node.paneId) {
                controller.createRemoteSession(
                    title = node.title,
                    remotePaneId = node.paneId,
                    onUserInput = { data ->
                        // Typing into a read-only path (no control of the host, or the host
                        // itself is view-only on this tab's upstream) prompts to request
                        // control instead of vanishing — same as the web viewer.
                        val upstreamReadOnly = upstreamFor(containerId)?.readOnly == true
                        if (conn.canControl && !upstreamReadOnly) {
                            conn.send(ClientMessage.Input(node.paneId, data))
                        } else {
                            state.remoteSessions.notifyBlockedInput(
                                this, containerId.takeIf { upstreamReadOnly }
                            )
                        }
                    },
                )
            }
            session.title.value = node.title
            session.workingDirectory.value = node.cwd
            SplitNode.Pane(id = node.paneId, session = session)
        }
        is PaneTreeNode.Split -> if (node.dir == "h") {
            SplitNode.HorizontalSplit(
                id = node.id.ifBlank { node.paneIdKey() },
                top = buildTree(node.a, containerId, controller),
                bottom = buildTree(node.b, containerId, controller),
                ratio = node.ratio,
            )
        } else {
            SplitNode.VerticalSplit(
                id = node.id.ifBlank { node.paneIdKey() },
                left = buildTree(node.a, containerId, controller),
                right = buildTree(node.b, containerId, controller),
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
        upstreamByTab.remove(container.id)
        windowByTab.remove(container.id)
        val idx = controller?.tabs?.indexOf(container) ?: -1
        if (idx >= 0) {
            if (controller!!.tabs.size <= 1) {
                // Don't let a remote-driven teardown close the window via onLastTabClosed (e.g. the
                // host closes its tab while this mirror is the only tab left). Dispose + remove the
                // mirror directly, leaving an empty window rather than exiting.
                runCatching { container.dispose() }
                controller.tabs.removeAt(idx)
            } else {
                controller.closeTab(idx) // index-safe removal; closeTab disposes the container
            }
        }
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
