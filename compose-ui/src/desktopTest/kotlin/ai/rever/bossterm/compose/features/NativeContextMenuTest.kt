package ai.rever.bossterm.compose.features

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the parts of the native context menu that a headless CI run can actually see: the
 * model-to-native mapping and the platform/settings gate.
 *
 * Deliberately no AWT here - constructing any `java.awt.MenuComponent` throws
 * `HeadlessException`, which is why [planNativeMenu] exists as a toolkit-independent step.
 */
class NativeContextMenuTest {

    private fun item(id: String, label: String = id, enabled: Boolean = true, shortcut: Char? = null) =
        ContextMenuController.MenuItem(id, label, enabled, {}, shortcut)

    @Test
    fun `plain items keep label, enabled state and shortcut`() {
        val ops = planNativeMenu(
            listOf(
                item("copy", "Copy", enabled = false, shortcut = 'C'),
                item("paste", "Paste")
            )
        )

        assertEquals(2, ops.size)
        val copy = ops[0] as NativeMenuOp.Item
        assertEquals("Copy", copy.label)
        assertFalse(copy.enabled, "a disabled model item must stay disabled natively")
        assertEquals('C', copy.shortcut)

        val paste = ops[1] as NativeMenuOp.Item
        assertTrue(paste.enabled)
        assertNull(paste.shortcut)
    }

    @Test
    fun `items whose id starts with separator become separators`() {
        // createHyperlinkContextMenuItems encodes a separator as a MenuItem, not a MenuSeparator.
        val ops = planNativeMenu(listOf(item("separator_hyperlink", label = "")))
        assertEquals(listOf(NativeMenuOp.Separator), ops)
    }

    @Test
    fun `an unlabelled separator maps to exactly one separator`() {
        val ops = planNativeMenu(listOf(ContextMenuController.MenuSeparator("separator_folder")))
        assertEquals(listOf(NativeMenuOp.Separator), ops)
    }

    @Test
    fun `a labelled separator becomes a separator plus a disabled header item`() {
        val ops = planNativeMenu(
            listOf(ContextMenuController.MenuSeparator("custom_section_ai", label = "AI Assistants"))
        )

        assertEquals(2, ops.size)
        assertEquals(NativeMenuOp.Separator, ops[0])
        val header = ops[1] as NativeMenuOp.Item
        assertEquals("AI Assistants", header.label)
        assertFalse(header.enabled, "a section header must not be selectable")
    }

    @Test
    fun `submenus nest and degrade their children the same way`() {
        val ops = planNativeMenu(
            listOf(
                ContextMenuController.MenuSubmenu(
                    id = "share",
                    label = "Share",
                    items = listOf(
                        item("share_tab", "Tab..."),
                        ContextMenuController.MenuSeparator("sep", label = "Scope"),
                        ContextMenuController.MenuSubmenu("deeper", "More", listOf(item("x", "X")))
                    )
                )
            )
        )

        val submenu = ops.single() as NativeMenuOp.Submenu
        assertEquals("Share", submenu.label)
        // Tab..., separator, disabled "Scope" header, nested submenu
        assertEquals(4, submenu.ops.size)
        assertEquals(NativeMenuOp.Separator, submenu.ops[1])
        assertFalse((submenu.ops[2] as NativeMenuOp.Item).enabled)

        val nested = submenu.ops[3] as NativeMenuOp.Submenu
        assertEquals("More", nested.label)
        assertEquals("X", (nested.ops.single() as NativeMenuOp.Item).label)
    }

    @Test
    fun `item actions are carried through unchanged`() {
        var fired = 0
        val ops = planNativeMenu(
            listOf(ContextMenuController.MenuItem("copy", "Copy", true, { fired += 1 }))
        )
        (ops.single() as NativeMenuOp.Item).action()
        assertEquals(1, fired)
    }

    // ----- renderer selection and the menuVisible / staleness lifecycle -----

    /** One `show()` call, kept individually so a test can act on an older menu specifically. */
    private class ShownMenu(val isCurrent: () -> Boolean, val onDismiss: () -> Unit) {
        /** Simulate the user picking an item on this menu. */
        fun fireItem(action: () -> Unit) {
            if (isCurrent()) action()
            onDismiss()
        }
    }

    private class FakeRenderer : ContextMenuRenderer {
        val shown = mutableListOf<ShownMenu>()
        var hides = 0

