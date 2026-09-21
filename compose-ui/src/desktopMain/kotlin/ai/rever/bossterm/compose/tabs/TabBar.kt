package ai.rever.bossterm.compose.tabs

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import ai.rever.bossterm.compose.util.uiTextWithFallback
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import ai.rever.bossterm.compose.window.LocalWindowGlassTint
import ai.rever.bossterm.compose.window.LocalWindowGlassMode
import ai.rever.bossterm.compose.window.WindowGlassMode
import ai.rever.bossterm.compose.window.LocalNativeWindowGlass
import ai.rever.bossterm.compose.window.surfaceOpacity
import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Terminal
import ai.rever.bossterm.compose.window.MacToolbarIcon
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.unit.DpOffset
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

/**
 * Accent for remote (mirrored) sessions - the box border + tab-chip color.
 *
 * A `get()` and not a `val`: a file-scope val is initialised once at class-init and
 * would pin the colour to whatever theme happened to be active then. Same reason
 * every member of `SettingsTheme` is a getter.
 */
private val RemoteAccent: Color get() = BossUiTheme.current.data

/** Gap between pane chips within the same tab-group. Tight, so they read as one cluster. */
private val TabChipGap: Dp = 3.dp

/**
 * Hover dwell before a chip's tooltip appears. Deliberately slower than the MCP pill's
 * 350ms: the bar is a row of adjacent hover targets, and the pointer crosses several of
 * them on any trip to the terminal.
 */
private const val TabTooltipDelayMillis: Int = 500

/** Preview rows kept from the tail of a pane's screen. Enough to recognise, not to read. */
private const val TabTooltipPreviewLines: Int = 6

/**
 * Per-row character clip for the preview. Rows arrive padded to the terminal's full width and
 * a wide pane is 200+ columns; laying all of that out is wasted work.
 *
 * NOT the visible boundary — at 10sp monospace the card's 360dp fits roughly 55 characters, so
 * the row's own `maxLines = 1` ellipsis always lands first (and eats the "…" this clip appends).
 * It exists to bound layout work, so tuning it changes nothing you can see.
 */
private const val TabTooltipPreviewChars: Int = 96

/**
 * Character clip for the title and detail rows.
 *
 * Every one of them is terminal-controlled: the title is whatever OSC 1/2 said, the path
 * whatever OSC 7 said, the status an exception's message. The chip caps its own title at one
 * line, so without this the tooltip would be the one surface where a program that emits a
 * multi-kilobyte title paints a card taller than the window.
 */
private const val TabTooltipTextChars: Int = 200

/** Rows a title or detail line may wrap to before it is ellipsised. */
private const val TabTooltipTextLines: Int = 2

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
 *
 * [fullPath], [hostLabel] and [statusLabel] are tooltip-only: neither bar has the
 * room for them, but a chip clipped to 200dp of monospace hides exactly the detail
 * needed to tell two same-named tabs apart, and hover is where that belongs.
 */
data class TabBarPane(
    val paneId: String,
    val title: String,
    val colorHex: String? = null,
    val subtitle: String? = null,
    val branch: String? = null,
    val isGitRepo: Boolean? = null,
    /** Untruncated working directory (home as "~"), not the elided [subtitle]. */
    val fullPath: String? = null,
    /** For a mirrored chip, the remote session it belongs to (shown as "via <host>"). */
    val hostLabel: String? = null,
    /** Session state worth calling out while it is not simply connected ("starting…", an error). */
    val statusLabel: String? = null,
    /** True when a user rename overrides shell/CLI title updates. */
    val hasCustomTitle: Boolean = false
)

/**
 * Fold a leading [home] in [path] to "~", trimming a trailing slash. Nothing is elided — this
 * is the whole path, which is what the tooltip promises and what the chip's abbreviation drops.
 *
 * [home] is a parameter, not a `user.home` read, for two reasons: it is testable, and a
 * MIRRORED pane's cwd belongs to the remote host, where the local home means nothing (fold
 * `/home/alice` there and a same-named remote user turns `/home/alice/proj` into `~/proj`).
 * Callers pass null for those.
 *
 * The "$home/" guard is what keeps a sibling directory intact: `/Users/alice-backup` must stay
 * itself and not become `~-backup`.
 */
internal fun tildePath(path: String?, home: String?): String? {
    if (path.isNullOrBlank()) return null
    val cleanHome = home?.trimEnd('/')
    val clean = path.trimEnd('/').ifEmpty { "/" }
    return if (!cleanHome.isNullOrEmpty() && (clean == cleanHome || clean.startsWith("$cleanHome/"))) {
        "~" + clean.removePrefix(cleanHome)
    } else {
        clean
    }
}

/** Clip one terminal-controlled tooltip line to [TabTooltipTextChars]. */
private fun clipTooltipText(text: String): String =
    if (text.length > TabTooltipTextChars) text.take(TabTooltipTextChars) + "…" else text

/** The tooltip's title line: the chip's title, bounded (see [TabTooltipTextChars]). */
internal fun tabTooltipTitle(title: String): String = clipTooltipText(title)

/**
 * Detail lines of a chip's hover tooltip, in render order. The title is rendered
 * separately (emphasized), so it never appears here, and a line that would only
 * restate the title is dropped — a tooltip that says "BossTerm" twice is noise.
 */
