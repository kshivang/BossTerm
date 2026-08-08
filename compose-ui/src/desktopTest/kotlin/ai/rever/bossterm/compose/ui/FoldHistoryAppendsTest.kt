package ai.rever.bossterm.compose.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The arithmetic behind keeping a bottom-anchored viewport on the same content as history grows.
 *
 * This is the half with the edges - the buffer-side notification is covered by
 * `HistoryAppendAnchorTest` in bossterm-core-mpp. It lives outside the composable precisely so
 * these cases are ordinary assertions rather than something needing a Compose harness.
 */
class FoldHistoryAppendsTest {

    @Test
    fun addingTheAppendCountReAddressesTheSameContent() {
        // Scrolled 10 back, 3 lines appended: the content is now 13 rows from the live bottom.
        assertEquals(13, foldHistoryAppends(current = 10, appended = 3, historyCount = 500))
    }

    @Test
    fun followingTheBottomKeepsFollowingIt() {
        assertEquals(
            0,
            foldHistoryAppends(current = 0, appended = 40, historyCount = 500),
            "offset 0 is 'follow the live bottom', not a pinned position",
        )
    }

    @Test
    fun aCappedHistoryPinsTheViewToItsOldestSurvivingLine() {
        // History is full at 100, so the content the user was reading has been evicted; the
        // viewport stops at the oldest line that still exists instead of running off the end.
        assertEquals(100, foldHistoryAppends(current = 98, appended = 20, historyCount = 100))
    }

    @Test
    fun anOffsetAlreadyPastTheHistoryIsBroughtBackToTheOldestLine() {
        assertEquals(50, foldHistoryAppends(current = 80, appended = 5, historyCount = 50))
    }

    @Test
    fun nothingAppendedLeavesTheOffsetExactlyWhereItWas() {
        assertEquals(42, foldHistoryAppends(current = 42, appended = 0, historyCount = 500))
        assertEquals(
            42,
            foldHistoryAppends(current = 42, appended = -3, historyCount = 500),
            "a negative count must never walk the viewport backwards",
        )
    }

    @Test
    fun aSingleLineAppendMovesTheViewportOneRow() {
        // The steady case: one line of streaming output, one row of compensation.
        var offset = 7
        repeat(5) { offset = foldHistoryAppends(offset, appended = 1, historyCount = 500) }
        assertEquals(12, offset, "five appended lines move the viewport five rows")
    }
}
