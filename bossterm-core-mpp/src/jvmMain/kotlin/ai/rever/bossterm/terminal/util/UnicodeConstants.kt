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

    // === Emoji Blocks (Supplementary Plane) ===
    /** Enclosed Alphanumeric Supplement - includes regional indicators */
    val ENCLOSED_ALPHANUMERIC_SUPPLEMENT_RANGE = 0x1F100..0x1F1FF
    /** Misc Symbols and Pictographs - weather, food, animals, objects */
    val MISC_SYMBOLS_PICTOGRAPHS_RANGE = 0x1F300..0x1F5FF
    /** Emoticons - smileys & people */
    val EMOTICONS_RANGE = 0x1F600..0x1F64F
    /** Transport and Map Symbols */
    val TRANSPORT_MAP_SYMBOLS_RANGE = 0x1F680..0x1F6FF
    /** Supplemental Symbols and Pictographs */
    val SUPPLEMENTAL_SYMBOLS_RANGE = 0x1F900..0x1F9FF
    /** Symbols and Pictographs Extended-A */
    val SYMBOLS_PICTOGRAPHS_EXTENDED_A_RANGE = 0x1FA70..0x1FAFF
    /** Chess Symbols */
    val CHESS_SYMBOLS_RANGE = 0x1FA00..0x1FA6F

    // === BMP Emoji (unambiguous emoji, not text symbols) ===
    /** Watch (U+231A) */
    const val WATCH = 0x231A
    /** Hourglass (U+231B) */
    const val HOURGLASS = 0x231B
    /** Umbrella with rain (U+2614) */
    const val UMBRELLA_RAIN = 0x2614
    /** Hot beverage (U+2615) */
    const val HOT_BEVERAGE = 0x2615
    /** Zodiac signs range */
    val ZODIAC_RANGE = 0x2648..0x2653
    /** Wheelchair (U+267F) */
    const val WHEELCHAIR = 0x267F
    /** Anchor (U+2693) */
    const val ANCHOR = 0x2693
    /** High voltage (U+26A1) */
    const val HIGH_VOLTAGE = 0x26A1
    /** White circle (U+26AA) */
    const val WHITE_CIRCLE = 0x26AA
    /** Black circle (U+26AB) */
    const val BLACK_CIRCLE = 0x26AB
    /** Soccer ball (U+26BD) */
    const val SOCCER_BALL = 0x26BD
    /** Baseball (U+26BE) */
    const val BASEBALL = 0x26BE
    /** Snowman (U+26C4) */
    const val SNOWMAN = 0x26C4
    /** Sun behind cloud (U+26C5) */
    const val SUN_CLOUD = 0x26C5
    /** Ophiuchus (U+26CE) */
    const val OPHIUCHUS = 0x26CE
    /** No entry (U+26D4) */
    const val NO_ENTRY = 0x26D4
    /** Church (U+26EA) */
    const val CHURCH = 0x26EA
    /** Fountain (U+26F2) */
    const val FOUNTAIN = 0x26F2
    /** Golf (U+26F3) */
    const val GOLF = 0x26F3
    /** Sailboat (U+26F5) */
    const val SAILBOAT = 0x26F5
    /** Tent (U+26FA) */
    const val TENT = 0x26FA
    /** Fuel pump (U+26FD) */
    const val FUEL_PUMP = 0x26FD
    /** Check mark button (U+2705) */
    const val CHECK_MARK_BUTTON = 0x2705
    /** Sparkles (U+2728) */
    const val SPARKLES = 0x2728
    /** Cross mark (U+274C) */
    const val CROSS_MARK = 0x274C
    /** Cross mark button (U+274E) */
    const val CROSS_MARK_BUTTON = 0x274E
    /** Question/exclamation marks range */
    val QUESTION_EXCLAMATION_RANGE = 0x2753..0x2755
    /** Exclamation mark (U+2757) */
    const val EXCLAMATION_MARK = 0x2757
    /** Math operators emoji range (➕➖➗) */
    val MATH_OPERATORS_EMOJI_RANGE = 0x2795..0x2797
    /** Curly loop (U+27B0) */
    const val CURLY_LOOP = 0x27B0
    /** Double curly loop (U+27BF) */
    const val DOUBLE_CURLY_LOOP = 0x27BF
    /** Curved arrow up (U+2934) */
    const val CURVED_ARROW_UP = 0x2934
    /** Curved arrow down (U+2935) */
    const val CURVED_ARROW_DOWN = 0x2935
    /** Directional arrows range (⬅⬆⬇) */
    val DIRECTIONAL_ARROWS_RANGE = 0x2B05..0x2B07
    /** Black large square (U+2B1B) */
    const val BLACK_LARGE_SQUARE = 0x2B1B
    /** White large square (U+2B1C) */
    const val WHITE_LARGE_SQUARE = 0x2B1C
    /** Star (U+2B50) */
    const val STAR = 0x2B50
    /** Heavy large circle (U+2B55) */
    const val HEAVY_CIRCLE = 0x2B55
    /** Wavy dash (U+3030) */
    const val WAVY_DASH = 0x3030
    /** Part alternation mark (U+303D) */
    const val PART_ALTERNATION = 0x303D
    /** Circled Ideograph Congratulation (U+3297) */
    const val CIRCLED_CONGRATULATION = 0x3297
    /** Circled Ideograph Secret (U+3299) */
    const val CIRCLED_SECRET = 0x3299

    // === Combining Characters ===
    /** Combining Diacritical Marks (U+0300 to U+036F) - e.g., accents, umlauts */
    val COMBINING_DIACRITICS_RANGE = 0x0300..0x036F
    /** Combining Diacritical Marks for Symbols (U+20D0 to U+20FF) */
    val COMBINING_MARKS_FOR_SYMBOLS_RANGE = 0x20D0..0x20FF
    /** Hebrew combining marks (U+0591 to U+05BD) */
    val HEBREW_COMBINING_MARKS_RANGE = 0x0591..0x05BD
    /** Arabic combining marks (U+0610 to U+061A) */
    val ARABIC_COMBINING_MARKS_RANGE = 0x0610..0x061A

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

    /** Check if code point is a combining diacritical mark */
    fun isCombiningDiacritic(codePoint: Int): Boolean = codePoint in COMBINING_DIACRITICS_RANGE

    /** Check if code point is any combining character (diacritics, symbols, Hebrew, Arabic) */
    fun isCombiningCharacter(codePoint: Int): Boolean =
        codePoint in COMBINING_DIACRITICS_RANGE ||
        codePoint in COMBINING_MARKS_FOR_SYMBOLS_RANGE ||
        codePoint in HEBREW_COMBINING_MARKS_RANGE ||
        codePoint in ARABIC_COMBINING_MARKS_RANGE

    /** Check if code point is in supplementary plane emoji blocks (always 2-cell) */
    fun isSupplementaryPlaneEmoji(codePoint: Int): Boolean =
        codePoint in ENCLOSED_ALPHANUMERIC_SUPPLEMENT_RANGE ||
        codePoint in MISC_SYMBOLS_PICTOGRAPHS_RANGE ||
        codePoint in EMOTICONS_RANGE ||
        codePoint in TRANSPORT_MAP_SYMBOLS_RANGE ||
        codePoint in SUPPLEMENTAL_SYMBOLS_RANGE ||
        codePoint in SYMBOLS_PICTOGRAPHS_EXTENDED_A_RANGE ||
        codePoint in CHESS_SYMBOLS_RANGE

    /**
     * Check if code point is a BMP emoji (Basic Multilingual Plane, U+0000 to U+FFFF).
     *
     * ## Selection Criteria
     * This list includes ONLY characters that:
     * 1. Have `Emoji_Presentation=Yes` in Unicode data (default emoji rendering)
     * 2. Are UNAMBIGUOUS emoji - not commonly used as 1-cell text symbols in TUIs
     *
     * ## Intentionally EXCLUDED
     * Many BMP symbols are NOT included even though they can render as emoji:
     * - Play/pause/stop controls (U+23F8-U+23FA): Used as 1-cell TUI buttons
     * - Geometric shapes (U+25A0-U+25FF): Used in progress bars, menus
     * - Arrows (U+2190-U+21FF): Common text navigation symbols
     * - Box drawing (U+2500-U+257F): TUI borders
     *
     * These characters render as 2-cell emoji ONLY when followed by VS16 (U+FE0F).
     * Without VS16, they default to 1-cell text presentation.
     *
     * ## Reference
     * Based on Unicode 15.1 emoji-data.txt `Emoji_Presentation=Yes` entries for BMP,
     * filtered to exclude common TUI text symbols. See:
     * https://unicode.org/Public/emoji/15.1/emoji-data.txt
     *
     * @return True if this character should render as 2 cells by default (without VS16)
     */
    fun isBmpEmoji(codePoint: Int): Boolean = when (codePoint) {
        WATCH, HOURGLASS -> true
        UMBRELLA_RAIN, HOT_BEVERAGE -> true
        in ZODIAC_RANGE -> true
        WHEELCHAIR, ANCHOR, HIGH_VOLTAGE -> true
        WHITE_CIRCLE, BLACK_CIRCLE -> true
        SOCCER_BALL, BASEBALL -> true
        SNOWMAN, SUN_CLOUD -> true
        OPHIUCHUS, NO_ENTRY, CHURCH -> true
        FOUNTAIN, GOLF, SAILBOAT, TENT, FUEL_PUMP -> true
        CHECK_MARK_BUTTON, SPARKLES -> true
        CROSS_MARK, CROSS_MARK_BUTTON -> true
        in QUESTION_EXCLAMATION_RANGE -> true
        EXCLAMATION_MARK -> true
        in MATH_OPERATORS_EMOJI_RANGE -> true
        CURLY_LOOP, DOUBLE_CURLY_LOOP -> true
        CURVED_ARROW_UP, CURVED_ARROW_DOWN -> true
        in DIRECTIONAL_ARROWS_RANGE -> true
        BLACK_LARGE_SQUARE, WHITE_LARGE_SQUARE -> true
        STAR, HEAVY_CIRCLE -> true
        WAVY_DASH, PART_ALTERNATION -> true
        CIRCLED_CONGRATULATION, CIRCLED_SECRET -> true
        else -> false
    }
}
