package ai.rever.bossterm.compose.features

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Color
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Window
import java.awt.event.ActionListener
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource

/**
 * Controller for managing context menu state and actions.
 * Uses AWT JPopupMenu for native context menu that can extend beyond window bounds.
 */
class ContextMenuController {
    // Track current popup to ensure proper dismissal before showing new one
    private var currentPopup: JPopupMenu? = null

    /**
     * Sealed class for menu elements (items, separators, submenus)
     */
    sealed class MenuElement {
        abstract val id: String
    }

    /**
     * Menu item data class
     */
    data class MenuItem(
        override val id: String,
        val label: String,
        val enabled: Boolean,
        val action: () -> Unit
    ) : MenuElement()

    /**
     * Menu separator with optional label
     */
    data class MenuSeparator(
        override val id: String,
        val label: String? = null
    ) : MenuElement()

    /**
     * Submenu containing nested elements
     */
    data class MenuSubmenu(
        override val id: String,
        val label: String,
        val items: List<MenuElement>
    ) : MenuElement()

    /**
     * Menu state data class
     */
    data class MenuState(
        val isVisible: Boolean = false,
        val x: Float = 0f,
        val y: Float = 0f,
        val items: List<MenuElement> = emptyList()
    )

    private val _menuState = mutableStateOf(MenuState())
    val menuState: State<MenuState> = _menuState

    /**
     * Show context menu at screen coordinates using native AWT popup
     */
    fun showMenuAtScreenPosition(screenX: Int, screenY: Int, items: List<MenuItem>) {
        showNativeMenuAtScreen(screenX, screenY, items)
    }

    /**
     * Show context menu at current mouse position using native AWT popup
     */
    fun showMenu(x: Float, y: Float, items: List<MenuElement>, window: ComposeWindow? = null) {
        // Get actual mouse screen position - most reliable way
        val mouseLocation = MouseInfo.getPointerInfo()?.location
        if (mouseLocation != null) {
            showNativeMenuAtScreen(mouseLocation.x, mouseLocation.y, items)
        } else {
            // Fallback to state-based menu if mouse info not available
            _menuState.value = MenuState(
                isVisible = true,
                x = x,
                y = y,
                items = items
            )
        }
    }

