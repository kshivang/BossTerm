package ai.rever.bossterm.compose.rendering

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageCellGridTest {

    @Test
    fun keepsBakedGridWhenItFitsFromAnchor() {
        assertEquals(
            ImageCellGrid(columns = 6, rows = 4),
            refitImageCellGrid(
                totalColumns = 6,
                totalRows = 4,
                anchorColumn = 2,
                visibleColumns = 10
            )
        )
    }

    @Test
    fun refitsGridToReachableColumnsAndPreservesAspect() {
        assertEquals(
            ImageCellGrid(columns = 5, rows = 3),
            refitImageCellGrid(
                totalColumns = 10,
                totalRows = 6,
                anchorColumn = 3,
                visibleColumns = 8
            )
        )
    }

    @Test
    fun accountsForColumnsClippedLeftOfViewport() {
        assertEquals(
            ImageCellGrid(columns = 10, rows = 6),
            refitImageCellGrid(
                totalColumns = 10,
                totalRows = 6,
                anchorColumn = -2,
                visibleColumns = 8
            )
        )
    }
}