        val shows get() = shown.size
        val last get() = shown.last()

        override fun show(
            anchor: MenuAnchor,
            preferredInvoker: androidx.compose.ui.awt.ComposeWindow?,
            items: List<ContextMenuController.MenuElement>,
            isCurrent: () -> Boolean,
            onDismiss: () -> Unit
        ) {
            shown += ShownMenu(isCurrent, onDismiss)
        }

        override fun hide() { hides += 1 }
    }

    private fun controller(
        native: FakeRenderer,
        swing: FakeRenderer,
        nativePreferred: Boolean
    ) = ContextMenuController(
        swingRenderer = swing,
        nativeRenderer = native,
        nativePreferred = { nativePreferred }
    )

    @Test
    fun `the gate picks the renderer`() {
        val native = FakeRenderer()
        val swing = FakeRenderer()

        controller(native, swing, nativePreferred = true).showMenuAtScreenPosition(1, 1, emptyList())
        assertEquals(1, native.shows)
        assertEquals(0, swing.shows)

        controller(native, swing, nativePreferred = false).showMenuAtScreenPosition(1, 1, emptyList())
        assertEquals(1, native.shows)
        assertEquals(1, swing.shows)
    }

    @Test
    fun `menuVisible goes true on show and false on dismissal`() {
        val native = FakeRenderer()
        val c = controller(native, FakeRenderer(), nativePreferred = true)

        assertFalse(c.menuVisible.value)
        c.showMenuAtScreenPosition(1, 1, emptyList())
        assertTrue(c.menuVisible.value)

        native.last.onDismiss()
        assertFalse(c.menuVisible.value, "a dismissed menu must not leave the flag stuck true")
    }

    @Test
    fun `hideMenu clears visibility even though a native menu cannot be dismissed`() {
        val native = FakeRenderer()
        val c = controller(native, FakeRenderer(), nativePreferred = true)

        c.showMenuAtScreenPosition(1, 1, emptyList())
        c.hideMenu()

        assertFalse(c.menuVisible.value)
        assertEquals(1, native.hides)
    }

    @Test
    fun `an item from a menu the controller has moved past does not run`() {
        // The whole correctness argument for hideMenu() being advisory on the native path: the
        // menu can still be on screen and clickable, but its actions must be inert.
        val native = FakeRenderer()
        val c = controller(native, FakeRenderer(), nativePreferred = true)

        c.showMenuAtScreenPosition(1, 1, emptyList())
        val stale = native.last
        c.hideMenu()

        var ran = false
        stale.fireItem { ran = true }
        assertFalse(ran, "a stale menu item must not act on state the caller already tore down")
    }

    @Test
    fun `a menu replaced by a newer one is also inert`() {
        val native = FakeRenderer()
        val c = controller(native, FakeRenderer(), nativePreferred = true)

        c.showMenuAtScreenPosition(1, 1, emptyList())
        val first = native.last
        c.showMenuAtScreenPosition(2, 2, emptyList())

        var ran = false
        first.fireItem { ran = true }
        assertFalse(ran, "the previous menu's items must not fire once a new menu is up")
    }

    @Test
    fun `a stale dismissal cannot clear the visibility of a newer menu`() {
        val native = FakeRenderer()
        val c = controller(native, FakeRenderer(), nativePreferred = true)

        c.showMenuAtScreenPosition(1, 1, emptyList())
        val first = native.last
        c.showMenuAtScreenPosition(2, 2, emptyList())

        // The first menu's dismissal watcher fires late; the second menu is the live one.
        first.onDismiss()
        assertTrue(c.menuVisible.value, "an older menu's dismissal must not close out the new one")
    }

    @Test
    fun `an item still runs while its menu is the current one`() {
        val native = FakeRenderer()
        val c = controller(native, FakeRenderer(), nativePreferred = true)

        c.showMenuAtScreenPosition(1, 1, emptyList())
        var ran = false
        native.last.fireItem { ran = true }
        assertTrue(ran)
        assertFalse(c.menuVisible.value, "selecting an item dismisses the menu")
    }

    // ----- Windows mnemonic escaping -----

    @Test
    fun `ampersands are doubled on windows only`() {
        // A git branch named feat/a&b would otherwise render as "feat/ab" with an underlined b.
        val items = listOf(item("branch", "feat/a&b"))

        assertEquals(
            "feat/a&&b",
            (planNativeMenu(items, isWindows = true).single() as NativeMenuOp.Item).label
        )
        assertEquals(
            "feat/a&b",
            (planNativeMenu(items, isWindows = false).single() as NativeMenuOp.Item).label
        )
    }

