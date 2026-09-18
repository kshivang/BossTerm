package ai.rever.bossterm.compose.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.IntByReference
import java.awt.Toolkit
import java.awt.Window

/**
 * KWin's X11 blur hint, also applicable to AWT X11 windows running under XWayland.
 * Never reinterpret a native Wayland surface pointer as an XID. The JDK 17 desktop
 * backend is X11; a future native Wayland backend needs its own wl_surface integration.
 */
internal class LinuxWindowGlass(
    private val isDisplayable: () -> Boolean,
    private val xlib: GlassXlib,
    private val display: Pointer,
    private val xid: NativeLong,
    private val root: NativeLong,
    private val compositor: NativeLong,
    private val blur: NativeLong
) : DesktopGlassBackend {
    private var applied = false
    private var closed = false

    private fun available(): Boolean {
        if (xlib.XGetSelectionOwner(display, compositor).toLong() == 0L) return false
        // KWin advertises the blur atom on the root only while its blur effect is enabled.
        // Atom existence alone is NOT evidence of compositor support.
        val count = IntByReference()
        val properties = xlib.XListProperties(display, root, count) ?: return false
        return try {
            (0 until count.value.coerceIn(0, 65536)).any {
                properties.getNativeLong(it.toLong() * Native.LONG_SIZE).toLong() == blur.toLong()
            }
        } finally { xlib.XFree(properties) }
    }

    override fun update(enabled: Boolean, dark: Boolean): Boolean {
        if (closed || !isDisplayable()) return false
        val supported = enabled && available()
        if (supported && !applied) {
            // An empty CARDINAL region means the entire window. Compose paints opaque
            // terminal content above it when the user selects only tab/top-bar coverage.
            xlib.XChangeProperty(display, xid, blur, NativeLong(6), 32, 0, null, 0)
            xlib.XFlush(display)
            applied = true
        } else if (!supported && applied) {
            xlib.XDeleteProperty(display, xid, blur)
            xlib.XFlush(display)
            applied = false
        }
        return supported
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (applied && isDisplayable()) {
                xlib.XDeleteProperty(display, xid, blur)
                xlib.XFlush(display)
            }
        } finally {
            applied = false
            xlib.XCloseDisplay(display)
        }
    }

    companion object {
        fun create(window: Window): LinuxWindowGlass? {
            if (!Toolkit.getDefaultToolkit().javaClass.name.contains("XToolkit") ||
                !isTransparencySupported(window) || window.isOpaque || !window.isDisplayable) return null
            val xlib = Native.load("X11", GlassXlib::class.java)
            val display = xlib.XOpenDisplay(null) ?: return null
            try {
                val xid = NativeLong(Native.getWindowID(window))
                val root = NativeLongByReference()
                val children = PointerByReference()
                val queried = xlib.XQueryTree(display, xid, root, NativeLongByReference(), children, IntByReference())
                children.value?.let { xlib.XFree(it) }
                val screen = if (queried != 0) screenForGlassRoot(xlib, display, root.value) else null
                if (screen == null) {
                    xlib.XCloseDisplay(display)
                    return null
                }
                return LinuxWindowGlass({ window.isDisplayable }, xlib, display, xid,
                    root.value,
                    xlib.XInternAtom(display, "_NET_WM_CM_S$screen", false),
                    xlib.XInternAtom(display, "_KDE_NET_WM_BLUR_BEHIND_REGION", false))
            } catch (error: Throwable) {
                xlib.XCloseDisplay(display)
                throw error
            }
        }
    }
}

internal interface GlassXlib : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XCloseDisplay(display: Pointer): Int
    fun XScreenCount(display: Pointer): Int
    fun XQueryTree(display: Pointer, window: NativeLong, root: NativeLongByReference,
                   parent: NativeLongByReference, children: PointerByReference, count: IntByReference): Int
    fun XRootWindow(display: Pointer, screen: Int): NativeLong
    fun XInternAtom(display: Pointer, name: String, onlyIfExists: Boolean): NativeLong
    fun XGetSelectionOwner(display: Pointer, selection: NativeLong): NativeLong
    fun XListProperties(display: Pointer, window: NativeLong, count: IntByReference): Pointer?
    fun XChangeProperty(display: Pointer, window: NativeLong, property: NativeLong, type: NativeLong,
                        format: Int, mode: Int, data: Pointer?, count: Int): Int
    fun XDeleteProperty(display: Pointer, window: NativeLong, property: NativeLong): Int
    fun XFlush(display: Pointer): Int
    fun XFree(pointer: Pointer): Int
}

/** Separate X screens have separate compositor selections and root capabilities. */
internal fun screenForGlassRoot(xlib: GlassXlib, display: Pointer, root: NativeLong): Int? =
    (0 until xlib.XScreenCount(display)).firstOrNull { xlib.XRootWindow(display, it) == root }
