package ai.rever.bossterm.compose.features

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import java.awt.AWTEvent
import java.awt.Dialog
import java.awt.Font
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.SwingUtilities
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import ai.rever.bossterm.compose.settings.theme.toAwtColor
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils

/**
 * Controller for managing context menu state and actions.
 *
 * Owns the menu *model* and the visibility lifecycle; the actual widgets come from one of two
 * [ContextMenuRenderer]s picked per show:
 *
 * - [AwtNativeContextMenuRenderer] — `java.awt.PopupMenu`, which on macOS is peered by
 *   `sun.lwawt.macosx.CPopupMenu` onto a real `NSMenu` and on Windows onto a real Win32 menu.
 *   The genuine OS menu: system material and metrics, automatic light/dark, native keyboard
 *   nav and accessibility, correct emoji, and no z-order risk over the Skia canvas because it
 *   is an OS-owned window. The price is that it cannot be coloured, fonted, or given icons.
 * - [SwingContextMenuRenderer] — `JPopupMenu` painted in BOSS theme tokens. Used on Linux,
 *   where the AWT peer is the Motif-era XAWT menu that ignores GTK, and whenever the user
 *   turns `TerminalSettings.useNativeContextMenus` off.
 */
class ContextMenuController internal constructor(
    private val swingRenderer: ContextMenuRenderer,
    private val nativeRenderer: ContextMenuRenderer,
    private val nativePreferred: () -> Boolean
) {

    constructor() : this(
        swingRenderer = SwingContextMenuRenderer(),
        nativeRenderer = AwtNativeContextMenuRenderer(),
        nativePreferred = ::nativeMenusPreferred
    )

    /** The renderer that put the menu currently on screen there, so [hideMenu] talks to the right one. */
    private var activeRenderer: ContextMenuRenderer? = null

    /**
     * Bumped on every show and on [hideMenu]. A native menu cannot be dismissed programmatically
     * (verified: neither hiding, removing, nor disposing the invoker cancels an open `NSMenu`),
     * so item actions are fenced on the generation they were built for and no-op once the
     * controller has moved on. That is what actually protects against a menu outliving the tab
     * bar that owns it and then running a stale action against a disposed pane.
     */
    private var generation: Long = 0L

    /**
     * Sealed class for menu elements (items, separators, submenus)
     */
    sealed class MenuElement {
        abstract val id: String
    }

    /**
     * Menu item data class.
     *
     * [shortcut] is display-only: an AWT [java.awt.MenuShortcut] renders as `⌘X` on macOS and
     * `Ctrl+X` on Windows but does NOT register a live key equivalent (verified), so it cannot
     * double-fire with the terminal's own Cmd+C / Ctrl+C handling. The themed Swing renderer
     * ignores it rather than inventing a second accelerator convention.
     */
    data class MenuItem(
        override val id: String,
        val label: String,
        val enabled: Boolean,
        val action: () -> Unit,
        /** An uppercase letter, whose char code is the matching `KeyEvent.VK_*` constant. */
        val shortcut: Char? = null
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

    private val _menuVisible = mutableStateOf(false)

    /**
     * True while a menu is on screen. A popup swallows the pointer, so callers whose lifetime is
     * driven by hover (the sidebar's hover reveal) need this to know not to disappear from under
     * the menu they own.
     *
     * The Swing renderer clears this precisely, off `PopupMenuListener`. The native renderer has
     * no dismissal event of any kind to hang it on, so it clears on the next input event the app
     * receives instead — which works because an open OS menu holds the input grab and lets
     * nothing through. See [AwtNativeContextMenuRenderer].
     */
    val menuVisible: State<Boolean> = _menuVisible

    /**
     * Show context menu at explicit screen coordinates.
     */
    fun showMenuAtScreenPosition(screenX: Int, screenY: Int, items: List<MenuItem>) {
        showAt(MenuAnchor.Screen(screenX, screenY), items, window = null)
    }

    /**
     * Show context menu at the current mouse position.
     *
     * [x] and [y] are Compose-local coordinates, kept only as a last-resort anchor: a context
     * menu belongs at the pointer, and reading the cursor from [MouseInfo] gives screen
     * coordinates that are already AWT-scaled, which sidesteps the Compose-px to AWT-logical
     * density conversion entirely.
     */
    fun showMenu(x: Float, y: Float, items: List<MenuElement>, window: ComposeWindow? = null) {
        val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        val anchor = if (cursor != null) {
            MenuAnchor.Screen(cursor.x, cursor.y)
        } else {
            // No pointing device, or headless. Anchor on the invoker instead of dropping the
            // menu on the floor, which is what the old state-based fallback silently did.
            MenuAnchor.RelativeToInvoker(x.toInt(), y.toInt())
        }
        showAt(anchor, items, window)
    }

    private fun showAt(anchor: MenuAnchor, items: List<MenuElement>, window: ComposeWindow?) {
        val renderer = if (nativePreferred()) nativeRenderer else swingRenderer

        // Take down whatever is up before replacing it. On the native path this can only
        // invalidate, not dismiss — see [generation].
        activeRenderer?.hide()
        generation += 1
        val shown = generation

        activeRenderer = renderer
        _menuVisible.value = true

        renderer.show(
            anchor = anchor,
            preferredInvoker = window,
            items = items,
            // Fence every action on the generation it was built for.
            isCurrent = { generation == shown },
            onDismiss = {
                if (generation == shown) {
                    _menuVisible.value = false
                    activeRenderer = null
                }
            }
        )
    }

    /**
     * Take the menu down.
     *
     * On the Swing path this genuinely dismisses the popup. On the native path it cannot:
     * `NSMenu` tracking survives hiding, removing and disposing its invoker, and AWT exposes no
     * `cancelTracking`. So the menu may linger visually until the user clicks or presses Escape,
     * but [generation] guarantees its items are inert by then.
     */
    fun hideMenu() {
        generation += 1
        activeRenderer?.hide()
        activeRenderer = null
        _menuVisible.value = false
    }
}

/**
 * Where a menu should appear. Resolved to invoker-relative coordinates by the renderer, which is
 * the only side that knows the invoker.
 */
internal sealed interface MenuAnchor {
    /** Absolute screen coordinates, normally the mouse cursor. */
    data class Screen(val x: Int, val y: Int) : MenuAnchor

    /** Offset within the invoker, used when there is no pointer to read. */
    data class RelativeToInvoker(val x: Int, val y: Int) : MenuAnchor
}

/**
 * Native menus on macOS and Windows; themed Swing on Linux regardless of the setting, because
 * the AWT peer there is the Motif-era XAWT menu that ignores GTK.
 */
internal fun shouldUseNativeMenus(settingEnabled: Boolean, isLinux: Boolean): Boolean =
    settingEnabled && !isLinux

/**
 * The live answer for [shouldUseNativeMenus], read per show so flipping the setting takes effect
 * on the next right-click without a restart. Falls back to native if settings are unreadable.
 */
internal fun nativeMenusPreferred(): Boolean = shouldUseNativeMenus(
    settingEnabled = runCatching {
        SettingsManager.instance.settings.value.useNativeContextMenus
    }.getOrDefault(true),
    isLinux = ShellCustomizationUtils.isLinux()
)

/**
 * Renders a [ContextMenuController] menu model as actual widgets.
 *
 * `isCurrent` fences item actions: a native menu can outlive the controller that built it, and
 * an action that fires afterwards would run against state the caller has already torn down.
 */
internal interface ContextMenuRenderer {
    fun show(
        anchor: MenuAnchor,
        preferredInvoker: ComposeWindow?,
        items: List<ContextMenuController.MenuElement>,
        isCurrent: () -> Boolean,
        onDismiss: () -> Unit
    )

    fun hide()
}

/**
 * Pick the AWT window to hang a popup off.
 *
 * `Window.getWindows()` is not in z-order and also returns the heavyweight windows Swing creates
 * for popups — including the menu being replaced — so it is filtered to real frames and dialogs.
 * Smallest-area-first is a proxy for topmost: a heavyweight popup is owned by its invoker, so
 * choosing the window underneath would paint the menu behind the one on top.
 */
internal fun resolveInvoker(preferred: Window?, at: java.awt.Point?): Window? {
    preferred?.takeIf { it.isShowing }?.let { return it }

    KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        ?.takeIf { it.isShowing }
        ?.let { return it }

    return Window.getWindows()
        .filter { (it is Frame || it is Dialog) && it.isShowing }
        .filter { at == null || it.bounds.contains(at) }
        .sortedWith(
            compareByDescending<Window> { it.isActive }
                .thenBy { it.bounds.width.toLong() * it.bounds.height.toLong() }
        )
        .firstOrNull()
        ?.also { if (!it.isActive) { it.toFront(); it.requestFocus() } }
}

/** Resolve an anchor against its invoker into the invoker-relative point `show()` wants. */
internal fun MenuAnchor.toInvokerCoordinates(invoker: Window): java.awt.Point = when (this) {
    is MenuAnchor.RelativeToInvoker -> java.awt.Point(x, y)
    is MenuAnchor.Screen -> {
        val origin = invoker.locationOnScreen
        java.awt.Point(x - origin.x, y - origin.y)
    }
}

/** The screen point this anchor names, if any, for picking the window underneath it. */
internal fun MenuAnchor.screenPointOrNull(): java.awt.Point? =
    (this as? MenuAnchor.Screen)?.let { java.awt.Point(it.x, it.y) }

/**
 * The BOSS-themed renderer: a Swing [JPopupMenu] hand-painted in the active theme's tokens.
 *
 * Used on Linux and whenever `useNativeContextMenus` is off. Note this is Swing-drawn, not
 * native: on macOS `com.apple.laf.AquaPopupMenuUI` extends `BasicPopupMenuUI` and paints itself
 * with Java2D, which is exactly why every colour and font here has to be set by hand.
 */
internal class SwingContextMenuRenderer : ContextMenuRenderer {

    private var currentPopup: JPopupMenu? = null

    // Theme-derived AWT colors. Getters (not vals) so each menu build reads the
    // tokens for the currently active theme — the menu is rebuilt per right-click.
    private val menuBg get() = BossUiTheme.current.raised.toAwtColor()
    private val menuBorder get() = BossUiTheme.current.line.toAwtColor()
    private val menuText get() = BossUiTheme.current.chalk.toAwtColor()
    private val menuMuted get() = BossUiTheme.current.muted.toAwtColor()

    override fun show(
        anchor: MenuAnchor,
        preferredInvoker: ComposeWindow?,
        items: List<ContextMenuController.MenuElement>,
        isCurrent: () -> Boolean,
        onDismiss: () -> Unit
    ) {
        val invoker = resolveInvoker(preferredInvoker, anchor.screenPointOrNull())
        if (invoker == null) {
            // Nothing to hang the popup off. Report dismissal so the caller's visibility flag
            // cannot stick true for a menu that never appeared.
            onDismiss()
            return
        }

        currentPopup?.isVisible = false

        val popup = JPopupMenu().apply {
            background = menuBg
            border = BorderFactory.createLineBorder(menuBorder, 1)
            // A lightweight popup is painted into the Swing layered pane, which sits BEHIND the
            // accelerated Skia surface. BossConsole disables lightweight popups globally, but a
            // library cannot assume the host it is embedded in did, and standalone BossTerm has
            // no such call at all.
            isLightWeightPopupEnabled = false
        }

        addElementsToMenu(popup, items, isCurrent)

        popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
                currentPopup = null
                onDismiss()
            }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })

        currentPopup = popup

        val at = anchor.toInvokerCoordinates(invoker)
        popup.show(invoker, at.x, at.y)
    }

    override fun hide() {
        currentPopup?.isVisible = false
        currentPopup = null
    }

    private fun addElementsToMenu(
        menu: javax.swing.JComponent,
        elements: List<ContextMenuController.MenuElement>,
        isCurrent: () -> Boolean
    ) {
        elements.forEach { element ->
            when (element) {
                is ContextMenuController.MenuItem -> {
                    if (element.id.startsWith("separator")) {
                        addToMenu(menu, themedSeparator())
                    } else {
                        val menuItem = JMenuItem(element.label).apply {
                            isEnabled = element.enabled
                            background = menuBg
                            foreground = if (element.enabled) menuText else menuMuted
                            font = Font(".AppleSystemUIFont", Font.PLAIN, 13)
                            border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
                            isOpaque = true
                            // Signed-in account row (id "sign_in"/"custom_sign_in" with an email
                            // label) gets a person glyph drawn in Java2D — NOT an emoji in the text,
                            // which poisons the popup's font metrics and garbles every item.
                            if (element.id.endsWith("sign_in") && element.label.contains('@')) {
                                icon = AccountGlyphIcon
                                iconTextGap = 8
                            }
                            addActionListener { if (isCurrent()) element.action() }
                        }
                        addToMenu(menu, menuItem)
                    }
                }
                is ContextMenuController.MenuSeparator -> {
                    addToMenu(menu, themedSeparator())
                    if (element.label != null) {
                        val label = JLabel(element.label).apply {
                            foreground = menuMuted
                            font = Font(".AppleSystemUIFont", Font.PLAIN, 11)
                            border = BorderFactory.createEmptyBorder(2, 12, 2, 12)
                        }
                        addToMenu(menu, label)
                    }
                }
                is ContextMenuController.MenuSubmenu -> {
                    val submenu = JMenu(element.label).apply {
                        background = menuBg
                        foreground = menuText
                        font = Font(".AppleSystemUIFont", Font.PLAIN, 13)
                        border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
                        isOpaque = true
                        popupMenu.background = menuBg
                        popupMenu.border = BorderFactory.createLineBorder(menuBorder, 1)
                        popupMenu.isLightWeightPopupEnabled = false
                    }
                    addElementsToMenu(submenu, element.items, isCurrent)
                    addToMenu(menu, submenu)
                }
            }
        }
    }

    private fun themedSeparator() = JSeparator().apply {
        background = menuBg
        foreground = menuBorder
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
}

