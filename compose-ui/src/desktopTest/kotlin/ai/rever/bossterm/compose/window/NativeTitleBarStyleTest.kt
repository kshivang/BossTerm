package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.ui.unit.dp
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
 * runner including macOS.
 */
class NativeTitleBarStyleTest {
    @Test
    fun `a window with no root pane is declined rather than half-styled`() {
        // Returning false is what stops the caller reserving 28dp for a title bar that will never
        // be styled, which would show up as a strip of background above the tabs.
        assertFalse(applyFullWindowContent(null as JRootPane?))
    }

    @Test
    fun `on macOS the three client properties that produce the style are set`() {
        if (!ShellCustomizationUtils.isMacOS()) return

        val rootPane = JRootPane()
        assertTrue(applyFullWindowContent(rootPane), "macOS should accept a real root pane")

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
        if (ShellCustomizationUtils.isMacOS()) return

        // These client properties are read by the macOS JDK only. Elsewhere the platform draws its
        // own decorations, so this must be a no-op and the window must not reserve the strip.
        val rootPane = JRootPane()
        assertFalse(applyFullWindowContent(rootPane))
        assertNull(rootPane.getClientProperty("apple.awt.fullWindowContent"))
    }

    @Test
    fun `the reserved strip is the standard macOS title bar height`() {
        // A constant rather than a measurement: with fullWindowContent the content pane fills the
        // frame, so the usual "window height minus content height" reports zero. 28pt is what the
        // traffic lights are laid out against for a regular-size window.
        assertEquals(28.dp, NATIVE_TITLE_BAR_HEIGHT)
    }
}
