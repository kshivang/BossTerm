package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.theme.BuiltinColorPalettes
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.ColorPalette
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.voice.StampCachedValue
import ai.rever.bossterm.compose.voice.VoiceAgentStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

/** Resolves GUI-persisted appearance without the daemon's frozen GUI manager selections. */
internal class DaemonShareAppearance(
    // These are the actual ThemeManager/ColorPaletteManager locations, not the settings override.
    themesFile: File = File(System.getProperty("user.home"), ".bossterm/themes.json"),
    palettesFile: File = File(System.getProperty("user.home"), ".bossterm/palettes.json"),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val customThemes = StampCachedValue(
        stamp = { VoiceAgentStorage.fileStamp(themesFile) },
        read = { readEntries<Theme>(themesFile, "themes") },
    )
    private val customPalettes = StampCachedValue(
        stamp = { VoiceAgentStorage.fileStamp(palettesFile) },
        read = { readEntries<ColorPalette>(palettesFile, "palettes") },
    )

    fun resolve(settings: TerminalSettings): Pair<Theme, ColorPalette> {
        val theme = BuiltinThemes.getById(settings.activeThemeId)
            ?: customThemes.get()?.firstOrNull { it.id == settings.activeThemeId }
            ?: BuiltinThemes.PRODUCT_DEFAULT
        val palette = BuiltinColorPalettes.getById(settings.colorPaletteId)
            ?: customPalettes.get()?.firstOrNull { it.id == settings.colorPaletteId }
            ?: ColorPalette.fromTheme(theme)
        return theme to palette
    }

    private inline fun <reified T> readEntries(file: File, key: String): List<T>? = runCatching {
        val root = json.parseToJsonElement(file.readText()) as JsonObject
        (root[key] as JsonArray).map { json.decodeFromJsonElement<T>(it) }
    }.getOrNull()
}
