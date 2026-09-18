package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import java.awt.event.InputEvent

/**
 * Custom title bar for undecorated windows using WindowDraggableArea.
 * Provides macOS-style traffic light buttons and drag-to-move functionality.
 *
 * Must be called within a WindowScope (inside Window composable).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WindowScope.CustomTitleBar(
    title: String,
    windowState: WindowState,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onFullscreen: () -> Unit,
    onMaximize: () -> Unit,
    backgroundColor: Color,
    globalHotkeyHint: String? = null,
    modifier: Modifier = Modifier,
    glassEnabled: Boolean = false,
    isFullscreen: Boolean = false,
    nativeTrafficLights: Boolean = false,
    actions: (@Composable () -> Unit)? = null
) {
    val chrome = BossUiTheme.current
    val focused = LocalWindowInfo.current.isWindowFocused
    val density = LocalDensity.current
    var actionsWidth by remember { mutableStateOf(0.dp) }
    val highlight = chrome.chalk.copy(alpha = if (focused) 0.08f else 0.04f)
    val divider = chrome.chalk.copy(alpha = 0.08f)
    val glassBrush = remember(backgroundColor, highlight) {
        Brush.verticalGradient(listOf(highlight, Color.Transparent))
    }
    WindowDraggableArea(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(backgroundColor)
            .then(if (glassEnabled) Modifier.background(glassBrush) else Modifier)
            .drawBehind {
                drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
    ) {
        Box(Modifier.fillMaxSize()) {
            // A sibling behind the content receives only title/background clicks, never clicks
            // on the traffic lights or status actions. Observe down before the drag area starts
            // moving the window, and consume only the second click so ordinary dragging survives.
            Box(Modifier.matchParentSize().onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                val mouse = event.nativeEvent as? java.awt.event.MouseEvent
                if (!isFullscreen && mouse?.button == java.awt.event.MouseEvent.BUTTON1 && mouse.clickCount == 2) {
                    event.changes.forEach { it.consume() }
                    onMaximize()
                }
            })
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                // Equal gutters keep the title truly centered regardless of the shortcut's length.
                // At narrow widths the title truncates before either control area is crowded.
                val gutter = if (actions != null) minOf(maxOf(72.dp, actionsWidth + 12.dp), maxWidth / 2)
                    else minOf(if (globalHotkeyHint == null) 72.dp else 128.dp, maxWidth / 3)
                Text(
                    text = title,
                    color = chrome.chalk.copy(alpha = if (focused) 0.88f else 0.55f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = gutter)
                )

                if (!isFullscreen && !nativeTrafficLights) {
                    val groupInteractionSource = remember { MutableInteractionSource() }
                    val isGroupHovered by groupInteractionSource.collectIsHoveredAsState()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterStart)
                            .hoverable(interactionSource = groupInteractionSource)
                    ) {
                        CloseButton(isGroupHovered = isGroupHovered, focused = focused, onClick = onClose)
                        MinimizeButton(isGroupHovered = isGroupHovered, focused = focused, onClick = onMinimize)
                        FullscreenButton(
                            isGroupHovered = isGroupHovered,
                            focused = focused,
                            onFullscreen = onFullscreen,
                            onMaximize = onMaximize
                        )
                    }
                }

                if (actions != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd)
                            .widthIn(max = (maxWidth - 80.dp).coerceAtLeast(0.dp))
                            .onSizeChanged { actionsWidth = with(density) { it.width.toDp() } },
                        verticalAlignment = Alignment.CenterVertically
                    ) { actions() }
                } else if (globalHotkeyHint != null) {
                    Text(
                        text = globalHotkeyHint,
                        color = chrome.chalk.copy(alpha = if (focused) 0.55f else 0.35f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterEnd)
                            .widthIn(max = gutter)
                            .background(chrome.chalk.copy(alpha = 0.04f), RoundedCornerShape(5.dp))
                            .border(0.5.dp, divider, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/**
 * macOS-style traffic light palette.
 *
 * Deliberately NOT themed, and the one set of literals in this file that should
 * stay literal. These are the operating system's window controls: macOS paints the
 * same red/yellow/green in light and dark appearance, so a user identifies them by
 * colour before reading anything else in the window. Recolouring them per terminal
 * theme would make BossTerm the only app on the machine whose close button is not
 * red. `ChromeTokenCoverageTest` allowlists this object for that reason.
 */
