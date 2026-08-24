package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertEquals

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
