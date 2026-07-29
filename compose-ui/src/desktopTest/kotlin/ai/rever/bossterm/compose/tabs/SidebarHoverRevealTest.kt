package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarHoverRevealTest {

    @Test
    fun `reveal follows the pointer while the rail is shown`() {
        // Rail hovered, drawer not yet under the pointer — the reveal starts here.
        assertTrue(hoverRevealTarget(enabled = true, railShown = true, pointerOnRail = true, pointerOnDrawer = false))
        // Handoff: the drawer slides over the rail, so both zones report a hover.
        assertTrue(hoverRevealTarget(enabled = true, railShown = true, pointerOnRail = true, pointerOnDrawer = true))
        // Pointer moved onto the drawer proper (reaching for a tab chip).
        assertTrue(hoverRevealTarget(enabled = true, railShown = true, pointerOnRail = false, pointerOnDrawer = true))
        // Pointer left both zones.
        assertFalse(hoverRevealTarget(enabled = true, railShown = true, pointerOnRail = false, pointerOnDrawer = false))
    }

    @Test
    fun `setting off keeps the chevron as the only way in`() {
        assertFalse(hoverRevealTarget(enabled = false, railShown = true, pointerOnRail = true, pointerOnDrawer = false))
        assertFalse(hoverRevealTarget(enabled = false, railShown = true, pointerOnRail = true, pointerOnDrawer = true))
    }

    @Test
    fun `an expanded in-flow bar never reveals a drawer`() {
        assertFalse(hoverRevealTarget(enabled = true, railShown = false, pointerOnRail = true, pointerOnDrawer = false))
        assertFalse(hoverRevealTarget(enabled = true, railShown = false, pointerOnRail = true, pointerOnDrawer = true))
    }

    @Test
    fun `an interaction in flight holds the drawer open with the pointer gone`() {
        // Context menu, inline rename, or a tab drag: the drawer owns that state, so
        // retracting would silently discard it.
        assertTrue(
            hoverRevealTarget(
                enabled = true, railShown = true,
                pointerOnRail = false, pointerOnDrawer = false, drawerBusy = true
            )
        )
        // Not a way around the other two gates, though.
        assertFalse(
            hoverRevealTarget(
                enabled = false, railShown = true,
                pointerOnRail = false, pointerOnDrawer = false, drawerBusy = true
            )
        )
        assertFalse(
            hoverRevealTarget(
                enabled = true, railShown = false,
                pointerOnRail = false, pointerOnDrawer = false, drawerBusy = true
            )
        )
    }

    @Test
    fun `retract is slower than reveal so the rail to drawer handoff survives`() {
        assertTrue(SidebarRevealCloseDelayMs > SidebarRevealOpenDelayMs)
    }
}
