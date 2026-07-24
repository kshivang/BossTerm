package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.abs

/** Fixed height of the [TabBar] surface; referenced by overlays that must clear it. */
val TabBarHeight: Dp = 48.dp

/** Default width of the [TabBar] surface when rendered vertically (left position). */
val TabBarVerticalWidth: Dp = 180.dp

/** Width of the vertical bar when collapsed to the slim icon rail. */
val TabBarRailWidth: Dp = 44.dp

/**
 * Window width below which the vertical bar auto-collapses to the rail and the full
 * bar becomes an overlay drawer — mirrors the share-viewer's 700px phone breakpoint.
 */
val TabBarAutoCollapseWidth: Dp = 700.dp

/** Orientation of the tab bar: across the top (default) or down the left side. */
enum class TabBarOrientation { TOP, LEFT }

/** Gap between tab-groups (each group = one tab's panes). Larger than within a group. */
private val TabGroupGap: Dp = 18.dp

/** Accent for remote (mirrored) sessions — the box border + tab-chip color. */
private val RemoteAccent: Color = Color(0xFF4FC3F7)

/** Gap between pane chips within the same tab-group. Tight, so they read as one cluster. */
private val TabChipGap: Dp = 3.dp

/**
 * Consume secondary presses before click gestures can treat them as primary actions.
 * An optional callback supports controls that also open a context menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.consumeSecondaryPress(onSecondaryPress: (() -> Unit)? = null): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        if (event.button == PointerButton.Secondary) {
            event.changes.forEach { it.consume() }
            onSecondaryPress?.invoke()
        }
    }

/**
 * Preset accent colors offered in the chip "Color" submenu (Warp-style).
 * Stored as ARGB hex ("0xAARRGGBB") to match [ai.rever.bossterm.compose.settings.TerminalSettings].
 */
internal val TAB_COLOR_PRESETS: List<Pair<String, String>> = listOf(
    "Red" to "0xFFE06C75",
    "Orange" to "0xFFD19A66",
    "Yellow" to "0xFFE5C07B",
    "Green" to "0xFF98C379",
    "Blue" to "0xFF61AFEF",
    "Purple" to "0xFFC678DD",
    "Gray" to "0xFF888888"
)

/** Parse an ARGB hex string ("0xAARRGGBB") to a [Color], or null if malformed/blank. */
internal fun parseTabColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching { Color(hex.removePrefix("0x").toULong(16).toLong()) }.getOrNull()
}

/**
 * One pane shown as a chip in the tab bar. [colorHex] is the resolved accent
 * (manual or auto). [subtitle] (abbreviated cwd) and [branch] (git branch) are
 * the second and third lines shown on the vertical (left) bar's Warp-style chips;
 * both are ignored by the single-line top bar. [isGitRepo] gates repository-only
 * actions in the local chip menu; null means repository detection is pending.
 */
data class TabBarPane(
    val paneId: String,
    val title: String,
    val colorHex: String? = null,
    val subtitle: String? = null,
    val branch: String? = null,
    val isGitRepo: Boolean? = null
)

/** Keep worktree creation optimistic while repository detection is still pending. */
internal fun canCreateWorktree(isGitRepo: Boolean?): Boolean = isGitRepo != false

/** Return the nearest resting tab center; exact midpoint ties favor the first visual group. */
internal fun nearestTabIndex(pointerY: Float, tabCenters: List<Pair<Int, Float>>): Int? {
    return tabCenters.minByOrNull { (_, centerY) -> abs(centerY - pointerY) }?.first
}

/** A tab and its panes, rendered as a visually-grouped cluster of chips. */
data class TabBarGroup(val tabIndex: Int, val panes: List<TabBarPane>)

/**
 * A connected remote BossTerm session, rendered (in the left bar) as a bordered box: a
 * [header] (a custom name, else the remote link's host), the session's mirrored [groups]
 * (its tabs), and a footer of actions that target the remote (split + new tab + disconnect).
 *
 * A right-click on any mirrored chip opens the same host-routed menu the browser viewer shows
 * (new tab / split / AI assistant / rename / color / duplicate / close…). [canControl] gates the
 * host-mutating items; [onChipSplit]/[onChipLaunchAI] act on the clicked pane (the rest reuse the
 * shared TabBar callbacks, which route to the host for remote chips).
 *
 * A right-click on the box HEADER customizes the group locally: [onRename] sets the header
 * (inline edit; blank reverts to the host name), [onSetColor] sets [colorHex], the box's
 * border/icon/chip accent (null reverts to the default remote cyan).
 */
data class RemoteTabGroup(
    val id: String,
    val header: String,
    val colorHex: String?,
    val groups: List<TabBarGroup>,
    val canControl: Boolean,
    /** Connection state shown next to the header when not healthy (e.g. "connecting…",
     *  "disconnected"); null = connected. [statusError] picks red over amber. */
    val statusLabel: String? = null,
    val statusError: Boolean = false,
    val onSplitVertical: () -> Unit,
    val onSplitHorizontal: () -> Unit,
    val onNewTab: () -> Unit,
    val onDisconnect: () -> Unit,
    val onChipSplit: (tabIndex: Int, paneId: String, horizontal: Boolean) -> Unit,
    val onChipLaunchAI: (tabIndex: Int, paneId: String, assistantId: String) -> Unit,
    val onRename: (String) -> Unit,
    val onSetColor: (String?) -> Unit,
    /** Open this remote's share link in the default browser (the web viewer). */
    val onOpenInBrowser: () -> Unit,
    /** Copy this remote's share link to the clipboard. */
    val onCopyLink: () -> Unit,
    /** Ask the host to upgrade this view-only connection to control (host approves via toast). */
    val onRequestControl: () -> Unit,
    /**
     * Tabs the host itself mirrors from OTHER sessions, nested as labeled subsections inside
     * this box (instead of mixing with the host's own tabs). [RemoteNestedGroup.readOnly]
     * means the host is view-only on that upstream — input can't flow through it.
     */
    val nested: List<RemoteNestedGroup> = emptyList(),
    /**
     * For a host sharing ALL its windows: the host's own tabs grouped per host window,
     * rendered as labeled sub-sections (like the web viewer's window boxes). Empty =
     * single-window host, [groups] renders flat. The sections' groups union == [groups].
     */
    val windowSections: List<RemoteWindowSection> = emptyList(),
    /**
     * This remote host's MCP state, when it reported one — drives the small "MCP" pill in the
     * group header (green dot = the remote's MCP server is running). [onMcpClick] opens the
     * toggle/attach menu (control-gated by the owner). Null/false = no pill.
     */
    val mcpShown: Boolean = false,
    val mcpRunning: Boolean = false,
    val onMcpClick: () -> Unit = {},
)

