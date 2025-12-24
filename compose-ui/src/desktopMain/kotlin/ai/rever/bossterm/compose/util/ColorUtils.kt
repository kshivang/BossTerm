package ai.rever.bossterm.compose.util

import androidx.compose.ui.graphics.Color
import ai.rever.bossterm.terminal.TerminalColor
import ai.rever.bossterm.terminal.emulator.ColorPalette
import ai.rever.bossterm.terminal.emulator.ColorPaletteImpl
import ai.rever.bossterm.compose.settings.theme.ColorPaletteManager
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import ai.rever.bossterm.compose.settings.theme.ColorPalette as SettingsColorPalette

/**
 * Utility functions for color conversion in terminal rendering.
 */
object ColorUtils {
    // Default XTerm color palette (fallback)
    private val defaultPalette = ColorPaletteImpl.XTERM_PALETTE

    /**
     * Cached Compose Colors for indexed colors 0-255 (issue #144).
     * Invalidated when palette/theme changes.
     */
    @Volatile
    private var indexedColorCache: Array<Color>? = null
    private var cachedPaletteHash: Int = 0

    /**
     * LRU cache for 24-bit RGB truecolor (issue #144).
     * Key: packed RGB int. Value: Compose Color.
     * 512 entries covers typical truecolor usage patterns.
     */
    private val truecolorCache = object : LinkedHashMap<Int, Color>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Color>?): Boolean {
            return size > 512
        }
    }
    private val truecolorCacheLock = Any()

    /**
     * Get a cached Compose Color for indexed colors 0-255.
     * Rebuilds cache when palette changes.
     */
    private fun getOrCreateIndexedColor(colorIndex: Int): Color {
        val currentHash = try {
            getCurrentPalette().hashCode()
        } catch (e: Exception) {
            0
        }
        val cache = indexedColorCache

        if (cache == null || cachedPaletteHash != currentHash) {
            synchronized(this) {
                if (indexedColorCache == null || cachedPaletteHash != currentHash) {
                    val palette = try {
                        getCurrentPalette()
                    } catch (e: Exception) {
                        defaultPalette
                    }
                    indexedColorCache = Array(256) { idx ->
                        val tc = TerminalColor.index(idx)
                        val bossColor = if (idx < 16) {
                            palette.getForeground(tc)
                        } else {
                            // Colors 16-255 use pre-computed 256-color palette
                            ColorPalette.getIndexedTerminalColor(idx)?.let {
                                if (it.isIndexed) palette.getForeground(it) else it.toColor()
                            } ?: tc.toColor()
                        }
                        Color(
                            red = bossColor.red / 255f,
                            green = bossColor.green / 255f,
                            blue = bossColor.blue / 255f
                        )
                    }
                    cachedPaletteHash = currentHash
                }
            }
        }
        return indexedColorCache!![colorIndex]
    }

    /**
     * Get a cached Compose Color for 24-bit RGB truecolor.
     * Uses LRU cache to avoid repeated Color object creation.
     */
    private fun getOrCreateTruecolor(r: Int, g: Int, b: Int): Color {
        val key = (r shl 16) or (g shl 8) or b
        synchronized(truecolorCacheLock) {
            return truecolorCache.getOrPut(key) {
                Color(r / 255f, g / 255f, b / 255f)
            }
        }
    }

    /**
     * Invalidate the indexed color cache. Call when palette/theme changes.
     */
    fun invalidateColorCache() {
        indexedColorCache = null
    }

    /**
     * Get the current color palette based on the selected color palette (or theme's palette if none selected).
     * Falls back to XTERM_PALETTE if managers are not initialized.
     */
    private fun getCurrentPalette(): ColorPalette {
        return try {
            // First try to get the selected color palette from ColorPaletteManager
            val paletteManager = ColorPaletteManager.instance
            val selectedPalette = paletteManager.currentPalette.value

            if (selectedPalette != null) {
                // Use the explicitly selected color palette
                createPaletteFromSettingsPalette(selectedPalette)
            } else {
                // Fall back to the current theme's palette
                val theme = ThemeManager.instance.currentTheme.value
                createPaletteFromTheme(theme)
            }
        } catch (e: Exception) {
            defaultPalette
        }
    }

    /**
     * Create a ColorPalette from a settings ColorPalette's ANSI colors.
     */
    private fun createPaletteFromSettingsPalette(palette: SettingsColorPalette): ColorPalette {
        val colors = IntArray(16) { index ->
            parseHexColor(palette.getAnsiColorHex(index))
        }
        return ColorPaletteImpl.fromRgbInts(colors)
    }

    /**
     * Create a ColorPalette from a Theme's ANSI colors.
     */
    private fun createPaletteFromTheme(theme: Theme): ColorPalette {
        val colors = IntArray(16) { index ->
            parseHexColor(theme.getAnsiColorHex(index))
        }
        return ColorPaletteImpl.fromRgbInts(colors)
    }

    /**
     * Parse a hex color string (0xAARRGGBB or 0xRRGGBB) to RGB int.
     */
    private fun parseHexColor(hex: String): Int {
        val cleanHex = hex.removePrefix("0x").removePrefix("#")
        return when (cleanHex.length) {
            6 -> cleanHex.toLong(16).toInt()
            8 -> cleanHex.substring(2).toLong(16).toInt() // Skip alpha
            else -> 0xFFFFFF
        }
    }

    /**
     * Convert BossTerm TerminalColor to Compose Color using the active theme's palette.
     * Uses cached colors for indexed (0-255) and truecolor (LRU) to reduce allocations (issue #144).
     */
    fun convertTerminalColor(terminalColor: TerminalColor?): Color {
        if (terminalColor == null) return Color.Black

        // Fast path: indexed colors 0-255 use pre-computed cache
        if (terminalColor.isIndexed) {
            val idx = terminalColor.colorIndex
            if (idx in 0..255) {
                return getOrCreateIndexedColor(idx)
            }
        }

        // Slow path: RGB truecolor with LRU cache
        val bossColor = terminalColor.toColor()
        return getOrCreateTruecolor(bossColor.red, bossColor.green, bossColor.blue)
    }

    /**
     * Convert BossTerm TerminalColor to Compose Color using a specific theme's palette.
     * This is useful for rendering with a specific theme regardless of the global setting.
     */
    fun convertTerminalColor(terminalColor: TerminalColor?, theme: Theme): Color {
        if (terminalColor == null) return Color.Black

        val palette = createPaletteFromTheme(theme)
        val bossColor = if (terminalColor.isIndexed && terminalColor.colorIndex < 16) {
            palette.getForeground(terminalColor)
        } else {
            terminalColor.toColor()
        }

        return Color(
            red = bossColor.red / 255f,
            green = bossColor.green / 255f,
            blue = bossColor.blue / 255f
        )
    }

    /**
     * Apply DIM attribute by reducing color brightness to 50%.
     */
    fun applyDimColor(color: Color): Color {
        return Color(
            red = color.red * 0.5f,
            green = color.green * 0.5f,
            blue = color.blue * 0.5f,
            alpha = color.alpha
        )
    }
}
