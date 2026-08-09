package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JFrame

/**
 * Height the native macOS title bar occupies once the content extends underneath it.
 *
 * A constant rather than a measurement: with `fullWindowContent` the content pane fills the whole
 * frame, so the usual `window.height - contentPane.height` trick reports zero. 28pt is the
 * standard title bar height for a regular-sized window, and it is what the traffic lights are
 * laid out against.
 */
const val NATIVE_TITLE_BAR_HEIGHT_DP: Int = 28

/**
 * Make the native title bar part of the window rather than a strip above it.
 *
 * `fullWindowContent` lets the content pane extend under the title bar and `transparentTitleBar`
 * stops the system painting its own background there, so the app's own background runs edge to
 * edge and the traffic lights sit directly on it. That is the look every modern terminal has, and
 * it is the one part of "native window styling" reachable without giving something up.
 *
 * Deliberately NOT transparency. Those are separate things and only this one is available with a
 * native title bar: AWT allocates an alpha-capable backing store only for windows it considers
 * translucent, and refuses that for decorated frames (`IllegalComponentStateException: The frame
 * is decorated`). Forcing the `NSWindow` non-opaque underneath is not enough - measured, the
 * window reports `isOpaque = NO` and still composites alpha onto black, because the surface has
 * no alpha channel. macOS itself allows it, which is how Terminal.app is see-through with traffic
 * lights, but not through AWT's window. Transparency therefore remains the undecorated path's.
 *
 * Client properties are the supported JDK route on macOS and are simply ignored elsewhere, so
 * this is gated on macOS only to keep the intent obvious.
 *
 * @return true when the style was applied, so callers know whether to inset their content by
 *         [NATIVE_TITLE_BAR_HEIGHT_DP] to keep it clear of the traffic lights.
 */
fun applyFullWindowContent(window: Window): Boolean {
    if (!ShellCustomizationUtils.isMacOS()) return false

    val rootPane =
        when (window) {
            is JFrame -> window.rootPane
            is JDialog -> window.rootPane
            else -> null
        } ?: return false

    return runCatching {
        rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        // The title stays visible. It is drawn centred in the title bar strip, and callers reserve
        // exactly that strip with NATIVE_TITLE_BAR_HEIGHT_DP, so there is nothing for it to overlap
        // - hiding it would just lose the window name for no reason.
        rootPane.putClientProperty("apple.awt.windowTitleVisible", true)
        true
    }.getOrDefault(false)
}
