package ai.rever.bossterm.compose.window

import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JFrame

/** Configure AWT transparency. Native blur is owned separately by [NativeWindowGlass]. */
fun configureWindowTransparency(window: Window, isTransparent: Boolean) {
    if (!isTransparent || !isTransparencySupported(window)) return
    window.background = java.awt.Color(0, 0, 0, 0)
    val rootPane = when (window) {
        is JFrame -> window.rootPane
        is JDialog -> window.rootPane
        else -> null
    }
    rootPane?.let {
        it.isOpaque = false
        it.background = java.awt.Color(0, 0, 0, 0)
    }
}

/**
 * Check if the current platform supports window transparency.
 */
fun isTransparencySupported(window: Window? = null): Boolean {
    return try {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val gd = window?.graphicsConfiguration?.device ?: ge.defaultScreenDevice
        gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
    } catch (e: Exception) {
        false
    }
}
