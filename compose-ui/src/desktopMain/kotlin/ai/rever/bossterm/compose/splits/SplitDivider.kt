package ai.rever.bossterm.compose.splits

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * Visual divider thickness (the visible line).
 */
val DIVIDER_THICKNESS = 1.dp

/**
 * Drag hit area size (invisible overlay for easier grabbing).
 */
private val DRAG_AREA_SIZE = 16.dp

/**
 * Modifier extension for horizontal resize cursor (↔️).
 * Uses Compose's declarative pointerHoverIcon API for reliable hover state management.
 */
private fun Modifier.cursorForHorizontalResize(): Modifier {
    return this.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
}

/**
 * Modifier extension for vertical resize cursor (↕️).
 * Uses Compose's declarative pointerHoverIcon API for reliable hover state management.
 */
private fun Modifier.cursorForVerticalResize(): Modifier {
    return this.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
}

/**
 * The thin visible divider line between split panes.
 * This is what users see - a 1dp line.
 */
@Composable
fun SplitDividerLine(
    orientation: SplitOrientation,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .then(
                when (orientation) {
                    SplitOrientation.HORIZONTAL -> Modifier.fillMaxWidth().height(DIVIDER_THICKNESS)
                    SplitOrientation.VERTICAL -> Modifier.fillMaxHeight().width(DIVIDER_THICKNESS)
                }
            )
            .background(Color(0xFF2D2D2D))
    )
}

/**
 * The invisible drag target overlay.
 * This is positioned absolutely over the divider for a larger hit area.
 *
 * Uses cumulative drag tracking to avoid recomposition issues during drag.
 *
 * @param orientation The orientation of the split
 * @param currentRatio The current split ratio (used to capture start ratio)
 * @param availableSize The available size in pixels for ratio calculation
 * @param minRatio Minimum ratio when resizing (default 0.1 = 10%)
 * @param onRatioChange Called with the new ratio during drag
 * @param size The size of the drag area (default 16dp)
 */
@Composable
fun SplitDragHandle(
    orientation: SplitOrientation,
    currentRatio: Float,
    availableSize: Float,
    onRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minRatio: Float = 0.1f,
    size: Dp = DRAG_AREA_SIZE,
    /** Called when the drag ends (or is cancelled) — used to commit the final ratio to a host. */
    onDragEnd: () -> Unit = {}
) {
    // The pointerInput block below never restarts (its key never changes for a live split),
    // so parameters captured directly would stay frozen at their first-composition values:
    // a stale availableSize scales every drag by oldSize/newSize (sluggish or overshooting
    // dividers after the container resizes), and a stale currentRatio snaps the divider back
    // to its original position when the next drag starts. Read live values via
    // rememberUpdatedState instead.
    val latestRatio by rememberUpdatedState(currentRatio)
    val latestAvailableSize by rememberUpdatedState(availableSize)
    val latestMinRatio by rememberUpdatedState(minRatio)
    val latestOnRatioChange by rememberUpdatedState(onRatioChange)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    // Apply resize cursor based on orientation using direct AWT manipulation
    val cursorModifier = when (orientation) {
        SplitOrientation.HORIZONTAL -> Modifier.cursorForVerticalResize()
        SplitOrientation.VERTICAL -> Modifier.cursorForHorizontalResize()
    }

    Box(
        modifier = modifier
            .then(
                when (orientation) {
                    SplitOrientation.HORIZONTAL -> Modifier.fillMaxWidth().height(size)
                    SplitOrientation.VERTICAL -> Modifier.fillMaxHeight().width(size)
                }
            )
            .alpha(0f) // Invisible
            .then(cursorModifier)
            .pointerInput(orientation) {
                // Track the starting ratio when drag begins
                var startRatio = 0f
                var cumulativeDelta = 0f
                detectDragGestures(
                    onDragStart = {
                        // Capture the ratio at drag start
                        startRatio = latestRatio
                        cumulativeDelta = 0f
                    },
                    onDragEnd = {
                        latestOnDragEnd()
                    },
                    onDragCancel = {
                        latestOnDragEnd()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val delta = when (orientation) {
                            SplitOrientation.HORIZONTAL -> dragAmount.y
                            SplitOrientation.VERTICAL -> dragAmount.x
                        }
                        cumulativeDelta += delta

                        // Calculate new ratio from start ratio + cumulative delta
                        val deltaRatio = cumulativeDelta / latestAvailableSize
                        val maxRatio = 1f - latestMinRatio
                        val newRatio = (startRatio + deltaRatio).coerceIn(latestMinRatio, maxRatio)
                        latestOnRatioChange(newRatio)
                    }
                )
            }
    )
}

/**
 * Legacy single-component divider for simpler use cases.
 */
@Composable
fun SplitDivider(
    orientation: SplitOrientation,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Apply resize cursor based on orientation using direct AWT manipulation
    val cursorModifier = when (orientation) {
        SplitOrientation.HORIZONTAL -> Modifier.cursorForVerticalResize()
        SplitOrientation.VERTICAL -> Modifier.cursorForHorizontalResize()
    }

    Box(
        modifier = modifier
            .then(
                when (orientation) {
                    SplitOrientation.HORIZONTAL -> Modifier.fillMaxWidth().height(DIVIDER_THICKNESS)
                    SplitOrientation.VERTICAL -> Modifier.fillMaxHeight().width(DIVIDER_THICKNESS)
                }
            )
            .background(Color(0xFF2D2D2D))
            .then(cursorModifier)
            .pointerInput(orientation) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val delta = when (orientation) {
                        SplitOrientation.HORIZONTAL -> dragAmount.y
                        SplitOrientation.VERTICAL -> dragAmount.x
                    }
                    onDrag(delta)
                }
            }
    )
}
