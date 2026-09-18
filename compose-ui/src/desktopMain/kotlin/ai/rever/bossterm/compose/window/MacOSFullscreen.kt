package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.awt.Window
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities

enum class NativeFullscreenPhase { ENTERING, ENTERED, EXITING, EXITED }

/**
 * The JDK's macOS fullscreen API uses NSWindow.toggleFullScreen:, creating a real fullscreen
 * Space without changing AWT decorations or its transparent backing store. Reflection keeps
 * macOS-only JDK classes out of the cross-platform library's linkage. The standalone launcher
 * exports java.desktop/com.apple.eawt to this module.
 */
class MacOSFullscreen private constructor(
    private val window: Window,
    private val utilities: Class<*>,
    private val listenerType: Class<*>,
    private val listener: Any,
    private val application: Any,
    private val applicationType: Class<*>
) : AutoCloseable {
    fun toggle(): Boolean = try {
        applicationType.getMethod("requestToggleFullScreen", Window::class.java).invoke(application, window)
        true
    } catch (e: ReflectiveOperationException) {
        System.err.println("Native macOS fullscreen request failed: ${e.message}")
        false
    }

    override fun close() {
        utilities.getMethod("removeFullScreenListenerFrom", Window::class.java, listenerType)
            .invoke(null, window, listener)
    }

    companion object {
        fun create(window: Window, onPhase: (NativeFullscreenPhase) -> Unit): MacOSFullscreen? {
            if (!ShellCustomizationUtils.isMacOS()) return null
            return try {
                val utilities = Class.forName("com.apple.eawt.FullScreenUtilities")
                val listenerType = Class.forName("com.apple.eawt.FullScreenListener")
                val applicationType = Class.forName("com.apple.eawt.Application")
                val application = applicationType.getMethod("getApplication").invoke(null)
                val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "BossTerm native fullscreen listener"
                        else -> {
                            val phase = when (method.name) {
                                "windowEnteringFullScreen" -> NativeFullscreenPhase.ENTERING
                                "windowEnteredFullScreen" -> NativeFullscreenPhase.ENTERED
                                "windowExitingFullScreen" -> NativeFullscreenPhase.EXITING
                                "windowExitedFullScreen" -> NativeFullscreenPhase.EXITED
                                else -> null
                            }
                            if (phase != null) {
                                if (SwingUtilities.isEventDispatchThread()) onPhase(phase)
                                else SwingUtilities.invokeLater { onPhase(phase) }
                            }
                            null
                        }
                    }
                }
                utilities.getMethod("setWindowCanFullScreen", Window::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(null, window, true)
                utilities.getMethod("addFullScreenListenerTo", Window::class.java, listenerType)
                    .invoke(null, window, listener)
                MacOSFullscreen(window, utilities, listenerType, listener, application, applicationType)
            } catch (e: ReflectiveOperationException) {
                System.err.println("Native macOS fullscreen unavailable: ${e.message}")
                null
            }
        }
    }
}