/**
 * The OS-native renderer: `java.awt.PopupMenu`.
 *
 * On macOS this is peered by `sun.lwawt.macosx.CPopupMenu`, whose `nativeShowPopupMenu` calls
 * `popUpMenuPositioningItem:atLocation:inView:` on a real `NSMenu` (`CMenu.getNativeMenu()`
 * hands back the `NSMenu*`). On Windows it maps to a real Win32 popup.
 *
 * Three behaviours were measured rather than assumed, and each shapes the code below:
 *
 * 1. `show()` does NOT block — it returns immediately and the EDT stays live, so Compose keeps
 *    painting while the menu is up. It also means its return is not a dismissal signal.
 * 2. NOTHING cancels an open menu: hiding, removing and disposing the invoker all leave it
 *    tracking. Hence [ContextMenuRenderer.hide] here is a no-op and correctness rests on the
 *    controller's generation fence.
 * 3. There is no dismissal event on ANY AWT mask — mouse, motion, key, window, focus or action.
 *    Dismissal is inferred from the next input event the app sees.
 */
/**
 * A toolkit-independent description of a native menu.
 *
 * Kept separate from the AWT widgets purely so the mapping can be tested: constructing any
 * `java.awt.MenuComponent` throws `HeadlessException`, and CI is headless.
 */
