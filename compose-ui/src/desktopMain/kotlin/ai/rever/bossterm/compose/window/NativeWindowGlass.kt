package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.awt.Window
import javax.swing.SwingUtilities
import org.slf4j.LoggerFactory

/** One native backdrop per window. Foreign libraries are loaded only on their own platform. */
class NativeWindowGlass private constructor(
    private val mac: MacOSWindowGlass?,
    private val backend: DesktopGlassBackend?,
    private val onInstalled: (Boolean) -> Unit
) : AutoCloseable {
    private var closed = false
    private var warned = false
    private var lastDark = true
    private val capabilityTimer = javax.swing.Timer(3000) { setEnabled(true, width = 1, height = 1, dark = lastDark) }

    fun setEnabled(enabled: Boolean, cornerRadius: Double = 20.0, width: Int, height: Int,
                   style: String = "regular", refresh: Boolean = false, dark: Boolean = true) {
        if (closed) return
        if (mac != null) {
            mac.setEnabled(enabled, cornerRadius, width, height, style, refresh, dark)
            return
        }
        onEdt {
            if (!closed) {
                lastDark = dark
                if (enabled && backend != null) capabilityTimer.start() else capabilityTimer.stop()
                val installed = try {
                    backend?.update(enabled, dark) ?: false
                } catch (error: Exception) {
                    report(error)
                    false
                } catch (error: LinkageError) {
                    report(error)
                    false
                }
                onInstalled(installed)
            }
        }
    }

    private fun report(error: Throwable) {
        if (!warned) {
            warned = true
            LoggerFactory.getLogger(NativeWindowGlass::class.java)
                .warn("Native glass unavailable; using opaque surfaces: {}", error.toString())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        mac?.close()
        onEdt {
            capabilityTimer.stop()
            try { backend?.close() }
            catch (error: Exception) { report(error) }
            catch (error: LinkageError) { report(error) }
        }
    }

    companion object {
        fun create(window: Window, macHandle: Long, onInstalled: (Boolean) -> Unit): NativeWindowGlass {
            var mac: MacOSWindowGlass? = null
            val backend = try {
                when {
                    ShellCustomizationUtils.isMacOS() -> {
                        mac = MacOSWindowGlass.create(macHandle, onInstalled)
                        null
                    }
                    ShellCustomizationUtils.isWindows() -> WindowsWindowGlass(window)
                    ShellCustomizationUtils.isLinux() -> LinuxWindowGlass.create(window)
                    else -> null
                }
            } catch (error: Exception) {
                LoggerFactory.getLogger(NativeWindowGlass::class.java).debug("Native glass backend unavailable", error)
                null
            } catch (error: LinkageError) {
                LoggerFactory.getLogger(NativeWindowGlass::class.java).debug("Native glass library unavailable", error)
                null
            }
            return NativeWindowGlass(mac, backend, onInstalled)
        }
    }
}

internal interface DesktopGlassBackend : AutoCloseable {
    /** False means that callers must paint opaque, even when the saved opacity is zero. */
    fun update(enabled: Boolean, dark: Boolean): Boolean
}

private fun onEdt(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
}
