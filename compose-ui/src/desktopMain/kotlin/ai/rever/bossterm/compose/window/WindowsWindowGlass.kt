package ai.rever.bossterm.compose.window

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import org.jetbrains.skiko.SkiaLayer
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Window
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.RootPaneContainer

/** Windows 11 22H2+ documented Desktop Acrylic. Unsupported DWM attributes fail closed. */
internal class WindowsWindowGlass(private val window: Window) : DesktopGlassBackend {
    private val dwm = Native.load("dwmapi", DwmGlassApi::class.java)
    private val hwnd = Native.getWindowPointer(window)
    private var prepared = false
    private var oldContent: Container? = null
    private var oldBackground: Color? = null
    private var layer: SkiaLayer? = null
    private var oldLayerTransparency = false
    private var swingOpacity: SwingGlassOpacity? = null
    private val effect = WindowsAcrylicEffect(dwm, hwnd, { window.isDisplayable }, ::prepareSurface, ::restoreSurface)

    override fun update(enabled: Boolean, dark: Boolean) = effect.update(enabled, dark)
    override fun close() = effect.close()

    private fun prepareSurface(): Boolean {
        if (prepared) return true
        val root = (window as? RootPaneContainer)?.rootPane ?: return false
        val skia = findSkiaLayer(root) ?: return false
        oldBackground = window.background
        oldLayerTransparency = skia.transparency
        layer = skia
        oldContent = root.contentPane
        swingOpacity = SwingGlassOpacity(root, skia)
        prepared = true // any later failure must restore the original hierarchy and alpha state
        // DWM owns the backdrop: a layered AWT window would bypass that composition path.
        window.background = Color.BLACK
        skia.transparency = true
        swingOpacity?.enable()
        root.contentPane = AcrylicContentPane().apply {
            layout = BorderLayout()
            add(oldContent, BorderLayout.CENTER)
        }
        root.revalidate()
        window.repaint()
        return true
    }

    private fun restoreSurface() {
        if (!prepared) return
        val root = (window as? RootPaneContainer)?.rootPane
        oldContent?.let {
            root?.contentPane = it
        }
        swingOpacity?.restore()
        swingOpacity = null
        layer?.transparency = oldLayerTransparency
        // Avoid recreating native resources while the owning window is being disposed.
        if (window.isDisplayable) window.background = oldBackground
        prepared = false
        root?.revalidate()
        window.repaint()
    }
}

/** Lifecycle kept separate from AWT so failed native calls and rollback are testable. */
internal class WindowsAcrylicEffect(
    private val api: DwmGlassApi,
    private val hwnd: Pointer,
    private val isDisplayable: () -> Boolean,
    private val prepareSurface: () -> Boolean,
    private val restoreSurface: () -> Unit
) : DesktopGlassBackend {
    private var active = false
    private var prepared = false
    private var lastDark: Boolean? = null
    private var closed = false

    override fun update(enabled: Boolean, dark: Boolean): Boolean {
        if (closed || !isDisplayable()) return false
        if (!enabled) { disable(); return false }
        val composition = IntByReference()
        if (api.DwmIsCompositionEnabled(composition) < 0 || composition.value == 0) {
            disable()
            return false
        }
        if (active && lastDark == dark) return true
        // Supported from build 22621. Probe the documented attribute before touching AWT;
        // unsupported Windows versions must not repeatedly tear down/rebuild the surface.
        if (api.DwmGetWindowAttribute(hwnd, 38, IntByReference(), 4) < 0) {
            disable()
            return false
        }
        try {
            prepared = true
            if (!prepareSurface() || attribute(38, 3) < 0 ||
                api.DwmExtendFrameIntoClientArea(hwnd, GlassMargins(-1)) < 0) {
                disable()
                return false
            }
            attribute(20, if (dark) 1 else 0)
            active = true
            lastDark = dark
            return true
        } catch (error: Exception) {
            disable()
            throw error
        } catch (error: LinkageError) {
            disable()
            throw error
        }
    }

    private fun attribute(name: Int, value: Int) = api.DwmSetWindowAttribute(hwnd, name, IntByReference(value), 4)

    private fun disable() {
        try {
            if (prepared && isDisplayable()) {
                attribute(38, 1) // DWMSBT_NONE
                api.DwmExtendFrameIntoClientArea(hwnd, GlassMargins(0))
            }
        } finally {
            val restore = prepared
            prepared = false
            active = false
            lastDark = null
            if (restore) restoreSurface()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        disable()
    }
}

private fun findSkiaLayer(container: Container): SkiaLayer? {
    for (child in container.components) {
        if (child is SkiaLayer) return child
        if (child is Container) findSkiaLayer(child)?.let { return it }
    }
    return null
}

/** Clear the client alpha channel before Skia paints, without a layered top-level HWND. */
private class AcrylicContentPane : JPanel() {
    init { isOpaque = false }
    override fun paint(graphics: Graphics) {
        val clear = graphics.create() as Graphics2D
        try {
            clear.composite = AlphaComposite.Clear
            clear.fillRect(0, 0, width, height)
        } finally { clear.dispose() }
        super.paint(graphics)
    }
}

internal interface DwmGlassApi : StdCallLibrary {
    fun DwmIsCompositionEnabled(enabled: IntByReference): Int
    fun DwmGetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    fun DwmExtendFrameIntoClientArea(window: Pointer, margins: GlassMargins): Int
}

@Structure.FieldOrder("left", "right", "top", "bottom")
internal class GlassMargins(value: Int = 0) : Structure() {
    @JvmField var left = value
    @JvmField var right = value
    @JvmField var top = value
    @JvmField var bottom = value
}

/** Every Swing ancestor of Skia must allow client alpha through, including ComposeWindowPanel. */
internal class SwingGlassOpacity(root: JComponent, leaf: JComponent) {
    private val original = buildList {
        var current: java.awt.Component? = leaf
        while (current != null) {
            if (current is JComponent) add(current to current.isOpaque)
            if (current === root) break
            current = current.parent
        }
        require(current === root) { "Glass surface is not attached to its root pane" }
    }

    fun enable() = original.forEach { (component, _) -> component.isOpaque = false }
    fun restore() = original.forEach { (component, opaque) -> component.isOpaque = opaque }
}
