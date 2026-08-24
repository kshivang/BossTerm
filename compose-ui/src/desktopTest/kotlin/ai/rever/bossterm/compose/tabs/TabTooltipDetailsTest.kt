package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabTooltipDetailsTest {

    private fun pane(
        title: String = "BossTerm",
        subtitle: String? = null,
        branch: String? = null,
        fullPath: String? = null,
        hostLabel: String? = null,
        statusLabel: String? = null
    ) = TabBarPane(
        paneId = "p1",
        title = title,
        subtitle = subtitle,
        branch = branch,
        fullPath = fullPath,
        hostLabel = hostLabel,
        statusLabel = statusLabel
    )

    @Test
    fun `the untruncated path wins over the chip's elided subtitle`() {
        val details = tabTooltipDetails(
            pane(subtitle = "~/…/Boss/BossTerm", fullPath = "~/Development/Boss/BossTerm")
        )
        assertEquals(listOf("~/Development/Boss/BossTerm"), details)
    }

    @Test
    fun `a remote chip with no local cwd still falls back to the subtitle`() {
        // Mirrored chips carry the host's abbreviated path and nothing else — dropping the
        // fallback would leave the tooltip with only a title the chip already showed.
        val details = tabTooltipDetails(pane(subtitle = "~/src", hostLabel = "mac-mini"))
        assertEquals(listOf("~/src", "via mac-mini"), details)
    }

    @Test
    fun `every line renders in order`() {
        val details = tabTooltipDetails(
            pane(
                fullPath = "~/Development/Boss",
                branch = "master",
                hostLabel = "mac-mini",
                statusLabel = "starting…"
            )
        )
        assertEquals(listOf("~/Development/Boss", "⎇ master", "via mac-mini", "starting…"), details)
    }

    @Test
    fun `a path identical to the title is dropped rather than said twice`() {
        assertEquals(emptyList(), tabTooltipDetails(pane(title = "~", fullPath = "~")))
    }

    @Test
    fun `blank fields never become empty lines`() {
        val details = tabTooltipDetails(
            pane(fullPath = "  ", subtitle = "", branch = "", hostLabel = " ", statusLabel = "")
        )
        assertEquals(emptyList(), details)
    }

    @Test
    fun `a bare chip contributes no detail lines`() {
        assertEquals(emptyList(), tabTooltipDetails(pane()))
    }
}

class TabTooltipBoundsTest {

    private fun pane(
        title: String = "BossTerm",
        subtitle: String? = null,
        fullPath: String? = null,
        statusLabel: String? = null
    ) = TabBarPane(
        paneId = "p1",
        title = title,
        subtitle = subtitle,
        fullPath = fullPath,
        statusLabel = statusLabel
    )

    @Test
    fun `at home the tooltip falls through to the expanded subtitle`() {
        // abbreviateCwd deliberately expands $HOME ("the ~ title carries the tilde"), so the
        // folded fullPath only restates the title. Emitting nothing would leave the tooltip
        // hiding the very path the chip shows.
        val details = tabTooltipDetails(pane(title = "~", fullPath = "~", subtitle = "/Users/alice"))
        assertEquals(listOf("/Users/alice"), details)
    }

    @Test
    fun `a terminal-controlled title cannot grow the card without bound`() {
        // OSC 1/2 sets this, so it is as long as a program cares to make it.
        val huge = "A".repeat(20_000)
        val clipped = tabTooltipTitle(huge)
        assertTrue(clipped.length < 250, "title still $clipped.length chars")
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun `a detail line is clipped too - path and error message alike`() {
        val deepPath = "/" + (1..500).joinToString("/") { "dir$it" }
        val message = "error: " + "x".repeat(5_000)
        val details = tabTooltipDetails(pane(fullPath = deepPath, statusLabel = message))
        assertEquals(2, details.size)
        details.forEach { line ->
            assertTrue(line.length < 250, "detail line still ${line.length} chars")
            assertTrue(line.endsWith("…"))
        }
    }

    @Test
    fun `a title at the clip boundary keeps its exact text`() {
        val exact = "B".repeat(200)
        assertEquals(exact, tabTooltipTitle(exact))
    }
}