internal sealed interface NativeMenuOp {
    data class Item(
        val label: String,
        val enabled: Boolean,
        val shortcut: Char?,
        val action: () -> Unit
    ) : NativeMenuOp

    data object Separator : NativeMenuOp

    data class Submenu(val label: String, val ops: List<NativeMenuOp>) : NativeMenuOp
}

/**
 * Flatten the menu model into native-menu operations.
 *
 * Two shapes have no native equivalent and are degraded here:
 * - a [ContextMenuController.MenuSeparator] carrying a label becomes a separator plus a
 *   **disabled** item, which is how macOS renders a section header anyway;
 * - item icons are dropped, because `java.awt.MenuItem` has no icon API.
 */
internal fun planNativeMenu(elements: List<ContextMenuController.MenuElement>): List<NativeMenuOp> =
    elements.flatMap { element ->
        when (element) {
            is ContextMenuController.MenuItem ->
                if (element.id.startsWith("separator")) {
                    listOf(NativeMenuOp.Separator)
                } else {
                    listOf(
                        NativeMenuOp.Item(
                            label = element.label,
                            enabled = element.enabled,
                            shortcut = element.shortcut,
                            action = element.action
                        )
                    )
                }

            is ContextMenuController.MenuSeparator ->
                listOfNotNull(
                    NativeMenuOp.Separator,
                    element.label?.let {
                        NativeMenuOp.Item(label = it, enabled = false, shortcut = null, action = {})
                    }
                )

            is ContextMenuController.MenuSubmenu ->
                listOf(NativeMenuOp.Submenu(element.label, planNativeMenu(element.items)))
        }
    }