    /**
     * Show native AWT popup menu at screen coordinates with dark theme styling
     */
    private fun showNativeMenuAtScreen(screenX: Int, screenY: Int, items: List<MenuElement>) {
        // Dismiss any existing popup first to prevent stuck menus
        currentPopup?.let {
            it.isVisible = false
        }

        val popup = JPopupMenu().apply {
            // Dark theme colors
            background = Color(0x2B, 0x2B, 0x2B)
            border = BorderFactory.createLineBorder(Color(0x3C, 0x3F, 0x41), 1)
        }

        // Add elements to popup
        addElementsToMenu(popup, items)

        // Add listener to track popup dismissal for focus restoration
        popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
                // Update state when popup is dismissed (by any means)
                _menuState.value = MenuState()
                currentPopup = null
            }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })

        // Store reference for future dismissal
        currentPopup = popup

        // Find the window to use as invoker - prefer focused window, but find window at mouse position if not focused
        var targetWindow: Window? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow

        // If no focused window, find the window at the mouse position
        if (targetWindow == null) {
            val mousePoint = java.awt.Point(screenX, screenY)
            targetWindow = Window.getWindows()
                .filter { it.isVisible && it.bounds.contains(mousePoint) }
                .maxByOrNull { it.bounds.width * it.bounds.height } // Prefer larger window if overlapping

            // Request focus on the found window so popup dismisses properly
            targetWindow?.toFront()
            targetWindow?.requestFocus()
        }

        if (targetWindow != null) {
            // Convert screen coordinates to window-relative coordinates
            val windowLocation = targetWindow.locationOnScreen
            val relativeX = screenX - windowLocation.x
            val relativeY = screenY - windowLocation.y
            // Use show() for proper dismiss behavior
            popup.show(targetWindow, relativeX, relativeY)
        } else {
            // Fallback: show at screen location (may not dismiss properly)
            popup.location = java.awt.Point(screenX, screenY)
            popup.isVisible = true
        }
    }

    /**
     * Hide context menu
     */
    fun hideMenu() {
        // Dismiss native popup if visible
        currentPopup?.let {
            it.isVisible = false
            currentPopup = null
        }
        _menuState.value = MenuState()
    }

    /**
     * Add menu elements to a popup menu or submenu
     */
    private fun addElementsToMenu(menu: javax.swing.JComponent, elements: List<MenuElement>) {
        elements.forEach { element ->
            when (element) {
                is MenuItem -> {
                    if (element.id.startsWith("separator")) {
                        val separator = JSeparator().apply {
                            background = Color(0x2B, 0x2B, 0x2B)
                            foreground = Color(0x3C, 0x3F, 0x41)
                        }
                        addToMenu(menu, separator)
                    } else {
                        val menuItem = JMenuItem(element.label).apply {
                            isEnabled = element.enabled
                            background = Color(0x2B, 0x2B, 0x2B)
                            foreground = if (element.enabled) Color.WHITE else Color.GRAY
                            font = Font(".AppleSystemUIFont", Font.PLAIN, 13)
                            border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
                            isOpaque = true
                            addActionListener { element.action() }
                        }
                        addToMenu(menu, menuItem)
                    }
                }
                is MenuSeparator -> {
                    if (element.label != null) {
                        // Section with label
                        val separator = JSeparator().apply {
                            background = Color(0x2B, 0x2B, 0x2B)
                            foreground = Color(0x3C, 0x3F, 0x41)
                        }
                        addToMenu(menu, separator)
                        val label = JLabel(element.label).apply {
                            foreground = Color.GRAY
                            font = Font(".AppleSystemUIFont", Font.PLAIN, 11)
                            border = BorderFactory.createEmptyBorder(2, 12, 2, 12)
                        }
                        addToMenu(menu, label)
                    } else {
                        // Plain separator
                        val separator = JSeparator().apply {
                            background = Color(0x2B, 0x2B, 0x2B)
                            foreground = Color(0x3C, 0x3F, 0x41)
                        }
                        addToMenu(menu, separator)
                    }
                }
                is MenuSubmenu -> {
                    val submenu = JMenu(element.label).apply {
                        background = Color(0x2B, 0x2B, 0x2B)
                        foreground = Color.WHITE
                        font = Font(".AppleSystemUIFont", Font.PLAIN, 13)
                        border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
                        isOpaque = true
                        popupMenu.background = Color(0x2B, 0x2B, 0x2B)
                        popupMenu.border = BorderFactory.createLineBorder(Color(0x3C, 0x3F, 0x41), 1)
                    }
                    addElementsToMenu(submenu, element.items)
                    addToMenu(menu, submenu)
                }
            }
        }
    }

    /**
     * Helper to add component to the correct menu type.
     * JMenu and JPopupMenu have different add() methods than JComponent.
     */
    private fun addToMenu(menu: javax.swing.JComponent, item: java.awt.Component) {
        when (menu) {
            is JMenu -> menu.add(item)
            is JPopupMenu -> menu.add(item)
            else -> menu.add(item)
        }
    }

    /**
     * Execute menu item by ID (if enabled)
     */
    fun executeItem(id: String) {
        val item = _menuState.value.items.filterIsInstance<MenuItem>().find { it.id == id }
        if (item != null && item.enabled) {
            item.action()
            hideMenu()
        }
    }
}

/**
 * Create terminal-specific context menu items
 */
