package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabTooltipPreviewTest {

    @Test
    fun `the tail of the screen is what a hover shows`() {
        val screen = (1..20).joinToString("\n") { "line $it" }
        assertEquals(
            listOf("line 15", "line 16", "line 17", "line 18", "line 19", "line 20"),
            tabTooltipPreview(screen)
        )
    }

    @Test
    fun `an idle shell previews its last output, not the blank rows under the prompt`() {
        // getScreenLines() pads every row to the terminal width and emits the whole screen,
        // so an idle pane is mostly padding — the naive tail would be six empty lines.
        val screen = "$ ./gradlew build      \nBUILD SUCCESSFUL       \n$                      " +
            "\n                       ".repeat(30)
        assertEquals(listOf("$ ./gradlew build", "BUILD SUCCESSFUL", "$"), tabTooltipPreview(screen))
    }

    @Test
    fun `a long row is clipped with an ellipsis rather than laid out in full`() {
        val row = "x".repeat(400)
        val previewed = tabTooltipPreview(row, maxChars = 20).single()
        assertEquals("x".repeat(20) + "…", previewed)
        assertTrue(previewed.length < row.length)
    }

    @Test
    fun `a row exactly at the clip is left alone`() {
        assertEquals(listOf("x".repeat(20)), tabTooltipPreview("x".repeat(20), maxChars = 20))
    }

    @Test
    fun `a pane with nothing on screen contributes no preview`() {
        assertEquals(emptyList(), tabTooltipPreview(null))
        assertEquals(emptyList(), tabTooltipPreview(""))
        assertEquals(emptyList(), tabTooltipPreview("   \n   \n\n  "))
    }

    @Test
    fun `blank rows between output are dropped so the tail stays dense`() {
        val screen = "first\n\n\nsecond\n\nthird\n"
        assertEquals(listOf("first", "second", "third"), tabTooltipPreview(screen, maxLines = 3))
    }
}
