package ai.rever.bossterm.compose.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    private fun search(query: String, settings: TerminalSettings = TerminalSettings(),
                       categories: List<SettingsCategory> = SettingsCategory.entries) =
        SettingsSearchIndex.search(query, categories, settings)

    @Test fun matchesWordsAcrossLabelAndSectionIgnoringCase() {
        val hit = search("  IMAGE   blur ").first()
        assertEquals(SettingsCategory.VISUAL, hit.category)
        assertEquals("Background Image", hit.group)
        assertEquals("Blur Background Image", hit.label)
    }

    @Test fun requiresEveryWordAndHandlesEmptyQueries() {
        assertTrue(search("blur nonexistent").isEmpty())
        assertTrue(search("  ").isEmpty())
    }

    @Test fun hiddenCategoriesNeverAppear() {
        val visible = SettingsCategory.entries.filter { it != SettingsCategory.MCP }
        assertTrue(search("mcp", categories = visible).isEmpty())
    }

    @Test fun opacityPointsToTheActiveThemesSection() {
        assertEquals("Glass", search("background opacity").first().group)
        val opaqueTheme = TerminalSettings(activeThemeId = "boss-blueprint")
        assertEquals("Transparency", search("background opacity", opaqueTheme).first().group)
        assertTrue(search("glass tint", opaqueTheme).isEmpty())
        assertTrue(search(ai.rever.bossterm.compose.settings.theme.BuiltinThemes.LIQUID_GLASS_DARK.name, opaqueTheme).any { it.group == "Select Theme" })
    }

    @Test fun categoryContextDisambiguatesCommonLabels() {
        val hit = search("scrollbar colors").first()
        assertEquals(SettingsCategory.SCROLLBAR, hit.category)
        assertEquals("Colors", hit.group)
        assertEquals("Font Size", search("font size").first().label)
    }
}