    @Test
    fun `escaping reaches section headers and submenu labels too`() {
        val ops = planNativeMenu(
            listOf(
                ContextMenuController.MenuSeparator("sec", label = "A&B"),
                ContextMenuController.MenuSubmenu("sub", "C&D", listOf(item("x", "E&F")))
            ),
            isWindows = true
        )

        assertEquals("A&&B", (ops[1] as NativeMenuOp.Item).label)
        val submenu = ops[2] as NativeMenuOp.Submenu
        assertEquals("C&&D", submenu.label)
        assertEquals("E&&F", (submenu.ops.single() as NativeMenuOp.Item).label)
    }

    // ----- invoker selection (the "largest window" bug this PR fixes) -----

    private fun candidate(
        name: String,
        active: Boolean = false,
        w: Int = 100,
        h: Int = 100,
        x: Int = 0,
        y: Int = 0,
        frameOrDialog: Boolean = true,
        showing: Boolean = true
    ) = InvokerCandidate(
        window = name,
        isFrameOrDialog = frameOrDialog,
        isShowing = showing,
        isActive = active,
        bounds = java.awt.Rectangle(x, y, w, h)
    )

    @Test
    fun `an active window wins over a larger inactive one`() {
        val picked = pickInvoker(
            listOf(candidate("big", w = 2000, h = 2000), candidate("active", active = true)),
            at = null
        )
        assertEquals("active", picked?.window)
    }

    @Test
    fun `among inactive windows the smallest wins, not the largest`() {
        // The old rule picked the largest, which is the one most likely to be UNDERNEATH.
        val picked = pickInvoker(
            listOf(candidate("fullscreen", w = 3000, h = 2000), candidate("small", w = 400, h = 300)),
            at = null
        )
        assertEquals("small", picked?.window)
    }

    @Test
    fun `heavyweight popup windows and hidden windows are not eligible`() {
        // getWindows() also returns the heavyweight windows Swing makes for popups.
        val picked = pickInvoker(
            listOf(
                candidate("popup", frameOrDialog = false, w = 10, h = 10),
                candidate("hidden", showing = false, w = 20, h = 20),
                candidate("frame", w = 900, h = 900)
            ),
            at = null
        )
        assertEquals("frame", picked?.window)
    }

    @Test
    fun `only windows containing the point are eligible`() {
        val picked = pickInvoker(
            listOf(
                candidate("left", x = 0, y = 0, w = 100, h = 100),
                candidate("right", x = 500, y = 500, w = 300, h = 300)
            ),
            at = java.awt.Point(600, 600)
        )
        assertEquals("right", picked?.window)
    }

    @Test
    fun `no eligible window yields null rather than an arbitrary one`() {
        assertNull(pickInvoker(listOf(candidate("hidden", showing = false)), at = null))
        assertNull(pickInvoker(emptyList<InvokerCandidate<String>>(), at = null))
    }

    // ----- the dismissal heuristic -----

    private val grace = AwtNativeContextMenuRenderer.DISMISS_GRACE_MS
    private val moved = java.awt.event.MouseEvent.MOUSE_MOVED
    private val released = java.awt.event.MouseEvent.MOUSE_RELEASED

    @Test
    fun `nothing inside the grace window counts as dismissal`() {
        assertFalse(AwtNativeContextMenuRenderer.isDismissalEvent(moved, 0))
        assertFalse(AwtNativeContextMenuRenderer.isDismissalEvent(moved, grace - 1))
    }

    @Test
    fun `an ordinary event after the grace window counts as dismissal`() {
        // An open menu holds the input grab, so anything reaching us is genuinely post-dismissal.
        assertTrue(AwtNativeContextMenuRenderer.isDismissalEvent(moved, grace))
        assertTrue(AwtNativeContextMenuRenderer.isDismissalEvent(moved, 5_000))
    }

    @Test
    fun `a mouse release never counts, however late it arrives`() {
        // Wall-clock alone is not enough: under a busy EDT the opening right-click's own release
        // can be dispatched well after the grace window expires.
        assertFalse(AwtNativeContextMenuRenderer.isDismissalEvent(released, grace + 1))
        assertFalse(AwtNativeContextMenuRenderer.isDismissalEvent(released, 60_000))
    }