fun createTerminalContextMenuItems(
    hasSelection: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onClearScreen: () -> Unit,
    onClearScrollback: () -> Unit,
    onFind: () -> Unit,
    onNewTab: (() -> Unit)? = null,
    onSplitVertical: (() -> Unit)? = null,
    onSplitHorizontal: (() -> Unit)? = null,
    onMoveToNewTab: (() -> Unit)? = null,
    onClosePane: (() -> Unit)? = null,
    onShowDebug: (() -> Unit)? = null,
    onShowSettings: (() -> Unit)? = null,
    customItems: List<ai.rever.bossterm.compose.ContextMenuElement> = emptyList()
): List<ContextMenuController.MenuElement> {
    val baseItems = listOf(
        ContextMenuController.MenuItem(
            id = "copy",
            label = "Copy",
            enabled = hasSelection,
            action = onCopy
        ),
        ContextMenuController.MenuItem(
            id = "paste",
            label = "Paste",
            enabled = true,
            action = onPaste
        ),
        ContextMenuController.MenuItem(
            id = "select_all",
            label = "Select All",
            enabled = true,
            action = onSelectAll
        ),
        ContextMenuController.MenuItem(
            id = "find",
            label = "Find...",
            enabled = true,
            action = onFind
        ),
        ContextMenuController.MenuItem(
            id = "clear",
            label = "Clear Screen",
            enabled = true,
            action = onClearScreen
        ),
        ContextMenuController.MenuItem(
            id = "clear_scrollback",
            label = "Clear Scrollback",
            enabled = true,
            action = onClearScrollback
        )
    )

    // Add split options section
    val splitItems = mutableListOf<ContextMenuController.MenuItem>()

    if (onSplitVertical != null || onSplitHorizontal != null || onMoveToNewTab != null) {
        splitItems.add(
            ContextMenuController.MenuItem(
                id = "separator_split",
                label = "",
                enabled = false,
                action = {}
            )
        )

        if (onSplitVertical != null) {
            splitItems.add(
                ContextMenuController.MenuItem(
                    id = "split_vertical",
                    label = "Split Pane Vertically",
                    enabled = true,
                    action = onSplitVertical
                )
            )
        }

        if (onSplitHorizontal != null) {
            splitItems.add(
                ContextMenuController.MenuItem(
                    id = "split_horizontal",
                    label = "Split Pane Horizontally",
                    enabled = true,
                    action = onSplitHorizontal
                )
            )
        }

        if (onMoveToNewTab != null) {
            splitItems.add(
                ContextMenuController.MenuItem(
                    id = "move_to_new_tab",
                    label = "Move Pane to New Tab",
                    enabled = true,
                    action = onMoveToNewTab
                )
            )
        }

        if (onClosePane != null) {
            splitItems.add(
                ContextMenuController.MenuItem(
                    id = "close_pane",
                    label = "Close Pane",
                    enabled = true,
                    action = onClosePane
                )
            )
        }
    }

    // Add tab options section
    val tabItems = mutableListOf<ContextMenuController.MenuItem>()

    if (onNewTab != null) {
        tabItems.add(
            ContextMenuController.MenuItem(
                id = "separator_tab",
                label = "",
                enabled = false,
                action = {}
            )
        )
        tabItems.add(
            ContextMenuController.MenuItem(
                id = "new_tab",
                label = "New Tab",
                enabled = true,
                action = onNewTab
            )
        )
    }

    // Add extra options section
    val extraItems = mutableListOf<ContextMenuController.MenuItem>()

    if (onShowSettings != null || onShowDebug != null) {
        extraItems.add(
            ContextMenuController.MenuItem(
                id = "separator_extra",
                label = "",
                enabled = false,
                action = {}
            )
        )
    }

    if (onShowSettings != null) {
        extraItems.add(
            ContextMenuController.MenuItem(
                id = "show_settings",
                label = "Settings...",
                enabled = true,
                action = onShowSettings
            )
        )
    }

    if (onShowDebug != null) {
        extraItems.add(
            ContextMenuController.MenuItem(
                id = "show_debug",
                label = "Show Debug Panel",
                enabled = true,
                action = onShowDebug
            )
        )
    }

    // Add custom items with separator if any exist
    // Skip automatic separator if first custom item is already a section
    val customMenuItems = if (customItems.isNotEmpty()) {
        val needsSeparator = customItems.first() !is ai.rever.bossterm.compose.ContextMenuSection
        val separator = if (needsSeparator) {
            listOf<ContextMenuController.MenuElement>(
                ContextMenuController.MenuSeparator(id = "separator_custom")
            )
        } else {
            emptyList()
        }
        separator + customItems.map { element -> convertToMenuElement(element) }
    } else {
        emptyList()
    }

    return baseItems + splitItems + tabItems + extraItems + customMenuItems
}

/**
 * Convert ContextMenuElement to ContextMenuController.MenuElement
 */
private fun convertToMenuElement(element: ai.rever.bossterm.compose.ContextMenuElement): ContextMenuController.MenuElement {
    return when (element) {
        is ai.rever.bossterm.compose.ContextMenuItem -> ContextMenuController.MenuItem(
            id = "custom_${element.id}",
            label = element.label,
            enabled = element.enabled,
            action = element.action
        )
        is ai.rever.bossterm.compose.ContextMenuSection -> ContextMenuController.MenuSeparator(
            id = "custom_section_${element.id}",
            label = element.label
        )
        is ai.rever.bossterm.compose.ContextMenuSubmenu -> ContextMenuController.MenuSubmenu(
            id = "custom_submenu_${element.id}",
            label = element.label,
            items = element.items.map { convertToMenuElement(it) }
        )
    }
}

