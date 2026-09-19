package ai.rever.bossterm.compose.window

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.sun.jna.Pointer
import javax.swing.SwingUtilities

/** Coordinates of the native sidebar content, in logical window points. */
data class NativeSidebarGeometry(val width: Float, val leadingInset: Float, val bottomInset: Float)
val LocalNativeSidebarGeometry = staticCompositionLocalOf<NativeSidebarGeometry?> { null }

/**
 * AppKit owns the split-item material and its full-height relationship to the toolbar.
 * The existing AWT view remains NSWindow.contentView and renders the interactive foreground.
 * No constraints, reparenting, or replacements are applied to that input surface.
 */
class NativeSplitWindowLayout(
    private val handle: Long,
    private val onGeometry: (NativeSidebarGeometry?) -> Unit
) : AutoCloseable {
    private var root: Pointer? = null
    private var previousRoot: Pointer? = null
    private var controller: Pointer? = null
    private var nativeView: Pointer? = null
    private var sidebar: Pointer? = null
    private var sidebarContent: Pointer? = null
    private var detailContent: Pointer? = null
    private var chromeInset = 0.0
    private var published: NativeSidebarGeometry? = null
    @Volatile private var closed = false

    fun update(width: Int, height: Int, sidebarWidth: Float, background: Color, sidebarTint: Color?) {
        NativeGlass.dispatch {
            if (closed || width <= 0 || height <= 0 || !NativeGlass.isLiveWindow(handle)) return@dispatch
            if (NativeGlass.getClass("NSGlassEffectView") == null) return@dispatch
            val window = Pointer(handle)
            val awt = NativeGlass.sendPointer(window, "contentView") ?: return@dispatch
            val parent = NativeGlass.sendPointer(awt, "superview") ?: return@dispatch
            if (controller == null) install(window, awt)
            val view = nativeView ?: return@dispatch
            if (NativeGlass.sendPointer(view, "superview") != parent) {
                NativeGlass.sendVoid(view, "removeFromSuperview")
                NativeGlass.sendVoid(parent, "addSubview:positioned:relativeTo:", view, -1L, awt)
            }
            NativeGlass.sendVoid(view, "setFrameSize:", NativeSize(width.toDouble(), height.toDouble()))
            val requestedWidth = sidebarWidth.toDouble()
            val item = sidebar ?: return@dispatch
            NativeGlass.sendVoid(item, "setCollapsed:", if (sidebarWidth <= 0f) 1.toByte() else 0.toByte())
            if (sidebarWidth > 0f) {
                // AppKit supplies the inset. Measure it rather than duplicating a system metric.
                repeat(2) {
                    val contentWidth = (requestedWidth - chromeInset).coerceAtLeast(1.0)
                    NativeGlass.sendVoid(item, "setMinimumThickness:", 1.0)
                    NativeGlass.sendVoid(item, "setMaximumThickness:", contentWidth)
                    NativeGlass.sendVoid(item, "setMinimumThickness:", contentWidth)
                    NativeGlass.sendVoid(view, "layoutSubtreeIfNeeded")
                    val bounds = contentBounds(view) ?: return@repeat
                    chromeInset = bounds[0]
                }
            }
            NativeGlass.sendVoid(view, "layoutSubtreeIfNeeded")
            // Give AppKit the same content background used by the Compose foreground.
            // Its titlebar/scroll-edge treatment can now derive from the native detail pane.
            detailContent?.let { detail ->
                NativeGlass.sendVoid(detail, "setBackgroundColor:", nativeColor(background))
            }
            var ancestor = sidebarContent
            val glassClass = NativeGlass.getClass("NSGlassEffectView")
            while (ancestor != null && ancestor != view) {
                if (NativeGlass.isKindOf(ancestor, glassClass)) {
                    NativeGlass.sendVoid(ancestor, "setTintColor:", sidebarTint?.let(::nativeColor))
                    break
                }
                ancestor = NativeGlass.sendPointer(ancestor, "superview")
            }
            val bounds = contentBounds(view)
            val geometry = if (sidebarWidth > 0f && bounds != null) NativeSidebarGeometry(
                (bounds[0] + bounds[2]).toFloat(), bounds[0].toFloat(), bounds[1].toFloat()
            ) else NativeSidebarGeometry(0f, 0f, 0f)
            if (geometry != published) {
                published = geometry
                SwingUtilities.invokeLater { if (!closed) onGeometry(geometry) }
            }
        }
    }

    private fun install(window: Pointer, awt: Pointer) {
        fun new(name: String) = NativeGlass.sendPointer(NativeGlass.getClass(name), "new")!!
        val rootController = new("NSViewController")
        val splitController = new("NSSplitViewController")
        val sideController = new("NSViewController")
        val detailController = new("NSViewController")
        val sideView = new("NSView")
        val detailView = new("NSScrollView")
        // AppKit reads the background for its scroll-edge/toolbar treatment. Compose
        // paints the terminal surface, so drawing it again here would double its opacity.
        NativeGlass.sendVoid(detailView, "setDrawsBackground:", 0.toByte())
        NativeGlass.sendVoid(detailView, "setHasVerticalScroller:", 0.toByte())
        NativeGlass.sendVoid(detailView, "setHasHorizontalScroller:", 0.toByte())
        NativeGlass.sendVoid(sideController, "setView:", sideView)
        NativeGlass.sendVoid(detailController, "setView:", detailView)
        val itemClass = NativeGlass.getClass("NSSplitViewItem")
        val sideItem = NativeGlass.sendPointer(itemClass, "sidebarWithViewController:", sideController)!!
        val detailItem = NativeGlass.sendPointer(itemClass, "splitViewItemWithViewController:", detailController)!!
        NativeGlass.sendVoid(sideItem, "setAllowsFullHeightLayout:", 1.toByte())
        // Compose owns collapse, resizing and edge-hover reveal. The native item
        // supplies material/layout only; its default spring-loaded sidebar otherwise
        // starts a second fullscreen reveal, including a separate titlebar overlay.
        NativeGlass.sendVoid(sideItem, "setCanCollapse:", 0.toByte())
        NativeGlass.sendVoid(sideItem, "setCanCollapseFromWindowResize:", 0.toByte())
        NativeGlass.sendVoid(sideItem, "setSpringLoaded:", 0.toByte())
        NativeGlass.sendVoid(sideItem, "setPreferredThicknessFraction:", -1.0)
        NativeGlass.sendVoid(detailItem, "setAutomaticallyAdjustsSafeAreaInsets:", 1.toByte())
        NativeGlass.sendVoid(splitController, "setMinimumThicknessForInlineSidebars:", 0.0)
        NativeGlass.sendVoid(rootController, "setView:", awt)
        previousRoot = NativeGlass.sendPointer(window, "contentViewController")?.also { NativeGlass.sendVoid(it, "retain") }
        NativeGlass.sendVoid(rootController, "addChildViewController:", splitController)
        NativeGlass.sendVoid(window, "setContentViewController:", rootController)
        check(NativeGlass.sendPointer(window, "contentView") == awt) { "Native split layout replaced the AWT input view" }
        val view = NativeGlass.sendPointer(splitController, "view")!!
        NativeGlass.sendVoid(view, "setAutoresizingMask:", 18L)
        NativeGlass.sendVoid(splitController, "addSplitViewItem:", sideItem)
        NativeGlass.sendVoid(splitController, "addSplitViewItem:", detailItem)
        root = rootController
        controller = splitController
        nativeView = view
        nativeViews[handle] = view
        splitViews[handle] = NativeGlass.sendPointer(splitController, "splitView")!!
        sidebar = sideItem
        sidebarContent = sideView
        detailContent = detailView
        listOf(sideController, detailController, sideView, detailView).forEach { NativeGlass.sendVoid(it, "release") }
    }

    private fun nativeColor(color: Color): Pointer? = NativeGlass.sendPointer(
        NativeGlass.getClass("NSColor"), "colorWithSRGBRed:green:blue:alpha:",
        color.red.toDouble(), color.green.toDouble(), color.blue.toDouble(), color.alpha.toDouble())

    private fun contentBounds(ancestor: Pointer): DoubleArray? {
        var view = sidebarContent ?: return null
        val bounds = NativeGlass.rect(view, "frame") ?: return null
        while (true) {
            view = NativeGlass.sendPointer(view, "superview") ?: return null
            if (view == ancestor) return bounds
            val frame = NativeGlass.rect(view, "frame") ?: return null
            bounds[0] += frame[0]
            bounds[1] += frame[1]
        }
    }

    companion object {
        // AppKit queue only; keep the window-wide backdrop below the native sidebar.
        internal val nativeViews = mutableMapOf<Long, Pointer>()
        internal val splitViews = mutableMapOf<Long, Pointer>()
    }

    override fun close() {
        closed = true
        NativeGlass.dispatch {
            nativeViews.remove(handle)
            splitViews.remove(handle)
            NativeGlass.sendVoid(nativeView, "removeFromSuperview")
            val window = Pointer(handle)
            if (NativeGlass.isLiveWindow(handle) && NativeGlass.sendPointer(window, "contentViewController") == root) {
                // Clearing only the controller preserves NSWindow.contentView and AWT ownership.
                NativeGlass.sendVoid(window, "setContentViewController:", previousRoot)
            }
            NativeGlass.sendVoid(controller, "removeFromParentViewController")
            NativeGlass.sendVoid(controller, "release")
            NativeGlass.sendVoid(root, "release")
            NativeGlass.sendVoid(previousRoot, "release")
            controller = null
            root = null
            previousRoot = null
            nativeView = null
            sidebar = null
            sidebarContent = null
            detailContent = null
        }
    }
}