internal class AwtNativeContextMenuRenderer : ContextMenuRenderer {

    private var attached: Pair<Window, java.awt.PopupMenu>? = null
    private var dismissWatcher: java.awt.event.AWTEventListener? = null

    override fun show(
        anchor: MenuAnchor,
        preferredInvoker: ComposeWindow?,
        items: List<ContextMenuController.MenuElement>,
        isCurrent: () -> Boolean,
        onDismiss: () -> Unit
    ) {
        onEdt {
            val invoker = resolveInvoker(preferredInvoker, anchor.screenPointOrNull())
            if (invoker == null) {
                // Nothing to hang the popup off. Report dismissal so the caller's visibility flag
                // cannot stick true for a menu that never appeared.
                onDismiss()
                return@onEdt
            }

            // A PopupMenu must hang off a live Component, and AWT keeps it as a child until
            // removed. Detach the previous one so invokers don't accumulate menus.
            attached?.let { (owner, menu) -> runCatching { owner.remove(menu) } }

            val popup = java.awt.PopupMenu()
            materialize(popup, planNativeMenu(items), isCurrent, onDismiss)
            invoker.add(popup)
            attached = invoker to popup

            installDismissWatcher(onDismiss)

            val at = anchor.toInvokerCoordinates(invoker)
            popup.show(invoker, at.x, at.y)
        }
    }