/**
 * Show terminal context menu with standard items
 */
fun showTerminalContextMenu(
    controller: ContextMenuController,
    x: Float,
    y: Float,
    hasSelection: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onClearScreen: () -> Unit,
    onClearScrollback: () -> Unit,
    onFind: () -> Unit,
    onNewTab: (() -> Unit)? = null,
    onSplitVertical: (() -> Unit)? = null,
    onSplitHorizontal: (() -> Unit)? = null,
    onMoveToNewTab: (() -> Unit)? = null,
    onClosePane: (() -> Unit)? = null,
    onShowDebug: (() -> Unit)? = null,
    onShowSettings: (() -> Unit)? = null,
    customItems: List<ai.rever.bossterm.compose.ContextMenuElement> = emptyList(),
    window: ComposeWindow? = null
) {
    val items = createTerminalContextMenuItems(
        hasSelection = hasSelection,
        onCopy = onCopy,
        onPaste = onPaste,
        onSelectAll = onSelectAll,
        onClearScreen = onClearScreen,
        onClearScrollback = onClearScrollback,
        onFind = onFind,
        onNewTab = onNewTab,
        onSplitVertical = onSplitVertical,
        onSplitHorizontal = onSplitHorizontal,
        onMoveToNewTab = onMoveToNewTab,
        onClosePane = onClosePane,
        onShowDebug = onShowDebug,
        onShowSettings = onShowSettings,
        customItems = customItems
    )
    controller.showMenu(x, y, items, window)
}

/**
 * Create hyperlink-specific context menu items
 */
fun createHyperlinkContextMenuItems(
    url: String,
    onOpenLink: () -> Unit,
    onCopyLinkAddress: () -> Unit
): List<ContextMenuController.MenuItem> {
    return listOf(
        ContextMenuController.MenuItem(
            id = "open_link",
            label = "Open Link",
            enabled = true,
            action = onOpenLink
        ),
        ContextMenuController.MenuItem(
            id = "copy_link",
            label = "Copy Link Address",
            enabled = true,
            action = onCopyLinkAddress
        ),
        ContextMenuController.MenuItem(
            id = "separator_hyperlink",
            label = "",
            enabled = false,
            action = {}
        )
    )
}

/**
 * Show context menu with hyperlink actions followed by standard terminal items
 */
/**
 * Context menu popup composable - no longer needed when using native AWT menu.
 * Kept for backward compatibility but does nothing since native menu is shown directly.
 */
@Composable
fun ContextMenuPopup(
    controller: ContextMenuController,
    modifier: Modifier = Modifier
) {
    // No-op - native menu is shown directly via JPopupMenu
    // The state-based fallback could be implemented here if needed
}

fun showHyperlinkContextMenu(
    controller: ContextMenuController,
    x: Float,
    y: Float,
    url: String,
    onOpenLink: () -> Unit,
    onCopyLinkAddress: () -> Unit,
    hasSelection: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onClearScreen: () -> Unit,
    onClearScrollback: () -> Unit,
    onFind: () -> Unit,
    onNewTab: (() -> Unit)? = null,
    onSplitVertical: (() -> Unit)? = null,
    onSplitHorizontal: (() -> Unit)? = null,
    onMoveToNewTab: (() -> Unit)? = null,
    onClosePane: (() -> Unit)? = null,
    onShowDebug: (() -> Unit)? = null,
    onShowSettings: (() -> Unit)? = null,
    customItems: List<ai.rever.bossterm.compose.ContextMenuElement> = emptyList(),
    window: ComposeWindow? = null
) {
    val hyperlinkItems = createHyperlinkContextMenuItems(
        url = url,
        onOpenLink = onOpenLink,
        onCopyLinkAddress = onCopyLinkAddress
    )
    val terminalItems = createTerminalContextMenuItems(
        hasSelection = hasSelection,
        onCopy = onCopy,
        onPaste = onPaste,
        onSelectAll = onSelectAll,
        onClearScreen = onClearScreen,
        onClearScrollback = onClearScrollback,
        onFind = onFind,
        onNewTab = onNewTab,
        onSplitVertical = onSplitVertical,
        onSplitHorizontal = onSplitHorizontal,
        onMoveToNewTab = onMoveToNewTab,
        onClosePane = onClosePane,
        onShowDebug = onShowDebug,
        onShowSettings = onShowSettings,
        customItems = customItems
    )
    controller.showMenu(x, y, hyperlinkItems + terminalItems, window)
}