private object TrafficLightColors {
    val inactive = Color(0xFF8E8E93)

    // Close button (red)
    val closeDefault = Color(0xFFFF6159)
    val closeIcon = Color(0xFF4D0000)
    val closeBorder = Color(0x33000000)

    // Minimize button (yellow)
    val minimizeDefault = Color(0xFFFFBD2E)
    val minimizeIcon = Color(0xFF995700)
    val minimizeBorder = Color(0x33000000)

    // Maximize button (green)
    val maximizeDefault = Color(0xFF28C941)
    val maximizeIcon = Color(0xFF006500)
    val maximizeBorder = Color(0x33000000)
}

/**
 * Close button (red) with × icon on hover.
 * Icon: a compact cross centered inside the 6dp-radius circle.
 */
@Composable
private fun CloseButton(
    isGroupHovered: Boolean,
    focused: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (focused || isGroupHovered) TrafficLightColors.closeDefault else TrafficLightColors.inactive

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(0.5.dp, TrafficLightColors.closeBorder, CircleShape)
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        if (isGroupHovered) {
            Canvas(modifier = Modifier.size(6.dp)) {
                val strokeWidth = 1.1.dp.toPx()
                // Draw × icon - two diagonal lines
                drawLine(
                    color = TrafficLightColors.closeIcon,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = TrafficLightColors.closeIcon,
                    start = Offset(size.width, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Minimize button (yellow) with − icon on hover.
 * Icon: a centered 6dp horizontal line.
 */
@Composable
private fun MinimizeButton(
    isGroupHovered: Boolean,
    focused: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (focused || isGroupHovered) TrafficLightColors.minimizeDefault else TrafficLightColors.inactive

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(0.5.dp, TrafficLightColors.minimizeBorder, CircleShape)
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        if (isGroupHovered) {
            Canvas(modifier = Modifier.size(6.dp)) {
                val strokeWidth = 1.1.dp.toPx()
                // Draw − icon - horizontal line
                drawLine(
                    color = TrafficLightColors.minimizeIcon,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Fullscreen button (green) with diagonal arrows icon on hover.
 * - Click = Fullscreen
 * - Option+Click = Maximize (zoom)
 * Icon: two triangular arrows pointing to opposite corners
 */
@Composable
private fun FullscreenButton(
    isGroupHovered: Boolean,
    focused: Boolean,
    onFullscreen: () -> Unit,
    onMaximize: () -> Unit
) {
    val bgColor = if (focused || isGroupHovered) TrafficLightColors.maximizeDefault else TrafficLightColors.inactive

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(0.5.dp, TrafficLightColors.maximizeBorder, CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                            // Check for Option/Alt key
                            val nativeEvent = event.nativeEvent
                            val hasOptionModifier = if (nativeEvent is java.awt.event.MouseEvent) {
                                (nativeEvent.modifiersEx and InputEvent.ALT_DOWN_MASK) != 0
                            } else {
                                false
                            }

                            if (hasOptionModifier) {
                                onMaximize()
                            } else {
                                onFullscreen()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isGroupHovered) {
            Canvas(modifier = Modifier.size(6.dp)) {
                // Native-style solid corner triangles, separated by a diagonal gap.
                // Stroked arrow shafts look oversized at this scale.
                val arrows = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width * 0.72f, 0f)
                    lineTo(0f, size.height * 0.72f)
                    close()
                    moveTo(size.width, size.height)
                    lineTo(size.width * 0.28f, size.height)
                    lineTo(size.width, size.height * 0.28f)
                    close()
                }
                drawPath(arrows, TrafficLightColors.maximizeIcon)
            }
        }
    }
}
