package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Window
import javax.swing.JRootPane
import javax.swing.RootPaneContainer

/**
 * Height the native macOS title bar occupies once the content extends underneath it.
 *
 * A constant rather than a measurement: with `fullWindowContent` the content pane fills the whole
 * frame, so the usual `window.height - contentPane.height` trick reports zero. 28pt is the
 * standard title bar height for a regular-sized window, and it is what the traffic lights are
 * laid out against.
 */
val NATIVE_TITLE_BAR_HEIGHT: Dp = 28.dp

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
 *         [NATIVE_TITLE_BAR_HEIGHT] to keep it clear of the traffic lights. False off macOS, or
 *         for a window with no root pane, in which case the caller must not inset - leaving
 *         today's behaviour rather than a stray gap.
 */
fun applyFullWindowContent(window: Window): Boolean =
    // RootPaneContainer is the interface the JDK itself uses for this, and it covers JWindow too.
    applyFullWindowContent((window as? RootPaneContainer)?.rootPane)

/**
 * The decision and the three property writes, split out from finding the root pane so a headless
 * test can reach them: no [java.awt.Window] can be constructed headless (its constructor throws
 * `HeadlessException`) but a bare [JRootPane] can, so this is the largest part of the contract CI
 * can actually execute.
 *
 * @param rootPane null when the window has no root pane to style, which is a decline, not a crash.
 * @param isMacOS injectable so a test on any runner can assert both branches, rather than each
 *        platform's CI silently skipping half the contract.
 */
internal fun applyFullWindowContent(
    rootPane: JRootPane?,
    isMacOS: Boolean = ShellCustomizationUtils.isMacOS(),
): Boolean {
    // Null first: a window with no root pane is a decline on every platform, so checking the
    // platform ahead of it would make the null case unreachable off macOS.
    if (rootPane == null) return false
    if (!isMacOS) return false

    return runCatching {
        rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        // The title stays visible. It is drawn centred in the title bar strip, and callers reserve
        // exactly that strip with NATIVE_TITLE_BAR_HEIGHT, so there is nothing for it to overlap
        // - hiding it would just lose the window name for no reason.
        rootPane.putClientProperty("apple.awt.windowTitleVisible", true)
        true
    }.getOrElse {
        // putClientProperty on a JRootPane essentially cannot throw, so reaching here means
        // something is badly wrong - exactly when a silent un-inset window would be the worst
        // outcome to debug.
        println("NativeTitleBarStyle: could not apply full window content: $it")
        false
    }
}
