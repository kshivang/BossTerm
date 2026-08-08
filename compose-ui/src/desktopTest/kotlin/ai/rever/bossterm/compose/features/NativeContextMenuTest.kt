package ai.rever.bossterm.compose.features

import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ----- the dismissal heuristic -----

    @Test
    fun `nothing inside the grace window counts as dismissal`() {
        // The opening right-click's own MOUSE_RELEASED can still be in flight here.
        assertFalse(AwtNativeContextMenuRenderer.isDismissalEvent(0))
        assertFalse(
            AwtNativeContextMenuRenderer.isDismissalEvent(
                AwtNativeContextMenuRenderer.DISMISS_GRACE_MS - 1
            )
        )
    }

    @Test
    fun `any event after the grace window counts as dismissal`() {
        // An open menu holds the input grab, so anything reaching us is genuinely post-dismissal.
        assertTrue(
            AwtNativeContextMenuRenderer.isDismissalEvent(
                AwtNativeContextMenuRenderer.DISMISS_GRACE_MS
            )
        )
        assertTrue(AwtNativeContextMenuRenderer.isDismissalEvent(5_000))
    }

    // ----- the embedder override -----

    @Test
    fun `an embedder override wins over the settings file`() {
        try {
            NativeContextMenuOverride.set(false)
            assertFalse(nativeMenusPreferred(), "an explicit override of false must be honoured")

            NativeContextMenuOverride.set(true)
            // True everywhere except Linux, which is gated separately.
            assertEquals(
                !ai.rever.bossterm.compose.shell.ShellCustomizationUtils.isLinux(),
                nativeMenusPreferred()
            )
        } finally {
            NativeContextMenuOverride.set(null)
        }
    }

    @Test
    fun `clearing the override falls back to the settings file`() {
        NativeContextMenuOverride.set(false)
        NativeContextMenuOverride.set(null)
        assertNull(NativeContextMenuOverride.current())
    }

    @Test
    fun `linux never gets native menus regardless of the setting`() {
        assertFalse(shouldUseNativeMenus(settingEnabled = true, isLinux = true))
        assertFalse(shouldUseNativeMenus(settingEnabled = false, isLinux = true))
    }

    @Test
    fun `elsewhere the setting decides`() {
        assertTrue(shouldUseNativeMenus(settingEnabled = true, isLinux = false))
        assertFalse(shouldUseNativeMenus(settingEnabled = false, isLinux = false))
    }
}
