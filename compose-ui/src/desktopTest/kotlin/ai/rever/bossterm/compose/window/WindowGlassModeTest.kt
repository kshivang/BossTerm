package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.withThemeColors
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowGlassModeTest {
    @Test
    fun `coverage separates sidebar from the shared terminal and top bar`() {
        assertEquals(true, WindowGlassMode.BARS.includesSidebar)
        assertEquals(false, WindowGlassMode.BARS.includesTerminal)
        assertEquals(false, WindowGlassMode.TERMINAL.includesSidebar)
        assertEquals(true, WindowGlassMode.TERMINAL.includesTerminal)
        assertEquals(true, WindowGlassMode.WINDOW.includesSidebar)
        assertEquals(true, WindowGlassMode.WINDOW.includesTerminal)
        assertEquals(0.4f, WindowGlassMode.TERMINAL.terminalOpacity(0.4f))
    }

    @Test
    fun `only dedicated glass themes enable the material and keep opacity independent`() {
        val initial = TerminalSettings(backgroundOpacity = 0.9f, windowGlassMode = "window", windowGlassOpacity = 0f)
        for (theme in listOf(BuiltinThemes.LIQUID_GLASS_LIGHT, BuiltinThemes.LIQUID_GLASS_DARK)) {
            val glass = initial.withThemeColors(theme)
            assertEquals(WindowGlassMode.WINDOW, glass.effectiveWindowGlassMode)
            assertEquals(0f, glass.effectiveBackgroundOpacity)
            assertEquals(0.9f, glass.backgroundOpacity)
            for (ordinary in BuiltinThemes.ALL.filter { it.id != theme.id && !it.id.startsWith("liquid-glass-") }) {
                val restored = glass.withThemeColors(ordinary)
                assertEquals(WindowGlassMode.OFF, restored.effectiveWindowGlassMode)
                assertEquals(0.9f, restored.effectiveBackgroundOpacity)
                assertEquals(0f, restored.windowGlassOpacity)
            }
        }
    }

    @Test
    fun `selecting liquid glass with disabled coverage enables bars`() {
        val settings = TerminalSettings(windowGlassMode = "off").withThemeColors(BuiltinThemes.LIQUID_GLASS_DARK)
        assertEquals(WindowGlassMode.BARS, settings.effectiveWindowGlassMode)
        assertEquals(1f, settings.effectiveBackgroundOpacity)
        val restored = Json.decodeFromString<TerminalSettings>(Json.encodeToString(settings))
        assertEquals(settings.effectiveWindowGlassMode, restored.effectiveWindowGlassMode)
        assertEquals(settings.windowGlassOpacity, restored.windowGlassOpacity)
    }

    @Test
    fun `bars mode protects terminal legibility without losing the user's opacity setting`() {
        val settings = TerminalSettings(windowGlassMode = "bars", backgroundOpacity = 0.35f)
        val mode = WindowGlassMode.fromSetting(settings.windowGlassMode)
        assertEquals(1f, mode.terminalOpacity(settings.backgroundOpacity))
        assertEquals(0.35f, WindowGlassMode.WINDOW.terminalOpacity(settings.backgroundOpacity))
        assertEquals(0.35f, WindowGlassMode.OFF.terminalOpacity(settings.backgroundOpacity))
        assertEquals(0f, WindowGlassMode.WINDOW.terminalOpacity(0f))
        assertEquals(0f, WindowGlassMode.OFF.terminalOpacity(0f))
    }

    @Test
    fun `coverage selection survives settings serialization`() {
        for (mode in WindowGlassMode.entries) {
            val settings = TerminalSettings(windowGlassMode = mode.setting, windowGlassTint = 0.4f, windowGlassStyle = "clear")
            val restored = Json.decodeFromString<TerminalSettings>(Json.encodeToString(settings))
            assertEquals(mode, WindowGlassMode.fromSetting(restored.windowGlassMode))
            assertEquals(0.4f, restored.windowGlassTint)
            assertEquals("clear", restored.windowGlassStyle)
        }
    }

    @Test
    fun `missing coverage uses product default and unknown coverage stays off`() {
        assertEquals(WindowGlassMode.WINDOW, WindowGlassMode.fromSetting(Json.decodeFromString<TerminalSettings>("{}").windowGlassMode))
        assertEquals(WindowGlassMode.OFF, WindowGlassMode.fromSetting("unknown"))
    }
    @Test
    fun `unavailable glass stays opaque without overwriting saved opacity`() {
        for (mode in WindowGlassMode.entries) {
            // Pin a glass theme: the default is BOSS Blueprint (opaque) off macOS.
            val settings = TerminalSettings(activeThemeId = "liquid-glass-dark", windowGlassMode = mode.setting, windowGlassOpacity = 0f)
            assertEquals(1f, settings.surfaceOpacity(false))
            assertEquals(if (mode == WindowGlassMode.BARS) 1f else 0f, settings.surfaceOpacity(true))
            assertEquals(0f, settings.windowGlassOpacity)
        }
        val ordinary = TerminalSettings(activeThemeId = "boss-blueprint", backgroundOpacity = 0.6f)
        assertEquals(0.6f, ordinary.surfaceOpacity(false))
    }

}