    // ----- the embedder override -----

    private fun <T> withOverride(value: Boolean?, block: () -> T): T =
        try {
            NativeContextMenuOverride.set(value)
            block()
        } finally {
            NativeContextMenuOverride.set(null)
        }

    // The override's effect is asserted through shouldUseNativeMenus with an injected platform,
    // so these have teeth on a Linux CI runner too. Reading nativeMenusPreferred() directly would
    // collapse to `false == false` off macOS and pass even if the override were ignored.
    private fun gate(override: Boolean?, fileSetting: Boolean, isMacOs: Boolean) =
        shouldUseNativeMenus(settingEnabled = override ?: fileSetting, isMacOs = isMacOs)

    @Test
    fun `an embedder override wins over the settings file`() {
        assertFalse(gate(override = false, fileSetting = true, isMacOs = true))
        assertTrue(gate(override = true, fileSetting = false, isMacOs = true))
    }

    @Test
    fun `with no override the settings file decides`() {
        assertTrue(gate(override = null, fileSetting = true, isMacOs = true))
        assertFalse(gate(override = null, fileSetting = false, isMacOs = true))
    }

    @Test
    fun `set returns the previous value so siblings can restore it`() {
        // TabbedTerminal relies on this: unmounting one instance must not clear another's override.
        assertNull(NativeContextMenuOverride.set(true))
        try {
            val previous = NativeContextMenuOverride.set(false)
            assertEquals(true, previous)
            NativeContextMenuOverride.set(previous)
            assertEquals(true, NativeContextMenuOverride.current())
        } finally {
            NativeContextMenuOverride.set(null)
        }
        assertNull(NativeContextMenuOverride.current())
    }

    @Test
    fun `an unreadable settings source falls back to native rather than throwing`() {
        withOverride(null) {
            // nativeMenusPreferred wraps the SettingsManager read in runCatching; whatever it
            // returns, it must not propagate.
            nativeMenusPreferred()
        }
    }

    // ----- anchor arithmetic -----

    @Test
    fun `a screen anchor is converted relative to the invoker origin`() {
        val at = MenuAnchor.Screen(1200, 830).toInvokerCoordinates(java.awt.Point(1000, 800))
        assertEquals(200, at.x)
        assertEquals(30, at.y)
    }

    @Test
    fun `an invoker-relative anchor is passed through untouched`() {
        // The MouseInfo-null fallback: the offset is already invoker-local.
        val at = MenuAnchor.RelativeToInvoker(40, 60).toInvokerCoordinates(java.awt.Point(1000, 800))
        assertEquals(40, at.x)
        assertEquals(60, at.y)
    }

    @Test
    fun `a screen anchor on a second monitor can convert to negative coordinates`() {
        val at = MenuAnchor.Screen(-300, 50).toInvokerCoordinates(java.awt.Point(0, 0))
        assertEquals(-300, at.x)
        assertEquals(50, at.y)
    }

    // ----- shortcut contract -----

    @Test
    fun `a shortcut that is not a VK constant is rejected at construction`() {
        // 'c'.code is 99, which is not KeyEvent.VK_C and would render as something arbitrary.
        assertFailsWith<IllegalArgumentException> {
            ContextMenuController.MenuItem("copy", "Copy", true, {}, shortcut = 'c')
        }
        // Valid ones still build.
        ContextMenuController.MenuItem("copy", "Copy", true, {}, shortcut = 'C')
        ContextMenuController.MenuItem("one", "One", true, {}, shortcut = '1')
        ContextMenuController.MenuItem("none", "None", true, {}, shortcut = null)
    }

    @Test
    fun `only macOS gets native menus, and only if the setting allows`() {
        assertTrue(shouldUseNativeMenus(settingEnabled = true, isMacOs = true))
        assertFalse(shouldUseNativeMenus(settingEnabled = false, isMacOs = true))
    }

    @Test
    fun `windows and linux stay on the themed menu even with the setting on`() {
        // Windows is deliberately not enabled: TrackPopupMenu is unverified here and may block
        // the EDT. Linux's XAWT peer ignores GTK. Widening is one predicate.
        assertFalse(shouldUseNativeMenus(settingEnabled = true, isMacOs = false))
        assertFalse(shouldUseNativeMenus(settingEnabled = false, isMacOs = false))
    }
}