/**
 * One host-window sub-section inside a remote group box (see [RemoteTabGroup.windowSections]).
 * Each section carries its own split/new-tab actions targeting THAT host window (replacing
 * the group-level footer, which would only ever hit the host's anchor window).
 */
data class RemoteWindowSection(
    val label: String,
    val groups: List<TabBarGroup>,
    val onSplitVertical: () -> Unit = {},
    val onSplitHorizontal: () -> Unit = {},
    val onNewTab: () -> Unit = {},
)

/**
 * One upstream session's tabs inside a remote group box (see [RemoteTabGroup.nested]).
 * Actions are relayed by the host to the origin session; when [readOnly] (the host is
 * view-only on the origin), split/new-tab instead fire [onRequestControl] so the user is
 * routed to the upgrade path rather than a silent no-op.
 */
data class RemoteNestedGroup(
    val label: String,
    val readOnly: Boolean,
    /** The host's connection to this upstream is down — the tabs show frozen content. */
    val offline: Boolean = false,
    val groups: List<TabBarGroup>,
    val onSplitVertical: () -> Unit = {},
    val onSplitHorizontal: () -> Unit = {},
    val onNewTab: () -> Unit = {},
    /** Ask the host to disconnect from this upstream (the box's ✕). */
    val onClose: () -> Unit = {},
    /** Relay a control request to the origin (host asks it on our behalf). */
    val onRequestControl: () -> Unit = {},
    /**
     * The ORIGIN shared all its windows: its tabs sectioned per origin window (sub-title +
     * per-window actions), like [RemoteTabGroup.windowSections]. Empty = flat + one footer.
     */
    val windowSections: List<RemoteWindowSection> = emptyList(),
)

/** AI assistants offered in the remote chip menu — same set the browser viewer mirrors. */
private val REMOTE_AI_ASSISTANTS = listOf(
    "claude-code" to "Claude Code",
    "codex" to "Codex",
    "gemini-cli" to "Gemini CLI",
    "opencode" to "OpenCode",
)

