package ai.rever.bossterm.compose.util

import ai.rever.bossterm.compose.daemon.BossTermPaths
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * Special value indicating the bundled MesloLGS Nerd Font should be used.
 */
const val BUNDLED_FONT_NAME = "MesloLGS Nerd Font (Bundled)"

/** Section name for bundled font */
const val FONT_SECTION_BUNDLED = "Bundled"
/** Section name for fixed pitch (monospace) fonts */
const val FONT_SECTION_FIXED_PITCH = "Fixed Pitch"
/** Section name for variable pitch (proportional) fonts */
const val FONT_SECTION_VARIABLE_PITCH = "Variable Pitch"

/**
 * Get fonts organized by category (iTerm2-style).
 * Returns a map with sections: "Bundled", "Fixed Pitch", "Variable Pitch"
 */
fun getCategorizedFonts(): Map<String, List<String>> {
    val fontMgr = FontMgr.default
    val familyCount = fontMgr.familiesCount

    val allFamilies = (0 until familyCount)
        .map { fontMgr.getFamilyName(it) }
        .filter { it.isNotEmpty() }

    val fixedPitch = mutableListOf<String>()
    val variablePitch = mutableListOf<String>()

    for (familyName in allFamilies) {
        try {
            val typeface = fontMgr.matchFamilyStyle(familyName, FontStyle.NORMAL)
            if (typeface != null) {
                // Check if font is monospace by comparing glyph widths
                val font = org.jetbrains.skia.Font(typeface, 12f)
                val widthW = font.measureTextWidth("W")
                val widthI = font.measureTextWidth("i")
                // Allow small tolerance for floating point comparison
                if (kotlin.math.abs(widthW - widthI) < 0.1f) {
                    fixedPitch.add(familyName)
                } else {
                    variablePitch.add(familyName)
                }
            }
        } catch (e: Exception) {
            // Skip fonts that fail to load
        }
    }

    return mapOf(
        FONT_SECTION_BUNDLED to listOf(BUNDLED_FONT_NAME),
        FONT_SECTION_FIXED_PITCH to fixedPitch.sorted(),
        FONT_SECTION_VARIABLE_PITCH to variablePitch.sorted()
    )
}

/**
 * Get list of available fonts on the system using Skia's FontMgr.
 * @param monospaceOnly If true, only returns monospace fonts. If false, returns all fonts.
 * Includes the bundled font as the first option.
 * @deprecated Use getCategorizedFonts() for sectioned display
 */
fun getAvailableFonts(monospaceOnly: Boolean = true): List<String> {
    val categorized = getCategorizedFonts()
    return if (monospaceOnly) {
        categorized[FONT_SECTION_BUNDLED]!! + categorized[FONT_SECTION_FIXED_PITCH]!!
    } else {
        categorized[FONT_SECTION_BUNDLED]!! +
            categorized[FONT_SECTION_FIXED_PITCH]!! +
            categorized[FONT_SECTION_VARIABLE_PITCH]!!
    }
}

/**
 * Get list of available monospace fonts on the system.
 * Includes the bundled font as the first option.
 * @deprecated Use getCategorizedFonts() for sectioned display
 */
fun getAvailableMonospaceFonts(): List<String> = getAvailableFonts(monospaceOnly = true)

/**
 * Load terminal font by name.
 * @param fontName Font name from system fonts, or null/empty/BUNDLED_FONT_NAME for bundled font.
 * @return FontFamily for terminal rendering
 */
fun loadTerminalFont(fontName: String? = null): FontFamily {
    // Use bundled font if no name specified or if it's the bundled font marker
    if (fontName.isNullOrEmpty() || fontName == BUNDLED_FONT_NAME) {
        return loadBundledFont()
    }

    // Try to load system font by name using Skia FontMgr
    return try {
        // Use Skia's FontMgr to load system font by family name
        val skiaTypeface = FontMgr.default.matchFamilyStyle(fontName, FontStyle.NORMAL)
        if (skiaTypeface != null) {
            FontFamily(Typeface(skiaTypeface))
        } else {
            System.err.println("Font '$fontName' not found, falling back to bundled font")
            loadBundledFont()
        }
    } catch (e: Exception) {
        System.err.println("Failed to load font '$fontName': ${e.message}")
        loadBundledFont()
    }
}

/**
 * Lazily loaded bundled symbol font (Noto Sans Symbols 2).
 * Used as fallback for symbols like ⏵ ★ ⚡ when system font lacks coverage.
 */
val bundledSymbolFont: FontFamily by lazy {
    loadBundledSymbolFont()
}

/**
 * Cached Apple Color Emoji font for macOS.
 * Lazy-loaded once on first access, null if not available (non-macOS or font missing).
 * Used for emoji with variation selectors (U+FE0F) to ensure color rendering.
 */
val cachedAppleColorEmojiFont: FontFamily? by lazy {
    if (!ShellCustomizationUtils.isMacOS()) {
        null
    } else {
        runCatching {
            FontMgr.default.matchFamilyStyle("Apple Color Emoji", FontStyle.NORMAL)
                ?.let { FontFamily(Typeface(it)) }
        }.getOrNull()
    }
}

/**
 * Cached STIX Two Math font for technical symbols and math characters.
 * Lazy-loaded once on first access, null if not available.
 * Used for mathematical alphanumeric symbols (U+1D400-U+1D7FF) and technical symbols (U+23E9-U+23FF).
 */
val cachedSTIXMathFont: FontFamily? by lazy {
    runCatching {
        FontMgr.default.matchFamilyStyle("STIX Two Math", FontStyle.NORMAL)
            ?.let { FontFamily(Typeface(it)) }
    }.getOrNull()
}