internal fun tabTooltipDetails(pane: TabBarPane): List<String> {
    val details = mutableListOf<String>()
    // Prefer the untruncated path, but fall THROUGH to the chip's elided subtitle when the
    // full one only restates the title, rather than emitting no path at all. That case is
    // real: at $HOME the title is "~" and so is the folded path, while the subtitle
    // deliberately carries the expanded "/Users/you" — the one thing worth showing there.
    // (The subtitle is also the only path a remote chip has.)
    val path = listOfNotNull(pane.fullPath, pane.subtitle)
        .firstOrNull { it.isNotBlank() && it != pane.title }
    if (path != null) details += path
    pane.branch?.takeIf { it.isNotBlank() }?.let { details += "⎇ $it" }
    pane.hostLabel?.takeIf { it.isNotBlank() }?.let { details += "via $it" }
    pane.statusLabel?.takeIf { it.isNotBlank() }?.let { details += it }
    return details.map(::clipTooltipText)
}

/**
 * Shape a raw screen dump (see `TerminalTextBuffer.getScreenLines`, whose rows are padded to
 * the terminal width) into the tooltip's preview: trailing padding stripped, blank rows
 * dropped, the last [maxLines] kept, each clipped to [maxChars].
 *
 * Blank rows are dropped rather than kept, so an idle shell previews its last real output
 * instead of six empty rows below the prompt.
 */
internal fun tabTooltipPreview(
    screenText: String?,
    maxLines: Int = TabTooltipPreviewLines,
    maxChars: Int = TabTooltipPreviewChars
): List<String> {
    if (screenText.isNullOrBlank()) return emptyList()
    val rows = screenText.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()
    return rows.takeLast(maxLines).map { row ->
        if (row.length > maxChars) row.take(maxChars) + "…" else row
    }
}

/** Keep worktree creation optimistic while repository detection is still pending. */
internal fun canCreateWorktree(isGitRepo: Boolean?): Boolean = isGitRepo != false

/** Return the nearest resting tab center; exact midpoint ties favor the first visual group. */
internal fun nearestTabIndex(pointerY: Float, tabCenters: List<Pair<Int, Float>>): Int? {
    return tabCenters.minByOrNull { (_, centerY) -> abs(centerY - pointerY) }?.first
}

