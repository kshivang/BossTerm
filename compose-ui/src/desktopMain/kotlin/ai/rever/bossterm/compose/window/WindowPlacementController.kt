package ai.rever.bossterm.compose.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Insets
import java.awt.Rectangle

/** Logical desktop coordinates, never backing/Retina pixels. */
data class WindowBounds(val size: DpSize, val position: WindowPosition)

fun screenWindowBounds(screen: Rectangle, insets: Insets, placement: WindowPlacement): WindowBounds {
    val usable = placement == WindowPlacement.Maximized
    val left = if (usable) insets.left else 0
    val top = if (usable) insets.top else 0
    val right = if (usable) insets.right else 0
    val bottom = if (usable) insets.bottom else 0
    return WindowBounds(
        DpSize((screen.width - left - right).coerceAtLeast(1).dp, (screen.height - top - bottom).coerceAtLeast(1).dp),
        WindowPosition((screen.x + left).dp, (screen.y + top).dp)
    )
}

/**
 * Saves floating geometry before a transition. Native fullscreen is asynchronous: never resize
 * while AppKit is animating, and restore geometry only after its DID_EXIT notification. Custom
 * maximize uses logical usable-screen bounds; fullscreen uses an actual macOS Space.
 */
class WindowPlacementController(
    private val state: WindowState,
    private val customBounds: ((WindowPlacement) -> WindowBounds)? = null,
    private val nativeFullscreenToggle: (() -> Boolean)? = null
) {
    private var floatingBounds: WindowBounds? = null
    private var beforeFullscreen = WindowPlacement.Floating
    private var customPlacement by mutableStateOf(WindowPlacement.Floating)
    private var nativeFullscreen by mutableStateOf(false)
    private var isExitingFullscreen by mutableStateOf(false)
    val hasRoundedCorners: Boolean
        get() = placement == WindowPlacement.Floating ||
            (isExitingFullscreen && beforeFullscreen == WindowPlacement.Floating)

    var isTransitioning by mutableStateOf(false)
        private set
    val placement: WindowPlacement get() = when {
        nativeFullscreen -> WindowPlacement.Fullscreen
        customBounds != null -> customPlacement
        else -> state.placement
    }

    fun toggleFullscreen() {
        if (isTransitioning) return
        if (nativeFullscreenToggle != null) {
            if (!nativeFullscreen) saveBeforeFullscreen()
            isTransitioning = true
            isExitingFullscreen = nativeFullscreen
            if (!nativeFullscreenToggle.invoke()) {
                nativeFullscreenRequestFailed()
            }
        } else if (placement == WindowPlacement.Fullscreen) moveTo(beforeFullscreen)
        else {
            saveBeforeFullscreen()
            moveTo(WindowPlacement.Fullscreen)
        }
    }

    fun nativeFullscreenRequestFailed() {
        isTransitioning = false
        isExitingFullscreen = false
    }

    /** Also handles transitions requested by the OS, rather than our green button. */
    fun nativeFullscreenChanged(phase: NativeFullscreenPhase) {
        when (phase) {
            NativeFullscreenPhase.ENTERING -> {
                if (!isTransitioning) saveBeforeFullscreen()
                isTransitioning = true
                nativeFullscreen = true
            }
            NativeFullscreenPhase.ENTERED -> {
                nativeFullscreen = true
                isTransitioning = false
            }
            NativeFullscreenPhase.EXITING -> {
                isTransitioning = true
                isExitingFullscreen = true
            }
            NativeFullscreenPhase.EXITED -> {
                nativeFullscreen = false
                isTransitioning = false
                isExitingFullscreen = false
                // Do not pass through moveTo: an exit resize must never become the saved normal size.
                customPlacement = beforeFullscreen
                state.placement = if (customBounds == null) beforeFullscreen else WindowPlacement.Floating
                val bounds = if (beforeFullscreen == WindowPlacement.Floating) floatingBounds
                    else customBounds?.invoke(beforeFullscreen)
                bounds?.let { state.size = it.size; state.position = it.position }
            }
        }
    }

    fun toggleMaximized() {
        if (isTransitioning) return
        if (nativeFullscreen) {
            // Option-click in fullscreen exits first, then applies the requested zoom.
            beforeFullscreen = WindowPlacement.Maximized
            toggleFullscreen()
        } else moveTo(if (placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized)
    }

    private fun saveBeforeFullscreen() {
        beforeFullscreen = placement
        if (placement == WindowPlacement.Floating) floatingBounds = WindowBounds(state.size, state.position)
    }

    private fun moveTo(target: WindowPlacement) {
        if (placement == WindowPlacement.Floating) floatingBounds = WindowBounds(state.size, state.position)
        if (customBounds == null) state.placement = target
        else {
            customPlacement = target
            state.placement = WindowPlacement.Floating
        }
        val bounds = if (target == WindowPlacement.Floating) floatingBounds else customBounds?.invoke(target)
        bounds?.let { state.size = it.size; state.position = it.position }
    }
}