/**
 * Tab bar component for multiple terminal sessions.
 *
 * Displays a strip (top) or column (left) of pane-chips grouped by tab:
 * - Tab titles (cwd-derived, or a user rename), with ellipsis for long names
 * - Optional per-tab accent stripe (manual color or auto-by-directory)
 * - Close button per chip (X)
 * - New tab button (+)
 * - Active/focused pane highlighting
 * - Right-click context menu: Create Worktree for This…, Rename…, Color ▸,
 *   Duplicate, Close, Close Others, Close Tabs Below, Move Tab to New Window
 * - Drag a local tab group in the expanded left sidebar to reorder tabs
 *
 * Styling matches the Material 3 design of the search bar for visual consistency.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun TabBar(
    groups: List<TabBarGroup>,
    activeTabIndex: Int,
    focusedPaneId: String?,
    onPaneSelected: (tabIndex: Int, paneId: String) -> Unit,
    onPaneClosed: (tabIndex: Int, paneId: String) -> Unit,
    onNewTab: () -> Unit,
    onTabReordered: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onTabMoveToNewWindow: (Int) -> Unit = {},
    onRename: (tabIndex: Int, paneId: String, newTitle: String) -> Unit = { _, _, _ -> },
    onSetColor: (tabIndex: Int, paneId: String, hex: String?) -> Unit = { _, _, _ -> },
    onCloseOthers: (Int) -> Unit = {},
    onCloseBelow: (Int) -> Unit = {},
    onDuplicate: (Int) -> Unit = {},
    onCreateWorktree: (tabIndex: Int, paneId: String) -> Unit = { _, _ -> },
    onShareTab: (Int) -> Unit = {},
    onShareWindow: (Int) -> Unit = {},
    onShareAll: (Int) -> Unit = {},
    /** Account sign-in (4th Share-submenu item). Null hides it (e.g. embedded builds). */
    onSignIn: (() -> Unit)? = null,
    /** Label for the sign-in item — the account email once signed in, else "Sign In…". */
    signInLabel: String = "Sign In…",
    onStopShare: (Int) -> Unit = {},
    isSharing: (Int) -> Boolean = { false },
    onSplitVertical: () -> Unit = {},
    onSplitHorizontal: () -> Unit = {},
    onSettings: () -> Unit = {},
    onAddRemote: () -> Unit = {},
    remoteGroups: List<RemoteTabGroup> = emptyList(),
    orientation: TabBarOrientation = TabBarOrientation.TOP,
    verticalWidth: Dp = TabBarVerticalWidth,
    /** Vertical bar only: render as a slim icon rail ([TabBarRailWidth]) instead of the full panel. */
    collapsed: Boolean = false,
    /** Vertical bar only: collapse/expand chevron next to "Add remote" (and atop the rail). Null hides it. */
    onToggleCollapse: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Context menu controller for chip right-click menu
    val contextMenuController = remember { ContextMenuController() }
    val vertical = orientation == TabBarOrientation.LEFT

    // Pane currently being renamed inline (null = none). Set by the "Rename…"
    // context-menu item and cleared on commit/cancel.
    var editingPaneId by remember { mutableStateOf<String?>(null) }
    var draggedTabIndex by remember { mutableStateOf<Int?>(null) }
    var draggedTabOffsetY by remember { mutableStateOf(0f) }
    var primaryPressedTabIndex by remember { mutableStateOf<Int?>(null) }
    val localTabOrder = groups.map { it.tabIndex }
    // Full-list indices are reassigned after closes and remote-layout updates, so replace
    // the measurement map whenever the set of local slots changes.
    val localTabBounds = remember(localTabOrder) {
        mutableMapOf<Int, androidx.compose.ui.geometry.Rect>()
    }
    val latestOnTabReordered by rememberUpdatedState(onTabReordered)
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    val resetTabDrag: () -> Unit = {
        primaryPressedTabIndex = null
        draggedTabIndex = null
        draggedTabOffsetY = 0f
    }
    val finishTabDrag: (Int) -> Unit = { sourceIndex ->
        val targetIndex = if (draggedTabIndex == sourceIndex) {
            localTabBounds[sourceIndex]?.let { bounds ->
                // graphicsLayer translation is draw-only, so resting centers intentionally
                // provide a simple static snap target without a live insertion preview.
                nearestTabIndex(
                    pointerY = bounds.center.y + draggedTabOffsetY,
                    tabCenters = groups.mapNotNull { candidate ->
                        localTabBounds[candidate.tabIndex]?.center?.y?.let { centerY ->
                            candidate.tabIndex to centerY
                        }
                    }
                )
            }
        } else {
            null
        }
        resetTabDrag()
        if (targetIndex != null && targetIndex != sourceIndex) {
            latestOnTabReordered(sourceIndex, targetIndex)
        }
    }

    LaunchedEffect(isWindowFocused) {
        if (!isWindowFocused) resetTabDrag()
    }

    val localGitRepoByPane = remember(groups) {
        groups.flatMap { group ->
            group.panes.map { pane -> (group.tabIndex to pane.paneId) to pane.isGitRepo }
        }.toMap()
    }

    val showChipMenu: (Int, String) -> Unit = { tabIndex, paneId ->
        val colorSubmenu = ContextMenuController.MenuSubmenu(
            id = "tab_color",
            label = "Color",
            items = TAB_COLOR_PRESETS.map { (name, hex) ->
                ContextMenuController.MenuItem(id = "color_$name", label = name, enabled = true, action = { onSetColor(tabIndex, paneId, hex) })
            } + ContextMenuController.MenuSeparator(id = "separator_color") +
                ContextMenuController.MenuItem(id = "color_clear", label = "Clear", enabled = true, action = { onSetColor(tabIndex, paneId, null) })
        )
        val items = listOf(
            ContextMenuController.MenuItem(id = "new_tab", label = "New Tab", enabled = true, action = { onNewTab() }),
            ContextMenuController.MenuItem(
                id = "create_worktree",
                label = "Create Worktree for This…",
                enabled = canCreateWorktree(localGitRepoByPane[tabIndex to paneId]),
                action = { onCreateWorktree(tabIndex, paneId) }
            ),
            ContextMenuController.MenuSeparator(id = "separator_worktree"),
            ContextMenuController.MenuItem(id = "rename_tab", label = "Rename…", enabled = true, action = { editingPaneId = paneId }),
            colorSubmenu,
            ContextMenuController.MenuSeparator(id = "separator_tab_ops"),
            ContextMenuController.MenuItem(id = "duplicate_tab", label = "Duplicate Tab", enabled = true, action = { onDuplicate(tabIndex) }),
            ContextMenuController.MenuItem(id = "close_pane", label = "Close", enabled = true, action = { onPaneClosed(tabIndex, paneId) }),
            ContextMenuController.MenuItem(id = "close_others", label = "Close Other Tabs", enabled = true, action = { onCloseOthers(tabIndex) }),
            ContextMenuController.MenuItem(id = "close_below", label = "Close Tabs Below", enabled = true, action = { onCloseBelow(tabIndex) }),
            ContextMenuController.MenuItem(id = "move_to_new_window", label = "Move Tab to New Window", enabled = true, action = { onTabMoveToNewWindow(tabIndex) }),
            ContextMenuController.MenuSeparator(id = "separator_share"),
        ) + if (isSharing(tabIndex)) {
            listOf(ContextMenuController.MenuItem(id = "stop_share", label = "Stop Sharing", enabled = true, action = { onStopShare(tabIndex) }))
        } else {
            listOf(
                ContextMenuController.MenuSubmenu(
                    id = "share_submenu",
                    label = "Share",
                    items = listOf(
                        ContextMenuController.MenuItem(id = "share_tab", label = "Tab…", enabled = true, action = { onShareTab(tabIndex) }),
                        ContextMenuController.MenuItem(id = "share_window", label = "Window…", enabled = true, action = { onShareWindow(tabIndex) }),
                        ContextMenuController.MenuItem(id = "share_all", label = "All Windows…", enabled = true, action = { onShareAll(tabIndex) })
                    ) + (onSignIn?.let { signIn ->
                        listOf(ContextMenuController.MenuItem(id = "sign_in", label = signInLabel, enabled = true, action = { signIn() }))
                    } ?: emptyList())
                )
            )
        }
        contextMenuController.showMenu(0f, 0f, items)
    }

    // Map each mirrored chip's tabIndex → its remote session (and its upstream nest, when the
    // chip lives in a "via host" box), so a right-click opens the right host-routed menu
    // instead of the local one.
    val remoteByTabIndex: Map<Int, Pair<RemoteTabGroup, RemoteNestedGroup?>> =
        remoteGroups.flatMap { rg ->
            rg.groups.map { it.tabIndex to (rg to (null as RemoteNestedGroup?)) } +
                rg.nested.flatMap { nest -> nest.groups.map { it.tabIndex to (rg to nest) } }
        }.toMap()

    // Remote group box currently renaming its header inline (by RemoteTabGroup.id).
    var editingRemoteId by remember { mutableStateOf<String?>(null) }

    // Right-click menu on a remote group's HEADER — local customization of the box
    // (name + accent color) plus Disconnect. Nothing here touches the host.
    val showRemoteGroupMenu: (RemoteTabGroup) -> Unit = { rg ->
        val colorSubmenu = ContextMenuController.MenuSubmenu(
            id = "remote_group_color",
            label = "Color",
            items = TAB_COLOR_PRESETS.map { (name, hex) ->
                ContextMenuController.MenuItem(id = "rg_color_$name", label = name, enabled = true, action = { rg.onSetColor(hex) })
            } + ContextMenuController.MenuSeparator(id = "rg_color_sep") +
                ContextMenuController.MenuItem(id = "rg_color_clear", label = "Clear", enabled = true, action = { rg.onSetColor(null) })
        )
        // View-only connections get a control-upgrade request at the top (host approves it
        // via the same toast as join requests).
        val requestControlItem = if (!rg.canControl) listOf(
            ContextMenuController.MenuItem(id = "rg_request_control", label = "Request Control", enabled = true, action = { rg.onRequestControl() }),
            ContextMenuController.MenuSeparator(id = "rg_sep_control"),
        ) else emptyList()
        contextMenuController.showMenu(0f, 0f, requestControlItem + listOf(
            ContextMenuController.MenuItem(id = "rg_rename", label = "Rename…", enabled = true, action = { editingRemoteId = rg.id }),
            colorSubmenu,
            ContextMenuController.MenuItem(id = "rg_open_browser", label = "Open in Browser", enabled = true, action = { rg.onOpenInBrowser() }),
            ContextMenuController.MenuItem(id = "rg_copy_link", label = "Copy Link", enabled = true, action = { rg.onCopyLink() }),
            ContextMenuController.MenuSeparator(id = "rg_sep"),
            ContextMenuController.MenuItem(id = "rg_disconnect", label = "Disconnect remote", enabled = true, action = { rg.onDisconnect() }),
        ))
    }

    // Right-click menu for a mirrored remote chip — mirrors the browser viewer's menu, all
    // routed to the host. Host-mutating items are gated on control; "Disconnect remote" is the
    // one native-only affordance and is always enabled.
    val showRemoteChipMenu: (RemoteTabGroup, RemoteNestedGroup?, Int, String) -> Unit = { rg, nest, tabIndex, paneId ->
        if (!rg.canControl) {
            // View-only: every chip action mutates the host, so offer just the upgrade path
            // and the local disconnect instead of a wall of disabled items.
            contextMenuController.showMenu(0f, 0f, listOf(
                ContextMenuController.MenuItem(id = "remote_request_control", label = "Request Control", enabled = true, action = { rg.onRequestControl() }),
                ContextMenuController.MenuSeparator(id = "remote_sep_view"),
                ContextMenuController.MenuItem(id = "remote_disconnect", label = "Disconnect remote", enabled = true, action = { rg.onDisconnect() }),
            ))
        } else if (nest?.readOnly == true) {
            // Upstream read-only (A→B→C): actions would die at the host — lean menu with the
            // relayed control request (the host asks the origin on our behalf).
            contextMenuController.showMenu(0f, 0f, listOf(
                ContextMenuController.MenuItem(id = "remote_request_upstream", label = "Request Control", enabled = true, action = { nest.onRequestControl() }),
            ))
        } else {
        val ctl = rg.canControl
        val aiSubmenu = ContextMenuController.MenuSubmenu(
            id = "remote_ai",
            label = "AI assistant",
            items = REMOTE_AI_ASSISTANTS.map { (id, label) ->
                ContextMenuController.MenuItem(id = "remote_ai_$id", label = label, enabled = ctl,
                    action = { rg.onChipLaunchAI(tabIndex, paneId, id) })
            }
        )
        val colorSubmenu = ContextMenuController.MenuSubmenu(
            id = "remote_color",
            label = "Color",
            items = TAB_COLOR_PRESETS.map { (name, hex) ->
                ContextMenuController.MenuItem(id = "remote_color_$name", label = name, enabled = ctl, action = { onSetColor(tabIndex, paneId, hex) })
            } + ContextMenuController.MenuSeparator(id = "remote_color_sep") +
                ContextMenuController.MenuItem(id = "remote_color_clear", label = "Clear", enabled = ctl, action = { onSetColor(tabIndex, paneId, null) })
        )
        val items = listOf(
            ContextMenuController.MenuItem(id = "remote_new_tab", label = "New Tab", enabled = ctl, action = { rg.onNewTab() }),
            ContextMenuController.MenuItem(id = "remote_split_v", label = "Split Left/Right", enabled = ctl, action = { rg.onChipSplit(tabIndex, paneId, false) }),
            ContextMenuController.MenuItem(id = "remote_split_h", label = "Split Top/Bottom", enabled = ctl, action = { rg.onChipSplit(tabIndex, paneId, true) }),
            aiSubmenu,
            ContextMenuController.MenuSeparator(id = "remote_sep_rename"),
            ContextMenuController.MenuItem(id = "remote_rename", label = "Rename…", enabled = ctl, action = { editingPaneId = paneId }),
            colorSubmenu,
            ContextMenuController.MenuSeparator(id = "remote_sep_close"),
            ContextMenuController.MenuItem(id = "remote_duplicate", label = "Duplicate Tab", enabled = ctl, action = { onDuplicate(tabIndex) }),
            ContextMenuController.MenuItem(id = "remote_close", label = "Close", enabled = ctl, action = { onPaneClosed(tabIndex, paneId) }),
            ContextMenuController.MenuItem(id = "remote_close_others", label = "Close Other Tabs", enabled = ctl, action = { onCloseOthers(tabIndex) }),
            ContextMenuController.MenuItem(id = "remote_close_below", label = "Close Tabs Below", enabled = ctl, action = { onCloseBelow(tabIndex) }),
            ContextMenuController.MenuSeparator(id = "remote_sep_disconnect"),
            ContextMenuController.MenuItem(id = "remote_disconnect", label = "Disconnect remote", enabled = true, action = { rg.onDisconnect() }),
        )
        contextMenuController.showMenu(0f, 0f, items)
        }
    }

    val showMenuFor: (Int, String) -> Unit = { tabIndex, paneId ->
        val remote = remoteByTabIndex[tabIndex]
        if (remote != null) showRemoteChipMenu(remote.first, remote.second, tabIndex, paneId)
        else showChipMenu(tabIndex, paneId)
    }

    // Tab-bar chrome follows the active terminal theme, so the left panel
    // re-styles live when the theme/palette is switched (collectAsState recomposes).
    val tabBarTheme by ThemeManager.instance.currentTheme.collectAsState()
    val barBg = tabBarTheme.backgroundColorValue
    val barFg = tabBarTheme.foregroundColor
    val barMuted = barFg.copy(alpha = 0.62f)
    val barDivider = barFg.copy(alpha = 0.14f)

    val newTabButton: @Composable () -> Unit = {
        IconButton(onClick = onNewTab, modifier = Modifier.size(36.dp)) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "New Tab", tint = barFg)
        }
    }

    // A single compact action button for the left bar's top toolbar.
    val barButton: @Composable (ImageVector, String, () -> Unit) -> Unit = { icon, desc, onClick ->
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(30.dp)
                .consumeSecondaryPress()
        ) {
            Icon(imageVector = icon, contentDescription = desc, tint = barMuted, modifier = Modifier.size(16.dp))
        }
    }

    // Action toolbar for the vertical tab bar: Settings, Split L/R, Split T/B, Share, New Tab.
    val actionBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            barButton(Icons.Default.Settings, "Settings", onSettings)
            barButton(Icons.Default.VerticalSplit, "Split Left/Right", onSplitVertical)
            barButton(Icons.Default.HorizontalSplit, "Split Top/Bottom", onSplitHorizontal)
            barButton(Icons.Default.QrCode2, "Share Window (QR)", { onShareWindow(activeTabIndex) })
            barButton(Icons.Default.Add, "New Tab", onNewTab)
        }
    }

    // One chip per pane. Panes of the same tab are clustered together (TabChipGap);
    // separate tabs are spaced further apart (TabGroupGap). The focused pane of the
    // active tab is highlighted.
    val chip: @Composable (TabBarGroup, TabBarPane, Modifier) -> Unit = { group, pane, chipModifier ->
        TabItem(
            title = pane.title,
            subtitle = pane.subtitle,
            branch = pane.branch,
            multiLine = vertical,
            tabTheme = tabBarTheme,
            isActive = group.tabIndex == activeTabIndex && pane.paneId == focusedPaneId,
            colorHex = pane.colorHex,
            isEditing = pane.paneId == editingPaneId,
            onSelected = { onPaneSelected(group.tabIndex, pane.paneId) },
            onCommitRename = { newTitle ->
                editingPaneId = null
                onRename(group.tabIndex, pane.paneId, newTitle)
            },
            onCancelRename = { editingPaneId = null },
            onClose = { onPaneClosed(group.tabIndex, pane.paneId) },
            onContextMenu = { showMenuFor(group.tabIndex, pane.paneId) },
            modifier = chipModifier
        )
    }

    // Right-clicking empty sidebar chrome targets the active pane. Child controls consume
    // their own secondary presses, so the Final-pass handler below only handles background.
    val showActivePaneMenu: () -> Unit = {
        val allGroups = groups + remoteGroups.flatMap { it.groups + it.nested.flatMap { n -> n.groups } }
        val group = allGroups.firstOrNull { it.tabIndex == activeTabIndex }
        val pane = group?.panes?.firstOrNull { it.paneId == focusedPaneId } ?: group?.panes?.firstOrNull()
        if (group != null && pane != null) {
            showMenuFor(group.tabIndex, pane.paneId)
        }
    }

    Surface(
        modifier = modifier
            .then(
                if (vertical) Modifier.fillMaxHeight().width(if (collapsed) TabBarRailWidth else verticalWidth)
                else Modifier.fillMaxWidth().height(TabBarHeight)
            )
            .then(
                if (vertical) {
                    Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Final) { event ->
                        if (event.button == PointerButton.Secondary && event.changes.none { it.isConsumed }) {
                            event.changes.forEach { it.consume() }
                            showActivePaneMenu()
                        }
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (vertical) {
                    Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                        // A fresh press is the fallback cleanup for a release that was lost
                        // without a focus change. Leaving the narrow sidebar must not cancel
                        // an otherwise valid in-flight drag.
                        if (draggedTabIndex != null) resetTabDrag()
                    }
                } else {
                    Modifier
                }
            ),
        color = barBg,
        shadowElevation = 2.dp
    ) {
        if (vertical && collapsed) {
            // Slim icon rail: expand chevron on top, one accent dot per pane (click to
            // focus, tooltip for the title), "Add remote" pinned at the bottom.
            Column(
                modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                onToggleCollapse?.let { toggle ->
                    barButton(Icons.Default.ChevronRight, "Expand sidebar", toggle)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.7f).height(1.dp).background(barDivider))
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(TabGroupGap / 2),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Same flattened ordering as the top bar: local tabs, then remote mirrors.
                    (groups + remoteGroups.flatMap { it.groups + it.nested.flatMap { n -> n.groups } }).forEach { group ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(TabChipGap),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            group.panes.forEach { pane ->
                                val active = group.tabIndex == activeTabIndex && pane.paneId == focusedPaneId
                                val accent = parseTabColor(pane.colorHex) ?: barMuted
                                TooltipArea(tooltip = {
                                    Surface(color = barBg, shadowElevation = 4.dp, shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            pane.title, color = barFg, fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }) {
                                    Box(
                                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                            .consumeSecondaryPress {
                                                showMenuFor(group.tabIndex, pane.paneId)
                                            }
                                            .clickable { onPaneSelected(group.tabIndex, pane.paneId) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (active) Box(Modifier.size(16.dp).border(1.5.dp, barFg, CircleShape))
                                        Box(
                                            Modifier.size(10.dp).clip(CircleShape)
                                                .background(if (active) accent else accent.copy(alpha = 0.55f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(1.dp).background(barDivider))
                Spacer(Modifier.height(4.dp))
                barButton(Icons.Default.Cloud, "Add remote session", onAddRemote)
            }
        } else if (vertical) {
            Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                // Action toolbar pinned at the top, then a divider…
                actionBar()
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(barDivider))
                Spacer(Modifier.height(8.dp))
                // …with scrollable tab/pane chips filling the rest.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(TabGroupGap)
                ) {
                    groups.forEach { group ->
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    localTabBounds[group.tabIndex] = coordinates.boundsInParent()
                                }
                                .zIndex(if (draggedTabIndex == group.tabIndex) 1f else 0f)
                                .graphicsLayer {
                                    val isDragged = draggedTabIndex == group.tabIndex
                                    alpha = if (isDragged) 0.88f else 1f
                                    if (isDragged) {
                                        translationY = draggedTabOffsetY
                                    }
                                }
                                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                                    if (event.button == PointerButton.Primary) {
                                        primaryPressedTabIndex = group.tabIndex
                                    }
                                }
                                .onPointerEvent(PointerEventType.Release, PointerEventPass.Initial) { event ->
                                    if (event.button == PointerButton.Primary) {
                                        if (draggedTabIndex == group.tabIndex) {
                                            finishTabDrag(group.tabIndex)
                                        } else {
                                            primaryPressedTabIndex = null
                                        }
                                    }
                                }
                                .pointerInput(group.tabIndex, localTabOrder) {
                                    var acceptsDrag = false
                                    try {
                                        detectDragGestures(
                                            onDragStart = {
                                                acceptsDrag = primaryPressedTabIndex == group.tabIndex
                                                if (acceptsDrag) {
                                                    draggedTabIndex = group.tabIndex
                                                    draggedTabOffsetY = 0f
                                                }
                                            },
                                            onDragCancel = {
                                                if (acceptsDrag) {
                                                    resetTabDrag()
                                                    acceptsDrag = false
                                                }
                                            },
                                            onDragEnd = {
                                                if (acceptsDrag) {
                                                    finishTabDrag(group.tabIndex)
                                                    acceptsDrag = false
                                                }
                                            },
                                            onDrag = { change, dragAmount ->
                                                if (
                                                    acceptsDrag &&
                                                    draggedTabIndex == group.tabIndex
                                                ) {
                                                    change.consume()
                                                    draggedTabOffsetY += dragAmount.y
                                                }
                                            }
                                        )
                                    } finally {
                                        if (acceptsDrag) {
                                            resetTabDrag()
                                            acceptsDrag = false
                                        }
                                    }
                                },
                            verticalArrangement = Arrangement.spacedBy(TabChipGap)
                        ) {
                            group.panes.forEach { pane -> chip(group, pane, Modifier.fillMaxWidth()) }
                        }
                    }
                    // Each connected remote session: a bordered box with the link header, its
                    // mirrored tab chips, and footer actions that target the remote.
                    remoteGroups.forEach { rg ->
                        // Group accent: a custom color set via the header's right-click, else the
                        // default remote cyan. Drives the box border, the cloud icon, and (via
                        // colorHexFor upstream) the chips' accent stripes.
                        val groupAccent = parseTabColor(rg.colorHex) ?: RemoteAccent
                        // The host box + its upstream ("via host") boxes render as ONE unit,
                        // tethered by short accent connector lines — making the "these tabs
                        // arrive through the box above" relationship visible.
                        Column(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .border(1.dp, groupAccent, RoundedCornerShape(8.dp)).padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(TabChipGap)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
                                    .consumeSecondaryPress { showRemoteGroupMenu(rg) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Cloud, contentDescription = null, tint = groupAccent, modifier = Modifier.size(13.dp))
                                if (rg.id == editingRemoteId) {
                                    TabRenameField(
                                        initial = rg.header,
                                        onCommit = { editingRemoteId = null; rg.onRename(it) },
                                        onCancel = { editingRemoteId = null },
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Text(
                                        rg.header, color = Color(0xFFB0B0B0), fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                    )
                                }
                                rg.statusLabel?.let { label ->
                                    // Connection state (connecting…/disconnected) — amber while
                                    // it may heal, red when it gave up.
                                    Text(
                                        "· $label",
                                        color = if (rg.statusError) Color(0xFFE57373) else Color(0xFFE0A030),
                                        fontSize = 10.sp, maxLines = 1
                                    )
                                }
                                if (rg.mcpShown) {
                                    // This remote's MCP — dot (green = running) + "MCP"; click opens
                                    // the toggle/attach menu (control-gated by the session).
                                    Row(
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                            .clickable(onClick = rg.onMcpClick).padding(horizontal = 3.dp, vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Box(Modifier.size(6.dp).background(
                                            if (rg.mcpRunning) Color(0xFF4CAF50) else Color(0xFF6B6B6B),
                                            androidx.compose.foundation.shape.CircleShape
                                        ))
                                        Text("MCP", color = Color(0xFFB0B0B0), fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                                if (!rg.canControl) {
                                    // Read-only session: eye badge (right-click → Request Control).
                                    Icon(
                                        Icons.Default.Visibility,
                                        contentDescription = "View only — right-click to request control",
                                        tint = Color(0xFF808080),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = rg.onDisconnect).padding(2.dp)
                                ) { Icon(Icons.Default.Close, contentDescription = "Disconnect remote", tint = Color(0xFF808080), modifier = Modifier.size(13.dp)) }
                            }
                            // Match the local bar: split panes of one tab hug together
                            // (TabChipGap), separate tabs are spaced further apart (TabGroupGap).
                            // A host sharing ALL its windows sections its own tabs per window
                            // (dim sub-title + that window's clusters), like the web viewer.
                            Column(verticalArrangement = Arrangement.spacedBy(TabGroupGap)) {
                                if (rg.windowSections.isEmpty()) {
                                    rg.groups.forEach { group ->
                                        Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                            group.panes.forEach { pane -> chip(group, pane, Modifier.fillMaxWidth()) }
                                        }
                                    }
                                } else {
                                    rg.windowSections.forEach { sec ->
                                        Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    sec.label, color = Color(0xFF8A8A8A), fontSize = 10.sp,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                                )
                                                // Hairline ties the sub-title to its section.
                                                Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF3A3A3A)))
                                            }
                                            Column(verticalArrangement = Arrangement.spacedBy(TabGroupGap)) {
                                                sec.groups.forEach { group ->
                                                    Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                                        group.panes.forEach { pane -> chip(group, pane, Modifier.fillMaxWidth()) }
                                                    }
                                                }
                                            }
                                            // Per-window actions — split/new-tab land in THIS
                                            // host window (the group footer is hidden below).
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                barButton(Icons.Default.VerticalSplit, "Split Left/Right in ${sec.label}", sec.onSplitVertical)
                                                barButton(Icons.Default.HorizontalSplit, "Split Top/Bottom in ${sec.label}", sec.onSplitHorizontal)
                                                barButton(Icons.Default.Add, "New tab in ${sec.label}", sec.onNewTab)
                                            }
                                        }
                                    }
                                }
                            }
                            // Group-level footer only when not sectioned per window — the
                            // sections carry their own targeted action rows instead.
                            if (rg.windowSections.isEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    barButton(Icons.Default.VerticalSplit, "Split Left/Right", rg.onSplitVertical)
                                    barButton(Icons.Default.HorizontalSplit, "Split Top/Bottom", rg.onSplitHorizontal)
                                    barButton(Icons.Default.Add, "New tab", rg.onNewTab)
                                }
                            }
                        }
                        // Tabs the host itself mirrors from OTHER sessions: flattened — each
                        // upstream gets its own sibling box (same accent ties it to the host's
                        // box above; the eye = the host is view-only on it, so input can't
                        // flow through). No footer — structural actions belong to the origin.
                        rg.nested.forEach { nest ->
                            // Connector line: ties this upstream box to the box above it
                            // (aligned under the cloud icon), in the group's accent.
                            Box(
                                Modifier.padding(start = 13.dp).width(2.dp).height(10.dp)
                                    .background(groupAccent)
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, groupAccent, RoundedCornerShape(8.dp)).padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(TabChipGap)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.Cloud, contentDescription = null, tint = groupAccent, modifier = Modifier.size(13.dp))
                                    Text(
                                        "${nest.label} · via ${rg.header}", color = Color(0xFFB0B0B0), fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                    )
                                    if (nest.offline) {
                                        // The host lost its upstream — these tabs are frozen.
                                        Text("· offline", color = Color(0xFFE57373), fontSize = 10.sp, maxLines = 1)
                                    }
                                    if (nest.readOnly) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = "Read-only via this host",
                                            tint = Color(0xFF808080),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = nest.onClose).padding(2.dp)
                                    ) { Icon(Icons.Default.Close, contentDescription = "Ask host to disconnect this upstream", tint = Color(0xFF808080), modifier = Modifier.size(13.dp)) }
                                }
                                // The origin may share ALL its windows — section its tabs per
                                // origin window (sub-title + targeted actions), like the host box.
                                Column(verticalArrangement = Arrangement.spacedBy(TabGroupGap)) {
                                    if (nest.windowSections.isEmpty()) {
                                        nest.groups.forEach { group ->
                                            Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                                group.panes.forEach { pane -> chip(group, pane, Modifier.fillMaxWidth()) }
                                            }
                                        }
                                    } else {
                                        nest.windowSections.forEach { sec ->
                                            Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        sec.label, color = Color(0xFF8A8A8A), fontSize = 10.sp,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                                    )
                                                    Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF3A3A3A)))
                                                }
                                                Column(verticalArrangement = Arrangement.spacedBy(TabGroupGap)) {
                                                    sec.groups.forEach { group ->
                                                        Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                                            group.panes.forEach { pane -> chip(group, pane, Modifier.fillMaxWidth()) }
                                                        }
                                                    }
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    barButton(Icons.Default.VerticalSplit, "Split Left/Right in ${sec.label}", sec.onSplitVertical)
                                                    barButton(Icons.Default.HorizontalSplit, "Split Top/Bottom in ${sec.label}", sec.onSplitHorizontal)
                                                    barButton(Icons.Default.Add, "New tab in ${sec.label}", sec.onNewTab)
                                                }
                                            }
                                        }
                                    }
                                }
                                // Same footer as the host box; when read-only these route to
                                // the request-control prompt instead of silently doing nothing.
                                // Hidden when sectioned — the sections carry targeted rows.
                                if (nest.windowSections.isEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        barButton(Icons.Default.VerticalSplit, "Split Left/Right", nest.onSplitVertical)
                                        barButton(Icons.Default.HorizontalSplit, "Split Top/Bottom", nest.onSplitHorizontal)
                                        barButton(Icons.Default.Add, "New tab", nest.onNewTab)
                                    }
                                }
                            }
                        }
                        } // end tether unit (host box + its upstream boxes)
                    }
                }
                // Bottom bar — connect to another BossTerm's shared session, plus the
                // collapse chevron that shrinks the panel to the icon rail.
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(barDivider))
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                            .consumeSecondaryPress()
                            .clickable(onClick = onAddRemote)
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = "Add remote session",
                            tint = Color(0xFFB0B0B0),
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Add remote", color = Color(0xFFB0B0B0), fontSize = 12.sp)
                    }
                    onToggleCollapse?.let { toggle ->
                        IconButton(
                            onClick = toggle,
                            modifier = Modifier.size(28.dp)
                                .consumeSecondaryPress()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = "Collapse sidebar",
                                tint = barMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(TabGroupGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Local tab clusters, then remote-session clusters (flattened in the top
                    // bar — the boxed grouping with header/footer is a left-bar affordance).
                    (groups + remoteGroups.flatMap { it.groups + it.nested.flatMap { n -> n.groups } }).forEach { group ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(TabChipGap),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            group.panes.forEach { pane -> chip(group, pane, Modifier) }
                        }
                    }
                }
                newTabButton()
            }
        }
    }
}

