package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.model.image.ImageCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class ChangeWidthOperationImageTest {

    @Test
    fun imageBearingRowPassesThroughNarrowerReflowAtomically() {
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(width = 8, height = 4, styleState = styleState)
        val imageLine = TerminalLine().apply {
            repeat(6) { column ->
                setImageCell(
                    column,
                    ImageCell(
                        imageId = 42,
                        cellX = column,
                        cellY = 1,
                        totalCellsX = 6,
                        totalCellsY = 3
                    )
                )
            }
        }
        textBuffer.screenLinesStorage.addToBottom(imageLine)

        ChangeWidthOperation(textBuffer, myNewWidth = 4, myNewHeight = 4).run()

        val outputLine = textBuffer.screenLinesStorage[0]
        assertNotSame(imageLine, outputLine)
        assertEquals((0 until 6).toSet(), outputLine.getAllImageCells().keys)
        assertEquals(
            (0 until 6).toList(),
            outputLine.getAllImageCells().toSortedMap().values.map { it.cellX }
        )
        assertEquals(setOf(1), outputLine.getAllImageCells().values.map { it.cellY }.toSet())
    }
}
