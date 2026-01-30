package ai.rever.bossterm.compose.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.Frame
import java.awt.Window
import java.lang.reflect.InaccessibleObjectException
import javax.swing.SwingUtilities

/**
 * JNA interface for additional Win32 window management APIs.
 */
private interface Win32WindowApi : Library {
    companion object {
        val INSTANCE: Win32WindowApi? = try {
            Native.load("user32", Win32WindowApi::class.java)
        } catch (e: Throwable) {
            null
        }

        const val SW_RESTORE = 9
    }

    /**
     * Brings the specified window to the foreground and activates it.
     * This is more reliable than Java's toFront() on Windows.
     */
    fun SetForegroundWindow(hWnd: HWND): Boolean

    /**
     * Shows a window in a specified state.
     */
    fun ShowWindow(hWnd: HWND, nCmdShow: Int): Boolean

    /**
     * Checks if a window is minimized (iconic).
     */
    fun IsIconic(hWnd: HWND): Boolean

    /**
     * Checks if a window is visible.
     */
    fun IsWindowVisible(hWnd: HWND): Boolean
}

/**
 * Controller for toggling window visibility in response to global hotkey.
 *
 * Implements iTerm2-style toggle behavior:
 * - Not visible/minimized → Show and focus
 * - Visible but not focused → Bring to front and focus
 * - Visible and focused → Minimize (iconify)
 */
object WindowVisibilityController {

    /**
     * Toggle visibility of the given windows.
     * Uses the most recently focused window if multiple are provided.
     *
     * @param windows List of AWT windows to toggle.
     */
    fun toggleWindow(windows: List<Window>) {
        if (windows.isEmpty()) return

        // Find the most appropriate window to toggle
        // Prefer: focused window > visible non-minimized window > any window
        val targetWindow = windows.find { it.isFocused }
            ?: windows.find { it.isVisible && (it as? Frame)?.state != Frame.ICONIFIED }
            ?: windows.firstOrNull()
            ?: return

        SwingUtilities.invokeLater {
            toggleSingleWindow(targetWindow)
        }
    }

    /**
     * Toggle a single window's visibility.
     */
    private fun toggleSingleWindow(window: Window) {
        val frame = window as? Frame

        // Determine current state
        val isMinimized = frame?.state == Frame.ICONIFIED
        val isVisible = window.isVisible
        val isFocused = window.isFocused

        when {
            // Not visible → Show and focus
            !isVisible -> {
                window.isVisible = true
                bringToFrontAndFocus(window)
            }

            // Minimized → Restore and focus
            isMinimized -> {
                frame?.state = Frame.NORMAL
                bringToFrontAndFocus(window)
            }

            // Visible and focused → Minimize
            isFocused -> {
                frame?.state = Frame.ICONIFIED
            }

            // Visible but not focused → Bring to front and focus
            else -> {
                bringToFrontAndFocus(window)
            }
        }
    }

    /**
     * Bring window to front and focus it.
     * Uses Win32 API on Windows for reliable focusing.
     */
    private fun bringToFrontAndFocus(window: Window) {
        val api = Win32WindowApi.INSTANCE
        val hwnd = getWindowHandle(window)

        if (api != null && hwnd != null) {
            // Windows-specific: Use native API for reliable focus
            if (api.IsIconic(hwnd)) {
                api.ShowWindow(hwnd, Win32WindowApi.SW_RESTORE)
            }
            api.SetForegroundWindow(hwnd)
        } else {
            // Fallback for non-Windows or if JNA fails
            window.toFront()
            window.requestFocus()
        }
    }

    /**
     * Get the native HWND handle for an AWT window.
     *
     * Requires JVM arg on Java 16+: --add-opens java.desktop/java.awt=ALL-UNNAMED
     *
     * Without this flag, reflection access to Component.peer will fail on modern JVMs
     * and the hotkey toggle functionality will fall back to standard show/hide.
     */
    private fun getWindowHandle(window: Window): HWND? {
        return try {
            // Try to get the native peer and extract HWND
            val peerField = java.awt.Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            val peer = peerField.get(window) ?: return null

            // Try WComponentPeer.getHWnd() method
            val getHWndMethod = try {
                peer.javaClass.getMethod("getHWnd")
            } catch (e: NoSuchMethodException) {
                // Try alternative method name
                peer.javaClass.getDeclaredMethod("getHWnd")
            }

            getHWndMethod.isAccessible = true
            val hwndLong = getHWndMethod.invoke(peer) as Long

            if (hwndLong != 0L) {
                HWND(com.sun.jna.Pointer(hwndLong))
            } else {
                null
            }
        } catch (e: InaccessibleObjectException) {
            // Java 16+ requires explicit module access
            println("WindowVisibilityController: Reflection blocked by Java module system")
            println("  Add JVM flag: --add-opens java.desktop/java.awt=ALL-UNNAMED")
            println("  Window toggle will fall back to standard show/hide")
            null
        } catch (e: SecurityException) {
            // SecurityManager denies reflection access
            println("WindowVisibilityController: Reflection blocked by SecurityManager")
            println("  Grant reflection permission or disable SecurityManager for window toggle")
            println("  Window toggle will fall back to standard show/hide")
            null
        } catch (e: Exception) {
            // Other reflection or JNA failures - return null to use fallback
            println("WindowVisibilityController: Failed to get HWND: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }
}
