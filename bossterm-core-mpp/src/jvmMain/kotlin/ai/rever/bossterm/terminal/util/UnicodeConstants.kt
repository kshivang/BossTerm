package ai.rever.bossterm.terminal.util

/**
 * Unicode constants for grapheme cluster handling.
 * Centralized to ensure consistency and ease of maintenance.
 *
 * These constants are used across multiple files for:
 * - Grapheme segmentation (GraphemeUtils, GraphemeBoundaryUtils)
 * - Column conversion (ColumnConversionUtils)
 * - Character analysis (GraphemeCluster, GraphemeMetadata)
 * - Rendering (TerminalCanvasRenderer)
 */
object UnicodeConstants {
    // === Variation Selectors ===
    /** VS15 - text presentation selector */
    const val VARIATION_SELECTOR_TEXT = 0xFE0E
    /** VS16 - emoji presentation selector */
    const val VARIATION_SELECTOR_EMOJI = 0xFE0F

    // === Zero-Width Joiner ===
    /** ZWJ - joins emoji into composite sequences (e.g., family emoji) */
    const val ZWJ = 0x200D

    // === Skin Tone Modifiers (Fitzpatrick scale) ===
    /** Range of skin tone modifier code points (U+1F3FB to U+1F3FF) */
    val SKIN_TONE_RANGE = 0x1F3FB..0x1F3FF

    // === Gender Symbols (used in ZWJ sequences) ===
    /** Female sign - used in gendered emoji sequences */
    const val FEMALE_SIGN = 0x2640
    /** Male sign - used in gendered emoji sequences */
    const val MALE_SIGN = 0x2642

    // === Regional Indicators (flag emoji) ===
    /** Range of Regional Indicator code points (U+1F1E6 to U+1F1FF) */
    val REGIONAL_INDICATOR_RANGE = 0x1F1E6..0x1F1FF
    /** High surrogate for Regional Indicators */
    const val REGIONAL_INDICATOR_HIGH_SURROGATE = '\uD83C'
    /** Low surrogate range for Regional Indicators */
    val REGIONAL_INDICATOR_LOW_SURROGATE_RANGE = 0xDDE6..0xDDFF

    // === Helper Functions ===

    /** Check if code point is a variation selector (VS15 or VS16) */
    fun isVariationSelector(codePoint: Int): Boolean =
        codePoint == VARIATION_SELECTOR_TEXT || codePoint == VARIATION_SELECTOR_EMOJI

    /** Check if char is a variation selector */
    fun isVariationSelector(char: Char): Boolean = isVariationSelector(char.code)

    /** Check if code point is a skin tone modifier */
    fun isSkinToneModifier(codePoint: Int): Boolean = codePoint in SKIN_TONE_RANGE

    /** Check if code point is a gender symbol */
    fun isGenderSymbol(codePoint: Int): Boolean =
        codePoint == FEMALE_SIGN || codePoint == MALE_SIGN

    /** Check if code point is a Regional Indicator */
    fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in REGIONAL_INDICATOR_RANGE

    /** Check if char is a Regional Indicator high surrogate */
    fun isRegionalIndicatorHighSurrogate(char: Char): Boolean =
        char == REGIONAL_INDICATOR_HIGH_SURROGATE

    /** Check if char code is a Regional Indicator low surrogate */
    fun isRegionalIndicatorLowSurrogate(charCode: Int): Boolean =
        charCode in REGIONAL_INDICATOR_LOW_SURROGATE_RANGE
}
