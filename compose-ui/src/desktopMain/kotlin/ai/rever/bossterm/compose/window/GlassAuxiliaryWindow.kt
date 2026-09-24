package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.settings.SettingsTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import ai.rever.bossterm.compose.settings.DialogTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Window as AwtWindow
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlinx.coroutines.delay

/** Neutral outside a glass-capable window, including embedded settings panels. */
val LocalAuxiliaryGlassOpacity = staticCompositionLocalOf { 1f }
val LocalAuxiliaryGlassTint = staticCompositionLocalOf { 1f }
private val LocalAuxiliaryGlassWindow = staticCompositionLocalOf<AwtWindow?> { null }

@Composable
fun GlassWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    resizable: Boolean = true,
    alwaysOnTop: Boolean = false,
    content: @Composable FrameWindowScope.() -> Unit
) {
    val mac = remember { ShellCustomizationUtils.isMacOS() }
    val linuxAlpha = remember { ShellCustomizationUtils.isLinux() && isTransparencySupported() }
    Window(onCloseRequest = onCloseRequest, state = state, visible = visible, title = title,
        resizable = resizable, alwaysOnTop = alwaysOnTop, undecorated = mac || linuxAlpha, transparent = mac || linuxAlpha) {
        AuxiliaryGlassSurface(window, configureFrame = mac, resizable = resizable) {
            Column(Modifier.fillMaxSize()) {
                if (mac) Box(Modifier.fillMaxWidth().height(28.dp).background(DialogTheme.SurfaceColor))
                else if (linuxAlpha) AuxiliaryTitleBar(window, title, onCloseRequest)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content()
                    if (linuxAlpha && resizable) {
                        AuxiliaryResizeGrip(window, Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
    }
}

@Composable
fun GlassDialogWindow(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "",
    resizable: Boolean = true,
    alwaysOnTop: Boolean = false,
    content: @Composable DialogWindowScope.() -> Unit
) {
    val mac = remember { ShellCustomizationUtils.isMacOS() }
    val linuxAlpha = remember { ShellCustomizationUtils.isLinux() && isTransparencySupported() }
    DialogWindow(onCloseRequest = onCloseRequest, state = state, visible = visible, title = title,
        resizable = resizable, alwaysOnTop = alwaysOnTop, undecorated = mac || linuxAlpha, transparent = mac || linuxAlpha) {
        AuxiliaryGlassSurface(window, configureFrame = mac, resizable = resizable) {
            Column(Modifier.fillMaxSize()) {
                if (mac) Box(Modifier.fillMaxWidth().height(28.dp).background(DialogTheme.SurfaceColor))
                else if (linuxAlpha) AuxiliaryTitleBar(window, title, onCloseRequest)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content()
                    if (linuxAlpha && resizable) {
                        AuxiliaryResizeGrip(window, Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
    }
}

/** Installs a backdrop only when Compose's modal owns a separate transparent native window. */
@Composable
fun InlineDialogGlassEffect(onInstalled: (Boolean) -> Unit = {}) = InlineDialogGlassContent {
    val installed = LocalNativeWindowGlass.current
    SideEffect { onInstalled(installed) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun InlineDialogGlassContent(content: @Composable () -> Unit) {
    val awtWindow = LocalAwtWindow.current
    if (awtWindow is ComposeDialog && awtWindow.isUndecorated && awtWindow !== LocalAuxiliaryGlassWindow.current) {
        AuxiliaryGlassSurface(awtWindow, configureFrame = false, resizable = false, content = content)
    } else content()
}

@Composable
private fun AuxiliaryGlassSurface(
    window: AwtWindow,
    configureFrame: Boolean,
    resizable: Boolean,
    content: @Composable () -> Unit
) {
    val settings by SettingsManager.instance.settings.collectAsState()
    val mac = remember { ShellCustomizationUtils.isMacOS() }
    val enabled = settings.isLiquidGlassTheme
    var controller by remember(window) { mutableStateOf<NativeWindowGlass?>(null) }
    var installed by remember(window) { mutableStateOf(false) }
    val latestSettings by rememberUpdatedState(settings)
    val latestEnabled by rememberUpdatedState(enabled)
    fun handle(): Long = when (window) {
        is ComposeWindow -> window.windowHandle
        is ComposeDialog -> window.windowHandle
        else -> 0L
    }
    fun update(glass: NativeWindowGlass?, config: TerminalSettings, active: Boolean) {
        glass?.setEnabled(active, cornerRadius = if (configureFrame) 0.0 else 20.0,
            width = window.width, height = window.height, style = config.windowGlassStyle,
            dark = config.defaultBackgroundColor.luminance() < 0.5f)
    }
    LaunchedEffect(window) {
        while (!window.isDisplayable || handle() == 0L) delay(25)
        if (mac) {
            configureWindowTransparency(window, true)
            if (configureFrame) MacOSWindowGlass.configureNativeFrame(handle(), resizable, auxiliary = true) { }
        }
        controller = NativeWindowGlass.create(window, handle()) { installed = it }
    }
    LaunchedEffect(controller, enabled, settings.windowGlassStyle, settings.activeThemeId) {
        update(controller, settings, enabled)
    }
    DisposableEffect(window, controller) {
        val glass = controller
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = update(glass, latestSettings, latestEnabled)
        }
        window.addComponentListener(listener)
        onDispose { window.removeComponentListener(listener); glass?.close() }
    }
    // Decorated on Windows, so the system title bar would otherwise follow the Windows app mode.
    // Skipped while acrylic is installed: that path owns the frame's appearance.
    WindowsTitleBarColorEffect(window, SettingsTheme.BackgroundColor, SettingsTheme.TextPrimary, enabled = !installed)
    CompositionLocalProvider(
        LocalAuxiliaryGlassWindow provides window,
        LocalNativeWindowGlass provides installed,
        // Auxiliary windows contain dense text and form controls. A changing native
        // backdrop must not turn a dark palette into a light, low-contrast surface.
        LocalAuxiliaryGlassOpacity provides if (installed && enabled) settings.windowGlassOpacity.coerceIn(0.94f, 1f) else 1f,
        LocalAuxiliaryGlassTint provides if (installed && enabled) settings.windowGlassTint.coerceIn(0f, 1f) else 1f
    ) { content() }
}

/** AWT requires undecorated windows for per-pixel transparency on X11. */
@Composable
private fun AuxiliaryTitleBar(window: AwtWindow, title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp).background(DialogTheme.SurfaceColor),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = SettingsTheme.TextPrimary, fontSize = 12.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                .pointerInput(window) {
                    var startPointer: java.awt.Point? = null
                    var startBounds = window.bounds
                    detectDragGestures(
                        onDragStart = {
                            startPointer = java.awt.MouseInfo.getPointerInfo()?.location
                            startBounds = window.bounds
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val pointer = java.awt.MouseInfo.getPointerInfo()?.location
                            if (pointer != null && startPointer != null) {
                                window.location = auxiliaryDragBounds(startBounds, startPointer!!, pointer, false, window.minimumSize).location
                            }
                        }
                    )
                })
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = SettingsTheme.TextPrimary) }
    }
}

@Composable
private fun AuxiliaryResizeGrip(window: AwtWindow, modifier: Modifier = Modifier) {
    val color = SettingsTheme.TextMuted
    Canvas(modifier.size(14.dp)
        .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.SE_RESIZE_CURSOR)))
        .pointerInput(window) {
            var startPointer: java.awt.Point? = null
            var startBounds = window.bounds
            detectDragGestures(
                onDragStart = {
                    startPointer = java.awt.MouseInfo.getPointerInfo()?.location
                    startBounds = window.bounds
                },
                onDrag = { change, _ ->
                    change.consume()
                    val pointer = java.awt.MouseInfo.getPointerInfo()?.location
                    if (pointer != null && startPointer != null) {
                        window.size = auxiliaryDragBounds(startBounds, startPointer!!, pointer, true, window.minimumSize).size
                    }
                }
            )
        }) {
        drawLine(color, Offset(size.width * 0.3f, size.height - 3.dp.toPx()),
            Offset(size.width - 3.dp.toPx(), size.height * 0.3f), 1.dp.toPx())
    }
}

/** AWT screen coordinates stay stable when moving the component under the pointer. */
internal fun auxiliaryDragBounds(
    start: java.awt.Rectangle,
    press: java.awt.Point,
    pointer: java.awt.Point,
    resize: Boolean,
    minimum: java.awt.Dimension
): java.awt.Rectangle {
    val dx = pointer.x - press.x
    val dy = pointer.y - press.y
    return if (resize) java.awt.Rectangle(start.x, start.y,
        (start.width + dx).coerceAtLeast(maxOf(250, minimum.width)),
        (start.height + dy).coerceAtLeast(maxOf(150, minimum.height)))
    else java.awt.Rectangle(start.x + dx, start.y + dy, start.width, start.height)
}