/** Return a neighboring tab in visual order; [delta] is normally -1 or 1. */
internal fun tabReorderNeighbor(tabIndex: Int, tabOrder: List<Int>, delta: Int): Int? {
    val position = tabOrder.indexOf(tabIndex)
    return if (position == -1) null else tabOrder.getOrNull(position + delta)
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
    val filesAvailable: Boolean = false,
    val onBrowseFiles: () -> Unit = {},
    val onUploadFiles: () -> Unit = {},
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

/**
 * AI assistants offered in the remote chip menu — same set the browser viewer mirrors, derived from
 * the tool registry in open-source-first order so the three lists can't drift apart.
 */
private val REMOTE_AI_ASSISTANTS: List<Pair<String, String>> by lazy {
    // by lazy, not get(): this is read while building a context menu during composition, and a
    // `get()` re-derived the whole sorted list on every read.
    AIAssistants.AI_ASSISTANTS_OSS_FIRST.map { it.id to it.displayName }
}

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
 *   Duplicate, Move Up/Down (or Left/Right), Close, Close Others, Close Tabs Below,
 *   Move Tab to New Window
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
    /**
     * Vertical bar only: marks this instance as a transient hover reveal over the collapsed
     * rail (see SidebarHoverReveal.kt). The bottom collapse chevron becomes a pin that keeps
     * the bar open; once pinned the bar is the real one again and the chevron returns.
     */
    onPin: (() -> Unit)? = null,
    /**
     * Reports whether an interaction that outlives a single click is in flight: a context
     * menu, an inline rename, or a tab drag. All of it is state owned by this composition,
     * so an owner that disposes the bar on its own schedule (the hover reveal, which
     * retracts when the pointer leaves) must keep it alive while this is true — otherwise
     * "Rename…" silently does nothing and a drag past the edge is dropped.
     */
    onTransientInteraction: ((Boolean) -> Unit)? = null,
    /**
     * Raw screen text for one pane (the caller's `TerminalTextBuffer.getScreenLines()`), read
     * ONLY while that pane's hover tooltip is on screen — never per composition, since it
     * locks the buffer. Null omits the tooltip's preview section entirely.
     */
    scrollbackPreview: ((tabIndex: Int, paneId: String) -> String?)? = null,
    modifier: Modifier = Modifier,
    /** Render the revealed sidebar with its own translucent backing above terminal content. */
    overlaySurface: Boolean = false,
    showSessionActions: Boolean = true,
    onNewTabAtCurrentPath: ((tabIndex: Int, paneId: String) -> Unit)? = null,
    terminalPreview: (@Composable (tabIndex: Int, paneId: String) -> Unit)? = null
) {
    // Context menu controller for chip right-click menu
    val contextMenuController = remember { ContextMenuController() }
    val vertical = orientation == TabBarOrientation.LEFT
    val groupGap = if (vertical) 8.dp else TabGroupGap

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
        val previousTabIndex = tabReorderNeighbor(tabIndex, localTabOrder, -1)
        val nextTabIndex = tabReorderNeighbor(tabIndex, localTabOrder, 1)
        val movePreviousLabel = if (vertical) "Move Tab Up" else "Move Tab Left"
        val moveNextLabel = if (vertical) "Move Tab Down" else "Move Tab Right"
        val hasCustomTitle = groups.firstOrNull { it.tabIndex == tabIndex }
            ?.panes?.firstOrNull { it.paneId == paneId }?.hasCustomTitle == true
        val items = listOfNotNull(
            ContextMenuController.MenuItem(id = "new_tab", label = "New Tab", enabled = true, action = { onNewTab() }),
            ContextMenuController.MenuItem(
                id = "new_tab_current_path", label = "New Tab at Current Path",
                enabled = onNewTabAtCurrentPath != null,
                action = { onNewTabAtCurrentPath?.invoke(tabIndex, paneId) }
            ),
            ContextMenuController.MenuItem(
                id = "split_tab_vertical", label = "Split Pane Vertically", enabled = true,
                action = {
                    onPaneSelected(tabIndex, paneId)
                    onSplitVertical()
                }
            ),
            ContextMenuController.MenuItem(
                id = "split_tab_horizontal", label = "Split Pane Horizontally", enabled = true,
                action = {
                    onPaneSelected(tabIndex, paneId)
                    onSplitHorizontal()
                }
            ),
            ContextMenuController.MenuItem(
                id = "create_worktree",
                label = "Create Worktree for This…",
                enabled = canCreateWorktree(localGitRepoByPane[tabIndex to paneId]),
                action = { onCreateWorktree(tabIndex, paneId) }
            ),
            ContextMenuController.MenuSeparator(id = "separator_worktree"),
            ContextMenuController.MenuItem(id = "rename_tab", label = "Rename…", enabled = true, action = { editingPaneId = paneId }),
            if (hasCustomTitle) ContextMenuController.MenuItem(
                id = "reset_tab_title", label = "Use Automatic Title", enabled = true,
                action = { onRename(tabIndex, paneId, "") }
            ) else null,
            colorSubmenu,
            ContextMenuController.MenuSeparator(id = "separator_tab_ops"),
            ContextMenuController.MenuItem(id = "duplicate_tab", label = "Duplicate Tab", enabled = true, action = { onDuplicate(tabIndex) }),
            ContextMenuController.MenuItem(
                id = "move_tab_previous",
                label = movePreviousLabel,
                enabled = previousTabIndex != null,
                action = { previousTabIndex?.let { latestOnTabReordered(tabIndex, it) } }
            ),
            ContextMenuController.MenuItem(
                id = "move_tab_next",
                label = moveNextLabel,
                enabled = nextTabIndex != null,
                action = { nextTabIndex?.let { latestOnTabReordered(tabIndex, it) } }
            ),
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

    // Vertical bar only: whether the "Remote connections (N)" section (all the boxes below
    // the local tabs) is expanded. Local, not persisted — same as the Share dialog's collapsed
    // "advanced" sections; defaults open so nothing already visible hides on upgrade.
    var remoteConnectionsExpanded by remember { mutableStateOf(true) }

    // Everything above that survives past a single click, reported upward so a hover-driven
    // owner doesn't dispose this composition mid-interaction. Disposal reports false —
    // whoever is still listening must not be left holding a stale "busy".
    val menuOnScreen by contextMenuController.menuVisible
    val transientInteraction = menuOnScreen || editingPaneId != null ||
        editingRemoteId != null || draggedTabIndex != null
    // The callback arrives fresh on every recomposition, so hold it by reference rather than
    // keying the effects on it — otherwise they restart constantly.
    val reportInteraction by rememberUpdatedState(onTransientInteraction)
    LaunchedEffect(transientInteraction) { reportInteraction?.invoke(transientInteraction) }
    DisposableEffect(Unit) {
        onDispose {
            reportInteraction?.invoke(false)
            // A native popup is a separate AWT window: nothing else takes it down when the
            // bar it belongs to goes away, leaving a menu orphaned over the terminal.
            contextMenuController.hideMenu()
        }
    }

    // Right-click menu on a remote group's HEADER — local customization of the box
    // (name + accent color), host file access, and Disconnect.
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
            ContextMenuController.MenuItem(id = "rg_files", label = "Browse Files…", enabled = rg.filesAvailable, action = rg.onBrowseFiles),
            ContextMenuController.MenuItem(id = "rg_upload", label = "Upload Files…", enabled = rg.filesAvailable, action = rg.onUploadFiles),
            ContextMenuController.MenuSeparator(id = "rg_files_sep"),
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
    val terminalTheme by ThemeManager.instance.currentTheme.collectAsState()
    val tabBarTheme = if (vertical) {
        if (terminalTheme.backgroundColorValue.luminance() < 0.5f)
            ai.rever.bossterm.compose.settings.theme.BuiltinThemes.LIQUID_GLASS_DARK
        else ai.rever.bossterm.compose.settings.theme.BuiltinThemes.LIQUID_GLASS_LIGHT
    } else terminalTheme
    val nativeFrame = ai.rever.bossterm.compose.window.LocalNativeWindowFrame.current
    val nativeGlass = LocalNativeWindowGlass.current
    val glassTint = LocalWindowGlassTint.current.coerceIn(0f, 1f)
    val sidebarGlassAllowed = LocalWindowGlassMode.current != WindowGlassMode.TERMINAL
    // Give the revealed drawer a consistent tint instead of the in-flow gradient.
    val glassEnabled = !overlaySurface && (vertical || LocalWindowGlassMode.current != WindowGlassMode.OFF)
    val overlayPreferences = ai.rever.bossterm.compose.window.rememberMacChromePreferences()
    val overlayOpacity = if (overlayPreferences.reduceTransparency || overlayPreferences.increaseContrast) 1f else 0.80f
    val barBg = tabBarTheme.backgroundColorValue.let { if (overlaySurface) it.copy(alpha = overlayOpacity) else it }
    val barFg = tabBarTheme.foregroundColor
    val barMuted = barFg.copy(alpha = 0.62f)
    val barDivider = barFg.copy(alpha = 0.14f)
    // Read here and passed down, not read inside TabItem: one subscriber for the whole
    // bar rather than one per chip, which is what the note in TabItem promises.
    val selectionTheme = remember(tabBarTheme) { ai.rever.bossterm.compose.settings.theme.UiTheme.fromTheme(tabBarTheme) }
    val barRaised = selectionTheme.raised
    // Light glass needs a stronger tint, composited over a stable base so the
    // desktop behind the window cannot wash the selection back into the sidebar.
    val selectionFill = if (selectionTheme.isDark) {
        if (vertical) lerp(selectionTheme.signalWash, barFg,
            if (terminalTheme.id == "liquid-glass-dark") 0.22f else 0.12f) else selectionTheme.signalWash
    } else
        selectionTheme.signalText.copy(alpha = 0.16f).compositeOver(selectionTheme.signalWash)

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
    // separate tabs are spaced further apart (groupGap). The focused pane of the
    // active tab is highlighted.
    val chip: @Composable (TabBarGroup, TabBarPane, Modifier) -> Unit = { group, pane, chipModifier ->
        // Hover reveals what the chip had to clip: the full title, the untruncated path,
        // the branch, and the remote it mirrors. Suppressed while renaming — the tooltip
        // would sit over the field being typed into, describing its stale title — and while
        // a sidebar drag is in flight: reordering slides chips under a held pointer, so each
        // one entered starts a fresh dwell that a slow drag outlasts, and the card would land
        // on the bar being rearranged.
        TabHoverTooltip(
            pane = pane,
            enabled = pane.paneId != editingPaneId && draggedTabIndex == null,
            beside = vertical,
            bg = barBg,
            fg = barFg,
            muted = barMuted,
            divider = barDivider,
            preview = scrollbackPreview?.let { read -> { read(group.tabIndex, pane.paneId) } },
            renderPreview = terminalPreview?.let { render -> { render(group.tabIndex, pane.paneId) } },
            modifier = chipModifier
        ) {
            Column {
                TabItem(
                    title = pane.title,
                    subtitle = pane.subtitle,
                    branch = pane.branch,
                    multiLine = vertical,
                    glassEnabled = glassEnabled,
                    tabTheme = tabBarTheme,
                    chipRaised = barRaised,
                    selectionFill = selectionFill,
                    lightSelection = !selectionTheme.isDark,
                    increaseContrast = overlayPreferences.increaseContrast,
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
                    // fillMaxWidth again inside the tooltip's wrapper Box: `chipModifier` sized
                    // that Box, and the chip would otherwise shrink to its text inside it.
                    modifier = if (vertical) Modifier.fillMaxWidth() else Modifier
                )
                if (vertical && (group.panes.size == 1 || pane != group.panes.last())) {
                    Box(Modifier.fillMaxWidth().padding(start = 34.dp, end = 10.dp, top = 2.dp)
                        .height(0.5.dp).background(barDivider))
                }
            }
        }
    }

    // A single enclosure makes the tab boundary distinct from its individual panes.
    val paneGroup: @Composable (TabBarGroup) -> Unit = { group ->
        val split = group.panes.size > 1
        Column(
            modifier = Modifier.fillMaxWidth().then(if (split) Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(barFg.copy(alpha = 0.035f))
                .border(0.5.dp, barDivider, RoundedCornerShape(9.dp))
                .padding(3.dp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (split) {
                Row(Modifier.fillMaxWidth().padding(start = 9.dp, end = 9.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MacToolbarIcon(Icons.Default.VerticalSplit, "Split tab", barMuted,
                        Modifier.size(12.dp), symbol = "rectangle.split.2x1")
                    Text("${group.panes.size} panes", color = barMuted, fontSize = 10.sp)
                }
            }
            group.panes.forEach { pane -> chip(group, pane, Modifier.fillMaxWidth()) }
        }
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

    val sidebarPanel = vertical && !collapsed
    var panelTop by remember { mutableStateOf(0f) }
    val panelShape = RoundedCornerShape(22.dp)
    val opaqueTerminal = LocalWindowGlassMode.current == WindowGlassMode.OFF
    // An opaque terminal cannot show a desktop backdrop. Give its pinned sidebar
    // the same contrasting wash as pane groups, slightly stronger for the full panel.
    val panelColor = if ((opaqueTerminal || !sidebarGlassAllowed) && !overlaySurface) {
        lerp(terminalTheme.backgroundColorValue, barFg,
            if (overlayPreferences.increaseContrast) {
                if (terminalTheme.backgroundColorValue.luminance() < 0.5f) 0.16f else 0.12f
            } else if (terminalTheme.backgroundColorValue.luminance() < 0.5f) 0.10f
            else 0.06f).copy(alpha = 1f)
    } else tabBarTheme.backgroundColorValue.copy(
        alpha = if (sidebarGlassAllowed && !overlayPreferences.reduceTransparency && !overlayPreferences.increaseContrast) glassTint else 1f
    )
    Surface(
        modifier = modifier
            .then(
                if (vertical) Modifier.fillMaxHeight().width(if (collapsed) TabBarRailWidth else verticalWidth)
                else Modifier.fillMaxWidth().height(TabBarHeight)
            )
            .then(
                if (sidebarPanel) Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                    .onGloballyPositioned { panelTop = it.positionInRoot().y }
                    .drawBehind {
                        // Anchor to the Compose root: AppKit can move the content view inside
                        // the window when entering fullscreen or revealing its toolbar.
                        // The pinned panel continues behind native window chrome. Content
                        // keeps its normal inset so tabs never overlap toolbar controls.
                        val extension = if (nativeFrame && !overlaySurface) (panelTop - 4.dp.toPx()).coerceAtLeast(0f) else 0f
                        val origin = androidx.compose.ui.geometry.Offset(0f, -extension)
                        val bounds = androidx.compose.ui.geometry.Size(size.width, size.height + extension)
                        val radius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx())
                        drawRoundRect(panelColor, origin, bounds, radius)
                        drawRoundRect(
                            barFg.copy(alpha = if (overlayPreferences.increaseContrast) 0.55f else 0.18f),
                            origin, bounds, radius,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                (if (overlayPreferences.increaseContrast) 1.5.dp else 0.75.dp).toPx())
                        )
                    }
                    .clip(panelShape)
                else if (glassEnabled) Modifier.background(
                    Brush.linearGradient(listOf(
                        lerp(barBg, barFg, 0.10f).copy(alpha = if (nativeGlass) (glassTint + 0.06f).coerceAtMost(1f) else 0.94f),
                        barBg.copy(alpha = if (nativeGlass) (glassTint - 0.06f).coerceAtLeast(0f) else 0.86f)
                    ))
                ).border(1.dp, barFg.copy(alpha = 0.10f)) else Modifier
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
        // Both horizontal and vertical bars can expose the native glass surface.
        shape = if (sidebarPanel) panelShape else androidx.compose.ui.graphics.RectangleShape,
        color = if (sidebarPanel || glassEnabled) Color.Transparent else barBg,
        shadowElevation = if (sidebarPanel) 0.dp else if (overlaySurface) 8.dp else if (glassEnabled) 0.dp else 2.dp
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
                    verticalArrangement = Arrangement.spacedBy(groupGap / 2),
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
                                // The rail is nothing but coloured dots, so its tooltip is the
                                // only label there is — the same card the chips get.
                                TabHoverTooltip(
                                    pane = pane,
                                    enabled = true,
                                    beside = true,
                                    bg = barBg,
                                    fg = barFg,
                                    muted = barMuted,
                                    divider = barDivider,
                                    preview = scrollbackPreview?.let { read ->
                                        { read(group.tabIndex, pane.paneId) }
                                    },
                                    renderPreview = terminalPreview?.let { render ->
                                        { render(group.tabIndex, pane.paneId) }
                                    }
                                ) {
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
                if (showSessionActions) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.7f).height(1.dp).background(barDivider))
                    Spacer(Modifier.height(4.dp))
                    barButton(Icons.Default.Cloud, "Add remote session", onAddRemote)
                }
            }
        } else if (vertical) {
            Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                // Action toolbar pinned at the top, then a divider…
                if (showSessionActions) {
                    actionBar()
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(barDivider))
                    Spacer(Modifier.height(8.dp))
                }
                // …with scrollable tab/pane chips filling the rest.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(groupGap)
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
                                // Desktop contract: the parent keeps wheel/trackpad scrolling,
                                // while a primary drag begun on a chip owns reordering. Revisit
                                // this gesture split if the component gains touch support.
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
                            paneGroup(group)
                        }
                    }
                    // Collapsible header for everything below: every tab mirrored in from another
                    // device signed into this account, one box per device (see remoteByTabIndex
                    // for the count — tabs, not devices, matching what "available" means here).
                    if (remoteGroups.isNotEmpty()) {
                        val chevronRotation by animateFloatAsState(if (remoteConnectionsExpanded) 90f else 0f)
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .consumeSecondaryPress()
                                .clickable { remoteConnectionsExpanded = !remoteConnectionsExpanded }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = if (remoteConnectionsExpanded) "Collapse remote connections" else "Expand remote connections",
                                tint = barMuted,
                                modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = chevronRotation }
                            )
                            Text(
                                "Remote connections (${remoteByTabIndex.size})",
                                color = barMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1
                            )
                        }
                    }
                    // Each connected remote session: a bordered box with the link header, its
                    // mirrored tab chips, and footer actions that target the remote.
                    (if (remoteConnectionsExpanded) remoteGroups else emptyList()).forEach { rg ->
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
                                        rg.header, color = BossUiTheme.current.mist, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                    )
                                }
                                Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable { showRemoteGroupMenu(rg) }.padding(3.dp)) {
                                    Text("⋯", color = BossUiTheme.current.mist, fontSize = 15.sp, modifier = Modifier.semantics { contentDescription = "Remote group options" })
                                }
                                rg.statusLabel?.let { label ->
                                    // Connection state (connecting…/disconnected) — amber while
                                    // it may heal, red when it gave up.
                                    Text(
                                        "· $label",
                                        color = if (rg.statusError) BossUiTheme.current.alert else BossUiTheme.current.warn,
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
                                            if (rg.mcpRunning) BossUiTheme.current.ok else BossUiTheme.current.muted,
                                            androidx.compose.foundation.shape.CircleShape
                                        ))
                                        Text("MCP", color = BossUiTheme.current.mist, fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                                if (!rg.canControl) {
                                    // Read-only session: eye badge (right-click → Request Control).
                                    Icon(
                                        Icons.Default.Visibility,
                                        contentDescription = "View only - right-click to request control",
                                        tint = BossUiTheme.current.mist,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = rg.onDisconnect).padding(2.dp)
                                ) { Icon(Icons.Default.Close, contentDescription = "Disconnect remote", tint = BossUiTheme.current.mist, modifier = Modifier.size(13.dp)) }
                            }
                            // Match the local bar: split panes of one tab hug together
                            // (TabChipGap), separate tabs are spaced further apart (groupGap).
                            // A host sharing ALL its windows sections its own tabs per window
                            // (dim sub-title + that window's clusters), like the web viewer.
                            Column(verticalArrangement = Arrangement.spacedBy(groupGap)) {
                                if (rg.windowSections.isEmpty()) {
                                    rg.groups.forEach { group ->
                                        Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                            paneGroup(group)
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
                                                    sec.label, color = BossUiTheme.current.mist, fontSize = 10.sp,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                                )
                                                // Hairline ties the sub-title to its section.
                                                Box(Modifier.weight(1f).height(1.dp).background(BossUiTheme.current.line))
                                            }
                                            Column(verticalArrangement = Arrangement.spacedBy(groupGap)) {
                                                sec.groups.forEach { group ->
                                                    Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                                        paneGroup(group)
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
                            // File actions belong to the connection, including multi-window shares.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = rg.onBrowseFiles,
                                    enabled = rg.filesAvailable,
                                    modifier = Modifier.size(30.dp).consumeSecondaryPress(),
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Browse remote files",
                                        tint = if (rg.filesAvailable) barMuted else barMuted.copy(alpha = 0.3f),
                                        modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = rg.onUploadFiles,
                                    enabled = rg.filesAvailable,
                                    modifier = Modifier.size(30.dp).consumeSecondaryPress(),
                                ) {
                                    Icon(Icons.Default.FileUpload, contentDescription = "Upload files to remote",
                                        tint = if (rg.filesAvailable) barMuted else barMuted.copy(alpha = 0.3f),
                                        modifier = Modifier.size(16.dp))
                                }
                                if (rg.windowSections.isEmpty()) {
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
                                        "${nest.label} · via ${rg.header}", color = BossUiTheme.current.mist, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                    )
                                    if (nest.offline) {
                                        // The host lost its upstream — these tabs are frozen.
                                        Text("· offline", color = BossUiTheme.current.alert, fontSize = 10.sp, maxLines = 1)
                                    }
                                    if (nest.readOnly) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = "Read-only via this host",
                                            tint = BossUiTheme.current.mist,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = nest.onClose).padding(2.dp)
                                    ) { Icon(Icons.Default.Close, contentDescription = "Ask host to disconnect this upstream", tint = BossUiTheme.current.mist, modifier = Modifier.size(13.dp)) }
                                }
                                // The origin may share ALL its windows — section its tabs per
                                // origin window (sub-title + targeted actions), like the host box.
                                Column(verticalArrangement = Arrangement.spacedBy(groupGap)) {
                                    if (nest.windowSections.isEmpty()) {
                                        nest.groups.forEach { group ->
                                            Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                                paneGroup(group)
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
                                                        sec.label, color = BossUiTheme.current.mist, fontSize = 10.sp,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                                    )
                                                    Box(Modifier.weight(1f).height(1.dp).background(BossUiTheme.current.line))
                                                }
                                                Column(verticalArrangement = Arrangement.spacedBy(groupGap)) {
                                                    sec.groups.forEach { group ->
                                                        Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                                                            paneGroup(group)
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
                if (showSessionActions) {
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
                            tint = BossUiTheme.current.mist,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Add remote", color = BossUiTheme.current.mist, fontSize = 12.sp)
                    }
                    // A hover reveal pins itself open; the real bar collapses. Same slot, so
                    // the icon is the state: pin = "this will go away", chevron = "it's mine".
                    (onPin ?: onToggleCollapse)?.let { action ->
                        IconButton(
                            onClick = action,
                            modifier = Modifier.size(28.dp)
                                .consumeSecondaryPress()
                        ) {
                            Icon(
                                imageVector = if (onPin != null) Icons.Default.PushPin
                                              else Icons.Default.ChevronLeft,
                                contentDescription = if (onPin != null) "Keep sidebar open"
                                                     else "Collapse sidebar",
                                tint = barMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
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
                    horizontalArrangement = Arrangement.spacedBy(groupGap),
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
 * Hover tooltip for one chip: the full title, [tabTooltipDetails], and — under a rule — the
 * tail of the pane's own screen from [preview] (see [tabTooltipPreview]), so a tab can be
 * identified by what it is *showing* and not just by what it is called.
 *
 * Rendered as a small raised card in the bar's own theme colors, matching the
 * status-pill tooltip rather than the Material default (which paints a surface the
 * terminal palette never asked for).
 */
@Composable
private fun TabTooltipCard(
    pane: TabBarPane,
    bg: Color,
    fg: Color,
    muted: Color,
    divider: Color,
    preview: (() -> String?)? = null,
    renderPreview: (@Composable () -> Unit)? = null
) {
    val latestPreview by rememberUpdatedState(preview)
    var previewLines by remember(pane.paneId) {
        mutableStateOf(if (renderPreview == null) tabTooltipPreview(preview?.invoke()) else emptyList())
    }
    LaunchedEffect(pane.paneId, renderPreview == null) {
        if (renderPreview == null) {
            while (true) {
                kotlinx.coroutines.delay(200)
                previewLines = tabTooltipPreview(latestPreview?.invoke())
            }
        }
    }
    val preferences = ai.rever.bossterm.compose.window.rememberMacChromePreferences()
    // The card is a snapshot of the terminal, so it wears the terminal area's own
    // opacity (ProperTerminal's background alpha), not the chrome tint: a 70% terminal
    // gets a 70% preview, an opaque one an opaque preview. Accessibility overrides win.
    val settings by ai.rever.bossterm.compose.settings.SettingsManager.instance.settings.collectAsState()
    val cardAlpha = if (preferences.reduceTransparency || preferences.increaseContrast) 1f
        else LocalWindowGlassMode.current.terminalOpacity(settings.surfaceOpacity(LocalNativeWindowGlass.current))
    // Tell the window root where we are so it blurs what shows through (HoverCardBackdrop).
    val backdrop = ai.rever.bossterm.compose.window.LocalHoverCardBackdrop.current
    DisposableEffect(backdrop) { onDispose { backdrop?.value = null } }
    Surface(
        color = bg.copy(alpha = cardAlpha),
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, fg.copy(alpha = 0.14f)),
        modifier = Modifier.onGloballyPositioned { c ->
            if (cardAlpha < 1f) backdrop?.value = androidx.compose.ui.geometry.Rect(c.positionOnScreen(), c.size.toSize())
        },
    ) {
        Column(
            // Wide enough for a real project path, wrapping past that rather than ellipsising
            // it away. A card carrying a preview always measures to the full width anyway —
            // preview rows are single-line with unbounded intrinsic width.
            modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // maxLines on both, not just the clip: 200 characters of a narrow path still wrap
            // to four rows, and the card must stay a tooltip.
            Text(
                text = uiTextWithFallback(tabTooltipTitle(pane.title)),
                color = fg,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = TabTooltipTextLines,
                overflow = TextOverflow.Ellipsis
            )
            tabTooltipDetails(pane).forEach { line ->
                Text(
                    text = uiTextWithFallback(line),
                    color = muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = TabTooltipTextLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (renderPreview != null) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.clip(RoundedCornerShape(6.dp))) { renderPreview() }
            } else if (previewLines.isNotEmpty()) {
                // A rule, not a gap: without it the terminal's own text reads as more tooltip
                // metadata — and the pane's last line can be anything, including a path.
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(divider))
                previewLines.forEach { line ->
                    Text(
                        text = uiTextWithFallback(line),
                        // Dimmer than the detail lines: this is quoted terminal output, and it
                        // must not out-shout the title it is attached to.
                        color = muted.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        // Clipped rather than wrapped — one screen row stays one preview row,
                        // so the shape of the output survives (a wrapped row reads as two).
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Wraps a chip in its hover tooltip. [enabled] false renders [content] bare, so a
 * chip being renamed is left alone — the popup would otherwise sit over the field
 * being typed into, describing the title it is replacing.
 *
 * Cursor-placed, like the MCP pill's tooltip, and not `ComponentRect` anchored to the
 * chip: only the cursor provider flips and clamps against the window, and a full path
 * on the last chip of a narrow window is exactly where an anchored card gets clipped
 * off the edge. [beside] (the left bar and its rail) nudges it clear to the right
 * instead of straight down, where it would cover the next chip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabHoverTooltip(
    pane: TabBarPane,
    enabled: Boolean,
    beside: Boolean,
    bg: Color,
    fg: Color,
    muted: Color,
    divider: Color,
    preview: (() -> String?)? = null,
    renderPreview: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        tooltip = {
            TabTooltipCard(
                pane = pane, bg = bg, fg = fg, muted = muted, divider = divider, preview = preview, renderPreview = renderPreview
            )
        },
        modifier = modifier,
        delayMillis = TabTooltipDelayMillis,
        tooltipPlacement = TooltipPlacement.CursorPoint(
            offset = if (beside) DpOffset(14.dp, 10.dp) else DpOffset(0.dp, 20.dp)
        ),
        content = content
    )
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
    /**
     * `UiTheme.raised`, collected once by [TabBar].
     *
     * A parameter rather than a `BossUiTheme.current` read in here, so the claim below
     * about one subscriber for the whole bar stays true - a token read inside this
     * composable subscribes every chip independently.
     */
    chipRaised: Color,
    selectionFill: Color,
    lightSelection: Boolean,
    increaseContrast: Boolean = false,
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
    glassEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val accent = parseTabColor(colorHex)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rowShape = RoundedCornerShape(if (multiLine) 8.dp else 6.dp)
    // Chip colors derive from the active theme, collected once in TabBar and
    // passed in (one subscriber for the whole bar, not one per tab).
    val itemBg = tabTheme.backgroundColorValue
    val itemFg = tabTheme.foregroundColor
    // The `raised` token rather than an interpolation. Compose's Color lerp
    // interpolates in Oklab, where a small step off a near-black floor rounds back
    // to the floor - so on BOSS Blueprint (#05070B) the active chip had barely any
    // surface at all. `raised` is the same idea (the floor stepped toward the text)
    // computed in gamma sRGB by UiTheme's own mix, and on the light identities it is
    // the white card the active chip should be.
    val itemRaised = chipRaised                     // active chip surface
    val itemAccent = tabTheme.cursorColor           // active chip border
    val itemMuted = itemFg.copy(alpha = 0.62f)      // inactive text / active close-icon
    val itemSubtle = itemFg.copy(alpha = 0.22f)     // inactive border (hairline)
    val itemIcon = itemFg.copy(alpha = 0.42f)       // inactive close-icon — readable on the dark floor
    Surface(
        modifier = modifier
            .then(if (multiLine) Modifier.heightIn(min = 36.dp) else Modifier.height(36.dp))
            .then(if (multiLine) Modifier.fillMaxWidth() else Modifier.widthIn(min = 80.dp, max = 200.dp))
            .hoverable(interaction)
            .then(
                if (glassEnabled && !multiLine) Modifier
                    .clip(RoundedCornerShape(6.dp))
                    // Tint the glass from the same pane color used by the context menu.
                    // Keep the neutral highlight above it for a reflective finish.
                    .background(accent?.copy(alpha = if (isActive) 0.24f else 0.12f) ?: Color.Transparent)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                itemFg.copy(alpha = if (isActive) 0.16f else 0.07f),
                                itemFg.copy(alpha = if (isActive) 0.06f else 0.02f)
                            )
                        )
                    ) else Modifier
            )
            .then(
                if (isEditing) Modifier
                else Modifier
                    // Consume secondary presses before clickable can interpret them as a
                    // normal selection gesture.
                    .consumeSecondaryPress(onContextMenu)
                    .selectable(selected = isActive, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onSelected)
            ),
        shape = rowShape,
        color = if (multiLine) {
            when {
                isActive -> selectionFill.copy(alpha = if (increaseContrast || lightSelection || !glassEnabled) 1f else 0.95f)
                hovered -> itemFg.copy(alpha = 0.08f)
                else -> Color.Transparent
            }
        } else if (glassEnabled) Color.Transparent else if (isActive) itemRaised else itemBg,
        border = if (multiLine && !increaseContrast) null else androidx.compose.foundation.BorderStroke(
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
            if (multiLine) {
                MacToolbarIcon(Icons.Default.Terminal, "Terminal session",
                    tint = accent ?: if (isActive) itemFg else itemMuted,
                    modifier = Modifier.size(16.dp), symbol = "terminal")
                Spacer(Modifier.width(8.dp))
            }
            if (isEditing) {
                TabRenameField(
                    initial = title,
                    onCommit = onCommitRename,
                    onCancel = onCancelRename,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Sidebar labels use the UI font; terminal content retains its configured font.
                Text(
                    text = uiTextWithFallback(title),
                    color = if (multiLine || isActive) itemFg else itemMuted,
                    fontSize = 13.sp,
                    fontFamily = if (multiLine) FontFamily.Default else FontFamily.Monospace,
                    fontWeight = if (multiLine && isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Keep the trailing slot stable as the close control appears on hover.
                if (!multiLine || isActive || hovered) IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(if (multiLine) 24.dp else 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Tab",
                        tint = if (isActive) itemMuted else itemIcon,
                        modifier = Modifier.size(14.dp)
                    )
                } else Spacer(Modifier.size(24.dp))
            }
        }

        Row(
            // Sidebar rows grow with their available metadata; horizontal tabs stay compact.
            modifier = if (multiLine) Modifier.fillMaxWidth().heightIn(min = 36.dp).height(IntrinsicSize.Min)
                       else Modifier.fillMaxSize(),
            verticalAlignment = if (multiLine) Alignment.Top else Alignment.CenterVertically
        ) {
            // Leading accent stripe (manual color or auto-by-directory)
            if (accent != null && !multiLine) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            }
            if (multiLine) {
                // Warp-style three lines: title · working directory · git branch.
                // Lines 2/3 are hidden when absent, so the chip shrinks to 2 (or 1) lines.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) { titleRow() }

                    if (!subtitle.isNullOrBlank() && subtitle != title) {
                        Text(
                            text = uiTextWithFallback(subtitle),
                            color = itemMuted,
                            fontSize = 11.sp,
                            fontFamily = if (multiLine) FontFamily.Default else FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp)
                        )
                    }
                    if (!branch.isNullOrBlank()) {
                        Text(
                            text = uiTextWithFallback("⎇ $branch"),
                            // `itemMuted`, not `ok`: `ok` is a FILL token held to the
                            // 3:1 component floor and is 3.2:1 on the light identities.
                            // The ⎇ glyph already says "branch"; the green did not.
                            color = itemMuted,
                            fontSize = 11.sp,
                            fontFamily = if (multiLine) FontFamily.Default else FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp)
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
            color = BossUiTheme.current.chalk,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        ),
        cursorBrush = SolidColor(BossUiTheme.current.chalk),
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
