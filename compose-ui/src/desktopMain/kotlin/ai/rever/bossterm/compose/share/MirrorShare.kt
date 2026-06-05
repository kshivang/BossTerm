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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Whether a share covers one tab (incl. its splits) or the whole window (all tabs). */
enum class ShareScope { TAB, WINDOW }

/**
 * One active share (issue #276) mirroring a window→tabs→panes model to browser viewers.
 * TAB scope mirrors the single initiating tab (with its splits); WINDOW scope mirrors
 * every tab of the owning [TabbedTerminalState], reactive to tab add/remove.
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

    // Observable so the layout observer re-emits when the scope is toggled live
    // (Tab ↔ Window) — same tokens/viewers, just a different set of mirrored tabs.
    private var scopeVar by mutableStateOf(initialScope)
    val scope: ShareScope get() = scopeVar
    fun setScope(s: ShareScope) { scopeVar = s }

    private val viewers = CopyOnWriteArrayList<ViewerConnection>()
    private val viewerSeq = AtomicInteger(0)
    private val coro = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null

    private class TapEntry(val tab: TerminalTab, val prev: ((String) -> Unit)?)
    private val taps = HashMap<String, TapEntry>()

    fun start() {
        observerJob = coro.launch {
            snapshotFlow { computeSignature() }
                .distinctUntilChanged()
                .collect { sig -> reconcile(sig) }
        }
    }

    fun stop() {
        observerJob?.cancel()
        synchronized(taps) {
            taps.values.forEach { e -> runCatching { e.tab.dataStream.onRawOutput = e.prev } }
            taps.clear()
        }
        viewers.forEach { it.outbox.close() }
        viewers.clear()
        coro.cancel()
    }

    // ---- viewers ----
    fun addViewer(canControl: Boolean): ViewerConnection {
        val vc = ViewerConnection(viewerSeq.incrementAndGet(), canControl)
        viewers.add(vc)
        broadcast(ServerMessage.Presence(viewers.size))
        return vc
    }

    fun removeViewer(vc: ViewerConnection) {
        if (viewers.remove(vc)) {
            vc.outbox.close()
            broadcast(ServerMessage.Presence(viewers.size))
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
        out.add(ServerMessage.Layout(sig.tabs, sig.activeTabId, sig.tabBarOnLeft, sig.summaryMode))
        for ((id, tab) in paneTabMap()) {
            val sz = sig.sizes[id] ?: listOf(80, 24)
            out.add(ServerMessage.PaneSnapshot(id, snapshotText(tab), sz[0], sz[1]))
        }
        return out
    }

    /** Route viewer messages — input + tab close/new — to the host (controller role only). */
    fun handleClient(vc: ViewerConnection, msg: ClientMessage) {
        if (!vc.canControl) return // all mutating actions require the control role
        when (msg) {
            is ClientMessage.Input ->
                (taps[msg.paneId]?.tab ?: paneTabMap()[msg.paneId])?.writeUserInput(msg.data)
            is ClientMessage.CloseTab ->
                McpTerminalRegistry.findState(tabId)?.closeTab(msg.tabId)
            is ClientMessage.NewTab ->
                McpTerminalRegistry.findState(tabId)?.createTab()
            is ClientMessage.SplitVertical ->
                McpTerminalRegistry.findState(tabId)?.splitVerticalFromPane(msg.tabId, msg.paneId)
            is ClientMessage.SplitHorizontal ->
                McpTerminalRegistry.findState(tabId)?.splitHorizontalFromPane(msg.tabId, msg.paneId)
            is ClientMessage.ClosePane -> {
                // Target the clicked pane (focus it), then close; closeFocusedPane closes
                // the whole tab when it's the only pane (matches the host's behavior).
                val st = McpTerminalRegistry.findState(tabId)
                st?.splitStates?.get(msg.tabId)?.setFocusedPane(msg.paneId)
                st?.closeFocusedPane(msg.tabId)
            }
            is ClientMessage.LaunchAI -> {
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
                McpTerminalRegistry.findState(tabId)?.renameChip(msg.tabId, msg.paneId, msg.title)
            is ClientMessage.SetTabColor ->
                McpTerminalRegistry.findState(tabId)?.setChipColor(msg.tabId, msg.paneId, msg.color)
            is ClientMessage.DuplicateTab ->
                McpTerminalRegistry.findState(tabId)?.duplicateTab(msg.tabId)
            is ClientMessage.CloseOtherTabs ->
                McpTerminalRegistry.findState(tabId)?.closeOtherTabs(msg.tabId)
            is ClientMessage.CloseTabsBelow ->
                McpTerminalRegistry.findState(tabId)?.closeTabsBelow(msg.tabId)
            is ClientMessage.ResizeHost -> resizeHostWindow(msg.cols, msg.rows)
            is ClientMessage.ResizeSplit ->
                McpTerminalRegistry.findState(tabId)?.splitStates?.get(msg.tabId)?.updateSplitRatio(msg.splitId, msg.ratio)
            else -> {} // Hello / Focus / RequestControl: no-op (focus is viewer-side; control via token)
        }
    }

    /**
     * "Fit host to client": resize the host's OS window so its terminal grid lands near
     * [cols]×[rows]. The grid is slaved to the canvas, so we nudge the window by the pixel
     * delta for the column/row change — the per-cell pixel size comes from the host's own
     * measurement and the window chrome (left tab bar, title) cancels out of the delta.
     * Best-effort and single-pane-oriented; multi-window picks the focused (else first) window.
     */
    private fun resizeHostWindow(cols: Int, rows: Int) {
        if (cols < 2 || rows < 2) return
        val tab = McpTerminalRegistry.findTab(tabId) ?: return
        val cw = tab.terminal.cellWidthPx
        val ch = tab.terminal.cellHeightPx
        if (cw <= 0f || ch <= 0f) return
        val cur = tab.display.termSize.value
        val win = WindowManager.windows.firstOrNull { it.isWindowFocused.value && it.awtWindow != null }?.awtWindow
            ?: WindowManager.windows.firstOrNull { it.awtWindow != null }?.awtWindow
            ?: return
        // AWT window size is in points; cell px is physical — divide by the display scale.
        javax.swing.SwingUtilities.invokeLater {
            runCatching {
                val gc = win.graphicsConfiguration
                val sx = gc?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
                val sy = gc?.defaultTransform?.scaleY?.takeIf { it > 0 } ?: 1.0
                var newW = (win.width + (cols - cur.columns) * cw / sx).toInt()
                var newH = (win.height + (rows - cur.rows) * ch / sy).toInt()
                gc?.bounds?.let { b -> newW = newW.coerceIn(480, b.width); newH = newH.coerceIn(320, b.height) }
                if (newW != win.width || newH != win.height) {
                    win.setSize(newW, newH)
                    win.validate()
                }
            }
        }
    }

    // ---- reconcile on structural change ----
    private fun reconcile(sig: WindowSig) {
        val paneMap = paneTabMap()
        if (paneMap.isEmpty()) { onEnded(); return }
        synchronized(taps) {
            (taps.keys - paneMap.keys).toList().forEach { id ->
                taps.remove(id)?.let { e -> runCatching { e.tab.dataStream.onRawOutput = e.prev } }
            }
            for ((id, tab) in paneMap) {
                if (id !in taps) {
                    val prev = tab.dataStream.onRawOutput
                    taps[id] = TapEntry(tab, prev)
                    tab.dataStream.onRawOutput = { d -> prev?.invoke(d); broadcast(ServerMessage.PaneOutput(id, d)) }
                    val sz = sig.sizes[id] ?: listOf(80, 24)
                    broadcast(ServerMessage.PaneSnapshot(id, snapshotText(tab), sz[0], sz[1]))
                }
            }
        }
        broadcast(ServerMessage.Layout(sig.tabs, sig.activeTabId, sig.tabBarOnLeft, sig.summaryMode))
        sig.sizes.forEach { (id, sz) -> broadcast(ServerMessage.PaneResize(id, sz[0], sz[1])) }
    }

    // ---- window-state signature (pure-serializable; drives distinctUntilChanged) ----
    private data class WindowSig(
        val tabs: List<TabNode>,
        val activeTabId: String?,
        val sizes: Map<String, List<Int>>,
        val tabBarOnLeft: Boolean,
        val summaryMode: Boolean,
    )

    private fun inScopeTabs(state: TabbedTerminalState): List<TerminalTab> = when (scope) {
        ShareScope.WINDOW -> state.tabs
        ShareScope.TAB -> state.tabs.filter { it.id == tabId }
    }

    private fun computeSignature(): WindowSig {
        val state = McpTerminalRegistry.findState(tabId) ?: return WindowSig(emptyList(), null, emptyMap(), false, false)
        val tabNodes = ArrayList<TabNode>()
        val sizes = HashMap<String, List<Int>>()
        val activeId = if (scope == ShareScope.TAB) tabId else state.activeTabId
        val settings = SettingsManager.instance.settings.value
        val onLeft = settings.tabBarPosition == "left"
        val summary = settings.tabBarSummaryMode
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
            tabNodes.add(
                TabNode(
                    id = tab.id, title = tab.title.value, active = tab.id == activeId, tree = tree,
                    color = tabColorCss(tab), cwd = tab.workingDirectory.value, branch = tab.gitBranch.value
                )
            )
        }
        return WindowSig(tabNodes, activeId, sizes, onLeft, summary)
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

    /** Current paneId → owning session, across all in-scope tabs. */
    private fun paneTabMap(): Map<String, TerminalTab> {
        val state = McpTerminalRegistry.findState(tabId) ?: return emptyMap()
        val m = LinkedHashMap<String, TerminalTab>()
        for (tab in inScopeTabs(state)) {
            val ss = state.splitStates[tab.id]
            if (ss != null) ss.getAllPanes().forEach { p -> (p.session as? TerminalTab)?.let { m[p.id] = it } }
            else m[tab.id] = tab
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
class ViewerConnection(val id: Int, val canControl: Boolean) {
    val outbox: Channel<String> = Channel(capacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
