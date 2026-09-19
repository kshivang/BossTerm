package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.compose.rendering.RenderingContext
import ai.rever.bossterm.compose.rendering.TerminalCanvasRenderer
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.util.loadTerminalFont
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** A read-only screen snapshot: no PTY, input handlers, cursor timer or resize side effects. */
@Composable
internal fun TerminalTabPreview(session: TerminalSession, settings: TerminalSettings) {
    val snapshot = remember(session) { session.textBuffer.createIncrementalSnapshot() }
    val rowRange = remember(snapshot) { terminalPreviewRows(snapshot.screenLines.map { it.line.text }) }
    val images = remember(session) { session.terminal.getImageDataCache().snapshotImages() }
    val font = remember(settings.fontName) { loadTerminalFont(settings.fontName) }
    val measurer = rememberTextMeasurer()
    val previewFontSize = 12f
    val metrics = remember(font, measurer) {
        measurer.measure("M", TextStyle(fontFamily = font, fontSize = previewFontSize.sp))
    }
    val rowHeight = metrics.size.height.toFloat().coerceAtLeast(1f) * settings.lineSpacing.coerceAtLeast(0.5f)
    val previewHeight = with(LocalDensity.current) { (rowRange.count() * rowHeight).toDp() }
    Canvas(Modifier.width(320.dp).height(previewHeight)) {
        val cellWidth = metrics.size.width.toFloat().coerceAtLeast(1f)
        val baseHeight = metrics.size.height.toFloat().coerceAtLeast(1f)
        val cellHeight = baseHeight * settings.lineSpacing.coerceAtLeast(0.5f)
        val cols = minOf(snapshot.width.coerceAtLeast(1), (size.width / cellWidth).toInt().coerceAtLeast(1))
        val rows = rowRange.count()
        val context = RenderingContext(
            bufferSnapshot = snapshot, cellWidth = cellWidth, cellHeight = cellHeight,
            baseCellHeight = baseHeight, cellBaseline = metrics.firstBaseline,
            scrollOffset = -rowRange.first, visibleCols = cols, visibleRows = rows,
            textMeasurer = measurer, measurementFontFamily = font, fontSize = previewFontSize,
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
