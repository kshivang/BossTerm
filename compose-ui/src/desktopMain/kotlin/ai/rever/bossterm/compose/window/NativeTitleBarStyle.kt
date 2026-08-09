package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
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

    return try {
        rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        // This is what makes the title's colour our problem: AppKit then draws the title text in
        // the colour the window's EFFECTIVE APPEARANCE dictates, not one picked to contrast with
        // whatever shows through. See [nativeTitleBarAppearance], which is why the app no longer
        // simply follows the system here. Confirmed by hand: with the stock
        // -Dapple.awt.application.appearance=system, a light system appearance drew a near-black
        // title over the dark terminal background and the window name was unreadable.
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        // The title stays visible. It is drawn centred in the title bar strip, and callers reserve
        // exactly that strip with NATIVE_TITLE_BAR_HEIGHT, so there is nothing for it to overlap
        // - hiding it would just lose the window name for no reason.
        rootPane.putClientProperty("apple.awt.windowTitleVisible", true)
        true
    } catch (e: Exception) {
        // putClientProperty on a JRootPane essentially cannot throw, so reaching here means
        // something is badly wrong - exactly when a silent un-inset window would be the worst
        // outcome to debug.
        System.err.println("NativeTitleBarStyle: could not apply full window content: $e")
        false
    }
}

/**
 * How much of the top the native title bar is covering, and therefore how far content must be
 * pushed down to stay clear of the traffic lights. Zero whenever nothing needs reserving, so
 * callers can apply it unconditionally instead of repeating the predicate.
 *
 * Repeating it is exactly what went wrong once already: two copies drifted, one of them handled
 * fullscreen and the other did not, and the un-inset one rendered inside the title bar strip.
 *
 * Fullscreen ONLY, never Maximized. macOS zoom keeps its title bar, so a zoomed window still needs
 * the inset; native fullscreen hides the bar entirely, so reserving there would leave a dead band
 * of background above the content.
 *
 * [placement] is trustworthy for this, traced rather than assumed: Compose syncs it from a
 * `componentResized` handler specifically because "fullscreen changing doesn't fire
 * windowStateChanged, only componentResized", and the value it reads bottoms out in skiko's
 * `osxIsFullscreenNative` - the real NSWindow state, not a flag set only when we request
 * fullscreen. So a fullscreen entered from the green traffic light is covered. A bounds-vs-screen
 * heuristic was tried in its place and removed: it cannot tell fullscreen from a zoomed window
 * once the menu bar and Dock auto-hide, and guessing wrong there slides the tab bar under the
 * traffic lights.
 *
 * @param styleApplied what [applyFullWindowContent] returned. False means the platform is drawing
 *        its own title bar above the content, which needs no inset at all.
 */
fun titleBarInset(styleApplied: Boolean, placement: WindowPlacement): Dp =
    if (styleApplied && placement != WindowPlacement.Fullscreen) NATIVE_TITLE_BAR_HEIGHT else 0.dp

/**
 * Whether a background colour is dark enough that light text belongs on it.
 *
 * @param argbHex the stored `defaultBackground`, an `0xAARRGGBB` string.
 * @return true for anything unparseable, because the shipped default is dark and a wrong guess
 *         towards light is the one that produces unreadable text.
 */
internal fun isDarkBackground(argbHex: String): Boolean {
    val value = argbHex.removePrefix("0x").removePrefix("0X").toULongOrNull(16) ?: return true
    val red = ((value shr 16) and 0xFFu).toDouble()
    val green = ((value shr 8) and 0xFFu).toDouble()
    val blue = (value and 0xFFu).toDouble()
    // Rec. 709 luma. The channels are weighted because the eye is far more sensitive to green
    // than to blue, so a plain average calls colours like deep blue "light" when they read black.
    return (0.2126 * red + 0.7152 * green + 0.0722 * blue) < 128.0
}

/**
 * The value for `apple.awt.application.appearance`, or null to leave it alone.
 *
 * The native title bar makes this necessary. `transparentTitleBar` stops the system painting its
 * own strip, so the title text ends up over OUR background, but AppKit still picks that text's
 * colour from the window appearance. Following the SYSTEM appearance therefore guarantees an
 * unreadable title whenever the two disagree, which was confirmed by hand: light system appearance
 * plus the default dark terminal background gave a near-black title on near-black.
 *
 * Deriving it from the terminal background instead fixes both directions at once, and does it
 * through a supported system property rather than JNA. There is no per-window appearance client
 * property to reach for - `CPlatformWindow` honours only `fullWindowContent`,
 * `transparentTitleBar`, `windowTitleVisible`, `fullscreenable` and some fade/shadow keys - and the
 * ObjC bridge in `window/WindowTransparency.kt` is not the alternative it looks like: its only
 * consumer is commented out there as having "compatibility issues with modern macOS/Java".
 *
 * The cost is that this is app-wide, so other AWT chrome (notably the native context menus) follows
 * the terminal background rather than the system. For a terminal that is the more consistent
 * answer, and it only applies on the native title bar path: with a custom title bar there is no
 * system-drawn title to keep legible, so the system appearance is left alone.
 *
 * Must be applied BEFORE AWT boots - the property is read once at initialisation.
 */
fun nativeTitleBarAppearance(useNativeTitleBar: Boolean, backgroundHex: String): String? {
    if (!ShellCustomizationUtils.isMacOS()) return null
    if (!useNativeTitleBar) return null
    return if (isDarkBackground(backgroundHex)) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
}