    /**
     * Deliberately does not try to dismiss: verified that `setVisible(false)`, `remove()` and
     * `dispose()` on the invoker all leave an open `NSMenu` tracking and selectable. The
     * controller's generation fence is what makes a lingering menu harmless.
     */
    override fun hide() {
        onEdt { clearDismissWatcher() }
    }

    /**
     * Infer dismissal from the next input event, since AWT reports none.
     *
     * This is sound rather than merely convenient, and both halves were measured: while the menu
     * is open the OS tracking loop swallows *everything* (dragging the pointer repeatedly across
     * an open menu delivered zero AWT events), and dismissal immediately produces a burst -
     * `MOUSE_EXITED`, the Escape key-release, then `MOUSE_ENTERED`. So the flag cannot clear
     * early, and it clears promptly even if the user never moves the mouse afterwards.
     *
     * Two guards remain for the gap between `show()` being posted to the EDT and the OS actually
     * taking the input grab: `MOUSE_RELEASED` is ignored, because the release of the very
     * right-click that opened the menu could land in that window, and so is anything inside a
     * short grace period.
     */
    private fun installDismissWatcher(onDismiss: () -> Unit) {
        clearDismissWatcher()
        val armedAt = System.currentTimeMillis()
        val toolkit = Toolkit.getDefaultToolkit()
        val listener = object : java.awt.event.AWTEventListener {
            override fun eventDispatched(event: AWTEvent) {
                if (System.currentTimeMillis() - armedAt < DISMISS_GRACE_MS) return
                if (event.id == MouseEvent.MOUSE_RELEASED) return
                toolkit.removeAWTEventListener(this)
                if (dismissWatcher === this) dismissWatcher = null
                onDismiss()
            }
        }
        dismissWatcher = listener
        toolkit.addAWTEventListener(
            listener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.KEY_EVENT_MASK
        )
    }

