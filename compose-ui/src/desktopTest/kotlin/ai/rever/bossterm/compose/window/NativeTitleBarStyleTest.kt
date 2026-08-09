package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
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

    // ---- titleBarInset: the subtlest decision here, and the one that already drifted ----

    @Test
    fun `a zoomed window still reserves the strip`() {
        // The distinction the whole function exists for. macOS zoom KEEPS its title bar, so a
        // maximized window must still inset or the tab bar slides under the traffic lights.
        assertEquals(NATIVE_TITLE_BAR_HEIGHT, titleBarInset(true, WindowPlacement.Maximized))
        assertEquals(NATIVE_TITLE_BAR_HEIGHT, titleBarInset(true, WindowPlacement.Floating))
    }

    @Test
    fun `fullscreen reserves nothing because the title bar is gone`() {
        // Reserving here would leave a dead band of background above the content.
        assertEquals(0.dp, titleBarInset(true, WindowPlacement.Fullscreen))
    }

    @Test
    fun `without the style applied nothing is reserved in any placement`() {
        // Off macOS, or for a window that could not be styled: the platform draws its own title
        // bar above the content, so an inset would be pure dead space.
        for (placement in WindowPlacement.entries) {
            assertEquals(0.dp, titleBarInset(false, placement), "unstyled $placement must not inset")
        }
    }

    // ---- the appearance the title text is drawn from ----

    @Test
    fun `the shipped default background is dark`() {
        // 0xFF05070B. If this ever reads light, every window ships an unreadable title.
        assertTrue(isDarkBackground("0xFF05070B"))
    }

    @Test
    fun `luminance is weighted, not averaged`() {
        // A plain channel average calls saturated blue "light" at 0,0,255 (avg 85 vs the 128
        // threshold is dark, but 0,0,255 against a naive max/mid test is not), while green at the
        // same value is genuinely light. Weighting is what separates them.
        assertTrue(isDarkBackground("0xFF0000FF"), "saturated blue reads as dark")
        assertFalse(isDarkBackground("0xFF00FF00"), "saturated green reads as light")
    }

    @Test
    fun `an unparseable background is treated as dark`() {
        // Guessing "light" would put dark text on what is probably a dark background. The shipped
        // default is dark, so this is the safe direction.
        assertTrue(isDarkBackground("not a colour"))
        assertTrue(isDarkBackground(""))
    }

    @Test
    fun `appearance follows the background, not the system`() {
        if (!ShellCustomizationUtils.isMacOS()) return
        assertEquals(
            "NSAppearanceNameDarkAqua",
            nativeTitleBarAppearance(useNativeTitleBar = true, backgroundHex = "0xFF05070B"),
        )
        assertEquals(
            "NSAppearanceNameAqua",
            nativeTitleBarAppearance(useNativeTitleBar = true, backgroundHex = "0xFFFFFFFF"),
        )
    }

    @Test
    fun `the custom title bar leaves the system appearance alone`() {
        // Nothing system-drawn to keep legible there, so forcing the whole app's chrome would be
        // an unrelated change the user did not ask for.
        assertNull(nativeTitleBarAppearance(useNativeTitleBar = false, backgroundHex = "0xFF05070B"))
    }
}
