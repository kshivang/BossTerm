package ai.rever.bossterm.compose.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.compose.hyperlinks.Hyperlink
import ai.rever.bossterm.compose.hyperlinks.HyperlinkDetector
import ai.rever.bossterm.compose.hyperlinks.HyperlinkRegistry
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.util.ColorUtils
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import ai.rever.bossterm.terminal.model.image.ImageCell
import ai.rever.bossterm.terminal.model.image.ImageDataCache
import ai.rever.bossterm.terminal.util.CharUtils
import ai.rever.bossterm.terminal.util.ColumnConversionUtils
import ai.rever.bossterm.terminal.util.GraphemeUtils
import ai.rever.bossterm.terminal.util.UnicodeConstants
import ai.rever.bossterm.terminal.TextStyle as BossTextStyle
import org.jetbrains.skia.FontMgr

/**
 * Holds all the state needed for terminal rendering.
 * Passed to the renderer to avoid long parameter lists.
 */
data class RenderingContext(
    // Buffer and dimensions
    val bufferSnapshot: VersionedBufferSnapshot,
    val cellWidth: Float,
    val cellHeight: Float,
    val baseCellHeight: Float,
    val cellBaseline: Float,

    // Scroll and visible area
    val scrollOffset: Int,
    val visibleCols: Int,
    val visibleRows: Int,

    // Font and text
    val textMeasurer: TextMeasurer,
    val measurementFontFamily: FontFamily,
    val fontSize: Float,

    // Settings
    val settings: TerminalSettings,
    val ambiguousCharsAreDoubleWidth: Boolean,

    // Selection state
    val selectionStart: Pair<Int, Int>?,
    val selectionEnd: Pair<Int, Int>?,
    val selectionMode: SelectionMode,

    // Search state
    val searchVisible: Boolean,
    val searchQuery: String,
    val searchMatches: List<Pair<Int, Int>>,
    val currentMatchIndex: Int,

    // Cursor state
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val cursorBlinkVisible: Boolean,
    val cursorShape: CursorShape?,
    val cursorColor: Color?,
    val isFocused: Boolean,

    // Hyperlink state
    val hoveredHyperlink: Hyperlink?,
    val isModifierPressed: Boolean,

    // Blink state
    val slowBlinkVisible: Boolean,
    val rapidBlinkVisible: Boolean,

    // Cell-based image rendering (images flow with text)
    val imageDataCache: ImageDataCache? = null,
    val terminalWidthCells: Int = 80,
    val terminalHeightCells: Int = 24,

    // Pre-computed hyperlinks cache (for version-based caching optimization)
    // If provided, hyperlink detection will be skipped and these will be used
    val precomputedHyperlinks: Map<Int, List<Hyperlink>>? = null,

    // Working directory for resolving relative file paths in hyperlinks
    val workingDirectory: String? = null,

    // Whether to detect file/folder paths as clickable hyperlinks
    val detectFilePaths: Boolean = true,

    // Custom hyperlink registry for per-instance pattern customization
    val hyperlinkRegistry: HyperlinkRegistry = HyperlinkDetector.registry
)

/**
 * Result of analyzing a character for rendering purposes.
 * Encapsulates surrogate pair handling, double-width detection, and variation selector info.
 *
 * ## Width Properties (evaluated in order)
 *
 * @property isWcwidthDoubleWidth True if the character is double-width by any of:
 *   - `wcwidth()` returns 2 (CJK, fullwidth forms)
 *   - A DWC marker exists at col+1 (buffer-level marking)
 *   - A variation selector at col+1 has DWC at col+2 (emoji+VS layout)
 *
 * @property isBaseDoubleWidth True if:
 *   - Code point >= U+1F100 (supplementary plane, always 2-cell), OR
 *   - [isWcwidthDoubleWidth] is true
 *   This captures characters that are inherently double-width regardless of modifiers.
 *
 * @property isDoubleWidth True if:
 *   - [isBaseDoubleWidth] is true, OR
 *   - Character has a variation selector (emoji presentation)
 *   This is the final width determination used for rendering and cursor movement.
 *
 * @property visualWidth The actual cell count (1 or 2), derived from [isDoubleWidth].
 */
data class CharacterAnalysis(
    val actualCodePoint: Int,
    val lowSurrogate: Char?,
    val charTextToRender: String,
    val isWcwidthDoubleWidth: Boolean,
    val isBaseDoubleWidth: Boolean,
    val hasVariationSelector: Boolean,
    val isEmojiWithVariationSelector: Boolean,
    val isDoubleWidth: Boolean,
    val visualWidth: Int,
    // Character classification for font selection
    val isCursiveOrMath: Boolean,
    val isTechnicalSymbol: Boolean,
    val isEmojiOrWideSymbol: Boolean
)

/**
 * Analyze a character at the given column position for rendering.
 * Handles surrogate pairs, double-width detection, and variation selectors.
 * This is shared between renderBackgrounds() and renderText() to avoid duplication.
 *
 * ## Edge Cases
 * - **Orphaned high surrogate**: If a high surrogate appears without a matching low surrogate
 *   (e.g., at line boundary), it renders as-is. The font will typically display U+FFFD
 *   (replacement character) or a placeholder glyph. This is intentional - the terminal
 *   buffer may legitimately contain orphaned surrogates from incomplete writes.
 *
 * - **DWC marker between surrogates**: Some buffer layouts place a DWC marker between
 *   high and low surrogates: [High][DWC][Low]. This is handled by checking col+2 when
 *   col+1 contains a DWC marker.
 */
