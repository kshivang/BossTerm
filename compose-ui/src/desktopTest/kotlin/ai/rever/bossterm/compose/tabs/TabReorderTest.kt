package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertEquals

class TabReorderTest {

    @Test
    fun `active tab follows itself when moved`() {
        assertEquals(3, indexAfterTabMove(index = 1, fromIndex = 1, toIndex = 3))
        assertEquals(0, indexAfterTabMove(index = 3, fromIndex = 3, toIndex = 0))
    }

    @Test
    fun `active index shifts around a moved neighboring tab`() {
        assertEquals(1, indexAfterTabMove(index = 2, fromIndex = 0, toIndex = 3))
        assertEquals(2, indexAfterTabMove(index = 1, fromIndex = 3, toIndex = 0))
        assertEquals(2, indexAfterTabMove(index = 2, fromIndex = 0, toIndex = 1))
    }

    @Test
    fun `drop target is nearest tab center`() {
        val centers = listOf(0 to 20f, 1 to 60f, 2 to 100f)

        assertEquals(null, nearestTabIndex(pointerY = 20f, tabCenters = emptyList()))
        assertEquals(0, nearestTabIndex(pointerY = -10f, tabCenters = centers))
        assertEquals(1, nearestTabIndex(pointerY = 72f, tabCenters = centers))
        assertEquals(2, nearestTabIndex(pointerY = 130f, tabCenters = centers))
    }

    @Test
    fun `local reorder leaves interspersed remote slots untouched`() {
        assertEquals(
            listOf(2, 1, 4, 3, 0),
            tabOrderAfterMoveWithin(
                tabCount = 5,
                fromIndex = 0,
                toIndex = 4,
                movableIndices = listOf(0, 2, 4)
            )
        )
        assertEquals(
            listOf(4, 1, 0, 3, 2),
            tabOrderAfterMoveWithin(
                tabCount = 5,
                fromIndex = 4,
                toIndex = 0,
                movableIndices = listOf(0, 2, 4)
            )
        )
    }

    @Test
    fun `subset reorder rejects non-local source or target`() {
        assertEquals(
            null,
            tabOrderAfterMoveWithin(
                tabCount = 5,
                fromIndex = 0,
                toIndex = 3,
                movableIndices = listOf(0, 2, 4)
            )
        )
    }
}
