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
    data class Layout(
        val tabs: List<TabNode>,
        val activeTabId: String?,
        /** Host's tab-bar orientation, so the viewer mirrors it: true = left (vertical), false = top. */
        val tabBarOnLeft: Boolean = false,
        /**
         * Host's `tabBarSummaryMode`: true = one chip per tab (active pane, tab title);
         * false (default) = one chip per split pane (the viewer shows per-pane sub-tabs).
         */
        val summaryMode: Boolean = false,
    ) : ServerMessage()

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

    /** Host requires approval and this viewer's request is awaiting the host's decision. */
    @Serializable
    @SerialName("pending")
    data object Pending : ServerMessage()

    /**
     * The host approved this device: a per-device access [key] valid until [expiresAt]
     * (epoch ms). The viewer persists it and replays it on reconnect to skip re-approval;
     * the host slides [expiresAt] forward on each accepted use (24h rolling window).
     */
    @Serializable
    @SerialName("grant")
    data class Grant(val key: String, val expiresAt: Long, val control: Boolean) : ServerMessage()

    /** The host denied the request (or it timed out / the key expired). */
    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String? = null) : ServerMessage()

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

/**
 * One tab in the [ServerMessage.Layout]: id, title, whether active, and its split tree.
 * [color] (CSS, e.g. "#E06C75"), [cwd], and [branch] mirror the host's tab-chip styling
 * (accent stripe + the left bar's cwd/branch lines); all optional.
 */
@Serializable
data class TabNode(
    val id: String,
    val title: String,
    val active: Boolean,
    val tree: PaneTreeNode,
    val color: String? = null,
    val cwd: String? = null,
    val branch: String? = null,
)

/** Recursive split-layout node: either a binary split or a leaf pane. */
@Serializable
sealed class PaneTreeNode {
    /** A split: [dir] "v" = side-by-side (left/right at [ratio]); "h" = stacked (top/bottom). */
    @Serializable
    @SerialName("split")
    data class Split(val dir: String, val ratio: Float, val a: PaneTreeNode, val b: PaneTreeNode) : PaneTreeNode()

    /**
     * A leaf terminal pane. [color] (CSS accent) and [branch] (git branch) mirror the
     * host's per-pane chip styling in the left bar's per-split sub-tabs; both optional.
     */
    @Serializable
    @SerialName("pane")
    data class Pane(
        val paneId: String,
        val title: String,
        val cwd: String?,
        val focused: Boolean,
        val color: String? = null,
        val branch: String? = null,
    ) : PaneTreeNode()
}

/** Viewer → host messages. */
@Serializable
sealed class ClientMessage {
    /**
     * Handshake. [name] is a display label for the host's approval prompt; [clientId]
     * is a stable per-browser id (localStorage) so a device is recognized across
     * reconnects; [key] is a previously granted access key replayed to skip re-approval.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val name: String? = null,
        val clientId: String? = null,
        val key: String? = null,
    ) : ClientMessage()

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

    /** Close a tab on the host (controller role only). Mirrors the host tab's close button. */
    @Serializable
    @SerialName("closeTab")
    data class CloseTab(val tabId: String) : ClientMessage()

    /** Open a new tab on the host (controller role only). Mirrors the host's new-tab (+) button. */
    @Serializable
    @SerialName("newTab")
    data object NewTab : ClientMessage()

    /**
     * Split [paneId] in [tabId] into left/right panes (vertical divider) — the host's
     * "Split Left/Right". Controller role only.
     */
    @Serializable
    @SerialName("splitVertical")
    data class SplitVertical(val tabId: String, val paneId: String) : ClientMessage()

    /**
     * Split [paneId] in [tabId] into top/bottom panes (horizontal divider) — the host's
     * "Split Top/Bottom". Controller role only.
     */
    @Serializable
    @SerialName("splitHorizontal")
    data class SplitHorizontal(val tabId: String, val paneId: String) : ClientMessage()

    /** Close [paneId] in [tabId]; closes the tab if it's the last pane (controller role only). */
    @Serializable
    @SerialName("closePane")
    data class ClosePane(val tabId: String, val paneId: String) : ClientMessage()

    /**
     * Launch an AI assistant (by [assistantId], e.g. "claude-code") in [paneId] of [tabId]
     * — mirrors the host's AI-assistant menu, running the same configured launch command
     * (incl. the user's YOLO/auto-mode setting). Controller role only.
     */
    @Serializable
    @SerialName("launchAI")
    data class LaunchAI(val tabId: String, val paneId: String, val assistantId: String) : ClientMessage()

    /**
     * Rename a tab/pane chip ([paneId] == [tabId] for a tab-level chip). A blank [title]
     * clears the custom title (reverts to the cwd-derived one). Controller role only.
     */
    @Serializable
    @SerialName("renameTab")
    data class RenameTab(val tabId: String, val paneId: String, val title: String) : ClientMessage()

    /**
     * Set ([color] = CSS "#RRGGBB") or clear ([color] = null) a chip's accent, mirroring
     * the host chip menu's Color ▸ presets / Clear. Controller role only.
     */
    @Serializable
    @SerialName("setTabColor")
    data class SetTabColor(val tabId: String, val paneId: String, val color: String? = null) : ClientMessage()

    /** Duplicate [tabId] into a new tab in the same cwd ("Duplicate Tab"). Controller role only. */
    @Serializable
    @SerialName("duplicateTab")
    data class DuplicateTab(val tabId: String) : ClientMessage()

    /** Close every tab except [tabId] ("Close Other Tabs"). Controller role only. */
    @Serializable
    @SerialName("closeOtherTabs")
    data class CloseOtherTabs(val tabId: String) : ClientMessage()

    /** Close all tabs after [tabId] ("Close Tabs Below"). Controller role only. */
    @Serializable
    @SerialName("closeTabsBelow")
    data class CloseTabsBelow(val tabId: String) : ClientMessage()
}