fun analyzeCharacter(
    char: Char,
    line: TerminalLine,
    col: Int,
    width: Int,
    ambiguousCharsAreDoubleWidth: Boolean
): CharacterAnalysis {
    val charAtCol1 = if (col + 1 < width) line.charAt(col + 1) else null
    val charAtCol2 = if (col + 2 < width) line.charAt(col + 2) else null

    // Handle surrogate pairs
    val lowSurrogate = if (Character.isHighSurrogate(char)) {
        when {
            charAtCol1 != null && Character.isLowSurrogate(charAtCol1) -> charAtCol1
            charAtCol1 == CharUtils.DWC && charAtCol2 != null && Character.isLowSurrogate(charAtCol2) -> charAtCol2
            else -> null
        }
    } else null

    val actualCodePoint = if (lowSurrogate != null && Character.isLowSurrogate(lowSurrogate)) {
        Character.toCodePoint(char, lowSurrogate)
    } else char.code

    val charTextToRender = if (lowSurrogate != null && Character.isLowSurrogate(lowSurrogate)) {
        "$char$lowSurrogate"
    } else {
        char.toString()
    }

    // Double-width detection
    val wcwidthResult = char != ' ' && char != '\u0000' &&
        CharUtils.isDoubleWidthCharacter(actualCodePoint, ambiguousCharsAreDoubleWidth)

    // Check for DWC at col+1, OR DWC at col+2 when col+1 is variation selector
    // For emoji+VS like ⚠️: Buffer = [⚠][FE0F][DWC] - DWC is at col+2
    val hasVariationSelectorAtCol1 = charAtCol1 != null && UnicodeConstants.isVariationSelector(charAtCol1)
    val isWcwidthDoubleWidth = charAtCol1 == CharUtils.DWC ||
        (hasVariationSelectorAtCol1 && charAtCol2 == CharUtils.DWC) ||
        wcwidthResult

    val isBaseDoubleWidth = if (actualCodePoint >= UnicodeConstants.ENCLOSED_ALPHANUMERIC_SUPPLEMENT_RANGE.first) true else isWcwidthDoubleWidth

    // Check for variation selector - handle both DWC and non-DWC cases
    val nextCharOffset = if (isWcwidthDoubleWidth) 2 else 1
    val nextChar = if (col + nextCharOffset < width) line.charAt(col + nextCharOffset) else null
    val hasVariationSelector = (nextChar != null && UnicodeConstants.isVariationSelector(nextChar)) ||
        hasVariationSelectorAtCol1
    val isEmojiWithVariationSelector = hasVariationSelector

    val isDoubleWidth = isBaseDoubleWidth || isEmojiWithVariationSelector
    val visualWidth = if (isDoubleWidth) 2 else 1

    // Character classification for font selection
    val isCursiveOrMath = actualCodePoint in 0x1D400..0x1D7FF
    val isTechnicalSymbol = actualCodePoint in 0x23E9..0x23FF
    // Use shared emoji detection to ensure renderer is consistent with buffer DWC markers
    val isEmojiOrWideSymbol = ai.rever.bossterm.terminal.util.GraphemeUtils.isEmojiPresentation(actualCodePoint)

    return CharacterAnalysis(
        actualCodePoint = actualCodePoint,
        lowSurrogate = lowSurrogate,
        charTextToRender = charTextToRender,
        isWcwidthDoubleWidth = isWcwidthDoubleWidth,
        isBaseDoubleWidth = isBaseDoubleWidth,
        hasVariationSelector = hasVariationSelector,
        isEmojiWithVariationSelector = isEmojiWithVariationSelector,
        isDoubleWidth = isDoubleWidth,
        visualWidth = visualWidth,
        isCursiveOrMath = isCursiveOrMath,
        isTechnicalSymbol = isTechnicalSymbol,
        isEmojiOrWideSymbol = isEmojiOrWideSymbol
    )
}

/**
 * Cache for CharacterAnalysis results to avoid redundant analysis between render passes.
 * Key: (row, col) pair, Value: CharacterAnalysis result
 */
private typealias AnalysisCache = MutableMap<Pair<Int, Int>, CharacterAnalysis>

/**
 * Cache key for text measurements (issue #147 - special character rendering optimization).
 * Includes all factors that affect measurement: text content, font family, size, and style.
 */
data class MeasurementCacheKey(
    val text: String,
    val fontFamilyHash: Int,
    val fontSize: Float,
    val isBold: Boolean,
    val isItalic: Boolean
)

/**
 * Cached measurement result with dimensions needed for character rendering.
 */
data class CachedMeasurement(
    val width: Float,
    val height: Float,
    val firstBaseline: Float
)

/**
 * Terminal canvas renderer that handles all drawing operations.
 * Separates rendering logic from the composable for better maintainability.
 */
object TerminalCanvasRenderer {