/**
 * Extract a bundled font resource to a file Skia can open, reusing the previous launch's copy.
 *
 * Skiko can't read a typeface straight out of the jar (classloader issues), so the bytes have to
 * land on disk first. Doing that with [java.io.File.createTempFile] means every launch hands the
 * OS a brand-new multi-megabyte file — the worst case for an endpoint-security agent, which scans
 * it on first map. That scan happens inside dyld's loader lock (Skia's font path calls `dlsym`
 * for the CoreText weight mapping), so a slow scan doesn't just delay the font: it blocks the AWT
 * event thread before the window ever paints. Observed in the wild as a launch that shows no
 * window for minutes while the process sits at ~0% CPU.
 *
 * So the copy is content-addressed and persistent: `~/.bossterm/fonts/<name>-<sha256-12>.ttf`.
 * Same bytes, same path, so the scanner sees a familiar file and the write only happens once per
 * font per upgrade. The hash in the name is what makes a new bundled font invalidate the old copy
 * without needing a version check.
 *
 * @return the on-disk font file, or null if the resource is missing.
 */
internal fun extractBundledFont(resourcePath: String): java.io.File? {
    val bytes = object {}.javaClass.classLoader
        ?.getResourceAsStream(resourcePath)
        ?.use { it.readBytes() }
        ?: return null

    val fileName = resourcePath.substringAfterLast('/')
    val baseName = fileName.substringBeforeLast('.')
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "ttf")
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .take(6)
        .joinToString("") { "%02x".format(it) }

    // Cache under the BossTerm dir. If anything about it is unusable (read-only home, sandbox),
    // fall back to the old per-launch temp file rather than failing to load a font at all.
    val cached = runCatching {
        val dir = java.io.File(BossTermPaths.dir(), "fonts").apply { mkdirs() }
        java.io.File(dir, "$baseName-$digest.$extension").takeIf { it.parentFile?.isDirectory == true }
    }.getOrNull()

    if (cached != null) {
        // Catches a truncated copy (a write killed partway). Deliberately NOT a corruption check:
        // the name states the intended content, nothing here verifies the bytes match it. The
        // durable write below is what keeps a same-length-but-garbage file from happening, since
        // such a file would pass this check on every later launch.
        if (cached.isFile && cached.length() == bytes.size.toLong()) return cached
        // Unique temp + atomic rename, so two BossTerm launches racing here can't publish a torn
        // file to the shared path (same rationale as SettingsManager.writeToFileLocked).
        val published = runCatching {
            val tmp = java.io.File.createTempFile(".$baseName", ".tmp", cached.parentFile)
            try {
                // fsync before the rename: a rename is atomic for *ordering*, not durability, and
                // delayed allocation can otherwise publish a correctly-sized file with unwritten
                // extents after a power loss. Truncation self-heals via the length check above;
                // same-length garbage would be sticky until the bundled font itself changes.
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                runCatching {
                    java.nio.file.Files.move(
                        tmp.toPath(), cached.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    java.nio.file.Files.move(
                        tmp.toPath(), cached.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                // Write path only, so a warm launch stays zero-I/O: drop the previous version's
                // copy and any orphaned temp. The hash in the name invalidates for *lookup* but
                // never evicts, and `finally` below can't run if the JVM is SIGKILLed mid-write —
                // which is exactly what someone does to a launch that shows no window.
                runCatching {
                    // An orphan is only orphaned if nobody is mid-write: another launch racing us
                    // has a temp of its own in here, so temps are pruned by age, superseded copies
                    // immediately. Deleting a file Skia has mapped is safe on POSIX (unlink) and
                    // simply fails on Windows, which the per-file runCatching absorbs.
                    val tempCutoff = System.currentTimeMillis() - 3_600_000
                    cached.parentFile?.listFiles { f ->
                        f != cached && f.isFile && when {
                            f.name.startsWith("$baseName-") && f.name.endsWith(".$extension") -> true
                            f.name.startsWith(".$baseName") && f.name.endsWith(".tmp") ->
                                f.lastModified() < tempCutoff
                            else -> false
                        }
                    }?.forEach { stale -> runCatching { stale.delete() } }
                }
                cached
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
        }.getOrNull()
        if (published != null) return published
    }

    return java.io.File.createTempFile(baseName, ".$extension").also { temp ->
        temp.deleteOnExit()
        temp.writeBytes(bytes)
    }
}

/**
 * Load the bundled Noto Sans Symbols 2 font for symbol fallback.
 */
private fun loadBundledSymbolFont(): FontFamily {
    return try {
        val fontFile = extractBundledFont("fonts/NotoSansSymbols2-Regular.ttf")
            ?: return FontFamily.Default

        FontFamily(
            androidx.compose.ui.text.platform.Font(
                file = fontFile,
                weight = FontWeight.Normal
            )
        )
    } catch (e: Exception) {
        System.err.println("Failed to load bundled symbol font: ${e.message}")
        FontFamily.Default
    }
}

/**
 * The bundled terminal font, resolved once. [loadTerminalFont] falls back here on every system-font
 * miss and is itself behind `remember(settings.fontName)`, so without this the 2.5 MB inflate + hash
 * would be re-paid on the composition thread each time the font setting changes.
 */
private val bundledFontFile: java.io.File? by lazy { extractBundledFont("fonts/MesloLGSNF-Regular.ttf") }

/**
 * Load the bundled MesloLGS Nerd Font.
 */
private fun loadBundledFont(): FontFamily {
    return try {
        val fontFile = bundledFontFile ?: return FontFamily.Monospace

        FontFamily(
            androidx.compose.ui.text.platform.Font(
                file = fontFile,
                weight = FontWeight.Normal
            )
        )
    } catch (e: Exception) {
        System.err.println("Failed to load bundled terminal font: ${e.message}")
        FontFamily.Monospace
    }
}
