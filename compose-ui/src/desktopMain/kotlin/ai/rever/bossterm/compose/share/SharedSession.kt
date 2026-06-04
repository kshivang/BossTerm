package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.theme.ColorPalette
import ai.rever.bossterm.compose.settings.theme.ColorPaletteManager
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import ai.rever.bossterm.compose.tabs.TerminalTab
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One actively-shared terminal tab (issue #276, Phase 1 — read-only mirror).
 *
 * Holds the per-share state: an unguessable view token, the set of connected
 * browser viewers, and a tap on the tab's [ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream]
 * that fans raw PTY output out to every viewer. The Ktor route in
 * [SessionShareManager] creates a [ViewerConnection] per WebSocket, sends the
 * initial [ServerMessage.Snapshot], then drains the connection's outbox to the
 * socket while this class pushes live [ServerMessage.Output] frames in.
 *
 * The target tab is resolved fresh from [McpTerminalRegistry] each time so a
 * closed/reopened tab can't be written to through a stale reference.
 *
 * Threading: [broadcast] runs on the emulator data-loop thread (via the raw tap)
 * and from Ktor coroutine threads; it only does lock-free `trySend` into each
 * viewer's bounded channel, so it never blocks the terminal. Control (viewer →
 * PTY input) is Phase 2 and intentionally absent here.
 */
class SharedSession(val tabId: String, val title: String) {

    /** Unguessable token that gates read-only viewer access; embedded in the view URL. */
    val viewToken: String = secureToken()

    /**
     * Separate unguessable token that grants write/control access (Phase 2). A viewer
     * connecting with this token may send input to the PTY. Shared deliberately and
     * independently of [viewToken] — the OpenClaw-style "explicit grant" reduced to a
     * capability link, so the host needn't be present to approve.
     */
    val controlToken: String = secureToken()

    private val viewers = CopyOnWriteArrayList<ViewerConnection>()
    private val viewerSeq = AtomicInteger(0)

    @Volatile private var tapped = false
    private var previousTap: ((String) -> Unit)? = null

    /** Resolve the live tab by id, or null if it has since closed. */
    private fun resolve(): TerminalTab? = McpTerminalRegistry.findTab(tabId)

    /** Begin mirroring: install the raw-output tap on the tab's data stream. Idempotent. */
    @Synchronized
    fun start() {
        if (tapped) return
        val tab = resolve() ?: return
        previousTap = tab.dataStream.onRawOutput
        val prev = previousTap
        tab.dataStream.onRawOutput = { data ->
            prev?.invoke(data)               // chain any prior observer
            broadcast(ServerMessage.Output(data))
        }
        tapped = true
    }

    /** Stop mirroring: remove the tap and close every viewer socket. Idempotent. */
    @Synchronized
    fun stop() {
        if (tapped) {
            resolve()?.let { it.dataStream.onRawOutput = previousTap }
            previousTap = null
            tapped = false
        }
        viewers.forEach { it.outbox.close() }
        viewers.clear()
    }

    /** Build the one-time initial paint from the current scrollback + screen. */
    fun snapshotMessage(): ServerMessage.Snapshot {
        val tab = resolve() ?: return ServerMessage.Snapshot("", 80, 24)
        val snap = tab.textBuffer.createSnapshot()
        val sb = StringBuilder()
        var row = -snap.historyLinesCount
        while (row < snap.height) {
            sb.append(snap.getLine(row).text.trimEnd())
            if (row < snap.height - 1) sb.append("\r\n")
            row++
        }
        val size = tab.display.termSize.value
        val cols = if (size.columns > 0) size.columns else snap.width
        val rows = if (size.rows > 0) size.rows else snap.height
        return ServerMessage.Snapshot(sb.toString(), cols, rows)
    }

    /**
     * The host's terminal theme (active Theme + ANSI palette + font) so the browser
     * viewer renders identically to BossTerm. Sent once before the first snapshot.
     */
    fun themeMessage(): ServerMessage.Theme {
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

    /** "0xAARRGGBB" / "0xRRGGBB" / "#RRGGBB" → CSS "#RRGGBB" (alpha dropped). */
    private fun hexToCss(argb: String): String {
        val h = argb.removePrefix("0x").removePrefix("0X").removePrefix("#")
        val rrggbb = when {
            h.length >= 8 -> h.substring(h.length - 6)
            h.length == 6 -> h
            else -> "FFFFFF"
        }
        return "#$rrggbb"
    }

    /** Current terminal dimensions, for the resize observer in the manager. */
    fun currentSize(): Pair<Int, Int>? {
        val size = resolve()?.display?.termSize?.value ?: return null
        return size.columns to size.rows
    }

    /** Register a new viewer connection and announce updated presence. */
    fun addViewer(canControl: Boolean): ViewerConnection {
        val vc = ViewerConnection(viewerSeq.incrementAndGet(), canControl)
        viewers.add(vc)
        broadcast(ServerMessage.Presence(viewers.size))
        return vc
    }

    /**
     * Write viewer input to the PTY — Phase 2 control. No-op unless [vc] holds the
     * controller role. The shell echo returns through the normal output tap, so all
     * viewers see the typed text.
     */
    fun handleInput(vc: ViewerConnection, data: String) {
        if (!vc.canControl) return
        resolve()?.writeUserInput(data)
    }

    /** Remove a viewer connection (on disconnect) and announce updated presence. */
    fun removeViewer(vc: ViewerConnection) {
        if (viewers.remove(vc)) {
            vc.outbox.close()
            broadcast(ServerMessage.Presence(viewers.size))
        }
    }

    val viewerCount: Int get() = viewers.size

    /** Encode once and fan out to every viewer's bounded outbox (lock-free, non-blocking). */
    fun broadcast(msg: ServerMessage) {
        val text = ShareProtocol.encodeServer(msg)
        for (v in viewers) v.outbox.trySend(text)
    }

    private fun secureToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * A single connected browser viewer. The host pushes pre-encoded JSON frames into
 * [outbox]; the Ktor route's writer coroutine drains it to the WebSocket. Bounded
 * + DROP_OLDEST so a slow/stalled viewer can never apply backpressure to the
 * terminal — it just misses intermediate frames (the next snapshot/refresh heals it).
 */
class ViewerConnection(val id: Int, val canControl: Boolean = false) {
    val outbox: Channel<String> = Channel(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
