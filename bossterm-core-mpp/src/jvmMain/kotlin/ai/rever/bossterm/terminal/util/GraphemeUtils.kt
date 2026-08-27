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
     * Maximum cache size for grapheme width calculations.
     */
    private const val MAX_CACHE_SIZE = 1024

    /**
     * Lock-free cache for grapheme width calculations.
     * Uses ConcurrentHashMap for thread-safe access without lock contention.
     * Trade-off: No LRU eviction, but terminal workloads have minimal cache churn.
     */
    private val widthCache = ConcurrentHashMap<String, Int>(MAX_CACHE_SIZE)

    /**
     * Thread-local BreakIterator for grapheme segmentation.
     * BreakIterator is not thread-safe, so we use ThreadLocal.
     */
    private val breakIterator: ThreadLocal<BreakIterator> = ThreadLocal.withInitial {
        BreakIterator.getCharacterInstance()
    }

    /**
     * Thread-local buffer for code point extraction.
     * Avoids allocating IntArray for each width calculation.
     * Size 16 covers all practical grapheme clusters (typical max is 7-8 code points for complex ZWJ).
     */
    private val codePointBuffer: ThreadLocal<IntArray> = ThreadLocal.withInitial {
        IntArray(16)
    }

    // ==================== FAST-PATH DETECTION FUNCTIONS ====================
    // These O(1) checks allow avoiding full grapheme segmentation for simple cases

    /**
     * O(1) check for Zero-Width Joiner presence.
     * ZWJ (U+200D) joins emoji into composite sequences like family emoji.
     *
     * @param text The string to check
     * @return True if text contains ZWJ
     */
    fun containsZWJ(text: String): Boolean {
        for (i in text.indices) {
            if (text[i].code == UnicodeConstants.ZWJ) return true
        }
        return false
    }

    /**
     * O(n) check for skin tone modifier presence.
     * Skin tones are in the supplementary plane (U+1F3FB..U+1F3FF),
     * so we need to check surrogate pairs.
     *
     * @param text The string to check
     * @return True if text contains skin tone modifier
     */
    fun containsSkinTone(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length) {
                val cp = Character.toCodePoint(c, text[i + 1])
                if (cp in UnicodeConstants.SKIN_TONE_RANGE) return true
                i += 2
            } else {
                i++
            }
        }
        return false
    }

    /**
     * O(n) check for variation selector presence.
     * Variation selectors (VS15=U+FE0E, VS16=U+FE0F) modify character presentation.
     *
     * @param text The string to check
     * @return True if text contains variation selector
     */
    fun containsVariationSelector(text: String): Boolean {
        for (i in text.indices) {
            val c = text[i].code
            if (c == UnicodeConstants.VARIATION_SELECTOR_TEXT || c == UnicodeConstants.VARIATION_SELECTOR_EMOJI) {
                return true
            }
        }
        return false
    }

    /**
     * Attempts to extract the first grapheme without full ICU4J segmentation.
     * Returns null if the text contains complex grapheme boundaries that require
     * proper segmentation (ZWJ sequences, skin tones, etc.).
     *
     * Fast paths:
     * - Empty string -> null
     * - Single ASCII char -> that char
     * - Simple supplementary plane char (no modifiers) -> the 2-char surrogate pair
     *
     * @param text The string to analyze
     * @return The first grapheme as a string, or null if complex segmentation needed
     */
    fun getFirstGraphemeSimple(text: String): String? {
        if (text.isEmpty()) return null

        // Fast path: single ASCII character
        if (text.length == 1) {
            val code = text[0].code
            if (code < 0x80) return text
        }

        // Check for complex sequences that require full segmentation
        if (containsZWJ(text)) return null
        if (containsSkinTone(text)) return null
        if (containsVariationSelector(text)) return null

        // Simple surrogate pair (supplementary plane character without modifiers)
        val firstChar = text[0]
        if (firstChar.isHighSurrogate() && text.length >= 2 && text[1].isLowSurrogate()) {
            // Check if there's more after the surrogate pair
            if (text.length == 2) return text
            // Check if the third char is a grapheme extender
            if (!isGraphemeExtender(text[2])) {
                return text.substring(0, 2)
            }
        }

        return null // Fall back to full segmentation
    }

    /**
     * Clears the grapheme width cache.
     * Useful for testing or when theme/palette changes affect rendering.
     */
    fun clearCache() {
        widthCache.clear()
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
    /**
     * True when every character is printable ASCII (U+0020..U+007E).
     *
     * Control characters are excluded deliberately: the emulator routes them separately and
     * their width is not simply 1, so letting them into the fast path would be a behaviour
     * change rather than an optimisation.
     */
    internal fun isAllPrintableAscii(text: String): Boolean {
        for (ch in text) {
            if (ch.code < 0x20 || ch.code > 0x7E) return false
        }
        return true
    }

    /** Interned single-character clusters for printable ASCII, built once. */
    private val asciiClusters: Array<GraphemeCluster> = Array(0x7F - 0x20) { i ->
        val ch = (0x20 + i).toChar()
        GraphemeCluster(ch.toString(), 1, intArrayOf(ch.code))
    }

    private fun asciiCluster(ch: Char): GraphemeCluster = asciiClusters[ch.code - 0x20]

    fun segmentIntoGraphemes(text: String): List<GraphemeCluster> {
        if (text.isEmpty()) return emptyList()

        // Printable ASCII cannot form a multi-character grapheme cluster and is always one
        // column wide: no combining marks, no ZWJ, no variation selectors, no surrogates,
        // nothing wide or ambiguous lives below U+0080. So the ICU BreakIterator, the
        // per-cluster substring and the width calculation are all decidable without them.
        //
        // This is not a marginal case - it is essentially all terminal output. Stack
        // sampling the emulator thread through a 5 MB `cat` put ~27% of its time in this
        // call chain (`segmentIntoGraphemes` -> `calculateGraphemeWidth` ->
        // `extractCodePoints` / `isEmojiPresentation` / `RuleBasedBreakIterator`), for text
        // that was ASCII from end to end.
        //
        // Anything non-ASCII falls through to ICU unchanged; the boundary is one cheap scan.
        if (isAllPrintableAscii(text)) {
            return ArrayList<GraphemeCluster>(text.length).apply {
                for (ch in text) add(asciiCluster(ch))
            }
        }

        return segmentViaBreakIterator(text)
    }

    /**
     * The full ICU segmentation, split out so the ASCII fast path above can be proved
     * equivalent to it rather than argued to be.
     */
    internal fun segmentViaBreakIterator(text: String): List<GraphemeCluster> {
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
     * Extracts only the last grapheme cluster from a string.
     *
     * Much more efficient than segmentIntoGraphemes() when only the last
     * grapheme is needed (e.g., for REP command tracking).
     *
     * Uses reverse iteration to find the last grapheme boundary, with
     * fast paths for common cases:
     * - Empty string: returns null
     * - Single character: returns the character as string
     * - ASCII ending: returns the last character (no BreakIterator needed)
     *
     * @param text The string to analyze
     * @return The last grapheme cluster as a string, or null if empty
     */
    fun getLastGrapheme(text: String): String? {
        if (text.isEmpty()) return null

        // Fast path: single character
        if (text.length == 1) return text

        // Fast path: last char is ASCII and not a grapheme extender
        val lastChar = text.last()
        if (lastChar.code < 0x80 && !isGraphemeExtender(lastChar)) {
            return lastChar.toString()
        }

        // Use BreakIterator for complex cases
        val iterator = breakIterator.get()
        iterator.setText(text)

        // Navigate to end and find previous boundary
        val lastBoundary = iterator.last()
        val prevBoundary = iterator.previous()

        return if (prevBoundary != BreakIterator.DONE) {
            text.substring(prevBoundary, lastBoundary)
        } else {
            text  // Entire string is one grapheme
        }
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
     * Results are cached for performance using lock-free ConcurrentHashMap.
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

        // Check cache (lock-free read)
        val cacheKey = if (ambiguousIsDWC) "$grapheme:DWC" else grapheme
        widthCache[cacheKey]?.let { return it }

        // Calculate width for complex grapheme
        val width = calculateGraphemeWidth(grapheme, ambiguousIsDWC)

        // Cache if we have room (lock-free write)
        if (widthCache.size < MAX_CACHE_SIZE) {
            widthCache[cacheKey] = width
        }
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
     *
     * Uses ThreadLocal buffer to avoid allocation per call.
     */
    private fun calculateGraphemeWidth(grapheme: String, ambiguousIsDWC: Boolean): Int {
        if (grapheme.isEmpty()) return 0

        // Extract code points into ThreadLocal buffer (avoids allocation)
        val buffer = codePointBuffer.get()
        val count = extractCodePoints(grapheme, buffer)

        // Check for ZWJ sequence (multiple emoji joined)
        for (i in 0 until count) {
            if (buffer[i] == UnicodeConstants.ZWJ) {
                return 2  // ZWJ sequence: treat as single emoji
            }
        }

        // Check for Regional Indicator sequence (flag emoji)
        // Two consecutive Regional Indicators form a flag (e.g., 🇺🇸 = U+1F1FA + U+1F1F8)
        if (count >= 2) {
            var allRegional = true
            for (i in 0 until count) {
                if (!UnicodeConstants.isRegionalIndicator(buffer[i])) {
                    allRegional = false
                    break
                }
            }
            if (allRegional) return 2  // Flag emoji
        }

        // Check for variation selector and skin tone modifier
        var hasVariationSelector = false
        var hasSkinTone = false
        for (i in 0 until count) {
            val cp = buffer[i]
            if (UnicodeConstants.isVariationSelector(cp)) hasVariationSelector = true
            if (UnicodeConstants.isSkinToneModifier(cp)) hasSkinTone = true
        }

        // For emoji with variation selector or skin tone, calculate base emoji width only
        if (hasVariationSelector || hasSkinTone) {
            // Get the first (base) code point width
            val baseCodePoint = buffer[0]
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
        if (count == 1 && isEmojiPresentation(buffer[0])) {
            return 2
        }

        // For combining character sequences, only count base character
        var totalWidth = 0
        var isFirst = true

        for (i in 0 until count) {
            val codePoint = buffer[i]
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
     * Extracts code points from a string into a pre-allocated buffer.
     * Returns the number of code points extracted.
     *
     * @param grapheme The string to extract code points from
     * @param buffer The buffer to fill with code points
     * @return Number of code points extracted
     */
    private fun extractCodePoints(grapheme: String, buffer: IntArray): Int {
        var count = 0
        var offset = 0
        while (offset < grapheme.length && count < buffer.size) {
            val codePoint = grapheme.codePointAt(offset)
            buffer[count++] = codePoint
            offset += Character.charCount(codePoint)
        }
        return count
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

}
