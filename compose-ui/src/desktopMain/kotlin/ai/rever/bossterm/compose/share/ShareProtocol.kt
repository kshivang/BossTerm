package ai.rever.bossterm.compose.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol for BossTerm session sharing (issue #276).
 *
 * JSON messages over a WebSocket between the host and a browser viewer. Independent
 * protocol — Warp's AGPLv3 `session-sharing-protocol` is only a design reference.
 *
 * The model is **window → tabs → panes**: a *tab* share is a one-tab window; a
 * *window* share is all tabs. The host sends a [ServerMessage.Layout] (tabs + split
 * trees) plus, per pane, a one-time [ServerMessage.PaneSnapshot] then live
 * [ServerMessage.PaneOutput]; the browser re-emulates each pane with xterm.js, lays
 * out splits per the tree, and switches tabs client-side. Control (Phase 2) routes
 * [ClientMessage.Input] to a specific pane.
 *
 * Backward-compat: add new fields with defaults so older peers tolerate them.
 */
object ShareProtocol {
    val json: Json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeServer(msg: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), msg)
    fun decodeClient(text: String): ClientMessage = json.decodeFromString(ClientMessage.serializer(), text)
}

/** Host → viewer messages. */
@Serializable
sealed class ServerMessage {
    /** The window layout: tabs + their split trees + which tab is active. Resent on change. */
    @Serializable
    @SerialName("layout")
    data class Layout(val tabs: List<TabNode>, val activeTabId: String?) : ServerMessage()

    /** One-time initial paint for a pane: scrollback+screen as a raw escape/text blob. */
    @Serializable
    @SerialName("paneSnapshot")
    data class PaneSnapshot(val paneId: String, val data: String, val cols: Int, val rows: Int) : ServerMessage()

    /** Incremental raw PTY output for a pane. */
    @Serializable
    @SerialName("paneOutput")
    data class PaneOutput(val paneId: String, val data: String) : ServerMessage()

    /** A pane's terminal dimensions changed. */
    @Serializable
    @SerialName("paneResize")
    data class PaneResize(val paneId: String, val cols: Int, val rows: Int) : ServerMessage()

    /** Number of connected viewers. */
    @Serializable
    @SerialName("presence")
    data class Presence(val viewers: Int) : ServerMessage()

    /** Whether this viewer has write/control access. */
    @Serializable
    @SerialName("control")
    data class Control(val granted: Boolean) : ServerMessage()

    /** Host terminal theme (core colors + 16 ANSI + font) so the viewer matches BossTerm. */
    @Serializable
    @SerialName("theme")
    data class Theme(
        val background: String,
        val foreground: String,
        val cursor: String,
        val cursorAccent: String,
        val selectionBackground: String,
        val ansi: List<String>,
        val fontFamily: String,
        val fontSize: Int,
    ) : ServerMessage()
}

/** One tab in the [ServerMessage.Layout]: id, title, whether active, and its split tree. */
@Serializable
data class TabNode(val id: String, val title: String, val active: Boolean, val tree: PaneTreeNode)

/** Recursive split-layout node: either a binary split or a leaf pane. */
@Serializable
sealed class PaneTreeNode {
    /** A split: [dir] "v" = side-by-side (left/right at [ratio]); "h" = stacked (top/bottom). */
    @Serializable
    @SerialName("split")
    data class Split(val dir: String, val ratio: Float, val a: PaneTreeNode, val b: PaneTreeNode) : PaneTreeNode()

    /** A leaf terminal pane. */
    @Serializable
    @SerialName("pane")
    data class Pane(val paneId: String, val title: String, val cwd: String?, val focused: Boolean) : PaneTreeNode()
}

/** Viewer → host messages. */
@Serializable
sealed class ClientMessage {
    /** Handshake with an optional display name. */
    @Serializable
    @SerialName("hello")
    data class Hello(val name: String? = null) : ClientMessage()

    /** Keystrokes for a specific pane. Honored only with controller role. */
    @Serializable
    @SerialName("input")
    data class Input(val paneId: String, val data: String) : ClientMessage()

    /** Viewer focused a pane (so the host can reflect focus). */
    @Serializable
    @SerialName("focus")
    data class Focus(val tabId: String, val paneId: String) : ClientMessage()

    /** Request write/control access from the host. */
    @Serializable
    @SerialName("requestControl")
    data object RequestControl : ClientMessage()
}
