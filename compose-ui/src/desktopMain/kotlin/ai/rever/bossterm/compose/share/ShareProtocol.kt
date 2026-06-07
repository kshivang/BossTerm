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

    // Client (native viewer) side — symmetric to the host helpers above.
    fun encodeClient(msg: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), msg)
    fun decodeServer(text: String): ServerMessage = json.decodeFromString(ServerMessage.serializer(), text)

    // ---- E2E key-exchange handshake ([SessionCrypto]) ----
    // The ONLY plaintext frames once a connection is encrypted: client sends its salt, host
    // replies with its salt + a key-confirmation tag. After this both sides derive per-connection
    // AES-GCM keys and every subsequent frame is an encrypted binary frame. Sent/parsed on its own
    // (not a Server/ClientMessage — those become the encrypted payloads).
    fun encodeKex(k: Kex): String = json.encodeToString(Kex.serializer(), k)
    fun decodeKex(text: String): Kex? = runCatching { json.decodeFromString(Kex.serializer(), text) }.getOrNull()

    /**
     * SHA-256 hex of [s]. Used as [TabNode.origin]: identifies which share a mirror tab came
     * from without leaking the share token itself to viewers.
     */
    fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * E2E key-exchange frame ([SessionCrypto]). [v] = protocol version; [salt] = this side's 16-byte
 * HKDF salt (base64url); [confirm] = the host's key-confirmation tag (base64url), null on the
 * client's opening frame. Plaintext — it carries no secret (salts are public; the key lives only
 * in the link fragment).
 */
@Serializable
data class Kex(
    val v: Int = 1,
    val salt: String,
    val confirm: String? = null,
)

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
        /**
         * The host's name for this shared session (defaults to its username; editable in the
         * Share window). Clients use it as the default group label instead of the link's host.
         */
        val sessionName: String? = null,
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
    /**
     * For a tab that itself mirrors another BossTerm session (a remote container): the SHA-256
     * hex of the share token this host dialed ([ShareProtocol.sha256Hex]). Lets a connecting
     * client recognize — and skip — tabs that mirror its OWN session (mirroring them back would
     * loop), without leaking the token to viewers. Null for the host's own tabs.
     */
    val origin: String? = null,
    /**
     * When [origin] != null: a friendly label for that upstream session (the host's custom
     * group name, else its link's host) — lets a nested viewer group these tabs under a
     * labeled subsection instead of mixing them with the host's own tabs.
     */
    val originName: String? = null,
    /**
     * When [origin] != null: true if the HOST itself is view-only on that upstream session —
     * input can't flow through it, so these tabs are effectively read-only for viewers too.
     */
    val originReadOnly: Boolean? = null,
    /**
     * When [origin] != null: true if the host's connection to that upstream is currently down
     * (reconnecting or failed) — these tabs show FROZEN content until it comes back.
     */
    val originOffline: Boolean? = null,
    /**
     * For an ALL-scope share spanning >1 window: the stable key of the window owning this tab
     * ([ai.rever.bossterm.compose.TabbedTerminalState.windowTag]), so viewers can group tabs
     * by window. Null for TAB/WINDOW shares and single-window ALL shares — those frames stay
     * identical to pre-ALL ones, and old clients ignore the field either way.
     */
    val windowId: String? = null,
    /** When [windowId] != null: the display label for that window (e.g. "Window 2"). */
    val windowName: String? = null,
    /**
     * When [origin] != null and the UPSTREAM session shared all ITS windows: the upstream's
     * window key for this tab — so viewers can section a "via host" group per origin window
     * (distinct from [windowId], which is the relaying host's OWN window).
     */
    val originWindowId: String? = null,
    /** When [originWindowId] != null: the upstream window's display label. */
    val originWindowName: String? = null,
)

/** Recursive split-layout node: either a binary split or a leaf pane. */
@Serializable
sealed class PaneTreeNode {
    /**
     * A split: [dir] "v" = side-by-side (left/right at [ratio]); "h" = stacked (top/bottom).
     * [id] is the host split node's stable id, so the viewer can drag the divider and ask the
     * host to update this split's ratio.
     */
    @Serializable
    @SerialName("split")
    data class Split(
        val dir: String,
        val ratio: Float,
        val a: PaneTreeNode,
        val b: PaneTreeNode,
        val id: String = "",
    ) : PaneTreeNode()

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

    /**
     * Request write/control access from the host. With a [tabId] naming a tab the host itself
     * mirrors from ANOTHER session, the host relays the request to that upstream instead
     * (A→B→C: C asks B, B asks A). Null/absent = plain upgrade of this connection (and what
     * older peers send/understand).
     */
    @Serializable
    @SerialName("requestControl")
    data class RequestControl(val tabId: String? = null) : ClientMessage()

    /** Close a tab on the host (controller role only). Mirrors the host tab's close button. */
    @Serializable
    @SerialName("closeTab")
    data class CloseTab(val tabId: String) : ClientMessage()

    /**
     * Open a new tab on the host (controller role only). With a [tabId] naming a tab the host
     * mirrors from another session, the host relays — the new tab opens in that upstream
     * session instead. Null/absent = a local tab on the host (what older peers send).
     */
    @Serializable
    @SerialName("newTab")
    data class NewTab(val tabId: String? = null) : ClientMessage()

    /**
     * Ask the host to disconnect from the upstream session that [tabId] mirrors (the ✕ on a
     * "via host" group in a nested viewer). Controller role only; old hosts ignore it.
     */
    @Serializable
    @SerialName("disconnectUpstream")
    data class DisconnectUpstream(val tabId: String) : ClientMessage()

    /**
     * Close a whole host window — every tab of the window keyed [windowId]
     * ([TabNode.windowId], stamped by ALL-scope shares). The ✕ on a viewer's window
     * box. Controller role only; old hosts ignore it.
     */
    @Serializable
    @SerialName("closeWindow")
    data class CloseWindow(val windowId: String) : ClientMessage()

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

    /**
     * "Fit host to my screen": resize the host so [tabId]'s terminal grid becomes about
     * [cols]×[rows] (what fits the viewer's viewport). The host terminal size is slaved to
     * its window, so the host resizes its OS window to best approximate this. Controller only.
     */
    @Serializable
    @SerialName("resizeHost")
    data class ResizeHost(val tabId: String, val cols: Int, val rows: Int) : ClientMessage()

    /**
     * Drag a split divider: set the split node [splitId] in [tabId] to [ratio] (0..1,
     * fraction of the first child) — mirrors dragging the divider on the host. Controller only.
     */
    @Serializable
    @SerialName("resizeSplit")
    data class ResizeSplit(val tabId: String, val splitId: String, val ratio: Float) : ClientMessage()

    /**
     * Two-way sharing: the connecting client offers its OWN session's share [link] so the host
     * mirrors the client's tabs back (the host dials [link] as a normal remote session — the
     * client's own approval/key flow applies). Controller role only; old hosts ignore it.
     */
    @Serializable
    @SerialName("offerShare")
    data class OfferShare(val link: String) : ClientMessage()
}
