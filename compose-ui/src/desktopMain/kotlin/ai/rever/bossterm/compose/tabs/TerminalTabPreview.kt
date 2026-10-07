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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun terminalPreviewRows(lines: List<String>): IntRange {
    val last = lines.indexOfLast { it.any { char -> !char.isWhitespace() && char != '\u0000' } }
    if (last < 0) return 0..0
    val first = lines.indexOfFirst { it.any { char -> !char.isWhitespace() && char != '\u0000' } }
    return maxOf(first, last - 7)..last
}

internal fun <T : Any> captureTerminalPreviewFrame(
    display: ComposeTerminalDisplay,
    previous: T?,
    capture: () -> T
): T? = display.captureStableRenderFrame(capture) ?: previous

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
    val fontSize = 12f
    val metrics = remember(font, measurer, fontSize, density) {
        val style = TextStyle(fontFamily = font, fontSize = fontSize.sp)
        val sample = measurer.measure("W".repeat(100), style)
        val single = measurer.measure("W", style)
        Triple(sample.size.width / 100f, single.size.height.toFloat(), single.firstBaseline)
    }
    val snapshot = frame?.first
    val rowRange = remember(snapshot) {
        terminalPreviewRows(snapshot?.screenLines?.map { it.line.text } ?: emptyList())
    }
    val baseHeight = metrics.second.coerceAtLeast(1f)
    val cellWidth = metrics.first.coerceAtLeast(1f)
    val lineSpacing = if (settings.disableLineSpacingInAlternateBuffer && snapshot?.isUsingAlternateBuffer == true) {
        1f
    } else settings.lineSpacing
    val cellHeight = baseHeight * lineSpacing.coerceAtLeast(0.5f)
    val previewHeight = with(density) { (rowRange.count() * cellHeight).toDp() }
    Canvas(Modifier.width(320.dp).height(previewHeight)) {
        drawRect(settings.defaultBackgroundColor)
        val (snapshot, images) = frame ?: return@Canvas
        val context = RenderingContext(
            bufferSnapshot = snapshot, cellWidth = cellWidth, cellHeight = cellHeight,
            baseCellHeight = baseHeight, cellBaseline = metrics.third,
            scrollOffset = -rowRange.first,
            visibleCols = minOf(snapshot.width, (size.width / cellWidth).toInt().coerceAtLeast(1)),
            visibleRows = rowRange.count(),
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
        clipRect {
            with(TerminalCanvasRenderer) { renderTerminal(context) }
        }
    }
}
