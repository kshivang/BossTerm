package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalPreviewRowsTest {
    @Test fun trimsEmptyScreenAroundContent() {
        assertEquals(2..3, terminalPreviewRows(listOf("", " ", "prompt", "output", "", "")))
    }
    @Test fun limitsPreviewToLastEightContentRows() {
        assertEquals(12..19, terminalPreviewRows(List(20) { "line $it" } + List(10) { "" }))
    }
    @Test fun emptyScreenHasOneRow() {
        assertEquals(0..0, terminalPreviewRows(listOf("", " ", "\u0000")))
    }
    @Test fun preservesBlankRowsBetweenOutput() {
        assertEquals(0..2, terminalPreviewRows(listOf("first", "", "last", "")))
    }
}
