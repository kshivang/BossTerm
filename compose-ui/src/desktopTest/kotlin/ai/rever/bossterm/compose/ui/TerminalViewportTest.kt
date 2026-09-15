package ai.rever.bossterm.compose.ui

import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.TerminalColor
import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import ai.rever.bossterm.terminal.model.pool.VersionedLine
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue

import ai.rever.bossterm.compose.rendering.RenderingContext
import ai.rever.bossterm.compose.rendering.TerminalCanvasRenderer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.test.assertEquals

class TerminalViewportTest {
    @Test fun contentSizeUsesPhysicalInsetsAtFractionalAndRetinaDensities() {
        for (scale in listOf(1f, 1.25f, 1.5f, 1.75f, 2f)) {
            val density = Density(scale)
            for (scrollbar in listOf(0.dp, 14.dp, 13.5.dp)) {
                val start = with(density) { 4.dp.roundToPx() }
                val end = with(density) { (4.dp + scrollbar).roundToPx() }
                for (width in listOf(0, 5, 80, 301, 640, 1280)) {
                    val content = terminalContentSize(IntSize(width, 480), density, scrollbar)
                    assertEquals((width - start - end).coerceAtLeast(0), content.width)
                    assertEquals(480 - start, content.height)
                    val cell = 8.37f * scale
                    val cols = (content.width / cell).toInt()
                    assertTrue(cols * cell <= content.width)
                    assertTrue(content.width < (cols + 1) * cell)
                    if (width >= start + end) assertTrue(width - start - cols * cell >= end)
                }
            }
        }
    }

    @Test fun gridChangesOnlyWhenAnotherWholeCellFitsAfterTheGutter() {
        for (scale in listOf(1f, 2f)) {
            val density = Density(scale)
            val insets = with(density) { 4.dp.roundToPx() + 18.dp.roundToPx() }
            val cell = 10f * scale
            val full = (80 * cell).toInt() + insets
            assertEquals(79, (terminalContentSize(IntSize(full - 1, 500), density, 14.dp).width / cell).toInt())
            assertEquals(80, (terminalContentSize(IntSize(full, 500), density, 14.dp).width / cell).toInt())
            // Regression: the old outerWidth-4 over-advertised at least one column.
            assertTrue(((full - 4) / cell).toInt() > 80)
        }
    }

    @Test fun realRendererKeepsFullRowAndLastColumnCursorClearOfTheRightGutter() {
        for (scale in listOf(1f, 2f)) for (outerWidth in listOf(300, 640, 1280)) {
            paintFullRow(outerWidth, Density(scale), 0)
            paintFullRow(outerWidth, Density(scale), 14)
        }
    }

    private fun paintFullRow(width: Int, density: Density, scrollbar: Int) {
        val outerHeight = 100
        val content = terminalContentSize(IntSize(width, outerHeight), density, scrollbar.dp)
        val edge = with(density) { 4.dp.roundToPx() }
        val end = width - edge - content.width
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        // Match production metrics: batched text uses actual font advance, not an arbitrary cell width.
        val style = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        val cellWidth = measurer.measure("W".repeat(100), style).size.width / 100f
        val cellHeight = measurer.measure("W", style).size.height.toFloat()
        val cols = (content.width / cellWidth).toInt()
        val settings = TerminalSettings(defaultForeground = "0xFFFFFFFF", defaultBackground = "0xFF000000",
            fillBackgroundInLineSpacing = false)
        val line = TerminalLine.createEmpty()
        line.writeString(0, CharBuffer("W".repeat(cols)), TextStyle(TerminalColor.WHITE, TerminalColor.BLACK))
        val snapshot = VersionedBufferSnapshot(
            screenLines = listOf(VersionedLine(line, line, 1L)), historyLines = emptyList(),
            width = cols, height = 1, historyLinesCount = 0, isUsingAlternateBuffer = false)
        val ctx = RenderingContext(
            bufferSnapshot = snapshot,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            baseCellHeight = cellHeight,
            cellBaseline = cellHeight * 0.8f,
            scrollOffset = 0,
            visibleCols = cols,
            visibleRows = 1,
            textMeasurer = measurer,
            measurementFontFamily = FontFamily.Monospace,
            fontSize = 14f,
            settings = settings,
            ambiguousCharsAreDoubleWidth = false,
            selectionStart = null,
            selectionEnd = null,
            selectionMode = SelectionMode.NORMAL,
            searchVisible = false,
            searchQuery = "",
            searchMatches = emptyList(),
            currentMatchIndex = -1,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            cursorBlinkVisible = false,
            cursorShape = CursorShape.STEADY_BLOCK,
            cursorColor = null,
            isFocused = false,
            hoveredHyperlink = null,
            isModifierPressed = false,
            slowBlinkVisible = true,
            rapidBlinkVisible = true,
            terminalWidthCells = cols,
            terminalHeightCells = 1
        )

        val bitmap = ImageBitmap(width, outerHeight)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), outerHeight.toFloat())) {
            drawRect(Color.Black)
            inset(edge.toFloat(), edge.toFloat(), end.toFloat(), 0f) {
                clipRect {
                    with(TerminalCanvasRenderer) { renderTerminal(ctx) }
                }
            }
        }
        var pixels = bitmap.toPixelMap()
        // Full row, including final glyph: every column has foreground ink.
        for (col in 0 until cols) {
            var ink = 0
            for (y in edge until edge + cellHeight.toInt()) {
                for (x in edge + (col * cellWidth).toInt() until edge + ((col + 1) * cellWidth).toInt()) {
                    if (pixels[x, y].red > 0.2f) ink++
                }
            }
            assertTrue(ink > 0, "Missing glyph column $col/$cols, width=$width density=${density.density}")
        }
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), outerHeight.toFloat())) {
            inset(edge.toFloat(), edge.toFloat(), end.toFloat(), 0f) {
                clipRect {
                    with(TerminalCanvasRenderer) {
                        renderCursorOverlay(cursorVisible = true, cursorBlinkVisible = true,
                            cursorShape = CursorShape.STEADY_BLOCK, cursorX = cols - 1, cursorY = 1,
                            scrollOffset = 0, cellWidth = cellWidth, cellHeight = cellHeight,
                            isFocused = true, cursorColor = Color.Red)
                    }
                }
            }
        }
        pixels = bitmap.toPixelMap()
        assertTrue(pixels[edge + ((cols - 0.5f) * cellWidth).toInt(), edge + (cellHeight / 2).toInt()].red > 0.9f)
        for (y in 0 until outerHeight) for (x in width - end until width) {
            assertEquals(Color.Black, pixels[x, y], "Text/cursor entered right gutter at $x,$y")
        }
    }
}
