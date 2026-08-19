package ai.rever.bossterm.compose.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
     *
     * Also drops the legibility-guard caches: a theme change moves the background
     * those results were solved against.
     */
    fun invalidateColorCache() {
        indexedColorCache = null
        guardMemo = null
        synchronized(guardCacheLock) { guardCache.clear() }
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

    // ===== Legibility guard for light backgrounds =====

    /**
     * WCAG relative-luminance pivot: above this, black text out-contrasts white
     * text on the same background. Solving (1.05)/(L+0.05) = (L+0.05)/0.05 gives
     * L = 0.1791.
     *
     * Used as the "this background is light" test rather than a flat 0.5 so that a
     * mid-grey background (#AAAAAA, L = 0.40) still counts as light — white glyphs
     * are illegible there too. Every built-in dark theme sits two orders of
     * magnitude below it (BOSS Blueprint's #05070B is 0.0028), so they are
     * provably untouched by the guard.
     */
    private const val LIGHT_BACKGROUND_PIVOT = 0.1791f

    /** Steps used when walking a color toward black to reach the contrast floor. */
    private const val CLAMP_STEPS = 20

    /** Whether [bg] is light enough that dark-authored glyph colors stop being readable on it. */
    fun isLightBackground(bg: Color): Boolean = bg.luminance() > LIGHT_BACKGROUND_PIVOT

    /**
     * The guard's effective floor for a surface whose background is [bg] — [minRatio] on a
     * light background, `1f` (off) otherwise.
     *
     * Exists so the share path can push the same decision to the web viewer without
     * restating [LIGHT_BACKGROUND_PIVOT]. The viewer cannot reuse the guard itself: it
     * renders through xterm.js, whose glyph pipeline has no per-cell color hook, and its
     * live text arrives as a raw pty stream rather than through this process's renderer.
     * What it *does* have is xterm.js's own `minimumContrastRatio`, which takes a floor —
     * so the floor is the part worth sharing.
     */
    fun lightBackgroundGuardRatio(bg: Color, minRatio: Float): Float =
        if (minRatio > 1f && isLightBackground(bg)) minRatio else 1f

    /**
     * WCAG contrast ratio between two colors, ignoring alpha.
     *
     * The first public copy in the repo; the same math is otherwise duplicated
     * privately in `UiTheme.Companion` and two test files.
     */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /**
     * One (fg, bg, minRatio) -> result memo. A single immutable holder behind one
     * volatile field rather than three fields, so a concurrent write can never be
     * read torn (a half-updated memo would paint the wrong color).
     */
    private class GuardMemo(val key: Long, val minRatio: Float, val result: Color)

    @Volatile
    private var guardMemo: GuardMemo? = null

    /**
     * LRU behind [guardMemo] for the handful of (fg, bg) pairs a frame actually
     * uses. Mirrors [truecolorCache]'s sizing.
     */
    private val guardCache = object : LinkedHashMap<Long, Color>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Color>?): Boolean {
            return size > 512
        }
    }
    private val guardCacheLock = Any()
    private var cachedMinRatio: Float = Float.NaN

    /**
     * Adapt a foreground color that was authored for a dark terminal so it stays
     * readable on a light background, leaving everything else exactly as-is.
     *
     * Truecolor CLIs hardcode their palette: Claude Code emits a literal
     * `ESC[38;2;255;255;255m` for primary text, which is 1.07:1 — invisible — on a
     * light theme's paper floor. Nothing in the theme or palette layer can reach a
     * 24-bit color, so the correction has to happen per cell at paint time.
     *
     * Returns [fg] untouched unless *all* of these hold:
     *  - the guard is enabled ([minRatio] > 1),
     *  - [bg] — the background actually painted for this cell, which for an INVERSE
     *    cell is the style's foreground — is light,
     *  - [fg] falls short of [minRatio] against it.
     *
     * A color that fails is corrected in two steps:
     *  1. **Mirror** its HSL lightness about 0.5, preserving hue, saturation and
     *     alpha. This is what makes the result look *right* rather than merely
     *     legible: white becomes near-black so primary text reads as ink, #999999
     *     stays a grey, and a brand orange stays orange.
     *  2. **Clamp** toward black until the floor is met. A fully saturated color
     *     sits near lightness 0.5 already, so the mirror barely moves it (#FFC107
     *     -> #F8BC00); the clamp is what rescues those.
     *
     * Never call this for a background color — backgrounds are painted as authored.
     */
    fun legibleOnLightBackground(fg: Color, bg: Color, minRatio: Float): Color {
        if (minRatio <= 1f) return fg

        // The luminance work is deliberately *behind* the cache rather than in front
        // of it: a bail-out costs six pow() calls, so on a dark theme — where every
        // cell bails — checking first would be a per-cell tax for no benefit. Caching
        // the identity result instead makes the disabled case a field read.
        val key = (fg.toArgb().toLong() shl 32) or (bg.toArgb().toLong() and 0xFFFFFFFFL)
        guardMemo?.let { if (it.key == key && it.minRatio == minRatio) return it.result }

        val result = synchronized(guardCacheLock) {
            if (cachedMinRatio != minRatio) {
                guardCache.clear()
                cachedMinRatio = minRatio
            }
            guardCache.getOrPut(key) { computeLegible(fg, bg, minRatio) }
        }
        guardMemo = GuardMemo(key, minRatio, result)
        return result
    }

    private fun computeLegible(fg: Color, bg: Color, minRatio: Float): Color {
        if (bg.luminance() <= LIGHT_BACKGROUND_PIVOT) return fg
        if (contrastRatio(fg, bg) >= minRatio) return fg

        // Step 1: mirror lightness, hue and saturation intact.
        val hsl = toHsl(fg)
        var out = if (hsl[2] > 0.5f) hslToColor(hsl[0], hsl[1], 1f - hsl[2], fg.alpha) else fg

        // Step 2: clamp toward black for whatever the mirror could not fix.
        if (contrastRatio(out, bg) < minRatio) {
            val start = out
            out = Color.Black.copy(alpha = fg.alpha)
            for (step in 1 until CLAMP_STEPS) {
                val candidate = mix(start, Color.Black, step.toFloat() / CLAMP_STEPS)
                if (contrastRatio(candidate, bg) >= minRatio) {
                    out = candidate
                    break
                }
            }
        }
        return out
    }

    /**
     * Component-space (gamma sRGB) mix, preserving [a]'s alpha. Compose's
     * [androidx.compose.ui.graphics.lerp] interpolates in Oklab, where small steps
     * off pure black round back to black — the same reason `UiTheme` carries its
     * own mix.
     */
    private fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha
    )

    /**
     * RGB -> HSL as `[hue, saturation, lightness]`, all 0..1.
     *
     * Hand-rolled because Compose Multiplatform's desktop `ui-graphics` ships
     * `Color.luminance()` but no `Color.hsl()` — that constructor is Android-only.
     */
    private fun toHsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val lightness = (max + min) / 2f
        val delta = max - min
        if (delta == 0f) return floatArrayOf(0f, 0f, lightness)

        val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
        val hue = when (max) {
            r -> (g - b) / delta + if (g < b) 6f else 0f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } / 6f
        return floatArrayOf(hue, saturation, lightness)
    }

    /** HSL -> RGB, the inverse of [toHsl]. */
    private fun hslToColor(hue: Float, saturation: Float, lightness: Float, alpha: Float): Color {
        if (saturation == 0f) {
            val v = lightness.coerceIn(0f, 1f)
            return Color(v, v, v, alpha)
        }
        val q = if (lightness < 0.5f) {
            lightness * (1f + saturation)
        } else {
            lightness + saturation - lightness * saturation
        }
        val p = 2f * lightness - q
        return Color(
            red = hueToChannel(p, q, hue + 1f / 3f),
            green = hueToChannel(p, q, hue),
            blue = hueToChannel(p, q, hue - 1f / 3f),
            alpha = alpha
        )
    }

    private fun hueToChannel(p: Float, q: Float, rawT: Float): Float {
        var t = rawT
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        val channel = when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
        return channel.coerceIn(0f, 1f)
    }
}
