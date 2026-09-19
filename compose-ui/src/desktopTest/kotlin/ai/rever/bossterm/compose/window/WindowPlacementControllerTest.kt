package ai.rever.bossterm.compose.window

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import java.awt.Insets
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowPlacementControllerTest {
    @Test
    fun `failed deferred exit clears early corner state and allows retry`() {
        val controller = WindowPlacementController(WindowState(), nativeFullscreenToggle = { true })
        controller.nativeFullscreenChanged(NativeFullscreenPhase.ENTERING)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.ENTERED)
        controller.toggleFullscreen()
        assertEquals(true, controller.hasRoundedCorners)
        controller.nativeFullscreenRequestFailed()
        assertEquals(false, controller.hasRoundedCorners)
        assertEquals(false, controller.isTransitioning)
        controller.toggleFullscreen()
        assertEquals(true, controller.hasRoundedCorners)
    }

    @Test
    fun `fullscreen restores geometry saved before native resize callbacks`() {
        val size = DpSize(900.dp, 650.dp)
        val position = WindowPosition((-1200).dp, 80.dp)
        val state = WindowState(size = size, position = position)
        val controller = WindowPlacementController(state)
        repeat(3) {
            controller.toggleFullscreen()
            assertEquals(WindowPlacement.Fullscreen, state.placement)
            // Model the asynchronous callbacks which previously overwrote previousBounds.
            state.size = DpSize(1728.dp, 1117.dp)
            state.position = WindowPosition((-1728).dp, 0.dp)
            controller.toggleFullscreen()
            assertEquals(WindowPlacement.Floating, state.placement)
            assertEquals(size, state.size)
            assertEquals(position, state.position)
        }
    }

    @Test
    fun `fullscreen from maximized returns to maximized then original floating bounds`() {
        val size = DpSize(820.dp, 530.dp)
        val position = WindowPosition(47.dp, 91.dp)
        val state = WindowState(size = size, position = position)
        val controller = WindowPlacementController(state)
        controller.toggleMaximized()
        state.size = DpSize(1440.dp, 875.dp)
        controller.toggleFullscreen()
        state.size = DpSize(1440.dp, 900.dp)
        controller.toggleFullscreen()
        assertEquals(WindowPlacement.Maximized, state.placement)
        controller.toggleMaximized()
        assertEquals(WindowPlacement.Floating, state.placement)
        assertEquals(size, state.size)
        assertEquals(position, state.position)
    }

    @Test
    fun `manual resize after restoring becomes the next restore size`() {
        val state = WindowState(size = DpSize(800.dp, 600.dp))
        val controller = WindowPlacementController(state)
        controller.toggleFullscreen()
        controller.toggleFullscreen()
        val resized = DpSize(1050.dp, 720.dp)
        state.size = resized
        controller.toggleFullscreen()
        state.size = DpSize(1728.dp, 1117.dp)
        controller.toggleFullscreen()
        assertEquals(resized, state.size)
    }
    @Test
    fun `custom fullscreen uses logical screen bounds without changing AWT placement`() {
        val screen = Rectangle(-1512, 0, 1512, 982)
        val insets = Insets(25, 0, 70, 0)
        val original = WindowBounds(DpSize(800.dp, 600.dp), WindowPosition((-1300).dp, 75.dp))
        val state = WindowState(size = original.size, position = original.position)
        val controller = WindowPlacementController(state, customBounds = { screenWindowBounds(screen, insets, it) })
        repeat(3) {
            controller.toggleFullscreen()
            assertEquals(WindowPlacement.Fullscreen, controller.placement)
            assertEquals(WindowPlacement.Floating, state.placement)
            assertEquals(DpSize(1512.dp, 982.dp), state.size)
            assertEquals(WindowPosition((-1512).dp, 0.dp), state.position)
            controller.toggleFullscreen()
            assertEquals(original.size, state.size)
            assertEquals(original.position, state.position)
        }
        controller.toggleMaximized()
        assertEquals(DpSize(1512.dp, 887.dp), state.size)
        assertEquals(WindowPosition((-1512).dp, 25.dp), state.position)
        controller.toggleFullscreen()
        controller.toggleFullscreen()
        assertEquals(WindowPlacement.Maximized, controller.placement)
        assertEquals(DpSize(1512.dp, 887.dp), state.size)
        controller.toggleMaximized()
        assertEquals(original.size, state.size)
        assertEquals(original.position, state.position)
    }
    @Test
    fun `native fullscreen waits for exit completion and ignores repeated clicks during animation`() {
        val original = WindowBounds(DpSize(900.dp, 650.dp), WindowPosition(70.dp, 90.dp))
        val state = WindowState(size = original.size, position = original.position)
        var requests = 0
        val controller = WindowPlacementController(state,
            customBounds = { WindowBounds(DpSize(1512.dp, 982.dp), WindowPosition(0.dp, 0.dp)) },
            nativeFullscreenToggle = { requests++; true })
        controller.toggleFullscreen()
        controller.toggleFullscreen()
        assertEquals(1, requests)
        assertEquals(original.size, state.size) // leave geometry to AppKit
        controller.nativeFullscreenChanged(NativeFullscreenPhase.ENTERING)
        assertEquals(original.size, state.size)
        // Native resize callbacks publish the animation's final bounds.
        state.size = DpSize(1512.dp, 982.dp)
        state.position = WindowPosition(0.dp, 0.dp)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.ENTERED)
        assertEquals(DpSize(1512.dp, 982.dp), state.size)
        assertEquals(WindowPosition(0.dp, 0.dp), state.position)
        controller.toggleFullscreen()
        assertEquals(2, requests)
        assertEquals(true, controller.hasRoundedCorners)
        assertEquals(WindowPlacement.Fullscreen, controller.placement)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.EXITING)
        assertEquals(true, controller.hasRoundedCorners)
        assertEquals(DpSize(1512.dp, 982.dp), state.size) // don't fight the exit animation
        controller.nativeFullscreenChanged(NativeFullscreenPhase.EXITED)
        assertEquals(original.size, state.size)
        assertEquals(original.position, state.position)
        assertEquals(WindowPlacement.Floating, controller.placement)
    }

    @Test
    fun `OS initiated fullscreen preserves normal bounds too`() {
        val original = DpSize(777.dp, 555.dp)
        val state = WindowState(size = original)
        val controller = WindowPlacementController(state, nativeFullscreenToggle = { true })
        controller.nativeFullscreenChanged(NativeFullscreenPhase.ENTERING)
        state.size = DpSize(1512.dp, 982.dp)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.ENTERED)
        assertEquals(false, controller.hasRoundedCorners)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.EXITING)
        assertEquals(true, controller.hasRoundedCorners)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.EXITED)
        assertEquals(original, state.size)
    }
    @Test
    fun `resize stays disabled throughout native fullscreen and its transitions`() {
        val controller = WindowPlacementController(WindowState(), nativeFullscreenToggle = { true })
        assertEquals(true, controller.allowsWindowResize)
        controller.toggleFullscreen()
        assertEquals(false, controller.allowsWindowResize)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.ENTERING)
        assertEquals(false, controller.allowsWindowResize)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.ENTERED)
        assertEquals(false, controller.allowsWindowResize)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.EXITING)
        assertEquals(false, controller.allowsWindowResize)
        controller.nativeFullscreenChanged(NativeFullscreenPhase.EXITED)
        assertEquals(true, controller.allowsWindowResize)
    }

    @Test
    fun `failed fullscreen request restores resizing`() {
        val controller = WindowPlacementController(WindowState(), nativeFullscreenToggle = { false })
        controller.toggleFullscreen()
        assertEquals(true, controller.allowsWindowResize)
    }

    @Test
    fun `compose fullscreen also disables resizing`() {
        val state = WindowState(placement = WindowPlacement.Fullscreen)
        val controller = WindowPlacementController(state)
        assertEquals(false, controller.allowsWindowResize)
        state.placement = WindowPlacement.Floating
        assertEquals(true, controller.allowsWindowResize)
    }

}
