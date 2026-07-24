package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabReorderTest {

    @Test
    fun `drop target is nearest tab center`() {
        val centers = listOf(0 to 20f, 1 to 60f, 2 to 100f)

        assertEquals(null, nearestTabIndex(pointerY = 20f, tabCenters = emptyList()))
        assertEquals(0, nearestTabIndex(pointerY = -10f, tabCenters = centers))
        assertEquals(0, nearestTabIndex(pointerY = 40f, tabCenters = centers))
        assertEquals(1, nearestTabIndex(pointerY = 40.01f, tabCenters = centers))
        assertEquals(1, nearestTabIndex(pointerY = 72f, tabCenters = centers))
        assertEquals(2, nearestTabIndex(pointerY = 130f, tabCenters = centers))
    }

    @Test
    fun `context menu reorder follows sparse local visual order`() {
        val localOrder = listOf(0, 2, 4)

        assertEquals(null, tabReorderNeighbor(0, localOrder, -1))
        assertEquals(2, tabReorderNeighbor(0, localOrder, 1))
        assertEquals(0, tabReorderNeighbor(2, localOrder, -1))
        assertEquals(4, tabReorderNeighbor(2, localOrder, 1))
        assertEquals(null, tabReorderNeighbor(4, localOrder, 1))
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

    @Test
    fun `controller moves local tab while active remote keeps its slot`() {
        val controller = controllerWithInterspersedTabs()
        try {
            val activeRemoteId = controller.tabs[1].id
            controller.switchToTab(1)

            assertTrue(controller.moveTabWithinIndices(0, 4, LOCAL_TAB_INDICES))
            assertEquals(
                listOf("local-b", "remote-a", "local-c", "remote-b", "local-a"),
                controller.tabs.map { it.title.value }
            )
            assertEquals(1, controller.activeTabIndex)
            assertEquals(activeRemoteId, controller.activeTabId)
        } finally {
            controller.disposeAll()
        }
    }

    @Test
    fun `controller active local tab follows its identity after reorder`() {
        val controller = controllerWithInterspersedTabs()
        try {
            val activeLocalId = controller.tabs.first().id

            assertTrue(controller.moveTabWithinIndices(0, 4, LOCAL_TAB_INDICES))
            assertEquals(4, controller.activeTabIndex)
            assertEquals(activeLocalId, controller.activeTabId)
        } finally {
            controller.disposeAll()
        }
    }

    private fun controllerWithInterspersedTabs(): TabController {
        val controller = TabController(
            settings = TerminalSettings(),
            onLastTabClosed = {}
        )
        listOf(
            "local-a" to false,
            "remote-a" to true,
            "local-b" to false,
            "remote-b" to true,
            "local-c" to false
        ).forEach { (title, isRemote) ->
            val tab = controller.createRemoteSession(title = title, feedsStream = false)
            tab.isRemote = isRemote
            controller.tabs.add(tab)
        }
        return controller
    }

    private companion object {
        val LOCAL_TAB_INDICES = listOf(0, 2, 4)
    }
}
