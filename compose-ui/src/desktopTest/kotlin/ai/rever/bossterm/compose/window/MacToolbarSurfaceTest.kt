package ai.rever.bossterm.compose.window

import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacToolbarSurfaceTest {
    private val window = Pointer(1)
    private val light = Pointer(2)
    private val dark = Pointer(3)
    private val fullscreenHost = Pointer(4)

    private inner class AppKit : MacToolbarSurfaceApi {
        var mask = 1L or 8L or 32768L // titled, resizable, full-size content (not fullscreen)
        var theme: Pointer? = light
        var host: Pointer? = window
        val transparency = mutableMapOf<Pointer, Boolean>()
        val appearances = mutableMapOf<Pointer, Pointer?>()
        override fun styleMask(window: Pointer) = mask
        override fun appearance(window: Pointer) = theme
        override fun toolbarWindow(window: Pointer) = host
        override fun setTransparent(window: Pointer, transparent: Boolean) {
            transparency[window] = transparent
        }
        override fun setAppearance(window: Pointer, appearance: Pointer?) {
            appearances[window] = appearance
        }
        fun refresh() = synchronizeMacToolbarSurface(window, this)
    }

    @Test fun fullscreenHostUsesTheTerminalThemeAndPaintsItsOwnMaterial() {
        val api = AppKit()
        api.refresh()
        assertTrue(api.transparency.getValue(window))
        api.mask = api.mask or (1L shl 14)
        api.host = fullscreenHost
        api.refresh()
        assertFalse(api.transparency.getValue(window))
        assertFalse(api.transparency.getValue(fullscreenHost))
        assertEquals(light, api.appearances[fullscreenHost])

        api.theme = dark
        api.refresh()
        assertEquals(dark, api.appearances[fullscreenHost])
        api.theme = light
        api.refresh()
        assertEquals(light, api.appearances[fullscreenHost])
    }

    @Test fun replacedHostsAreRefreshedWithoutTouchingTheOldHost() {
        val api = AppKit().apply {
            mask = mask or (1L shl 14)
            host = fullscreenHost
        }
        api.refresh()
        val replacement = Pointer(5)
        api.host = replacement
        api.transparency.clear()
        api.appearances.clear()
        api.refresh()
        assertEquals(mapOf<Pointer, Pointer?>(replacement to light), api.appearances)
        assertEquals(mapOf(window to false, replacement to false), api.transparency)
    }

    @Test fun leavingFullscreenRestoresComposeBackingWithoutTouchingDestroyedHost() {
        val api = AppKit().apply {
            mask = mask or (1L shl 14)
            host = fullscreenHost
        }
        api.refresh()
        api.mask = api.mask and (1L shl 14).inv()
        api.host = window
        api.transparency.clear()
        api.appearances.clear()
        api.refresh()
        assertEquals(mapOf(window to true), api.transparency)
        assertTrue(api.appearances.isEmpty())
    }

    @Test fun fullscreenWithoutSeparateHostStillPaintsMaterial() {
        val api = AppKit().apply { mask = mask or (1L shl 14) }
        api.refresh()
        assertEquals(mapOf(window to false), api.transparency)
        api.host = null // traffic-light view can be detached during a transition
        api.refresh()
        assertEquals(mapOf(window to false), api.transparency)
        assertTrue(api.appearances.isEmpty())
    }
}
