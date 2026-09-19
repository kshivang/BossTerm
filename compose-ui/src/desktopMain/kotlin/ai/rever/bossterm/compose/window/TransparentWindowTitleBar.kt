package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.util.uiTextWithFallback

import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import java.awt.event.InputEvent

/**
 * Custom title bar for undecorated windows using WindowDraggableArea.
 * Provides macOS-style traffic light buttons and drag-to-move functionality.
 *
 * Must be called within a WindowScope (inside Window composable).
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    actions: (@Composable () -> Unit)? = null,
    headerHeight: androidx.compose.ui.unit.Dp = 32.dp,
    onToggleSidebar: (() -> Unit)? = null,
    leadingActions: (@Composable () -> Unit)? = null,
    nativeWindowHandle: Long = 0L,
    sidebarPanelWidth: Double = 0.0
) {
    if (nativeWindowHandle != 0L) {
        NativeTitleToolbar(nativeWindowHandle, title, onToggleSidebar, actions, leadingActions, sidebarPanelWidth,
            dark = backgroundColor.luminance() < 0.5f)
        return
    }
    val preferences = rememberMacChromePreferences()
    val macOS = ai.rever.bossterm.compose.shell.ShellCustomizationUtils.isMacOS()
    val systemChrome = remember(preferences.dark) { titleBarTheme(preferences.dark) }
    val chrome = if (macOS) systemChrome else BossUiTheme.current
    val chromeBackground = if (macOS) chrome.panel.copy(alpha = if (preferences.reduceTransparency || preferences.increaseContrast) 1f else LocalWindowGlassTint.current.coerceIn(0f, 1f)) else backgroundColor
    val focused = LocalWindowInfo.current.isWindowFocused
    var fullscreenLightsRevealed by remember(isFullscreen) { mutableStateOf(false) }
    val lightsWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (!isFullscreen || fullscreenLightsRevealed) 70.dp else 0.dp,
        animationSpec = androidx.compose.animation.core.tween(160)
    )
    val titleMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val minimumTitleWidth = with(density) {
        titleMeasurer.measure("MMMMM", style = androidx.compose.ui.text.TextStyle(
            fontSize = 12.sp, fontWeight = FontWeight.Medium)).size.width.toDp()
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Keep both split actions visible whenever the controls and five-character
        // title fit; the default terminal window is narrower than 850 dp.
        val compact = maxWidth < minimumTitleWidth + 460.dp
        val stacked = maxWidth < minimumTitleWidth + 380.dp
        val rowHeight = headerHeight.coerceAtLeast(44.dp)
        CompositionLocalProvider(
            LocalTitleBarTheme provides chrome,
            LocalTitleBarPreferences provides preferences,
            LocalInTitleBar provides true,
            LocalCompactTitleBar provides compact
        ) {
            val toolbar: @Composable () -> Unit = {
                leadingActions?.invoke()
                if (actions != null) actions()
                CompositionLocalProvider(LocalTitleBarTrailing provides true) { leadingActions?.invoke() }
            }
            WindowDraggableArea(Modifier.fillMaxWidth()
                .height(rowHeight + if (stacked) 42.dp else 0.dp)
                .onPointerEvent(PointerEventType.Enter) {
                    if (isFullscreen) fullscreenLightsRevealed = true
                }
                .onPointerEvent(PointerEventType.Move) {
                    if (isFullscreen) fullscreenLightsRevealed = true
                }
                .onPointerEvent(PointerEventType.Exit) { fullscreenLightsRevealed = false }
                .background(chromeBackground)
                .drawBehind {
                    drawLine(chrome.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.matchParentSize().onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                        val mouse = event.nativeEvent as? java.awt.event.MouseEvent
                        if (!isFullscreen && mouse?.button == java.awt.event.MouseEvent.BUTTON1 && mouse.clickCount == 2) {
                            event.changes.forEach { it.consume() }
                            onMaximize()
                        }
                    })
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        Row(Modifier.fillMaxWidth().height(rowHeight),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            // Fullscreen controls expand into the same row, like Notes.
                            val lightInteraction = remember { MutableInteractionSource() }
                            val lightsHovered by lightInteraction.collectIsHoveredAsState()
                            if (lightsWidth > 0.dp) Box(Modifier.width(lightsWidth).fillMaxHeight().clipToBounds().hoverable(lightInteraction),
                                contentAlignment = Alignment.CenterStart) {
                                if (!nativeTrafficLights || isFullscreen) {
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = !isFullscreen || fullscreenLightsRevealed,
                                        enter = androidx.compose.animation.fadeIn() +
                                            androidx.compose.animation.slideInHorizontally { -it / 2 },
                                        exit = androidx.compose.animation.fadeOut()
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CloseButton(lightsHovered, focused, onClose)
                                            MinimizeButton(lightsHovered, focused, onMinimize, enabled = !isFullscreen)
                                            FullscreenButton(lightsHovered, focused, onFullscreen, onMaximize)
                                        }
                                    }
                                }
                            }
                            if (onToggleSidebar != null) ActionCapsule {
                                androidx.compose.material.IconButton(onToggleSidebar, Modifier.size(32.dp)) {
                                    MacToolbarIcon(Icons.Outlined.ViewSidebar, "Toggle sidebar", chrome.chalk,
                                        Modifier.size(18.dp), symbol = "sidebar.left")
                                }
                            }
                            // Always reserve the remaining title area. Long titles truncate,
                            // but are never removed at a width breakpoint; hover reveals all text.
                            androidx.compose.foundation.TooltipArea(
                                modifier = Modifier.weight(1f).widthIn(min = minimumTitleWidth),
                                tooltip = {
                                    androidx.compose.material.Surface(color = chrome.panel, shape = RoundedCornerShape(6.dp)) {
                                        Text(uiTextWithFallback(title), Modifier.padding(8.dp), color = chrome.chalk)
                                    }
                                }
                            ) {
                                Text(uiTextWithFallback(title), color = chrome.chalk.copy(alpha = if (focused) 1f else 0.65f),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth())
                            }
                            if (!stacked) toolbar()
                        }
                        if (stacked) {
                            Row(Modifier.fillMaxWidth().height(42.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically) { toolbar() }
                        }
                    }
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
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val bgColor = if (enabled && (focused || isGroupHovered)) TrafficLightColors.minimizeDefault else TrafficLightColors.inactive

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(0.5.dp, TrafficLightColors.minimizeBorder, CircleShape)
            .pointerInput(onClick) {
                detectTapGestures(onTap = { if (enabled) onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        if (enabled && isGroupHovered) {
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
