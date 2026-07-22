package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import ai.rever.bossterm.terminal.model.image.DimensionSpec
import ai.rever.bossterm.terminal.model.image.TerminalImage
import kotlin.test.Test
import kotlin.test.assertEquals

class BossTerminalImageSizingTest {

    @Test
    fun inlineImageUsesLastNormalGridDuringTransientTinyResize() {
        val terminal = createTerminal()
        terminal.resize(TermSize(100, 20), RequestOrigin.User)
        terminal.resize(TermSize(5, 2), RequestOrigin.User)

        val placement = terminal.processInlineImage(autoImage())!!

        assertEquals(50, placement.cellWidth)
        assertEquals(15, placement.cellHeight)
    }

    @Test
    fun stableTinyGridReplacesNormalFallback() {
        val terminal = createTerminal()
        terminal.resize(TermSize(100, 20), RequestOrigin.User)
        terminal.resize(TermSize(5, 2), RequestOrigin.User)
        terminal.markGridStable(columns = 5, rows = 2)

        val placement = terminal.processInlineImage(autoImage())!!

        assertEquals(5, placement.cellWidth)
        assertEquals(2, placement.cellHeight)
    }

    @Test
    fun transientDimensionFallsBackToOneTrustedGridPair() {
        val terminal = createTerminal()
        terminal.resize(TermSize(81, 24), RequestOrigin.User)
        terminal.resize(TermSize(100, 2), RequestOrigin.User)

        val placement = terminal.processInlineImage(autoImage(width = 900, height = 300))!!

        assertEquals(81, placement.cellWidth)
        assertEquals(14, placement.cellHeight)
    }

    @Test
    fun unicodePlaceholderWritesOneMovableImageCell() {
        val terminal = createTerminal()
        val image = autoImage(width = 40, height = 100).copy(
            widthSpec = DimensionSpec.Cells(4),
            heightSpec = DimensionSpec.Cells(5),
            preserveAspectRatio = false
        )

        terminal.processInlineImagePlaceholder(image, cellX = 2, cellY = 3)

        val cell = terminal.terminalTextBuffer.getLine(0).getImageCellAt(0)!!
        assertEquals(image.id, cell.imageId)
        assertEquals(2, cell.cellX)
        assertEquals(3, cell.cellY)
        assertEquals(4, cell.totalCellsX)
        assertEquals(5, cell.totalCellsY)
        assertEquals(2, terminal.cursorX)

        terminal.cursorPosition(1, 1)
        terminal.writeCharacters("x")
        assertEquals(null, terminal.terminalTextBuffer.getLine(0).getImageCellAt(0))
    }

    private fun createTerminal(): BossTerminal {
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(width = 80, height = 24, styleState = styleState)
        return BossTerminal(NoopTerminalDisplay(), textBuffer, styleState).apply {
            setCellDimensions(cellWidthPx = 10f, cellHeightPx = 20f)
        }
    }

    private fun autoImage(width: Int = 500, height: Int = 300) = TerminalImage(
        data = ByteArray(1),
        intrinsicWidth = width,
        intrinsicHeight = height
    )

    private class NoopTerminalDisplay : TerminalDisplay {
        override var windowTitle: String? = null
        override var iconTitle: String? = null
        override val selection: TerminalSelection? = null

        override fun setCursor(x: Int, y: Int) = Unit
        override fun setCursorShape(cursorShape: CursorShape?) = Unit
        override fun beep() = Unit
        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) = Unit
        override fun setCursorVisible(isCursorVisible: Boolean) = Unit
        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) = Unit
        override fun terminalMouseModeSet(mouseMode: MouseMode) = Unit
        override fun setMouseFormat(mouseFormat: MouseFormat) = Unit
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
    }
}