    private fun clearDismissWatcher() {
        dismissWatcher?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        dismissWatcher = null
    }

    private fun materialize(
        menu: java.awt.Menu,
        ops: List<NativeMenuOp>,
        isCurrent: () -> Boolean,
        onDismiss: () -> Unit
    ) {
        ops.forEach { op ->
            when (op) {
                is NativeMenuOp.Separator -> menu.addSeparator()

                is NativeMenuOp.Item -> menu.add(
                    java.awt.MenuItem(op.label).apply {
                        isEnabled = op.enabled
                        op.shortcut?.let { shortcut = java.awt.MenuShortcut(it.code) }
                        addActionListener {
                            if (isCurrent()) op.action()
                            onDismiss()
                        }
                    }
                )

                is NativeMenuOp.Submenu -> menu.add(
                    java.awt.Menu(op.label).also { materialize(it, op.ops, isCurrent, onDismiss) }
                )
            }
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private companion object {
        /** Long enough to outlast the opening right-click's own event tail. */
        const val DISMISS_GRACE_MS = 300L
    }
}

/**
 * Small person silhouette (head + shoulders) drawn in Java2D for the signed-in account menu row.
 * Java2D vector glyph, not a font character — so it can't trigger the emoji-font fallback that
 * corrupts AWT menu text metrics.
 */
private val AccountGlyphIcon = object : javax.swing.Icon {
    private val size = 14
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            g2.color = BossUiTheme.current.mist.toAwtColor()
            // Head: a circle in the upper-middle.
            g2.fill(java.awt.geom.Ellipse2D.Float(x + 4f, y + 1.5f, 6f, 6f))
            // Shoulders: a half-disc clipped to the lower band so it reads as a torso, not a full circle.
            val shoulders = java.awt.geom.Ellipse2D.Float(x + 1.5f, y + 8.5f, 11f, 11f)
            g2.clip(java.awt.geom.Rectangle2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), (size - 1).toFloat()))
            g2.fill(shoulders)
        } finally {
            g2.dispose()
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
    onOpenFolder: (() -> Unit)? = null,
    onNewTab: (() -> Unit)? = null,
    onSwitchShell: ((String) -> Unit)? = null,  // Windows: switch current tab's shell
    onSplitVertical: (() -> Unit)? = null,
    onSplitHorizontal: (() -> Unit)? = null,
    onMoveToNewTab: (() -> Unit)? = null,
    onClosePane: (() -> Unit)? = null,
    onShowDebug: (() -> Unit)? = null,
    onShowSettings: (() -> Unit)? = null,
    onShowWelcomeWizard: (() -> Unit)? = null,
    customItems: List<ai.rever.bossterm.compose.ContextMenuElement> = emptyList()
): List<ContextMenuController.MenuElement> {
    val baseItems = listOf(
        ContextMenuController.MenuItem(
            id = "copy",
            label = "Copy",
            enabled = hasSelection,
            action = onCopy,
            shortcut = 'C'
        ),
        ContextMenuController.MenuItem(
            id = "paste",
            label = "Paste",
            enabled = true,
            action = onPaste,
            shortcut = 'V'
        ),
        ContextMenuController.MenuItem(
            id = "select_all",
            label = "Select All",
            enabled = true,
            action = onSelectAll,
            shortcut = 'A'
        ),
        ContextMenuController.MenuItem(
            id = "find",
            label = "Find...",
            enabled = true,
            action = onFind,
            shortcut = 'F'
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

    // Add "Open Folder" option
    val folderItems = if (onOpenFolder != null) {
        listOf(
            ContextMenuController.MenuSeparator(id = "separator_folder"),
            ContextMenuController.MenuItem(
                id = "open_folder",
                label = "Open Folder...",
                enabled = true,
                action = onOpenFolder
            )
        )
    } else {
        emptyList()
    }

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
    val tabItems = mutableListOf<ContextMenuController.MenuElement>()

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

    if (onShowSettings != null || onShowDebug != null || onShowWelcomeWizard != null) {
        extraItems.add(
            ContextMenuController.MenuItem(
                id = "separator_extra",
                label = "",
                enabled = false,
                action = {}
            )
        )
    }

    if (onShowWelcomeWizard != null) {
        extraItems.add(
            ContextMenuController.MenuItem(
                id = "show_welcome_wizard",
                label = "Welcome Wizard...",
                enabled = true,
                action = onShowWelcomeWizard
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

    return baseItems + folderItems + splitItems + tabItems + extraItems + customMenuItems
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
    onOpenFolder: (() -> Unit)? = null,
    onNewTab: (() -> Unit)? = null,
    onSwitchShell: ((String) -> Unit)? = null,
    onSplitVertical: (() -> Unit)? = null,
    onSplitHorizontal: (() -> Unit)? = null,
    onMoveToNewTab: (() -> Unit)? = null,
    onClosePane: (() -> Unit)? = null,
    onShowDebug: (() -> Unit)? = null,
    onShowSettings: (() -> Unit)? = null,
    onShowWelcomeWizard: (() -> Unit)? = null,
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
        onOpenFolder = onOpenFolder,
        onNewTab = onNewTab,
        onSwitchShell = onSwitchShell,
        onSplitVertical = onSplitVertical,
        onSplitHorizontal = onSplitHorizontal,
        onMoveToNewTab = onMoveToNewTab,
        onClosePane = onClosePane,
        onShowDebug = onShowDebug,
        onShowSettings = onShowSettings,
        onShowWelcomeWizard = onShowWelcomeWizard,
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
    onOpenFolder: (() -> Unit)? = null,
    onNewTab: (() -> Unit)? = null,
    onSwitchShell: ((String) -> Unit)? = null,
    onSplitVertical: (() -> Unit)? = null,
    onSplitHorizontal: (() -> Unit)? = null,
    onMoveToNewTab: (() -> Unit)? = null,
    onClosePane: (() -> Unit)? = null,
    onShowDebug: (() -> Unit)? = null,
    onShowSettings: (() -> Unit)? = null,
    onShowWelcomeWizard: (() -> Unit)? = null,
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
        onOpenFolder = onOpenFolder,
        onNewTab = onNewTab,
        onSwitchShell = onSwitchShell,
        onSplitVertical = onSplitVertical,
        onSplitHorizontal = onSplitHorizontal,
        onMoveToNewTab = onMoveToNewTab,
        onClosePane = onClosePane,
        onShowDebug = onShowDebug,
        onShowSettings = onShowSettings,
        onShowWelcomeWizard = onShowWelcomeWizard,
        customItems = customItems
    )
    controller.showMenu(x, y, hyperlinkItems + terminalItems, window)
}
