package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.runtime.staticCompositionLocalOf
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** True only after the host has installed an actual behind-window material. */
val LocalNativeWindowGlass = staticCompositionLocalOf { false }

/**
 * Owns one native glass surface for one Compose window. Uses NSGlassEffectView when
 * available (macOS 26+), otherwise NSVisualEffectView. [windowHandle] is Skiko's NSWindow
 * handle, never a title lookup or a guessed AWT peer. All AppKit work runs on the main
 * dispatch queue, not Swing's EDT. The material follows AWT's logical content size and never
 * participates in window layout. In particular, no constraints may resize the AWT content view.
 * Struct arguments are passed by value; we never use architecture-dependent struct returns.
 */
class MacOSWindowGlass private constructor(
    private val windowHandle: Long,
    private val onInstalled: (Boolean) -> Unit
) : AutoCloseable {
    @Volatile private var closed = false
    private var view: Pointer? = null // accessed only on the AppKit main queue

    fun setEnabled(enabled: Boolean, cornerRadius: Double = 20.0, width: Int, height: Int, style: String = "regular", refresh: Boolean = false, dark: Boolean = true) {
        if (closed) return
        NativeGlass.dispatch {
            var installed = false
            try {
                if (!closed && NativeGlass.isLiveWindow(windowHandle)) {
                    NativeGlass.setWindowAppearance(Pointer(windowHandle), dark)
                }
                if (!closed && enabled && NativeGlass.isLiveWindow(windowHandle)) {
                    if (width <= 0 || height <= 0) return@dispatch
                    if (refresh) removeView()
                    if (view == null) view = NativeGlass.install(Pointer(windowHandle))
                    view?.let {
                        NativeGlass.updateFrame(Pointer(windowHandle), it, width, height)
                        NativeGlass.setAppearance(it, cornerRadius, style, dark)
                        NativeGlass.enableWindowShadow(Pointer(windowHandle))
                    }
                    installed = view != null
                } else {
                    removeView()
                }
            } catch (e: Exception) {
                removeView()
                System.err.println("Native macOS glass unavailable: ${e.message}")
            }
            SwingUtilities.invokeLater { if (!closed) onInstalled(installed) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        NativeGlass.dispatch { removeView() }
    }

    private fun removeView() {
        view?.let {
            NativeGlass.sendVoid(it, "removeFromSuperview")
            NativeGlass.sendVoid(it, "release")
        }
        view = null
    }

    companion object {
        /** Keep Compose's header in the content area, outside AppKit's auto-hiding fullscreen toolbar. */
        fun setNativeFrameFullscreen(windowHandle: Long, fullscreen: Boolean) {
            if (!ShellCustomizationUtils.isMacOS() || windowHandle == 0L) return
            NativeGlass.dispatch {
                if (NativeGlass.isLiveWindow(windowHandle)) {
                    NativeGlass.setFrameFullscreen(Pointer(windowHandle), fullscreen)
                }
            }
        }
        internal fun loadSystemSymbol(name: String, onRead: (ByteArray?) -> Unit) {
            NativeGlass.dispatch {
                val png = runCatching { NativeGlass.systemSymbolPng(name) }.getOrNull()
                if (png == null) org.slf4j.LoggerFactory.getLogger(MacOSWindowGlass::class.java)
                    .warn("SF Symbol '{}' unavailable; using the toolbar fallback icon", name)
                SwingUtilities.invokeLater { onRead(png) }
            }
        }
        internal fun readChromePreferences(onRead: (MacChromePreferences) -> Unit) {
            NativeGlass.dispatch {
                val preferences = runCatching { NativeGlass.chromePreferences() }
                    .getOrDefault(MacChromePreferences(dark = true))
                SwingUtilities.invokeLater { onRead(preferences) }
            }
        }
        /** Keep AWT undecorated/alpha-capable, but let AppKit own the native window silhouette. */
        fun configureNativeFrame(windowHandle: Long, resizable: Boolean = true, auxiliary: Boolean = false,
                                 onHeaderHeight: (Double) -> Unit = {}, onConfigured: (Boolean) -> Unit) {
            if (!ShellCustomizationUtils.isMacOS() || windowHandle == 0L) return
            NativeGlass.dispatch {
                var configured = false
                var headerHeight: Double? = null
                try {
                    if (NativeGlass.isLiveWindow(windowHandle)) {
                        NativeGlass.configureFrame(Pointer(windowHandle), resizable, auxiliary)
                        if (!auxiliary) headerHeight = NativeGlass.headerHeight(Pointer(windowHandle))
                        configured = true
                    }
                } finally {
                    val result = configured
                    val height = headerHeight
                    SwingUtilities.invokeLater {
                        height?.let(onHeaderHeight)
                        onConfigured(result)
                    }
                }
            }
        }

        fun create(windowHandle: Long, onInstalled: (Boolean) -> Unit): MacOSWindowGlass? {
            if (!ShellCustomizationUtils.isMacOS() || windowHandle == 0L) return null
            return try {
                NativeGlass.initialize()
                MacOSWindowGlass(windowHandle, onInstalled)
            } catch (e: LinkageError) {
                System.err.println("Native macOS glass unavailable: ${e.message}")
                null
            } catch (e: Exception) {
                System.err.println("Native macOS glass unavailable: ${e.message}")
                null
            }
        }
    }
}

private fun interface MainQueueCallback : Callback {
    fun invoke(context: Pointer?)
}

private interface DispatchApi : Library {
    fun dispatch_async_f(queue: Pointer, context: Pointer?, callback: MainQueueCallback)
}

internal object NativeGlass {
    fun systemSymbolPng(name: String): ByteArray? {
        val symbolName = sendPointer(getClass("NSString"), "stringWithUTF8String:", name)
        val original = sendPointer(getClass("NSImage"), "imageWithSystemSymbolName:accessibilityDescription:", symbolName, null)
            ?: return null
        val configuration = sendPointer(getClass("NSImageSymbolConfiguration"), "configurationWithPointSize:weight:", 32.0, 0.0)
        val image = sendPointer(original, "imageWithSymbolConfiguration:", configuration) ?: original
        val tiff = sendPointer(image, "TIFFRepresentation") ?: return null
        val bitmap = sendPointer(getClass("NSBitmapImageRep"), "imageRepWithData:", tiff) ?: return null
        val png = sendPointer(bitmap, "representationUsingType:properties:", 4L,
            sendPointer(getClass("NSDictionary"), "dictionary")) ?: return null
        val length = message.invokeLong(args(png, "length", emptyArray()))
        if (length !in 1L..1_048_576L) return null
        return sendPointer(png, "bytes")?.getByteArray(0, length.toInt())
    }
    fun chromePreferences(): MacChromePreferences {
        val workspace = sendPointer(getClass("NSWorkspace"), "sharedWorkspace")
        val defaults = sendPointer(getClass("NSUserDefaults"), "standardUserDefaults")
        val key = sendPointer(getClass("NSString"), "stringWithUTF8String:", "AppleInterfaceStyle")
        val style = sendPointer(defaults, "stringForKey:", key)
        val dark = sendPointer(style, "UTF8String")?.getString(0) == "Dark"
        fun flag(selector: String) = message.invokeInt(args(workspace, selector, emptyArray())) != 0
        return MacChromePreferences(dark, flag("accessibilityDisplayShouldReduceTransparency"),
            flag("accessibilityDisplayShouldIncreaseContrast"))
    }
    private val objc = NativeLibrary.getInstance("objc")
    private val message = objc.getFunction("objc_msgSend")
    private val dispatchLibrary = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
    private val mainQueue = dispatchLibrary.getGlobalVariableAddress("_dispatch_main_q")
    private val dispatchApi = Native.load("/usr/lib/libSystem.B.dylib", DispatchApi::class.java)
    // libdispatch owns a function pointer, not a Java reference. Keep callbacks alive until run.
    private val pending = ConcurrentHashMap.newKeySet<MainQueueCallback>()
    private val selectors = ConcurrentHashMap<String, Pointer>()

    fun initialize() = Unit

    fun dispatch(action: () -> Unit) {
        lateinit var callback: MainQueueCallback
        callback = MainQueueCallback {
            val pool = sendPointer(getClass("NSAutoreleasePool"), "new")
            try {
                action()
            } catch (e: Exception) {
                System.err.println("Native macOS glass: ${e.message}")
            } finally {
                sendVoid(pool, "drain")
                pending.remove(callback)
            }
        }
        pending.add(callback)
        try {
            dispatchApi.dispatch_async_f(mainQueue, null, callback)
        } catch (e: Exception) {
            pending.remove(callback)
            throw e
        }
    }

    fun getClass(name: String): Pointer? =
        objc.getFunction("objc_getClass").invokePointer(arrayOf(name))

    private fun args(receiver: Pointer?, selector: String, arguments: Array<out Any?>): Array<Any?> =
        arrayOf(receiver, selectors.computeIfAbsent(selector) {
            objc.getFunction("sel_registerName").invokePointer(arrayOf(it))
        }, *arguments)

    // Function calls use fixed arguments, not a variadic JNA objc_msgSend declaration;
    // variadic arguments use a different calling convention on Apple Silicon.
    fun sendPointer(receiver: Pointer?, selector: String, vararg arguments: Any?): Pointer? =
        message.invokePointer(args(receiver, selector, arguments))

    fun sendVoid(receiver: Pointer?, selector: String, vararg arguments: Any?) {
        message.invokeVoid(args(receiver, selector, arguments))
    }

    fun setFrameFullscreen(window: Pointer, fullscreen: Boolean) {
        val mask = message.invokeLong(args(window, "styleMask", emptyArray()))
        // A real AppKit toolbar owns both its controls and fullscreen layout.
        sendVoid(window, "setStyleMask:", (mask or 1L or 32768L))
        sendPointer(window, "toolbar")?.let { sendVoid(it, "setVisible:", 1.toByte()) }
        preserveUnifiedToolbarSurface(window)
        enableWindowShadow(window)
        for (buttonType in 0L..2L) {
            sendVoid(sendPointer(window, "standardWindowButton:", buttonType), "setHidden:", 0.toByte())
        }
    }

    /** AppKit may host the fullscreen toolbar in a separate window above our content. */
    fun preserveUnifiedToolbarSurface(window: Pointer) {
        sendVoid(window, "setTitlebarAppearsTransparent:", 1.toByte())
        sendPointer(window, "toolbar")?.let { sendVoid(it, "setShowsBaselineSeparator:", 0.toByte()) }
        // Use the public NSView.window relationship rather than private fullscreen classes.
        val button = sendPointer(window, "standardWindowButton:", 0L) ?: return
        val toolbarWindow = sendPointer(button, "window") ?: return
        if (toolbarWindow != window) {
            sendVoid(toolbarWindow, "setTitlebarAppearsTransparent:", 1.toByte())
            sendVoid(toolbarWindow, "setOpaque:", 0.toByte())
            sendVoid(toolbarWindow, "setBackgroundColor:", sendPointer(getClass("NSColor"), "clearColor"))
        }
    }

    fun enableWindowShadow(window: Pointer) {
        sendVoid(window, "setHasShadow:", 1.toByte())
        sendVoid(window, "invalidateShadow")
    }

    fun configureFrame(window: Pointer, resizable: Boolean, auxiliary: Boolean) {
        val mask = message.invokeLong(args(window, "styleMask", emptyArray()))
        // Titled + resizable + fullSizeContentView. Java still sees an undecorated window,
        // preserving its alpha backing store. AppKit now supplies the real rounded frame
        // and fullscreen snapshot/animation instead of a borderless rectangular surface.
        sendVoid(window, "setStyleMask:", (mask or 1L or 2L or 4L or 32768L).let { if (resizable) it or 8L else it and 8L.inv() })
        enableWindowShadow(window)
        sendVoid(window, "setTitlebarAppearsTransparent:", 1.toByte())
        sendVoid(window, "setTitleVisibility:", if (auxiliary) 0L else 1L)
        if (!auxiliary && getClass("NSGlassEffectView") != null) {
            // A real unified toolbar lets AppKit choose the larger Liquid Glass window
            // silhouette, shadow and fullscreen shape. Full-size content keeps Compose
            // beneath the transparent toolbar; its controls occupy the reserved header.
            sendVoid(window, "setToolbarStyle:", 4L) // NSWindowToolbarStyleUnifiedCompact
            if (sendPointer(window, "toolbar") == null) {
                val identifier = sendPointer(getClass("NSString"), "stringWithUTF8String:", "ai.rever.bossterm.window-toolbar")
                val toolbar = sendPointer(sendPointer(getClass("NSToolbar"), "alloc"), "initWithIdentifier:", identifier)
                if (toolbar != null) {
                    try {
                        sendVoid(toolbar, "setAllowsUserCustomization:", 0.toByte())
                        sendVoid(toolbar, "setShowsBaselineSeparator:", 0.toByte())
                        sendVoid(window, "setToolbar:", toolbar)
                    } finally {
                        sendVoid(toolbar, "release") // NSWindow retains its toolbar.
                    }
                }
            }
        }
        for (buttonType in 0L..2L) {
            sendVoid(sendPointer(window, "standardWindowButton:", buttonType), "setHidden:", 0.toByte())
        }
    }

    fun headerHeight(window: Pointer): Double? {
        // KVC boxes NSRect as NSValue. Copying its bytes avoids objc_msgSend's
        // architecture-dependent struct-return ABI (notably on Intel Macs).
        fun rect(key: String): DoubleArray? {
            val name = sendPointer(getClass("NSString"), "stringWithUTF8String:", key)
            val value = sendPointer(window, "valueForKey:", name) ?: return null
            return Memory(32).use { bytes ->
                sendVoid(value, "getValue:size:", bytes, 32L)
                DoubleArray(4) { bytes.getDouble(it * 8L) }
            }
        }
        val frame = rect("frame") ?: return null
        val layout = rect("contentLayoutRect") ?: return null
        return (frame[3] - layout[1] - layout[3]).takeIf { it.isFinite() && it > 0.0 }
    }

    fun isLiveWindow(handle: Long): Boolean {
        val app = sendPointer(getClass("NSApplication"), "sharedApplication")
        val windows = sendPointer(app, "windows")
        val count = message.invokeLong(args(windows, "count", emptyArray()))
        return (0L until count).any {
            Pointer.nativeValue(sendPointer(windows, "objectAtIndex:", it)) == handle
        }
    }

    fun updateFrame(window: Pointer, view: Pointer, width: Int, height: Int) {
        val content = sendPointer(window, "contentView") ?: return
        val parent = sendPointer(content, "superview") ?: return
        // AppKit may replace the theme frame during fullscreen transitions.
        if (sendPointer(view, "superview") != parent) {
            sendVoid(view, "removeFromSuperview")
            sendVoid(parent, "addSubview:positioned:relativeTo:", view, -1L, content)
        }
        // Borderless AWT content starts at the parent's origin. AWT dimensions are points,
        // not backing pixels: multiplying by Retina scale makes the material overshoot.
        sendVoid(view, "setFrameSize:", NativeSize(width.toDouble(), height.toDouble()))
    }

    fun setWindowAppearance(window: Pointer, dark: Boolean) {
        val name = sendPointer(getClass("NSString"), "stringWithUTF8String:",
            if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
        sendVoid(window, "setAppearance:", sendPointer(getClass("NSAppearance"), "appearanceNamed:", name))
    }

    fun setAppearance(view: Pointer, radius: Double, style: String, dark: Boolean) {
        val name = sendPointer(getClass("NSString"), "stringWithUTF8String:",
            if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
        val appearance = sendPointer(getClass("NSAppearance"), "appearanceNamed:", name)
        sendVoid(view, "setAppearance:", appearance)
        val glassClass = getClass("NSGlassEffectView")
        val liquidGlass = glassClass != null &&
            message.invokeInt(args(view, "isKindOfClass:", arrayOf(glassClass))) != 0
        if (liquidGlass) {
            // Let Liquid Glass shape its own lens; clipping its layer would cut off the effect.
            sendVoid(view, "setStyle:", if (style == "clear") 1L else 0L)
            sendVoid(view, "setCornerRadius:", radius)
            return
        }
        sendVoid(view, "setWantsLayer:", 1.toByte())
        val layer = sendPointer(view, "layer")
        sendVoid(layer, "setCornerRadius:", radius)
        sendVoid(layer, "setMasksToBounds:", 1.toByte())
    }

    fun install(window: Pointer): Pointer? {
        val liquidGlassClass = getClass("NSGlassEffectView")
        val effectClass = liquidGlassClass ?: getClass("NSVisualEffectView") ?: return null
        val content = sendPointer(window, "contentView") ?: return null
        val parent = sendPointer(content, "superview") ?: return null
        val effect = sendPointer(sendPointer(effectClass, "alloc"), "init") ?: return null
        try {
            if (liquidGlassClass != null) {
                sendVoid(effect, "setStyle:", 0L) // NSGlassEffectViewStyleRegular (public API)
                // AWT sends private mouse messages directly to NSWindow.contentView, so replacing
                // or reparenting that view breaks input. This is a native backdrop surface only;
                // Compose draws its own foreground and cannot inherit AppKit's adaptive text styling.
            } else {
                sendVoid(effect, "setMaterial:", 7L) // NSVisualEffectMaterialSidebar
                sendVoid(effect, "setBlendingMode:", 0L) // NSVisualEffectBlendingModeBehindWindow
                sendVoid(effect, "setState:", 0L) // follows window activation and system accessibility
            }
            sendVoid(effect, "setAutoresizingMask:", 18L) // width + height; never constrain AWT
            sendVoid(parent, "addSubview:positioned:relativeTo:", effect, -1L, content)
            org.slf4j.LoggerFactory.getLogger(MacOSWindowGlass::class.java).debug(
                "Native macOS glass installed: window={}, material={}",
                Pointer.nativeValue(window),
                if (liquidGlassClass != null) "NSGlassEffectView" else "NSVisualEffectView"
            )
            return effect // our alloc ownership is released on removal
        } catch (e: Exception) {
            sendVoid(effect, "removeFromSuperview")
            sendVoid(effect, "release")
            throw e
        }
    }
}

/** NSSize on both 64-bit macOS ABIs. Only used as a by-value setter argument. */
@Structure.FieldOrder("width", "height")
internal class NativeSize(
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0
) : Structure(), Structure.ByValue