/**
 * Individual tab item component.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TabItem(
    title: String,
    isActive: Boolean,
    tabTheme: Theme,
    colorHex: String?,
    isEditing: Boolean,
    onSelected: () -> Unit,
    onCommitRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onClose: () -> Unit,
    onContextMenu: () -> Unit,
    subtitle: String? = null,
    branch: String? = null,
    multiLine: Boolean = false,
    modifier: Modifier = Modifier
) {
    val accent = parseTabColor(colorHex)
    // Chip colors derive from the active theme, collected once in TabBar and
    // passed in (one subscriber for the whole bar, not one per tab).
    val itemBg = tabTheme.backgroundColorValue
    val itemFg = tabTheme.foregroundColor
    val itemRaised = lerp(itemBg, itemFg, 0.12f)    // active chip surface
    val itemAccent = tabTheme.cursorColor           // active chip border
    val itemMuted = itemFg.copy(alpha = 0.62f)      // inactive text / active close-icon
    val itemSubtle = itemFg.copy(alpha = 0.22f)     // inactive border (hairline)
    val itemIcon = itemFg.copy(alpha = 0.42f)       // inactive close-icon — readable on the dark floor
    Surface(
        modifier = modifier
            .then(if (multiLine) Modifier.heightIn(min = 36.dp) else Modifier.height(36.dp))
            .widthIn(min = 80.dp, max = 200.dp)
            .then(
                if (isEditing) Modifier
                else Modifier
                    // Consume secondary presses before clickable can interpret them as a
                    // normal selection gesture.
                    .consumeSecondaryPress(onContextMenu)
                    .clickable(onClick = onSelected)
            ),
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) itemRaised else itemBg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                isActive && accent != null -> accent
                isActive -> itemAccent
                else -> itemSubtle
            }
        )
    ) {
        // The title row + close button, shared by both layouts (it's line 1 of the
        // multi-line chip and the whole content of the single-line chip). Declared as a
        // RowScope receiver so the title's Modifier.weight(1f) resolves.
        val titleRow: @Composable RowScope.() -> Unit = {
            if (isEditing) {
                TabRenameField(
                    initial = title,
                    onCommit = onCommitRename,
                    onCancel = onCancelRename,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Tab title - use Monospace font (Menlo on macOS) for monochrome symbols
                Text(
                    text = title,
                    color = if (isActive) itemFg else itemMuted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,  // Menlo has monochrome Dingbats (✳, ❯, etc.)
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Close button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Tab",
                        tint = if (isActive) itemMuted else itemIcon,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Row(
            // Multi-line (left bar) chips are wrap-height inside a scroll column, where the
            // accent stripe's fillMaxHeight() would resolve against an unbounded constraint and
            // collapse to zero (invisible on unselected tabs). Bound the row to its intrinsic
            // height so the stripe spans the chip — like the viewer's always-on left border.
            modifier = if (multiLine) Modifier.fillMaxWidth().heightIn(min = 36.dp).height(IntrinsicSize.Min)
                       else Modifier.fillMaxSize(),
            verticalAlignment = if (multiLine) Alignment.Top else Alignment.CenterVertically
        ) {
            // Leading accent stripe (manual color or auto-by-directory)
            if (accent != null) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            }
            if (multiLine) {
                // Warp-style three lines: title · working directory · git branch.
                // Lines 2/3 are hidden when absent, so the chip shrinks to 2 (or 1) lines.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (accent != null) 8.dp else 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) { titleRow() }

                    if (!subtitle.isNullOrBlank() && subtitle != title) {
                        Text(
                            text = subtitle,
                            color = Color(0xFF808080),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!branch.isNullOrBlank()) {
                        Text(
                            text = "⎇ $branch",
                            color = Color(0xFF6A9955),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (accent != null) 8.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) { titleRow() }
            }
        }
    }
}

/**
 * Inline rename text field shown in place of the tab title while editing.
 * Commits on Enter or focus loss; cancels on Esc. Auto-focuses on appearance.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabRenameField(
    initial: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    // Guard so commit/cancel fire exactly once, and focus-loss only commits after
    // the field has actually gained focus (avoids an immediate mount-time commit).
    var done by remember { mutableStateOf(false) }
    var hasBeenFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        ),
        cursorBrush = SolidColor(Color.White),
        modifier = modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter -> {
                        if (!done) { done = true; onCommit(text) }
                        true
                    }
                    Key.Escape -> {
                        if (!done) { done = true; onCancel() }
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hasBeenFocused = true
                } else if (hasBeenFocused && !done) {
                    done = true
                    onCommit(text)
                }
            }
    )
}
