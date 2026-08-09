package ai.rever.bossterm.compose.window

import androidx.compose.ui.unit.dp
import java.awt.Rectangle
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The part of the native title bar style a headless run can see.
 *
 * Whether the traffic lights end up painted on the app's own background is an AppKit rendering
 * property and is not assertable here - that stays manual. What IS assertable is the contract the
 * caller depends on: the return value decides whether the window reserves
 * [NATIVE_TITLE_BAR_HEIGHT] at the top, so a wrong answer is either a 28dp dead band or a tab bar
 * under the traffic lights.
 *
 * These go through the [JRootPane] overload because no [java.awt.Window] can be constructed in a
 * headless JVM at all - the constructor throws `HeadlessException`, and CI is headless on every
 * runner including macOS. The platform is passed in rather than detected so that every assertion
 * runs on every runner; otherwise each machine would skip the half of the contract it cannot see.
 */
class NativeTitleBarStyleTest {
    @Test
    fun `a window with no root pane is declined on every platform`() {
        // Returning false is what stops the caller reserving 28dp for a title bar that will never
        // be styled, which would show up as a strip of background above the tabs.
        assertFalse(applyFullWindowContent(rootPane = null, isMacOS = true))
        assertFalse(applyFullWindowContent(rootPane = null, isMacOS = false))
    }

    @Test
    fun `on macOS the three client properties that produce the style are set`() {
        val rootPane = JRootPane()
        assertTrue(applyFullWindowContent(rootPane, isMacOS = true), "macOS accepts a real root pane")

        // fullWindowContent extends the content pane under the title bar, transparentTitleBar stops
        // the system painting its own background over it. Either one alone gives a broken look:
        // the first without the second leaves a grey strip, the second without the first leaves the
        // content below it.
        assertEquals(true, rootPane.getClientProperty("apple.awt.fullWindowContent"))
        assertEquals(true, rootPane.getClientProperty("apple.awt.transparentTitleBar"))
        // The window title stays visible; the caller reserves exactly the strip it is drawn in.
        assertEquals(true, rootPane.getClientProperty("apple.awt.windowTitleVisible"))
    }

    @Test
    fun `off macOS nothing is applied and the caller does not inset`() {
        // These client properties are read by the macOS JDK only. Elsewhere the platform draws its
        // own decorations, so this must be a no-op and the window must not reserve the strip.
        val rootPane = JRootPane()
        assertFalse(applyFullWindowContent(rootPane, isMacOS = false))
        assertNull(rootPane.getClientProperty("apple.awt.fullWindowContent"))
    }

    @Test
    fun `the reserved strip is the standard macOS title bar height`() {
        // A constant rather than a measurement: with fullWindowContent the content pane fills the
        // frame, so the usual "window height minus content height" reports zero. 28pt is what the
        // traffic lights are laid out against for a regular-size window.
        assertEquals(28.dp, NATIVE_TITLE_BAR_HEIGHT)
    }

    // ---- isNativeFullscreen: bounds are the signal, because placement may not be ----

    @Test
    fun `covering the whole display reads as fullscreen`() {
        // macOS native fullscreen takes the entire screen, menu bar strip included, so the
        // window's bounds match the display's exactly.
        val screen = Rectangle(0, 0, 1920, 1080)
        assertTrue(isNativeFullscreen(Rectangle(0, 0, 1920, 1080), screen))
    }

    @Test
    fun `a zoomed window is not fullscreen and keeps its inset`() {
        // This is the case the whole function exists to separate. macOS zoom (the green button
        // WITHOUT fullscreen, or Compose's Maximized) fills the VISIBLE frame - the menu bar is
        // still there, so the window starts below it and is shorter than the display. The title
        // bar is still on screen, so the caller must still reserve the strip.
        val screen = Rectangle(0, 0, 1920, 1080)
        val zoomedBelowTheMenuBar = Rectangle(0, 25, 1920, 1055)
        assertFalse(isNativeFullscreen(zoomedBelowTheMenuBar, screen))
    }

    @Test
    fun `an ordinary floating window is not fullscreen`() {
        assertFalse(isNativeFullscreen(Rectangle(120, 80, 1200, 800), Rectangle(0, 0, 1920, 1080)))
    }

    @Test
    fun `unknown bounds are not fullscreen`() {
        // A window with no graphics configuration yet must not be guessed as fullscreen: that
        // would drop the inset and put the tab bar under the traffic lights.
        val screen = Rectangle(0, 0, 1920, 1080)
        assertFalse(isNativeFullscreen(null, screen))
        assertFalse(isNativeFullscreen(Rectangle(0, 0, 1920, 1080), null))
    }
}
