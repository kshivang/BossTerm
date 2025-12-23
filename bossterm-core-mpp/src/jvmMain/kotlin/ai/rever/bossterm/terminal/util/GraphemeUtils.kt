package ai.rever.bossterm.terminal.util

import com.ibm.icu.text.BreakIterator
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class for Unicode grapheme cluster segmentation and width calculation.
 *
 * Uses ICU4J's BreakIterator for production-grade grapheme segmentation.
 * Includes caching for performance and fallback heuristics for common cases.
 *
 * A grapheme cluster is the smallest unit of text that should be treated as
 * indivisible from a user's perspective. Examples:
 * - Single character: "a"
 * - Surrogate pair: "𝕳" (U+1D573)
 * - Emoji with variation selector: "☁️"
 * - ZWJ sequence: "👨‍👩‍👧‍👦"
 * - Emoji with skin tone: "👍🏽"
 * - Combining characters: "á" (a + combining acute)
 */
object GraphemeUtils {
    /**
     * LRU cache for grapheme width calculations.
     * Caches complex grapheme clusters to avoid repeated segmentation.
     */
    private val widthCache = LRUCache<String, Int>(1024)

    /**
     * Thread-local BreakIterator for grapheme segmentation.
     * BreakIterator is not thread-safe, so we use ThreadLocal.
     */
    private val breakIterator: ThreadLocal<BreakIterator> = ThreadLocal.withInitial {
        BreakIterator.getCharacterInstance()
    }

    /**
     * Segments a string into grapheme clusters.
     *
     * Uses ICU4J's BreakIterator for accurate grapheme boundary detection.
     * Handles all Unicode edge cases including:
     * - Surrogate pairs (U+10000+)
     * - ZWJ sequences
     * - Variation selectors
     * - Skin tone modifiers
     * - Combining characters
     *
     * @param text The string to segment
     * @return List of grapheme clusters
     */
    fun segmentIntoGraphemes(text: String): List<GraphemeCluster> {
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<GraphemeCluster>()
        val iterator = breakIterator.get()
        iterator.setText(text)

        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            val graphemeText = text.substring(start, end)
            val width = calculateGraphemeWidth(graphemeText, ambiguousIsDWC = false)
            result.add(GraphemeCluster.fromString(graphemeText, width))

            start = end
            end = iterator.next()
        }

