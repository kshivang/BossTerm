package ai.rever.bossterm.compose.window

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.IntByReference
import kotlin.test.*

class NativeGlassBackendTest {
    private class Dwm : DwmGlassApi {
        var supported = true
        var composition = true
        var extendFails = false
        val attributes = mutableListOf<Pair<Int, Int>>()
        val margins = mutableListOf<Int>()
        override fun DwmIsCompositionEnabled(enabled: IntByReference): Int {
            enabled.value = if (composition) 1 else 0
            return 0
        }
        override fun DwmGetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int) =
            if (supported) 0 else -1
        override fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int {
            assertEquals(4, size)
            attributes += attribute to value.value
            return 0
        }
        override fun DwmExtendFrameIntoClientArea(window: Pointer, margins: GlassMargins): Int {
            assertEquals(16, margins.size())
            this.margins += margins.left
            return if (extendFails && margins.left == -1) -1 else 0
        }
    }

    @Test fun unsupportedWindowsNeverPreparesATransparentSurface() {
        val api = Dwm().apply { supported = false }
        val effect = WindowsAcrylicEffect(api, Pointer(1), { true }, { fail("must remain opaque") }, {})
        assertFalse(effect.update(true, true))
        assertTrue(api.attributes.isEmpty())
        effect.close()
    }

    @Test fun windowsFailureRollsBackAndSuccessReusesTheSurface() {
        val api = Dwm().apply { extendFails = true }
        var prepared = 0
        var restored = 0
        val effect = WindowsAcrylicEffect(api, Pointer(1), { true }, { prepared++; true }, { restored++ })
        assertFalse(effect.update(true, true))
        assertEquals(1, restored)
        assertTrue((38 to 1) in api.attributes)
        assertEquals(0, api.margins.last())
        api.extendFails = false
        assertTrue(effect.update(true, true))
        assertEquals(2, prepared)
        repeat(3) { assertTrue(effect.update(true, true)) }
        assertEquals(2, prepared)
        api.composition = false
        assertFalse(effect.update(true, true))
        assertEquals(2, restored)
        effect.close()
        assertFalse(effect.update(true, true))
    }

    @Test fun windowsDisposalDoesNotTouchADestroyedHwnd() {
        val api = Dwm()
        var displayable = true
        var restored = false
        val effect = WindowsAcrylicEffect(api, Pointer(1), { displayable }, { true }, { restored = true })
        assertTrue(effect.update(true, false))
        val callCount = api.attributes.size
        displayable = false
        effect.close()
        assertEquals(callCount, api.attributes.size)
        assertTrue(restored)
    }

    private class Xlib : GlassXlib {
        var compositor = true
        var advertised = true
        var writes = 0
        var deletes = 0
        var freed = 0
        var closed = 0
        var failDelete = false
        private val atoms = Memory(Native.LONG_SIZE.toLong()).apply { setNativeLong(0, NativeLong(42)) }
        override fun XOpenDisplay(name: String?) = Pointer(1)
        override fun XCloseDisplay(display: Pointer): Int { closed++; return 0 }
        override fun XScreenCount(display: Pointer) = 2
        override fun XQueryTree(display: Pointer, window: NativeLong, root: NativeLongByReference,
                                parent: NativeLongByReference, children: PointerByReference, count: IntByReference): Int {
            root.value = NativeLong(2)
            return 1
        }
        override fun XRootWindow(display: Pointer, screen: Int) = NativeLong((screen + 1).toLong())
        override fun XInternAtom(display: Pointer, name: String, onlyIfExists: Boolean) = NativeLong(42)
        override fun XGetSelectionOwner(display: Pointer, selection: NativeLong) = NativeLong(if (compositor) 1 else 0)
        override fun XListProperties(display: Pointer, window: NativeLong, count: IntByReference): Pointer {
            count.value = if (advertised) 1 else 0
            return atoms
        }
        override fun XChangeProperty(display: Pointer, window: NativeLong, property: NativeLong, type: NativeLong,
                                     format: Int, mode: Int, data: Pointer?, count: Int): Int {
            assertEquals(6L, type.toLong()) // XA_CARDINAL
            assertEquals(32, format)
            assertEquals(0, count) // whole window
            assertNull(data)
            writes++
            return 1
        }
        override fun XDeleteProperty(display: Pointer, window: NativeLong, property: NativeLong): Int {
            if (failDelete) throw IllegalStateException("display failure")
            deletes++
            return 1
        }
        override fun XFlush(display: Pointer) = 1
        override fun XFree(pointer: Pointer): Int { freed++; return 1 }
    }

    @Test fun linuxRequiresAdvertisementAndReactsToCompositorChanges() {
        val api = Xlib().apply { advertised = false }
        val effect = LinuxWindowGlass({ true }, api, Pointer(1), NativeLong(2), NativeLong(1), NativeLong(3), NativeLong(42))
        assertFalse(effect.update(true, true))
        assertEquals(0, api.writes)
        api.advertised = true
        assertTrue(effect.update(true, true))
        assertTrue(effect.update(true, false))
        assertEquals(1, api.writes)
        api.compositor = false
        assertFalse(effect.update(true, true))
        assertEquals(1, api.deletes)
        api.compositor = true
        assertTrue(effect.update(true, true))
        effect.close()
        effect.close()
        assertEquals(2, api.deletes)
        assertEquals(1, api.closed)
        assertTrue(api.freed > 0)
        assertFalse(effect.update(true, true))
    }
    @Test fun linuxUsesTheWindowsRootRatherThanTheDefaultScreen() {
        val api = Xlib()
        assertEquals(1, screenForGlassRoot(api, Pointer(1), NativeLong(2)))
        assertNull(screenForGlassRoot(api, Pointer(1), NativeLong(99)))
    }

    @Test fun linuxClosesTheConnectionEvenWhenPropertyCleanupFails() {
        val api = Xlib()
        val effect = LinuxWindowGlass({ true }, api, Pointer(1), NativeLong(2), NativeLong(1), NativeLong(3), NativeLong(42))
        assertTrue(effect.update(true, true))
        api.failDelete = true
        assertFailsWith<IllegalStateException> { effect.close() }
        assertEquals(1, api.closed)
        effect.close()
        assertEquals(1, api.closed)
    }

    @Test fun windowsNativeLinkageFailureRestoresTheSurface() {
        var restores = 0
        val effect = WindowsAcrylicEffect(Dwm(), Pointer(1), { true },
            { throw UnsatisfiedLinkError("missing native entrypoint") }, { restores++ })
        assertFailsWith<UnsatisfiedLinkError> { effect.update(true, true) }
        assertEquals(1, restores)
        effect.close()
        assertEquals(1, restores)
    }

    @Test fun dragAndResizeUseAbsoluteScreenDisplacement() {
        val bounds = java.awt.Rectangle(-900, 100, 750, 580)
        val press = java.awt.Point(-700, 120)
        val minimum = java.awt.Dimension(350, 250)
        assertEquals(java.awt.Rectangle(-860, 120, 750, 580),
            auxiliaryDragBounds(bounds, press, java.awt.Point(-660, 140), false, minimum))
        // Repeated updates derive from the press, not the moving component's local coordinates.
        assertEquals(java.awt.Rectangle(-850, 130, 750, 580),
            auxiliaryDragBounds(bounds, press, java.awt.Point(-650, 150), false, minimum))
        assertEquals(java.awt.Rectangle(-900, 100, 800, 610),
            auxiliaryDragBounds(bounds, press, java.awt.Point(-650, 150), true, minimum))
        assertEquals(java.awt.Rectangle(-900, 100, 350, 250),
            auxiliaryDragBounds(bounds, press, java.awt.Point(-1700, -880), true, minimum))
    }

    @Test fun windowsClearsAllSwingAncestorsAndRestoresTheirIndividualFlags() {
        val root = javax.swing.JPanel().apply { isOpaque = false }
        val content = javax.swing.JPanel()
        val compose = javax.swing.JPanel()
        val skia = javax.swing.JPanel()
        val sibling = javax.swing.JPanel()
        root.add(content)
        content.add(compose)
        compose.add(skia)
        content.add(sibling)
        val opacity = SwingGlassOpacity(root, skia)
        opacity.enable()
        assertFalse(root.isOpaque)
        assertFalse(content.isOpaque)
        assertFalse(compose.isOpaque)
        assertFalse(skia.isOpaque)
        assertTrue(sibling.isOpaque)
        opacity.restore()
        assertFalse(root.isOpaque)
        assertTrue(content.isOpaque)
        assertTrue(compose.isOpaque)
        assertTrue(skia.isOpaque)
    }

}