    /**
     * LRU cache for text measurements (issue #147 - special character rendering optimization).
     * Caches TextMeasurer results to avoid expensive measure() calls for repeated characters.
     * 256-entry capacity is sized for typical terminal usage (special chars + common glyphs).
     * Thread-safe: synchronized access for multi-window scenarios.
     */
    private val measurementCache = object : LinkedHashMap<MeasurementCacheKey, CachedMeasurement>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<MeasurementCacheKey, CachedMeasurement>): Boolean {
            return size > 256
        }
    }
    private val measurementCacheLock = Any()

    /**
     * ThreadLocal StringBuilder for emoji+variation selector concatenation.
     * Avoids allocating new strings in the hot rendering path (issue #143).
     */
    private val emojiVsBuilder = ThreadLocal.withInitial { StringBuilder(8) }

    /**
     * ThreadLocal StringBuilder for ZWJ sequence text extraction.
     * Avoids allocating new strings when checking for ZWJ sequences (issue #143).
     */
    private val zwjCheckBuilder = ThreadLocal.withInitial { StringBuilder(40) }

    /**
     * Builds emoji text with variation selector using ThreadLocal StringBuilder.
     * Avoids string concatenation allocation in hot path.
     */
    private fun buildEmojiWithVS(base: String, vs: Char): String {
        val builder = emojiVsBuilder.get()
        builder.setLength(0)
        builder.append(base)
        builder.append(vs)
        return builder.toString()
    }

    /**
     * Clear the measurement cache. Should be called when font size or font family changes.
     * Thread-safe.
     */
    fun invalidateMeasurementCache() {
        synchronized(measurementCacheLock) {
            measurementCache.clear()
        }
    }

    /**
     * Get a cached measurement or compute and cache it.
     * Thread-safe for multi-window scenarios where multiple canvases may render concurrently.
     */
    private fun getCachedMeasurement(
        ctx: RenderingContext,
        text: String,
        fontFamily: FontFamily,
        style: TextStyle
    ): CachedMeasurement {
        val key = MeasurementCacheKey(
            text = text,
            fontFamilyHash = fontFamily.hashCode(),
            fontSize = ctx.fontSize,
            isBold = style.fontWeight == FontWeight.Bold,
            isItalic = style.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic
        )

        synchronized(measurementCacheLock) {
            return measurementCache.getOrPut(key) {
                val measurement = ctx.textMeasurer.measure(text, style)
                CachedMeasurement(
                    width = measurement.size.width.toFloat(),
                    height = measurement.size.height.toFloat(),
                    firstBaseline = measurement.firstBaseline
                )
            }
        }
    }

    /**
     * Main rendering entry point. Renders the entire terminal buffer.
     * Uses a 3-pass system:
     * - Pass 1: Draw all backgrounds (and cache character analysis)
     * - Pass 2: Draw all text (reuse cached analysis)
     * - Pass 3: Draw overlays (hyperlinks, search, selection, cursor)
     *
     * @return Map of row to detected hyperlinks for mouse hover detection
     */
    fun DrawScope.renderTerminal(ctx: RenderingContext): Map<Int, List<Hyperlink>> {
        val hyperlinksCache = mutableMapOf<Int, List<Hyperlink>>()
        // Cache character analysis to avoid redundant computation between passes
        val analysisCache: AnalysisCache = mutableMapOf()

        // Pass 1: Draw backgrounds and populate analysis cache
        renderBackgrounds(ctx, analysisCache)

        // Pass 1.5: Draw selection highlight BEFORE text (so text renders on top)
        if (ctx.selectionStart != null && ctx.selectionEnd != null &&
            !(ctx.searchVisible && ctx.searchMatches.isNotEmpty())) {
            renderSelectionHighlight(ctx)
        }

        // Pass 2: Draw text and collect hyperlinks (reuse cached analysis)
        val detectedHyperlinks = renderText(ctx, analysisCache)
        hyperlinksCache.putAll(detectedHyperlinks)

        // Pass 3: Draw overlays (hyperlinks, search, cursor - but NOT selection)
        renderOverlays(ctx)

        return hyperlinksCache
    }

    /**
     * Pass 1: Render all cell backgrounds.
     * Populates the analysis cache for reuse in renderText().
     */
    private fun DrawScope.renderBackgrounds(ctx: RenderingContext, analysisCache: AnalysisCache) {
        val snapshot = ctx.bufferSnapshot

        for (row in 0 until ctx.visibleRows) {
            val lineIndex = row - ctx.scrollOffset
            val line = snapshot.getLine(lineIndex)

            var col = 0
            var visualCol = 0  // Track visual position separately from buffer position
            while (col < ctx.visibleCols) {
                val char = line.charAt(col)
                val style = line.getStyleAt(col)

                // Special handling for ZWJ: skip all characters until DWC
                // ZWJ sequences like 👨‍💻 are: [emoji1][ZWJ][emoji2][DWC]
                // We already rendered emoji1, now skip ZWJ and everything after until DWC
                if (char.code == UnicodeConstants.ZWJ) {
                    col++
                    while (col < ctx.visibleCols && line.charAt(col) != CharUtils.DWC) {
                        col++
                    }
                    continue
                }

                // Use shared skip logic for simple cases (DWC, variation selectors,
                // low surrogates, skin tones, gender symbols after ZWJ)
                val skipResult = ColumnConversionUtils.shouldSkipChar(line, col, ctx.visibleCols)
                if (skipResult.shouldSkip) {
                    col += skipResult.colsToAdvance
                    continue
                }

                // Handle Regional Indicator sequences (flag emoji) as a single unit
                // Flags like 🇺🇸 are two Regional Indicators that should render as one 2-cell glyph
                val flagColCount = checkRegionalIndicatorSequence(line, col, ctx.visibleCols)
                if (flagColCount > 0) {
                    val x = kotlin.math.floor(visualCol * ctx.cellWidth)
                    val y = kotlin.math.floor(row * ctx.cellHeight)

                    // Get attributes for background
                    val isInverse = style?.hasOption(BossTextStyle.Option.INVERSE) ?: false
                    val baseFg = style?.foreground?.let { ColorUtils.convertTerminalColor(it) }
                        ?: ctx.settings.defaultForegroundColor
                    val baseBg = style?.background?.let { ColorUtils.convertTerminalColor(it) }
                        ?: ctx.settings.defaultBackgroundColor
                    val bgColor = if (isInverse) baseFg else baseBg

                    // Draw 2-cell background for the flag
                    if (bgColor != ctx.settings.defaultBackgroundColor) {
                        val nextVisualCol = visualCol + 2
                        val nextX = kotlin.math.ceil(nextVisualCol * ctx.cellWidth)
                        val bgWidth = nextX - x
                        val nextRow = row + 1
                        val nextY = kotlin.math.ceil(nextRow * ctx.cellHeight)
                        val bgHeight = if (ctx.settings.fillBackgroundInLineSpacing) {
                            nextY - y
                        } else {
                            ctx.baseCellHeight
                        }
                        drawRect(
                            color = bgColor,
                            topLeft = Offset(x.toFloat(), y.toFloat()),
                            size = Size(bgWidth.toFloat(), bgHeight.toFloat())
                        )
                    }

                    // Skip all chars in the flag sequence using the exact count returned
                    col += flagColCount
                    visualCol += 2
                    continue
                }

                // Round to pixel boundaries to avoid anti-aliasing artifacts
                // Use visualCol for x position to match renderText
                val x = kotlin.math.floor(visualCol * ctx.cellWidth)
                val y = kotlin.math.floor(row * ctx.cellHeight)

                // Use shared character analysis helper and cache the result
                val analysis = analyzeCharacter(char, line, col, ctx.visibleCols, ctx.ambiguousCharsAreDoubleWidth)
                analysisCache[lineIndex to col] = analysis

                // Get attributes
                val isInverse = style?.hasOption(BossTextStyle.Option.INVERSE) ?: false

                // Apply defaults FIRST, then swap if INVERSE
                val baseFg = style?.foreground?.let { ColorUtils.convertTerminalColor(it) }
                    ?: ctx.settings.defaultForegroundColor
                val baseBg = style?.background?.let { ColorUtils.convertTerminalColor(it) }
                    ?: ctx.settings.defaultBackgroundColor

                // THEN swap if INVERSE attribute is set
                val bgColor = if (isInverse) baseFg else baseBg

                // Skip drawing if background matches default (canvas already has default bg)
                if (bgColor != ctx.settings.defaultBackgroundColor) {
                    // Calculate background dimensions using visual positions
                    val nextVisualCol = visualCol + analysis.visualWidth
                    val nextX = kotlin.math.ceil(nextVisualCol * ctx.cellWidth)
                    val bgWidth = nextX - x
                    val nextRow = row + 1
                    val nextY = kotlin.math.ceil(nextRow * ctx.cellHeight)
                    val bgHeight = if (ctx.settings.fillBackgroundInLineSpacing) {
                        nextY - y
                    } else {
                        ctx.baseCellHeight
                    }
                    drawRect(
                        color = bgColor,
                        topLeft = Offset(x.toFloat(), y.toFloat()),
                        size = Size(bgWidth.toFloat(), bgHeight.toFloat())
                    )
                }

                // Advance buffer position (must match renderText col advancement)
                col++
                if (analysis.isWcwidthDoubleWidth) col++  // Skip DWC marker
                if (analysis.isEmojiWithVariationSelector) col++  // Skip variation selector
                if (analysis.lowSurrogate != null) col++  // Skip low surrogate

                // Advance visual position
                visualCol += analysis.visualWidth
            }
        }
    }

    /**
     * Detect all hyperlinks in the visible buffer area.
     * This method can be called independently for caching purposes.
     *
     * Returns a map of row index to list of hyperlinks on that row.
     * Multi-row hyperlinks are added to ALL rows they span.
     *
     * @param ctx The rendering context containing buffer snapshot and scroll state
     * @return Map of row to detected hyperlinks
     */
    fun detectAllHyperlinks(ctx: RenderingContext): Map<Int, List<Hyperlink>> {
        val snapshot = ctx.bufferSnapshot
        val hyperlinksCache = mutableMapOf<Int, MutableList<Hyperlink>>()

        for (row in 0 until ctx.visibleRows) {
            val lineIndex = row - ctx.scrollOffset
            val line = snapshot.getLine(lineIndex)

            // Detect hyperlinks - handle wrapped lines specially
            // isWrapped semantics: true means "this line wraps INTO the next line"
            // Check if this row is a continuation of a wrapped line (previous line was wrapped)
            val prevLineIndex = lineIndex - 1
            val isPreviousLineWrapped = if (prevLineIndex >= -snapshot.historyLinesCount) {
                snapshot.getLine(prevLineIndex).isWrapped
            } else {
                false
            }

            // Determine if we need to detect hyperlinks for this row
            // Skip continuation rows IF we already processed them (check cache)
            // This handles the case where start row is scrolled off-screen
            val shouldDetect = if (isPreviousLineWrapped) {
                // Continuation row - only detect if not already in cache
                // (start row might be off-screen and not yet processed)
                !hyperlinksCache.containsKey(row)
            } else {
                true
            }

            if (shouldDetect) {
                val isPartOfWrappedSequence = isPreviousLineWrapped || line.isWrapped
                val hyperlinks = if (isPartOfWrappedSequence) {
                    // Part of a wrapped sequence - use wrapped detection
                    // This walks backwards to find start even if off-screen
                    HyperlinkDetector.detectHyperlinksWithWrapping(
                        snapshot, row, ctx.scrollOffset, ctx.visibleCols,
                        ctx.workingDirectory, ctx.detectFilePaths, ctx.hyperlinkRegistry
                    )
                } else {
                    // Single line - use standard detection
                    HyperlinkDetector.detectHyperlinks(
                        line.text, row, ctx.workingDirectory, ctx.detectFilePaths, ctx.hyperlinkRegistry
                    )
                }

                // Populate cache for ALL rows each hyperlink spans
                // Note: The same Hyperlink object is intentionally added to multiple rows
                // to enable efficient lookup when hovering over any part of a wrapped URL
                for (hyperlink in hyperlinks) {
                    for (spanRow in hyperlink.rowSpans.keys) {
                        hyperlinksCache.getOrPut(spanRow) { mutableListOf() }.add(hyperlink)
                    }
                }
            }
        }

        return hyperlinksCache
    }

    /**
     * Pass 2: Render all text with proper font handling.
     * Reuses character analysis from the cache populated by renderBackgrounds().
     * Returns map of row to detected hyperlinks.
     */
    private fun DrawScope.renderText(ctx: RenderingContext, analysisCache: AnalysisCache): Map<Int, List<Hyperlink>> {
        val snapshot = ctx.bufferSnapshot

        // Use precomputed hyperlinks if available (version-based caching),
        // otherwise detect them fresh
        val hyperlinksCache: Map<Int, List<Hyperlink>> = ctx.precomputedHyperlinks
            ?: detectAllHyperlinks(ctx)

        for (row in 0 until ctx.visibleRows) {
            val lineIndex = row - ctx.scrollOffset
            val line = snapshot.getLine(lineIndex)

            // Text batching state
            val batchText = StringBuilder()
            var batchStartCol = 0
            var batchFgColor: Color? = null
            var batchIsBold = false
            var batchIsItalic = false
            var batchIsUnderline = false

            // Helper function to flush accumulated batch
            fun flushBatch() {
                if (batchText.isNotEmpty()) {
                    val x = batchStartCol * ctx.cellWidth
                    val y = row * ctx.cellHeight

                    val textStyle = TextStyle(
                        color = batchFgColor ?: ctx.settings.defaultForegroundColor,
                        fontFamily = ctx.measurementFontFamily,
                        fontSize = ctx.fontSize.sp,
                        fontWeight = if (batchIsBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (batchIsItalic) androidx.compose.ui.text.font.FontStyle.Italic
                            else androidx.compose.ui.text.font.FontStyle.Normal
                    )

                    drawText(
                        textMeasurer = ctx.textMeasurer,
                        text = batchText.toString(),
                        topLeft = Offset(x, y),
                        style = textStyle
                    )

                    // Draw underline for entire batch if needed
                    if (batchIsUnderline) {
                        val underlineY = y + ctx.cellHeight - 2f
                        val underlineWidth = batchText.length * ctx.cellWidth
                        drawLine(
                            color = batchFgColor ?: Color.White,
                            start = Offset(x, underlineY),
                            end = Offset(x + underlineWidth, underlineY),
                            strokeWidth = 1f
                        )
                    }

                    batchText.clear()
                }
            }

            var col = 0
            var visualCol = 0

            while (col < ctx.visibleCols) {
                // Check for image cell first (cell-based image rendering)
                val imageCell = line.getImageCellAt(col)
                if (imageCell != null) {
                    // Flush any pending text batch before rendering image cell
                    flushBatch()

                    // Render this cell's portion of the image
                    val image = ctx.imageDataCache?.getImage(imageCell.imageId)
                    if (image != null) {
                        val bitmap = ImageRenderer.getOrDecodeImage(image)
                        if (bitmap != null) {
                            // Calculate source region - use exact boundaries to avoid gaps
                            val srcX1 = imageCell.cellX * bitmap.width / imageCell.totalCellsX
                            val srcX2 = (imageCell.cellX + 1) * bitmap.width / imageCell.totalCellsX
                            val srcY1 = imageCell.cellY * bitmap.height / imageCell.totalCellsY
                            val srcY2 = (imageCell.cellY + 1) * bitmap.height / imageCell.totalCellsY
                            val srcX = srcX1
                            val srcY = srcY1
                            val srcW = (srcX2 - srcX1).coerceAtLeast(1)
                            val srcH = (srcY2 - srcY1).coerceAtLeast(1)

                            // Destination - use exact boundaries to avoid gaps
                            val dstX1 = (visualCol * ctx.cellWidth).toInt()
                            val dstX2 = ((visualCol + 1) * ctx.cellWidth).toInt()
                            val dstY1 = (row * ctx.cellHeight).toInt()
                            val dstY2 = ((row + 1) * ctx.cellHeight).toInt()
                            val dstX = dstX1
                            val dstY = dstY1
                            val dstW = (dstX2 - dstX1).coerceAtLeast(1)
                            val dstH = (dstY2 - dstY1).coerceAtLeast(1)

                            // Draw the portion of the image for this cell
                            drawImage(
                                image = bitmap,
                                srcOffset = androidx.compose.ui.unit.IntOffset(srcX, srcY),
                                srcSize = androidx.compose.ui.unit.IntSize(srcW, srcH),
                                dstOffset = androidx.compose.ui.unit.IntOffset(dstX, dstY),
                                dstSize = androidx.compose.ui.unit.IntSize(dstW, dstH)
                            )
                        }
                    }

                    col++
                    visualCol++
                    continue
                }

                val char = line.charAt(col)
                val style = line.getStyleAt(col)

                // Skip DWC markers
                if (char == CharUtils.DWC) {
                    col++
                    continue
                }

                // Check for ZWJ sequences using ThreadLocal builder (issue #143 optimization)
                val builder = zwjCheckBuilder.get()
                builder.setLength(0)
                run {
                    var i = col
                    var count = 0
                    while (i < snapshot.width && count < 20) {
                        val c = line.charAt(i)
                        if (c != CharUtils.DWC) {
                            builder.append(c)
                            count++
                        }
                        i++
                    }
                }
                val cleanText = builder.toString()

                // Use fast-path detection functions (issue #143 optimization)
                val hasZWJ = GraphemeUtils.containsZWJ(cleanText)
                val hasSkinTone = checkFollowingSkinTone(line, col, snapshot.width)
                val hasRegionalIndicator = checkRegionalIndicatorSequence(line, col, snapshot.width) > 0

                if (hasZWJ || hasSkinTone || hasRegionalIndicator) {
                    val graphemes = GraphemeUtils.segmentIntoGraphemes(cleanText)
                    if (graphemes.isNotEmpty()) {
                        val grapheme = graphemes[0]
                        if (grapheme.hasZWJ || hasSkinTone || hasRegionalIndicator) {
                            flushBatch()
                            val (colsSkipped, visualWidth) = renderZWJSequence(
                                ctx, row, visualCol, col, grapheme, line, snapshot.width, style
                            )
                            col += colsSkipped
                            visualCol += visualWidth
                            continue
                        }
                    }
                }

                val x = visualCol * ctx.cellWidth
                val y = row * ctx.cellHeight
                val lineIndex = row - ctx.scrollOffset

                // Use cached analysis from renderBackgrounds, or compute if not found
                val analysis = analysisCache[lineIndex to col]
                    ?: analyzeCharacter(char, line, col, snapshot.width, ctx.ambiguousCharsAreDoubleWidth)

                // Get nextChar for rendering emoji with variation selectors
                val nextCharOffset = if (analysis.isWcwidthDoubleWidth) 2 else 1
                val nextChar = if (col + nextCharOffset < snapshot.width) line.charAt(col + nextCharOffset) else null

                // Skip standalone variation selectors
                if (UnicodeConstants.isVariationSelector(char) && !analysis.isEmojiOrWideSymbol) {
                    col++
                    continue
                }

                // Get text attributes
                val isBold = style?.hasOption(BossTextStyle.Option.BOLD) ?: false
                val isItalic = style?.hasOption(BossTextStyle.Option.ITALIC) ?: false
                val isInverse = style?.hasOption(BossTextStyle.Option.INVERSE) ?: false
                val isDim = style?.hasOption(BossTextStyle.Option.DIM) ?: false
                val isUnderline = style?.hasOption(BossTextStyle.Option.UNDERLINED) ?: false
                val isHidden = style?.hasOption(BossTextStyle.Option.HIDDEN) ?: false
                val isSlowBlink = style?.hasOption(BossTextStyle.Option.SLOW_BLINK) ?: false
                val isRapidBlink = style?.hasOption(BossTextStyle.Option.RAPID_BLINK) ?: false

                // Calculate colors
                val baseFg = style?.foreground?.let { ColorUtils.convertTerminalColor(it) }
                    ?: ctx.settings.defaultForegroundColor
                val baseBg = style?.background?.let { ColorUtils.convertTerminalColor(it) }
                    ?: ctx.settings.defaultBackgroundColor
                var fgColor = if (isInverse) baseBg else baseFg
                if (isDim) fgColor = ColorUtils.applyDimColor(fgColor)

                val isBlinkVisible = when {
                    isSlowBlink -> ctx.slowBlinkVisible
                    isRapidBlink -> ctx.rapidBlinkVisible
                    else -> true
                }

                val canBatch = !analysis.isDoubleWidth && !analysis.isEmojiOrWideSymbol && !analysis.isCursiveOrMath && !analysis.isTechnicalSymbol &&
                    !isHidden && isBlinkVisible && char != ' ' && char != '\u0000'

                val styleMatches = batchText.isNotEmpty() &&
                    batchFgColor == fgColor &&
                    batchIsBold == isBold &&
                    batchIsItalic == isItalic &&
                    batchIsUnderline == isUnderline

                if (canBatch && (batchText.isEmpty() || styleMatches)) {
                    if (batchText.isEmpty()) {
                        batchStartCol = visualCol
                        batchFgColor = fgColor
                        batchIsBold = isBold
                        batchIsItalic = isItalic
                        batchIsUnderline = isUnderline
                    }
                    batchText.append(char)
                } else {
                    flushBatch()

                    if (char != ' ' && char != '\u0000' && !isHidden && isBlinkVisible) {
                        renderCharacter(
                            ctx, x, y, analysis.charTextToRender, analysis.actualCodePoint,
                            analysis.isDoubleWidth, analysis.isEmojiOrWideSymbol, analysis.isEmojiWithVariationSelector,
                            analysis.isCursiveOrMath, analysis.isTechnicalSymbol, nextChar,
                            fgColor, isBold, isItalic, isUnderline
                        )

                        if (analysis.isEmojiWithVariationSelector) {
                            col++
                        }
                    }
                }

                if (analysis.isWcwidthDoubleWidth) col++
                col++
                if (analysis.lowSurrogate != null) col++
                visualCol++
                if (analysis.isDoubleWidth) visualCol++
            }

            flushBatch()
        }

        return hyperlinksCache
    }

    /**
     * Pass 3: Render overlays (hyperlinks, search, selection, cursor).
     */
    private fun DrawScope.renderOverlays(ctx: RenderingContext) {
        val snapshot = ctx.bufferSnapshot

        // Hyperlink underline - supports multi-row hyperlinks
        if (ctx.settings.hyperlinkUnderlineOnHover && ctx.hoveredHyperlink != null && ctx.isModifierPressed) {
            val link = ctx.hoveredHyperlink
            // Draw underline for each row the hyperlink spans
            for ((spanRow, span) in link.rowSpans) {
                if (spanRow in 0 until ctx.visibleRows) {
                    val y = spanRow * ctx.cellHeight
                    val underlineY = y + ctx.cellHeight - 1f
                    val startX = span.first * ctx.cellWidth
                    val endX = span.second * ctx.cellWidth
                    drawLine(
                        color = ctx.settings.hyperlinkColorValue,
                        start = Offset(startX, underlineY),
                        end = Offset(endX, underlineY),
                        strokeWidth = 1f
                    )
                }
            }
        }

        // Search match highlights
        if (ctx.searchVisible && ctx.searchMatches.isNotEmpty()) {
            val matchLength = ctx.searchQuery.length
            ctx.searchMatches.forEachIndexed { index, (matchCol, matchRow) ->
                val screenRow = matchRow + ctx.scrollOffset
                if (screenRow in 0 until ctx.visibleRows) {
                    val matchColor = if (index == ctx.currentMatchIndex) {
                        ctx.settings.currentSearchMarkerColorValue.copy(alpha = 0.6f)
                    } else {
                        ctx.settings.searchMarkerColorValue.copy(alpha = 0.4f)
                    }

                    for (charOffset in 0 until matchLength) {
                        val col = matchCol + charOffset
                        if (col in 0 until snapshot.width) {
                            val x = col * ctx.cellWidth
                            val y = screenRow * ctx.cellHeight
                            // Calculate size as difference to next cell to avoid floating-point gaps
                            val w = (col + 1) * ctx.cellWidth - x
                            val h = (screenRow + 1) * ctx.cellHeight - y
                            drawRect(
                                color = matchColor,
                                topLeft = Offset(x, y),
                                size = Size(w, h)
                            )
                        }
                    }
                }
            }
        }

        // Selection highlight is now rendered in Pass 1.5 (before text) so text is visible on top

        // Cursor
        if (ctx.cursorVisible) {
            renderCursor(ctx)
        }
    }

    /**
     * Render selection highlight rectangles.
     * Selection coordinates are in buffer columns but we render using visual columns.
     *
     * Note: Selection automatically snaps to grapheme boundaries - partial grapheme
     * selection expands to include the entire grapheme. This is intentional behavior
     * to ensure emoji, ZWJ sequences, and other multi-codepoint graphemes are
     * always selected as complete units.
     */
    private fun DrawScope.renderSelectionHighlight(ctx: RenderingContext) {
        val start = ctx.selectionStart ?: return
        val end = ctx.selectionEnd ?: return
        val snapshot = ctx.bufferSnapshot

        val (startCol, startRow) = start
        val (endCol, endRow) = end

        val (firstCol, firstRow, lastCol, lastRow) = if (startRow <= endRow) {
            listOf(startCol, startRow, endCol, endRow)
        } else {
            listOf(endCol, endRow, startCol, startRow)
        }

        // Use selection color directly (solid color like VS Code / BossEditor)
        val highlightColor = ctx.settings.selectionColorValue

        for (bufferRow in firstRow..lastRow) {
            val screenRow = bufferRow + ctx.scrollOffset
            if (screenRow in 0 until ctx.visibleRows) {
                // Get the line for this row to convert buffer columns to visual columns
                val lineIndex = bufferRow + snapshot.historyLinesCount
                val line = if (lineIndex >= 0 && lineIndex < snapshot.height + snapshot.historyLinesCount) {
                    snapshot.getLine(lineIndex)
                } else null

                val (bufColStart, bufColEnd) = when (ctx.selectionMode) {
                    SelectionMode.BLOCK -> {
                        minOf(firstCol, lastCol) to maxOf(firstCol, lastCol)
                    }
                    SelectionMode.NORMAL -> {
                        if (firstRow == lastRow) {
                            minOf(firstCol, lastCol) to maxOf(firstCol, lastCol)
                        } else {
                            when (bufferRow) {
                                firstRow -> firstCol to (snapshot.width - 1)
                                lastRow -> 0 to lastCol
                                else -> 0 to (snapshot.width - 1)
                            }
                        }
                    }
                }

                // Convert buffer columns to visual columns for proper rendering
                val visualColStart = if (line != null) {
                    bufferColToVisualCol(line, bufColStart, snapshot.width)
                } else bufColStart
                val visualColEnd = if (line != null) {
                    bufferColToVisualCol(line, bufColEnd + 1, snapshot.width)
                } else bufColEnd + 1

                // Draw a single rectangle for the entire selection range on this row
                if (visualColStart < visualColEnd) {
                    val x = visualColStart * ctx.cellWidth
                    val y = screenRow * ctx.cellHeight
                    val w = visualColEnd * ctx.cellWidth - x
                    val h = (screenRow + 1) * ctx.cellHeight - y
                    drawRect(
                        color = highlightColor,
                        topLeft = Offset(x, y),
                        size = Size(w, h)
                    )
                }
            }
        }
    }

    /**
     * Convert buffer column to visual column.
     * Delegates to shared ColumnConversionUtils.
     */
    fun bufferColToVisualCol(line: TerminalLine, bufferCol: Int, width: Int): Int =
        ColumnConversionUtils.bufferColToVisualCol(line, bufferCol, width)

    /**
     * Convert visual column to buffer column.
     * Delegates to shared ColumnConversionUtils.
     */
    fun visualColToBufferCol(line: TerminalLine, visualCol: Int, width: Int): Int =
        ColumnConversionUtils.visualColToBufferCol(line, visualCol, width)

    /**
     * Find the buffer column range for a grapheme at the given buffer column.
     * Returns (startCol, endCol) where endCol is inclusive.
     */
    fun findGraphemeBounds(line: TerminalLine, bufferCol: Int, width: Int): Pair<Int, Int> {
        // Find the start of the grapheme (scan backwards for non-extender)
        var startCol = bufferCol
        while (startCol > 0) {
            val char = line.charAt(startCol)
            // If this is a base character (not DWC, not extender), we found the start
            if (char != CharUtils.DWC &&
                !UnicodeConstants.isVariationSelector(char) &&
                char.code != UnicodeConstants.ZWJ &&
                !Character.isLowSurrogate(char)) {
                // Check if it's a skin tone modifier
                if (Character.isHighSurrogate(char) && startCol + 1 < width) {
                    val next = line.charAt(startCol + 1)
                    if (Character.isLowSurrogate(next)) {
                        val cp = Character.toCodePoint(char, next)
                        if (UnicodeConstants.isSkinToneModifier(cp)) {
                            startCol--
                            continue
                        }
                    }
                }
                // Check if preceded by ZWJ (part of sequence)
                if (startCol > 0 && line.charAt(startCol - 1).code == UnicodeConstants.ZWJ) {
                    startCol--
                    continue
                }
                break
            }
            startCol--
        }

        // Find the end of the grapheme (scan forward for extenders and DWC)
        var endCol = startCol
        while (endCol + 1 < width) {
            val nextChar = line.charAt(endCol + 1)
            // Continue if next is DWC, variation selector, ZWJ, low surrogate, or skin tone
            if (nextChar == CharUtils.DWC ||
                UnicodeConstants.isVariationSelector(nextChar) ||
                nextChar.code == UnicodeConstants.ZWJ ||
                Character.isLowSurrogate(nextChar)) {
                endCol++
                continue
            }
            // Check for skin tone modifier (surrogate pair)
            if (Character.isHighSurrogate(nextChar) && endCol + 2 < width) {
                val afterNext = line.charAt(endCol + 2)
                if (Character.isLowSurrogate(afterNext)) {
                    val cp = Character.toCodePoint(nextChar, afterNext)
                    if (UnicodeConstants.isSkinToneModifier(cp)) {
                        endCol += 2
                        continue
                    }
                }
            }
            // Check for gender symbol after ZWJ
            if (line.charAt(endCol).code == UnicodeConstants.ZWJ &&
                UnicodeConstants.isGenderSymbol(nextChar.code)) {
                endCol++
                continue
            }
            break
        }

        return Pair(startCol, endCol)
    }

    /**
     * Render the terminal cursor.
     * Cursor is rendered at its buffer position - when scrolled into history,
     * cursor will be below the visible area and won't be rendered.
     */
    private fun DrawScope.renderCursor(ctx: RenderingContext) {
        // cursorY is 1-indexed in the screen buffer, adjust to 0-indexed
        val bufferCursorY = (ctx.cursorY - 1).coerceAtLeast(0)
        // Convert buffer position to screen position by adding scrollOffset
        // scrollOffset=0 means viewing current screen, scrollOffset>0 means scrolled into history
        // When scrolled up, cursor (at bottom of buffer) will be below visible area
        val cursorScreenRow = bufferCursorY + ctx.scrollOffset

        // Don't render cursor if outside visible area
        if (cursorScreenRow < 0 || cursorScreenRow >= ctx.visibleRows) {
            return
        }

        val shouldShowCursor = when (ctx.cursorShape) {
            CursorShape.BLINK_BLOCK, CursorShape.BLINK_UNDERLINE, CursorShape.BLINK_VERTICAL_BAR -> ctx.cursorBlinkVisible
            else -> true
        }

        if (!shouldShowCursor) return

        val x = ctx.cursorX * ctx.cellWidth
        val y = cursorScreenRow * ctx.cellHeight
        // Calculate size as difference to next cell to avoid floating-point gaps
        val w = (ctx.cursorX + 1) * ctx.cellWidth - x
        val h = (cursorScreenRow + 1) * ctx.cellHeight - y
        val cursorAlpha = if (ctx.isFocused) 0.7f else 0.3f
        val cursorColor = (ctx.cursorColor ?: Color.White).copy(alpha = cursorAlpha)

        when (ctx.cursorShape) {
            CursorShape.BLINK_BLOCK, CursorShape.STEADY_BLOCK, null -> {
                drawRect(
                    color = cursorColor,
                    topLeft = Offset(x, y),
                    size = Size(w, h)
                )
            }
            CursorShape.BLINK_UNDERLINE, CursorShape.STEADY_UNDERLINE -> {
                val underlineHeight = h * 0.2f
                drawRect(
                    color = cursorColor,
                    topLeft = Offset(x, y + h - underlineHeight),
                    size = Size(w, underlineHeight)
                )
            }
            CursorShape.BLINK_VERTICAL_BAR, CursorShape.STEADY_VERTICAL_BAR -> {
                val barWidth = w * 0.15f
                drawRect(
                    color = cursorColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, h)
                )
            }
        }
    }

    /**
     * Check if current character is followed by skin tone modifier.
     */
    private fun checkFollowingSkinTone(line: TerminalLine, col: Int, width: Int): Boolean {
        var checkCol = col
        val currentChar = line.charAt(checkCol)

        if (Character.isHighSurrogate(currentChar)) {
            checkCol++
        }
        checkCol++

        if (checkCol < width && line.charAt(checkCol) == CharUtils.DWC) {
            checkCol++
        }

        if (checkCol < width - 1) {
            val c1 = line.charAt(checkCol)
            // Skin tones U+1F3FB-U+1F3FF use same high surrogate (0xD83C) as Regional Indicators
            if (c1 == '\uD83C' && checkCol + 1 < width) {
                val c2 = line.charAt(checkCol + 1)
                // Skin tone low surrogates: 0xDFFB..0xDFFF
                if (c2.code in 0xDFFB..0xDFFF) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Check if current position starts a Regional Indicator sequence (flag emoji).
     * Regional Indicators are surrogate pairs with high surrogate 0xD83C and low surrogate 0xDDE6-0xDDFF.
     * Two consecutive Regional Indicators form a flag (e.g., 🇺🇸 = U+1F1FA + U+1F1F8).
     *
     * @return Number of buffer columns the flag occupies (0 if not a flag sequence)
     *         Possible layouts:
     *         - [High1][Low1][High2][Low2] = 4 chars
     *         - [High1][Low1][DWC][High2][Low2] = 5 chars (DWC after first indicator)
     *         - [High1][Low1][DWC][High2][Low2][DWC] = 6 chars (DWC after both)
     */
    private fun checkRegionalIndicatorSequence(line: TerminalLine, col: Int, width: Int): Int {
        if (col + 3 >= width) return 0  // Need at least 4 chars for 2 surrogate pairs

        val c1 = line.charAt(col)
        val c2 = line.charAt(col + 1)

        // Check if first char is high surrogate for Regional Indicator
        // and second char is low surrogate in Regional Indicator range
        if (UnicodeConstants.isRegionalIndicatorHighSurrogate(c1) &&
            UnicodeConstants.isRegionalIndicatorLowSurrogate(c2.code)) {
            // Check for second Regional Indicator (may have DWC between them)
            var nextCol = col + 2
            if (nextCol < width && line.charAt(nextCol) == CharUtils.DWC) {
                nextCol++
            }
            if (nextCol + 1 < width) {
                val c3 = line.charAt(nextCol)
                val c4 = line.charAt(nextCol + 1)
                if (UnicodeConstants.isRegionalIndicatorHighSurrogate(c3) &&
                    UnicodeConstants.isRegionalIndicatorLowSurrogate(c4.code)) {
                    // Calculate total columns: position after second indicator's low surrogate
                    var endCol = nextCol + 2
                    // Skip trailing DWC if present
                    if (endCol < width && line.charAt(endCol) == CharUtils.DWC) {
                        endCol++
                    }
                    return endCol - col
                }
            }
        }
        return 0
    }

    /**
     * Render a ZWJ sequence (emoji family, skin tones, etc.).
     * Returns (columns skipped in buffer, visual width consumed).
     */
    private fun DrawScope.renderZWJSequence(
        ctx: RenderingContext,
        row: Int,
        visualCol: Int,
        col: Int,
        grapheme: ai.rever.bossterm.terminal.util.GraphemeCluster,
        line: TerminalLine,
        width: Int,
        style: BossTextStyle?
    ): Pair<Int, Int> {
        val x = visualCol * ctx.cellWidth
        val y = row * ctx.cellHeight

        val isBold = style?.hasOption(BossTextStyle.Option.BOLD) ?: false
        val isItalic = style?.hasOption(BossTextStyle.Option.ITALIC) ?: false
        val isInverse = style?.hasOption(BossTextStyle.Option.INVERSE) ?: false
        val isDim = style?.hasOption(BossTextStyle.Option.DIM) ?: false

        val baseFg = style?.foreground?.let { ColorUtils.convertTerminalColor(it) }
            ?: ctx.settings.defaultForegroundColor
        val baseBg = style?.background?.let { ColorUtils.convertTerminalColor(it) }
            ?: ctx.settings.defaultBackgroundColor
        var fgColor = if (isInverse) baseBg else baseFg
        if (isDim) fgColor = ColorUtils.applyDimColor(fgColor)

        val textStyle = TextStyle(
            color = fgColor,
            fontFamily = FontFamily.Default,
            fontSize = ctx.fontSize.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (isItalic) androidx.compose.ui.text.font.FontStyle.Italic
                else androidx.compose.ui.text.font.FontStyle.Normal
        )

        val measurement = ctx.textMeasurer.measure(grapheme.text, textStyle)
        val glyphWidth = measurement.size.width.toFloat()
        val glyphHeight = measurement.size.height.toFloat()

        val allocatedWidth = ctx.cellWidth * grapheme.visualWidth.toFloat()
        val targetHeight = ctx.cellHeight * 1.0f

        val widthScale = if (glyphWidth > 0) allocatedWidth / glyphWidth else 1.0f
        val heightScale = if (glyphHeight > 0) targetHeight / glyphHeight else 1.0f
        val scaleValue = minOf(widthScale, heightScale).coerceIn(0.8f, 2.5f)

        val scaledWidth = glyphWidth * scaleValue
        val scaledHeight = glyphHeight * scaleValue
        val centerX = x + (allocatedWidth - scaledWidth) / 2f

        scale(scaleX = scaleValue, scaleY = scaleValue, pivot = Offset(x, y + ctx.cellHeight / 2f)) {
            drawText(
                textMeasurer = ctx.textMeasurer,
                text = grapheme.text,
                topLeft = Offset(x + (centerX - x) / scaleValue, y),
                style = textStyle
            )
        }

        // Count buffer cells consumed by this grapheme
        var charsToSkip = 0
        var i = col
        var graphemeCharIndex = 0
        val graphemeText = grapheme.text

        while (i < width && graphemeCharIndex < graphemeText.length) {
            val c = line.charAt(i)

            if (c == CharUtils.DWC) {
                charsToSkip++
                i++
                continue
            }

            val expectedChar = graphemeText[graphemeCharIndex]

            if (Character.isHighSurrogate(c) && i + 1 < width) {
                val next = line.charAt(i + 1)
                if (Character.isLowSurrogate(next)) {
                    if (graphemeCharIndex + 1 < graphemeText.length &&
                        graphemeText[graphemeCharIndex] == c &&
                        graphemeText[graphemeCharIndex + 1] == next) {
                        charsToSkip += 2
                        i += 2
                        graphemeCharIndex += 2
                        continue
                    }
                }
            }

            if (expectedChar == c) {
                charsToSkip++
                i++
                graphemeCharIndex++
            } else {
                break
            }
        }

        // Skip trailing DWC markers
        while (i < width && line.charAt(i) == CharUtils.DWC) {
            charsToSkip++
            i++
        }

        return Pair(charsToSkip, grapheme.visualWidth)
    }

    /**
     * Render a single character with appropriate font and styling.
     */
    private fun DrawScope.renderCharacter(
        ctx: RenderingContext,
        x: Float,
        y: Float,
        charTextToRender: String,
        actualCodePoint: Int,
        isDoubleWidth: Boolean,
        isEmojiOrWideSymbol: Boolean,
        isEmojiWithVariationSelector: Boolean,
        isCursiveOrMath: Boolean,
        isTechnicalSymbol: Boolean,
        nextChar: Char?,
        fgColor: Color,
        isBold: Boolean,
        isItalic: Boolean,
        isUnderline: Boolean
    ) {
        val isMacOS = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
        // Default: macOS uses system font for emoji, Linux uses bundled font. Setting overrides this.
        val useSystemFontForEmoji = if (ctx.settings.preferTerminalFontForSymbols != null) {
            !ctx.settings.preferTerminalFontForSymbols!!
        } else {
            isMacOS  // Default: system font on macOS, bundled on Linux
        }
        val fontForChar = if (isEmojiWithVariationSelector) {
            // True color emoji (with variation selector) - use explicit emoji font for reliable color rendering
            // Use cached font to avoid expensive FontMgr lookup per character (issue #147)
            if (isMacOS) {
                ai.rever.bossterm.compose.util.cachedAppleColorEmojiFont ?: FontFamily.Default
            } else {
                FontFamily.Default
            }
        } else if (isEmojiOrWideSymbol) {
            // Emoji/symbols without variation selector - platform specific
            if (useSystemFontForEmoji) {
                FontFamily.Default
            } else {
                ai.rever.bossterm.compose.util.bundledSymbolFont
            }
        } else if (isTechnicalSymbol || isCursiveOrMath) {
            // Technical symbols (⏸ ⏵) and math - platform specific like other symbols
            // Use cached font to avoid expensive FontMgr lookup per character (issue #147)
            if (useSystemFontForEmoji) {
                // macOS default or user chose system: use STIX Two Math → terminal font
                ai.rever.bossterm.compose.util.cachedSTIXMathFont ?: ctx.measurementFontFamily
            } else {
                // Linux default or user chose bundled: use bundled symbol font
                ai.rever.bossterm.compose.util.bundledSymbolFont
            }
        } else {
            ctx.measurementFontFamily
        }

        val textStyle = TextStyle(
            color = fgColor,
            fontFamily = fontForChar,
            fontSize = ctx.fontSize.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (isItalic) androidx.compose.ui.text.font.FontStyle.Italic
                else androidx.compose.ui.text.font.FontStyle.Normal
        )

        if (isDoubleWidth) {
            // Include variation selector for emoji presentation (⚠️ needs FE0F to render as color emoji)
            // Uses ThreadLocal StringBuilder to avoid allocation (issue #143)
            val textToRender = if (isEmojiWithVariationSelector && nextChar != null &&
                UnicodeConstants.isVariationSelector(nextChar)) {
                buildEmojiWithVS(charTextToRender, nextChar)
            } else {
                charTextToRender
            }
            // Use cached measurement to avoid expensive measure() calls (issue #147)
            val cached = getCachedMeasurement(ctx, textToRender, fontForChar, textStyle)
            val glyphWidth = cached.width
            val allocatedWidth = ctx.cellWidth * 2

            if (glyphWidth < ctx.cellWidth * 1.5f) {
                val scaleX = allocatedWidth / glyphWidth.coerceAtLeast(1f)
                scale(scaleX = scaleX, scaleY = 1f, pivot = Offset(x, y + ctx.cellWidth)) {
                    drawText(
                        textMeasurer = ctx.textMeasurer,
                        text = textToRender,
                        topLeft = Offset(x, y),
                        style = textStyle
                    )
                }
            } else {
                val emptySpace = (allocatedWidth - glyphWidth).coerceAtLeast(0f)
                val centeringOffset = emptySpace / 2f
                drawText(
                    textMeasurer = ctx.textMeasurer,
                    text = textToRender,
                    topLeft = Offset(x + centeringOffset, y),
                    style = textStyle
                )
            }
        } else if (isEmojiOrWideSymbol) {
            // Uses ThreadLocal StringBuilder to avoid allocation (issue #143)
            val textToRender = if (isEmojiWithVariationSelector && nextChar != null) {
                buildEmojiWithVS(charTextToRender, nextChar)
            } else {
                charTextToRender
            }

            // Use cached measurement to avoid expensive measure() calls (issue #147)
            val cached = getCachedMeasurement(ctx, textToRender, fontForChar, textStyle)
            val glyphWidth = cached.width
            val glyphHeight = cached.height

            // Emoji span 2 cells - use same approach as ZWJ sequences
            val allocatedWidth = ctx.cellWidth * 2.0f
            val targetHeight = ctx.cellHeight * 1.0f

            val widthScale = if (glyphWidth > 0) allocatedWidth / glyphWidth else 1.0f
            val heightScale = if (glyphHeight > 0) targetHeight / glyphHeight else 1.0f
            val scaleValue = minOf(widthScale, heightScale).coerceIn(0.8f, 2.5f)

            val scaledWidth = glyphWidth * scaleValue
            val centerX = x + (allocatedWidth - scaledWidth) / 2f

            scale(scaleX = scaleValue, scaleY = scaleValue, pivot = Offset(x, y + ctx.cellHeight / 2f)) {
                drawText(
                    textMeasurer = ctx.textMeasurer,
                    text = textToRender,
                    topLeft = Offset(x + (centerX - x) / scaleValue, y),
                    style = textStyle
                )
            }
        } else if (isCursiveOrMath) {
            // Use cached measurement to avoid expensive measure() calls (issue #147)
            val cached = getCachedMeasurement(ctx, charTextToRender, fontForChar, textStyle)
            val glyphWidth = cached.width
            val centeringOffset = ((ctx.cellWidth - glyphWidth) / 2f).coerceAtLeast(0f)
            drawText(
                textMeasurer = ctx.textMeasurer,
                text = charTextToRender,
                topLeft = Offset(x + centeringOffset, y),
                style = textStyle
            )
        } else if (isTechnicalSymbol) {
            // Use cached measurement to avoid expensive measure() calls (issue #147)
            val cached = getCachedMeasurement(ctx, charTextToRender, fontForChar, textStyle)
            val glyphWidth = cached.width
            val glyphBaseline = cached.firstBaseline
            val baselineAlignmentOffset = ctx.cellBaseline - glyphBaseline
            val centeringOffset = ((ctx.cellWidth - glyphWidth) / 2f).coerceAtLeast(0f)

            drawText(
                textMeasurer = ctx.textMeasurer,
                text = charTextToRender,
                topLeft = Offset(x + centeringOffset, y + baselineAlignmentOffset),
                style = textStyle
            )
        } else {
            drawText(
                textMeasurer = ctx.textMeasurer,
                text = charTextToRender,
                topLeft = Offset(x, y),
                style = textStyle
            )
        }

        // Draw underline
        if (isUnderline) {
            val underlineY = y + ctx.cellHeight - 2f
            val underlineWidth = if (isDoubleWidth) ctx.cellWidth * 2 else ctx.cellWidth
            drawLine(
                color = fgColor,
                start = Offset(x, underlineY),
                end = Offset(x + underlineWidth, underlineY),
                strokeWidth = 1f
            )
        }
    }
}