        return result
    }

    /**
     * Finds all grapheme boundaries in a string.
     * Returns a list of indices where graphemes start.
     *
     * @param text The string to analyze
     * @return List of boundary positions (0-based indices)
     */
    fun findGraphemeBoundaries(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()

        val boundaries = mutableListOf<Int>()
        val iterator = breakIterator.get()
        iterator.setText(text)

        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            boundaries.add(boundary)
            boundary = iterator.next()
        }

        return boundaries
    }

    /**
     * Gets the visual width of a grapheme cluster.
     *
     * Handles special cases:
     * - Emoji + variation selector: width 2 (not 4)
     * - ZWJ sequences: width 2 (single visual unit)
     * - Skin tone modifiers: width 2 (not 4)
     * - Combining characters: width 0
     *
     * Results are cached for performance.
     *
     * @param grapheme The grapheme cluster text
     * @param ambiguousIsDWC Whether ambiguous-width characters are treated as double-width
     * @return Visual width (0, 1, or 2)
     */
    fun getGraphemeWidth(grapheme: String, ambiguousIsDWC: Boolean): Int {
        if (grapheme.isEmpty()) return 0

        // Fast path: single BMP character
        if (grapheme.length == 1) {
            val codePoint = grapheme[0].code
            // Check for emoji with Emoji_Presentation=Yes (should be 2 cells by default)
            if (isEmojiPresentation(codePoint)) {
                return 2
            }
            return CharUtils.mk_wcwidth(codePoint, ambiguousIsDWC).coerceAtLeast(0)
        }

        // Check cache
        val cacheKey = if (ambiguousIsDWC) "$grapheme:DWC" else grapheme
        widthCache[cacheKey]?.let { return it }

        // Calculate width for complex grapheme
        val width = calculateGraphemeWidth(grapheme, ambiguousIsDWC)
        widthCache[cacheKey] = width
        return width
    }

    /**
     * Calculates the visual width of a complex grapheme cluster.
     *
     * Special handling for:
     * - ZWJ sequences: Treated as single emoji (width 2)
     * - Variation selectors: Don't add to width
     * - Skin tone modifiers: Don't add to width
     * - Combining characters: Width 0
     */
    private fun calculateGraphemeWidth(grapheme: String, ambiguousIsDWC: Boolean): Int {
        if (grapheme.isEmpty()) return 0

        // Extract code points
        val codePoints = mutableListOf<Int>()
        var offset = 0
        while (offset < grapheme.length) {
            val codePoint = grapheme.codePointAt(offset)
            codePoints.add(codePoint)
            offset += Character.charCount(codePoint)
        }

        // Check for ZWJ sequence (multiple emoji joined)
        if (codePoints.contains(UnicodeConstants.ZWJ)) {
            // ZWJ sequence: treat as single emoji (width 2)
            return 2
        }

        // Check for Regional Indicator sequence (flag emoji)
        // Two consecutive Regional Indicators form a flag (e.g., 🇺🇸 = U+1F1FA + U+1F1F8)
        if (codePoints.size >= 2 && codePoints.all { UnicodeConstants.isRegionalIndicator(it) }) {
            return 2  // Flag emoji
        }

        // Check for variation selector
        val hasVariationSelector = codePoints.any { UnicodeConstants.isVariationSelector(it) }

        // Check for skin tone modifier
        val hasSkinTone = codePoints.any { UnicodeConstants.isSkinToneModifier(it) }

        // For emoji with variation selector or skin tone, calculate base emoji width only
        if (hasVariationSelector || hasSkinTone) {
            // Get the first (base) code point width
            val baseCodePoint = codePoints.first()
            val baseWidth = CharUtils.mk_wcwidth(baseCodePoint, ambiguousIsDWC)

            // Emoji are typically width 2
            return when {
                baseWidth == 2 -> 2
                baseWidth == 1 -> 2 // Emoji presentation
                baseWidth <= 0 -> 0
                else -> baseWidth
            }
        }

        // Check for single emoji with Emoji_Presentation=Yes (e.g., ✅, ⭐)
        // These should be 2 cells even without variation selector
        if (codePoints.size == 1 && isEmojiPresentation(codePoints.first())) {
            return 2
        }

        // For combining character sequences, only count base character
        var totalWidth = 0
        var isFirst = true

        for (codePoint in codePoints) {
            val width = CharUtils.mk_wcwidth(codePoint, ambiguousIsDWC)

            if (isFirst) {
                // First character: use its width
                totalWidth = width.coerceAtLeast(0)
                isFirst = false
            } else {
                // Combining characters have width 0, don't add
                // Other characters shouldn't normally appear in a single grapheme
                if (width > 0) {
                    // This shouldn't happen for proper grapheme clusters, but handle gracefully
                    totalWidth = maxOf(totalWidth, width)
                }
            }
        }

        return totalWidth
    }

    /**
     * Checks if a code point should render as emoji (2 cells width) by default.
     *
     * This covers:
     * - Supplementary plane emoji (U+1F000+) which are always 2-cell wide
     * - BMP characters that are UNAMBIGUOUSLY emoji (not commonly used as text symbols)
     *
     * NOTE: Many BMP symbols (▶◀⏹⏺ etc.) are intentionally NOT included here because
     * they are often used as 1-cell text symbols in TUI applications. They will render
     * as 2-cell emoji ONLY when followed by variation selector FE0F.
     *
     * Used by both buffer (for DWC markers) and renderer (for font selection).
     *
     * @param codePoint The Unicode code point to check
     * @return True if this character should render as 2 cells by default
     */
    fun isEmojiPresentation(codePoint: Int): Boolean {
        return UnicodeConstants.isSupplementaryPlaneEmoji(codePoint) ||
               UnicodeConstants.isBmpEmoji(codePoint)
    }

    /**
     * Checks if a character is a grapheme extender (ZWJ, variation selector, skin tone, combining).
     * Used for incremental grapheme boundary detection in streaming scenarios.
     *
     * @param c The character to check
     * @return True if this character extends the previous grapheme
     */
    fun isGraphemeExtender(c: Char): Boolean {
        return when (c.code) {
            UnicodeConstants.ZWJ -> true // Zero-Width Joiner
            UnicodeConstants.VARIATION_SELECTOR_TEXT, UnicodeConstants.VARIATION_SELECTOR_EMOJI -> true // Variation selectors
            in UnicodeConstants.COMBINING_DIACRITICS_RANGE -> true // Combining diacritics
            in UnicodeConstants.SKIN_TONE_RANGE -> true // Skin tone modifiers (requires surrogate pair check)
            in UnicodeConstants.COMBINING_MARKS_FOR_SYMBOLS_RANGE -> true // Combining marks for symbols
            in UnicodeConstants.HEBREW_COMBINING_MARKS_RANGE -> true // Hebrew combining marks
            in UnicodeConstants.ARABIC_COMBINING_MARKS_RANGE -> true // Arabic combining marks
            else -> false
        }
    }

    /**
     * Simple LRU cache implementation for grapheme width caching.
     */
    private class LRUCache<K, V>(private val capacity: Int) {
        private val cache = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > capacity
            }
        }
        private val lock = Any()

        operator fun get(key: K): V? = synchronized(lock) {
            cache[key]
        }

        operator fun set(key: K, value: V) = synchronized(lock) {
            cache[key] = value
        }
    }
}
