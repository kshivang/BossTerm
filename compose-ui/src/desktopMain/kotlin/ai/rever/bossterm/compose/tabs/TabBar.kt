package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.features.ContextMenuController
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fixed height of the [TabBar] surface; referenced by overlays that must clear it. */
val TabBarHeight: Dp = 48.dp

/** Default width of the [TabBar] surface when rendered vertically (left position). */
val TabBarVerticalWidth: Dp = 180.dp

/** Orientation of the tab bar: across the top (default) or down the left side. */
enum class TabBarOrientation { TOP, LEFT }

/** Gap between tab-groups (each group = one tab's panes). Larger than within a group. */
private val TabGroupGap: Dp = 18.dp

/** Gap between pane chips within the same tab-group. Tight, so they read as one cluster. */
private val TabChipGap: Dp = 3.dp

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

/** One pane shown as a chip in the tab bar. [colorHex] is the resolved accent (manual or auto). */
data class TabBarPane(val paneId: String, val title: String, val colorHex: String? = null)

/** A tab and its panes, rendered as a visually-grouped cluster of chips. */
data class TabBarGroup(val tabIndex: Int, val panes: List<TabBarPane>)

/**
 * Tab bar component for multiple terminal sessions.
 *
 * Displays a strip (top) or column (left) of pane-chips grouped by tab:
 * - Tab titles (cwd-derived, or a user rename), with ellipsis for long names
 * - Optional per-tab accent stripe (manual color or auto-by-directory)
 * - Close button per chip (X)
 * - New tab button (+)
 * - Active/focused pane highlighting
 * - Right-click context menu: Rename…, Color ▸, Duplicate, Close, Close Others,
 *   Close Tabs Below, Move Tab to New Window
 * - Double-click a chip to rename inline
 *
 * Styling matches the Material 3 design of the search bar for visual consistency.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TabBar(
    groups: List<TabBarGroup>,
    activeTabIndex: Int,
    focusedPaneId: String?,
    onPaneSelected: (tabIndex: Int, paneId: String) -> Unit,
    onPaneClosed: (tabIndex: Int, paneId: String) -> Unit,
    onNewTab: () -> Unit,
    onTabMoveToNewWindow: (Int) -> Unit = {},
    onRename: (tabIndex: Int, paneId: String, newTitle: String) -> Unit = { _, _, _ -> },
    onSetColor: (tabIndex: Int, paneId: String, hex: String?) -> Unit = { _, _, _ -> },
    onCloseOthers: (Int) -> Unit = {},
    onCloseBelow: (Int) -> Unit = {},
    onDuplicate: (Int) -> Unit = {},
    onShareTab: (Int) -> Unit = {},
    onShareWindow: (Int) -> Unit = {},
    onStopShare: (Int) -> Unit = {},
    isSharing: (Int) -> Boolean = { false },
    onSplitVertical: () -> Unit = {},
    onSplitHorizontal: () -> Unit = {},
    onSettings: () -> Unit = {},
    orientation: TabBarOrientation = TabBarOrientation.TOP,
    verticalWidth: Dp = TabBarVerticalWidth,
    modifier: Modifier = Modifier
) {
    // Context menu controller for chip right-click menu
    val contextMenuController = remember { ContextMenuController() }
    val vertical = orientation == TabBarOrientation.LEFT

    // Pane currently being renamed inline (null = none). Set by double-click or the
    // "Rename…" menu item; cleared on commit/cancel.
    var editingPaneId by remember { mutableStateOf<String?>(null) }

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
                ContextMenuController.MenuItem(id = "share_tab", label = "Share Tab…", enabled = true, action = { onShareTab(tabIndex) }),
                ContextMenuController.MenuItem(id = "share_window", label = "Share Window…", enabled = true, action = { onShareWindow(tabIndex) })
            )
        }
        contextMenuController.showMenu(0f, 0f, items)
    }

    val newTabButton: @Composable () -> Unit = {
        IconButton(onClick = onNewTab, modifier = Modifier.size(36.dp)) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "New Tab", tint = Color.White)
        }
    }

    // A single compact action button for the left bar's top toolbar.
    val barButton: @Composable (ImageVector, String, () -> Unit) -> Unit = { icon, desc, onClick ->
        IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
            Icon(imageVector = icon, contentDescription = desc, tint = Color(0xFFB0B0B0), modifier = Modifier.size(16.dp))
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
            isActive = group.tabIndex == activeTabIndex && pane.paneId == focusedPaneId,
            colorHex = pane.colorHex,
            isEditing = pane.paneId == editingPaneId,
            onSelected = { onPaneSelected(group.tabIndex, pane.paneId) },
            onStartRename = { editingPaneId = pane.paneId },
            onCommitRename = { newTitle ->
                editingPaneId = null
                onRename(group.tabIndex, pane.paneId, newTitle)
            },
            onCancelRename = { editingPaneId = null },
            onClose = { onPaneClosed(group.tabIndex, pane.paneId) },
            onContextMenu = { showChipMenu(group.tabIndex, pane.paneId) },
            modifier = chipModifier
        )
    }

    Surface(
        modifier = modifier.then(
            if (vertical) Modifier.fillMaxHeight().width(verticalWidth)
            else Modifier.fillMaxWidth().height(TabBarHeight)
        ),
        color = Color(0xFF1E1E1E),
        shadowElevation = 2.dp
    ) {
        if (vertical) {
            Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                // Action toolbar pinned at the top, then a divider…
                actionBar()
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
                Spacer(Modifier.height(8.dp))
                // …with scrollable tab/pane chips filling the rest.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(TabGroupGap)
                ) {
                    groups.forEach { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(TabChipGap)) {
                            group.panes.forEach { pane -> chip(group, pane, Modifier.fillMaxWidth()) }
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
                    groups.forEach { group ->
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
    colorHex: String?,
    isEditing: Boolean,
    onSelected: () -> Unit,
    onStartRename: () -> Unit,
    onCommitRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onClose: () -> Unit,
    onContextMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = parseTabColor(colorHex)
    Surface(
        modifier = modifier
            .height(36.dp)
            .widthIn(min = 80.dp, max = 200.dp)
            .then(
                if (isEditing) Modifier
                else Modifier
                    .combinedClickable(onClick = onSelected, onDoubleClick = onStartRename)
                    .onPointerEvent(PointerEventType.Press) { event ->
                        // Handle right-click for context menu
                        if (event.button == PointerButton.Secondary) {
                            onContextMenu()
                        }
                    }
            ),
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) Color(0xFF2B2B2B) else Color(0xFF1E1E1E),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                isActive && accent != null -> accent
                isActive -> Color(0xFF4A90E2)
                else -> Color(0xFF404040)
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading accent stripe (manual color or auto-by-directory)
            if (accent != null) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (accent != null) 8.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
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
                        color = if (isActive) Color.White else Color(0xFFB0B0B0),
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
                            tint = if (isActive) Color(0xFFB0B0B0) else Color(0xFF707070),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
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
