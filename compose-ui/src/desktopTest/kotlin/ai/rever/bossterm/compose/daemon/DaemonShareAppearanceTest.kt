package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.ColorPalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonShareAppearanceTest {
    @Test
    fun `built in and newly persisted custom appearance changes resolve without restarting managers`() {
        val directory = java.nio.file.Files.createTempDirectory("daemon-appearance").toFile()
        val themes = directory.resolve("themes.json")
        val palettes = directory.resolve("palettes.json")
        val appearance = DaemonShareAppearance(themes, palettes)
        try {
            val first = BuiltinThemes.ALL.first()
            val second = BuiltinThemes.ALL.last()
            assertEquals(first.id, appearance.resolve(TerminalSettings.DEFAULT.copy(activeThemeId = first.id)).first.id)
            assertEquals(second.id, appearance.resolve(TerminalSettings.DEFAULT.copy(activeThemeId = second.id)).first.id)
            val custom = second.copy(id = "new-custom-theme", name = "New custom", cursor = "0xFF123456")
            val palette = ColorPalette.fromTheme(first).copy(id = "new-custom-palette", name = "New palette")
            // Resolve once before either custom file exists, reproducing a daemon started earlier.
            val settings = TerminalSettings.DEFAULT.copy(activeThemeId = custom.id, colorPaletteId = palette.id)
            appearance.resolve(settings)
            themes.writeText(buildJsonObject {
                put("themes", buildJsonArray { add(Json.encodeToJsonElement(custom)) })
            }.toString())
            palettes.writeText(buildJsonObject {
                put("palettes", buildJsonArray { add(Json.encodeToJsonElement(palette)) })
            }.toString())
            val resolved = appearance.resolve(settings)
            assertEquals(custom, resolved.first)
            assertEquals(palette, resolved.second)
        } finally {
            directory.deleteRecursively()
        }
    }
}
