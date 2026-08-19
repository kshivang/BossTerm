package ai.rever.bossterm.compose.rendering

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end proof, in painted pixels, that a truecolor glyph a CLI hardcoded for a
 * dark terminal is visible on a light theme.
 *
 * The unit tests in `util/ColorUtilsContrastTest` pin the color math; this one runs the
 * real [TerminalCanvasRenderer.renderTerminal] over a real buffer and reads the bitmap
 * back, because a correct color that never reaches the canvas fixes nothing.
 *
 * Reproduces what Claude Code emits: `ESC[38;2;255;255;255m` on a light host theme,
 * whose paper floor is `BossBlueprintLightColorScheme.ink`.
 */
class LightBackgroundLegibilityTest {

    private val paper = Color(0xFFF5F7FB)
    private val cellWidth = 10f
    private val cellHeight = 20f

    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr
    )

    private fun lightSettings(minContrast: Float) = TerminalSettings(
        defaultForeground = "0xFF05070B",
        defaultBackground = "0xFFF5F7FB",
        lightBackgroundMinContrast = minContrast,
        // Keep the glyph on the batched fast path and out of the blink/dim branches.
        fillBackgroundInLineSpacing = false
    )

    /** The luminance extremes found inside a painted cell. */
    private data class CellPixels(val darkest: Float, val brightest: Float)

    /**
     * Paints a single cell of [text] styled with [fg]/[bg] onto a paper-filled canvas
     * and returns the luminance extremes inside that cell.
     *
     * Extremes rather than an average: antialiasing means most of a cell is background
     * even when the glyph is crisp, so a mean would wash the signal out entirely. Which
     * extreme carries the evidence depends on the case — a dark glyph on paper shows up
     * in [CellPixels.darkest], a white glyph on a black cell in [CellPixels.brightest].
     */
    private fun paintCell(
        text: String,
        fg: TerminalColor?,
        bg: TerminalColor? = null,
        minContrast: Float,
        canvasBackground: Color = paper
    ): CellPixels {
        val settings = lightSettings(minContrast)
        val line = TerminalLine.createEmpty()
        line.writeString(0, CharBuffer(text), TextStyle(fg, bg))

        val snapshot = VersionedBufferSnapshot(
            screenLines = listOf(VersionedLine(line, line, 1L)),
            historyLines = emptyList(),
            width = 1,
            height = 1,
            historyLinesCount = 0,
            isUsingAlternateBuffer = false
        )

        val ctx = RenderingContext(
            bufferSnapshot = snapshot,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            baseCellHeight = cellHeight,
            cellBaseline = cellHeight * 0.8f,
            scrollOffset = 0,
            visibleCols = 1,
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
            terminalWidthCells = 1,
            terminalHeightCells = 1
        )

        val width = cellWidth.toInt()
        val height = cellHeight.toInt()
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat())
        ) {
            // The real app paints the floor with a background modifier, and Pass 1 skips
            // any cell whose background equals the default — so the canvas has to start
            // out as paper or the glyph would be measured against transparent black.
            drawRect(color = canvasBackground, topLeft = androidx.compose.ui.geometry.Offset.Zero, size = size)
            with(TerminalCanvasRenderer) { renderTerminal(ctx) }
        }

        val pixels = bitmap.toPixelMap()
        var darkest = Float.MAX_VALUE
        var brightest = -1f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val l = pixels[x, y].luminance()
                darkest = minOf(darkest, l)
                brightest = maxOf(brightest, l)
            }
        }
        return CellPixels(darkest, brightest)
    }

    @Test
    fun `hardcoded white text is painted dark on a light background`() {
        val white = TerminalColor.rgb(255, 255, 255)

        // With the guard off — the behaviour before this fix — the white glyph leaves the
        // paper floor essentially untouched, which is the bug: nothing readable is drawn.
        val unguarded = paintCell("W", white, minContrast = 1f)
        assertTrue(
            unguarded.darkest > 0.8f,
            "precondition: unguarded white on paper should leave no dark pixel, " +
                "darkest was ${unguarded.darkest}"
        )

        // With the guard on, real dark pixels land in the cell. This is the assertion that
        // fails without the fix, and it cannot pass vacuously — an unpainted cell would
        // stay at the paper floor.
        val guarded = paintCell("W", white, minContrast = 4.5f)
        assertTrue(
            guarded.darkest < 0.25f,
            "guarded white on paper painted no dark pixel, darkest was ${guarded.darkest}"
        )
    }

    @Test
    fun `text keeps its own dark cell background untouched`() {
        // Claude Code's ESC[48;2;0;0;0m blocks: white on its own black background is
        // already legible, and the guard must leave the pair alone.
        val cell = paintCell(
            "W",
            fg = TerminalColor.rgb(255, 255, 255),
            bg = TerminalColor.rgb(0, 0, 0),
            minContrast = 4.5f
        )

        // The cell's own background survived Pass 1 rather than being lightened...
        assertTrue(
            cell.darkest < 0.05f,
            "the cell's own black background did not survive, darkest was ${cell.darkest}"
        )
        // ...and the glyph is still painted WHITE on it. This is the half that actually
        // pins the scope decision: had the guard followed the theme instead of the cell's
        // real background, the glyph would have been darkened into the black and there
        // would be no bright pixel here at all.
        assertTrue(
            cell.brightest > 0.5f,
            "expected a white glyph on the black cell, brightest was ${cell.brightest}"
        )
    }

    @Test
    fun `default foreground on a light theme is unchanged`() {
        // The theme's own ink already clears the floor, so the guard is a no-op and the
        // glyph paints at full strength.
        val cell = paintCell("W", fg = null, minContrast = 4.5f)
        assertTrue(
            cell.darkest < 0.05f,
            "theme ink should paint dark on paper, darkest was ${cell.darkest}"
        )
    }
}
