package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarWidthTest {
    @Test fun shrinkingWindowPreservesTerminalSpace() {
        assertEquals(480f, constrainedSidebarWidth(980f, 800f))
        assertEquals(980f, constrainedSidebarWidth(980f, 1300f))
    }
    @Test fun collapseGestureCanGoBelowExpandedMinimum() {
        assertEquals(200f, constrainedSidebarWidth(120f, 800f))
        assertEquals(120f, constrainedSidebarWidth(120f, 800f, dragging = true))
        assertEquals(44f, constrainedSidebarWidth(10f, 800f, dragging = true))
    }
    @Test fun veryNarrowWindowNeverProducesInvalidBounds() {
        assertEquals(100f, constrainedSidebarWidth(200f, 100f))
        assertEquals(0f, constrainedSidebarWidth(200f, 0f))
    }
}
