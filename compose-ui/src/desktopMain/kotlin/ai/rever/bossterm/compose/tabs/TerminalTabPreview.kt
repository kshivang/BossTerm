package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import ai.rever.bossterm.terminal.model.image.TerminalImage
import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.compose.rendering.RenderingContext
import ai.rever.bossterm.compose.rendering.TerminalCanvasRenderer
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.util.loadTerminalFont
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Uniformly fit the entire grid; neither terminal rows nor columns are cropped. */
internal fun terminalPreviewSize(grid: Size, bounds: Size): Size {
    val scale = minOf(bounds.width / grid.width, bounds.height / grid.height, 1f)
    return Size(grid.width * scale, grid.height * scale)
}

internal fun <T : Any> captureTerminalPreviewFrame(
    display: ComposeTerminalDisplay,
    previous: T?,
    capture: () -> T
): T? = display.captureStableRenderFrame(capture) ?: previous

/** Render in terminal coordinates, including the logical size used by text clipping. */
internal fun DrawScope.drawTerminalPreviewGrid(gridSize: Size, draw: DrawScope.() -> Unit) {
    clipRect {
        val scale = minOf(size.width / gridSize.width, size.height / gridSize.height)
        withTransform({
            scale(scale, scale, Offset.Zero)
            inset(0f, 0f, size.width - gridSize.width, size.height - gridSize.height)
        }, draw)
    }
}

/** A read-only screen snapshot: no PTY, input handlers, cursor timer or resize side effects. */
@Composable
internal fun TerminalTabPreview(session: TerminalSession, settings: TerminalSettings) {
    var frame by remember(session) {
        // No initial buffer read on the UI thread, or while a synchronized redraw is active.
        mutableStateOf<Pair<VersionedBufferSnapshot, Map<Long, TerminalImage>>?>(null)
    }
    // This composition exists only while the tooltip is visible. Throttle to five
    // frames per second and read off the UI thread; closing hover cancels the loop.
    LaunchedEffect(session) {
        while (isActive) {
            frame = withContext(Dispatchers.Default) {
                captureTerminalPreviewFrame(session.display, frame) {
                    val images = session.terminal.getImageDataCache().snapshotImages()
                    session.textBuffer.createIncrementalSnapshot() to images
                }
            }
            delay(200)
        }
    }
    val font = remember(settings.fontName) { loadTerminalFont(settings.fontName) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontSize = (session as? TerminalTab)?.fontSizeOverride?.value ?: settings.fontSize
    val metrics = remember(font, measurer, fontSize, density) {
        val style = TextStyle(fontFamily = font, fontSize = fontSize.sp)
        val sample = measurer.measure("W".repeat(100), style)
        val single = measurer.measure("W", style)
        Triple(sample.size.width / 100f, single.size.height.toFloat(), single.firstBaseline)
    }
    val snapshot = frame?.first
    val baseHeight = metrics.second.coerceAtLeast(1f)
    val cellWidth = metrics.first.coerceAtLeast(1f)
    val lineSpacing = if (settings.disableLineSpacingInAlternateBuffer && snapshot?.isUsingAlternateBuffer == true) {
        1f
    } else settings.lineSpacing
    val cellHeight = baseHeight * lineSpacing.coerceAtLeast(0.5f)
    val gridSize = Size((snapshot?.width ?: 1).coerceAtLeast(1) * cellWidth,
        (snapshot?.height ?: 1).coerceAtLeast(1) * cellHeight)
    val previewSize = with(density) {
        terminalPreviewSize(gridSize, Size(320.dp.toPx(), 240.dp.toPx()))
    }
    Canvas(with(density) { Modifier.width(previewSize.width.toDp()).height(previewSize.height.toDp()) }) {
        drawRect(settings.defaultBackgroundColor)
        val (snapshot, images) = frame ?: return@Canvas
        val context = RenderingContext(
            bufferSnapshot = snapshot, cellWidth = cellWidth, cellHeight = cellHeight,
            baseCellHeight = baseHeight, cellBaseline = metrics.third,
            scrollOffset = 0, visibleCols = snapshot.width, visibleRows = snapshot.height,
            textMeasurer = measurer, measurementFontFamily = font, fontSize = fontSize,
            settings = settings, ambiguousCharsAreDoubleWidth = session.display.ambiguousCharsAreDoubleWidth(),
            selectionStart = null, selectionEnd = null, selectionMode = SelectionMode.NORMAL,
            searchVisible = false, searchQuery = "", searchMatches = emptyList(), currentMatchIndex = -1,
            cursorX = 0, cursorY = 0, cursorVisible = false, cursorBlinkVisible = false,
            cursorShape = null, cursorColor = null, isFocused = false,
            hoveredHyperlink = null, isModifierPressed = false,
            slowBlinkVisible = true, rapidBlinkVisible = true,
            terminalWidthCells = snapshot.width, terminalHeightCells = snapshot.height, imageDataById = images
        )
        drawTerminalPreviewGrid(gridSize) {
            with(TerminalCanvasRenderer) { renderTerminal(context) }
        }
    }
}
