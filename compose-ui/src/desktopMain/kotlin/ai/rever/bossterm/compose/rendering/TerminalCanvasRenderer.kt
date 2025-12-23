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
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.util.ColorUtils
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import ai.rever.bossterm.terminal.model.image.ImageCell
import ai.rever.bossterm.terminal.model.image.ImageDataCache
import ai.rever.bossterm.terminal.util.CharUtils
import ai.rever.bossterm.terminal.util.ColumnConversionUtils
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
    val terminalHeightCells: Int = 24
)

/**
 * Result of analyzing a character for rendering purposes.
 * Encapsulates surrogate pair handling, double-width detection, and variation selector info.
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
    val hasVariationSelectorAtCol1 = charAtCol1 != null && (charAtCol1.code == 0xFE0F || charAtCol1.code == 0xFE0E)
    val isWcwidthDoubleWidth = charAtCol1 == CharUtils.DWC ||
        (hasVariationSelectorAtCol1 && charAtCol2 == CharUtils.DWC) ||
        wcwidthResult

    val isBaseDoubleWidth = if (actualCodePoint >= 0x1F100) true else isWcwidthDoubleWidth

    // Check for variation selector - handle both DWC and non-DWC cases
    val nextCharOffset = if (isWcwidthDoubleWidth) 2 else 1
    val nextChar = if (col + nextCharOffset < width) line.charAt(col + nextCharOffset) else null
    val hasVariationSelector = (nextChar != null && (nextChar.code == 0xFE0F || nextChar.code == 0xFE0E)) ||
        hasVariationSelectorAtCol1
    val isEmojiWithVariationSelector = hasVariationSelector

    val isDoubleWidth = isBaseDoubleWidth || isEmojiWithVariationSelector
    val visualWidth = if (isDoubleWidth) 2 else 1

    // Character classification for font selection
    val isCursiveOrMath = actualCodePoint in 0x1D400..0x1D7FF
    val isTechnicalSymbol = actualCodePoint in 0x23E9..0x23FF
    // Only treat as emoji if it matches isEmojiPresentation() in GraphemeUtils
    // to ensure renderer is consistent with buffer DWC markers
    val isEmojiOrWideSymbol = when (actualCodePoint) {
        // Miscellaneous Symbols - only specific emoji ones (must match GraphemeUtils.isEmojiPresentation)
        0x2614, 0x2615 -> true // ☔☕ Umbrella, coffee
        in 0x2648..0x2653 -> true // Zodiac signs
        0x267F -> true // ♿ Wheelchair
        0x2693 -> true // ⚓ Anchor
        0x26A1 -> true // ⚡ High voltage
        0x26AA, 0x26AB -> true // ⚪⚫ Circles
        0x26BD, 0x26BE -> true // ⚽⚾ Sports balls
        0x26C4, 0x26C5 -> true // ⛄⛅ Snowman, sun/cloud
        0x26CE -> true // ⛎ Ophiuchus
        0x26D4 -> true // ⛔ No entry
        0x26EA -> true // ⛪ Church
        0x26F2, 0x26F3 -> true // ⛲⛳ Fountain, golf
        0x26F5 -> true // ⛵ Sailboat
        0x26FA -> true // ⛺ Tent
        0x26FD -> true // ⛽ Fuel pump
        // Supplementary plane emoji (always 2-cell)
        in 0x1F100..0x1F1FF -> true
        in 0x1F300..0x1F5FF -> true
        in 0x1F600..0x1F64F -> true
        in 0x1F680..0x1F6FF -> true
        in 0x1F900..0x1F9FF -> true
        in 0x1FA00..0x1FAFF -> true
        else -> false
    }

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
 * Terminal canvas renderer that handles all drawing operations.
 * Separates rendering logic from the composable for better maintainability.
 */
object TerminalCanvasRenderer {

    /**
     * Main rendering entry point. Renders the entire terminal buffer.
     * Uses a 3-pass system:
     * - Pass 1: Draw all backgrounds
     * - Pass 2: Draw all text
     * - Pass 3: Draw overlays (hyperlinks, search, selection, cursor)
     *
     * @return Map of row to detected hyperlinks for mouse hover detection
     */
    fun DrawScope.renderTerminal(ctx: RenderingContext): Map<Int, List<Hyperlink>> {
        val hyperlinksCache = mutableMapOf<Int, List<Hyperlink>>()

        // Pass 1: Draw backgrounds
        renderBackgrounds(ctx)

        // Pass 2: Draw text and collect hyperlinks
        val detectedHyperlinks = renderText(ctx)
        hyperlinksCache.putAll(detectedHyperlinks)

        // Pass 3: Draw overlays
        renderOverlays(ctx)

        return hyperlinksCache
    }

    /**
     * Pass 1: Render all cell backgrounds.
     */
    private fun DrawScope.renderBackgrounds(ctx: RenderingContext) {
        val snapshot = ctx.bufferSnapshot

        for (row in 0 until ctx.visibleRows) {
            val lineIndex = row - ctx.scrollOffset
            val line = snapshot.getLine(lineIndex)

            var col = 0
            var visualCol = 0  // Track visual position separately from buffer position
            while (col < ctx.visibleCols) {
                val char = line.charAt(col)
                val style = line.getStyleAt(col)

                // Skip DWC markers (they don't occupy visual space)
                if (char == CharUtils.DWC) {
                    col++
                    continue
                }

                // Skip standalone variation selectors (they don't occupy visual space)
                if (char.code == 0xFE0F || char.code == 0xFE0E) {
                    col++
                    continue
                }

                // Skip Zero-Width Joiner and all subsequent chars until DWC
                // ZWJ sequences like 👨‍💻 are: [emoji1][ZWJ][emoji2][DWC]
                // We already rendered emoji1, now skip ZWJ and everything after until DWC
                if (char.code == 0x200D) {
                    col++
                    // Skip all characters until we hit DWC (end of grapheme)
                    while (col < ctx.visibleCols) {
                        val nextChar = line.charAt(col)
                        if (nextChar == CharUtils.DWC) break
                        col++
                    }
                    continue
                }

                // Skip skin tone modifiers (U+1F3FB-U+1F3FF) - they extend the previous emoji
                // These are surrogate pairs: high=0xD83C, low=0xDFFB-0xDFFF
                if (Character.isHighSurrogate(char)) {
                    val nextChar = if (col + 1 < ctx.visibleCols) line.charAt(col + 1) else null
                    if (nextChar != null && Character.isLowSurrogate(nextChar)) {
                        val codePoint = Character.toCodePoint(char, nextChar)
                        // Skin tone modifiers
                        if (codePoint in 0x1F3FB..0x1F3FF) {
                            col += 2  // Skip both surrogate chars
                            continue
                        }
                        // Male/female signs used in ZWJ sequences (♀️ U+2640, ♂️ U+2642)
                        // These are BMP so handled below
                    }
                }

                // Skip gender symbols only when preceded by ZWJ (part of ZWJ sequences)
                if (char.code == 0x2640 || char.code == 0x2642) {
                    if (col > 0 && line.charAt(col - 1).code == 0x200D) {
                        col++
                        continue
                    }
                }

                // Round to pixel boundaries to avoid anti-aliasing artifacts
                // Use visualCol for x position to match renderText
                val x = kotlin.math.floor(visualCol * ctx.cellWidth)
                val y = kotlin.math.floor(row * ctx.cellHeight)

                // Use shared character analysis helper
                val analysis = analyzeCharacter(char, line, col, ctx.visibleCols, ctx.ambiguousCharsAreDoubleWidth)

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
     * Pass 2: Render all text with proper font handling.
     * Returns map of row to detected hyperlinks.
     */
    private fun DrawScope.renderText(ctx: RenderingContext): Map<Int, List<Hyperlink>> {
        val snapshot = ctx.bufferSnapshot
        val hyperlinksCache = mutableMapOf<Int, List<Hyperlink>>()

        for (row in 0 until ctx.visibleRows) {
            val lineIndex = row - ctx.scrollOffset
            val line = snapshot.getLine(lineIndex)

            // Detect hyperlinks in current line
            val hyperlinks = HyperlinkDetector.detectHyperlinks(line.text, row)
            if (hyperlinks.isNotEmpty()) {
                hyperlinksCache[row] = hyperlinks
            }

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

                // Check for ZWJ sequences
                val cleanText = buildString {
                    var i = col
                    var count = 0
                    while (i < snapshot.width && count < 20) {
                        val c = line.charAt(i)
                        if (c != CharUtils.DWC) {
                            append(c)
                            count++
                        }
                        i++
                    }
                }

                val hasZWJ = cleanText.contains('\u200D')
                val hasSkinTone = checkFollowingSkinTone(line, col, snapshot.width)

                if (hasZWJ || hasSkinTone) {
                    val graphemes = ai.rever.bossterm.terminal.util.GraphemeUtils.segmentIntoGraphemes(cleanText)
                    if (graphemes.isNotEmpty()) {
                        val grapheme = graphemes[0]
                        if (grapheme.hasZWJ || hasSkinTone) {
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

                // Use shared character analysis helper
                val analysis = analyzeCharacter(char, line, col, snapshot.width, ctx.ambiguousCharsAreDoubleWidth)

                // Get nextChar for rendering emoji with variation selectors
                val nextCharOffset = if (analysis.isWcwidthDoubleWidth) 2 else 1
                val nextChar = if (col + nextCharOffset < snapshot.width) line.charAt(col + nextCharOffset) else null

                // Skip standalone variation selectors
                if ((char.code == 0xFE0F || char.code == 0xFE0E) && !analysis.isEmojiOrWideSymbol) {
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

        // Hyperlink underline
        if (ctx.settings.hyperlinkUnderlineOnHover && ctx.hoveredHyperlink != null && ctx.isModifierPressed) {
            val link = ctx.hoveredHyperlink
            if (link.row in 0 until ctx.visibleRows) {
                val y = link.row * ctx.cellHeight
                val underlineY = y + ctx.cellHeight - 1f
                val startX = link.startCol * ctx.cellWidth
                val endX = link.endCol * ctx.cellWidth
                drawLine(
                    color = ctx.settings.hyperlinkColorValue,
                    start = Offset(startX, underlineY),
                    end = Offset(endX, underlineY),
                    strokeWidth = 1f
                )
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

        // Selection highlight
        if (ctx.selectionStart != null && ctx.selectionEnd != null &&
            !(ctx.searchVisible && ctx.searchMatches.isNotEmpty())) {
            renderSelectionHighlight(ctx)
        }

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

        val highlightColor = ctx.settings.selectionColorValue.copy(alpha = 0.3f)

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
                char.code != 0xFE0E && char.code != 0xFE0F &&
                char.code != 0x200D &&
                !Character.isLowSurrogate(char)) {
                // Check if it's a skin tone modifier
                if (Character.isHighSurrogate(char) && startCol + 1 < width) {
                    val next = line.charAt(startCol + 1)
                    if (Character.isLowSurrogate(next)) {
                        val cp = Character.toCodePoint(char, next)
                        if (cp in 0x1F3FB..0x1F3FF) {
                            startCol--
                            continue
                        }
                    }
                }
                // Check if preceded by ZWJ (part of sequence)
                if (startCol > 0 && line.charAt(startCol - 1).code == 0x200D) {
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
                nextChar.code == 0xFE0E || nextChar.code == 0xFE0F ||
                nextChar.code == 0x200D ||
                Character.isLowSurrogate(nextChar)) {
                endCol++
                continue
            }
            // Check for skin tone modifier (surrogate pair)
            if (Character.isHighSurrogate(nextChar) && endCol + 2 < width) {
                val afterNext = line.charAt(endCol + 2)
                if (Character.isLowSurrogate(afterNext)) {
                    val cp = Character.toCodePoint(nextChar, afterNext)
                    if (cp in 0x1F3FB..0x1F3FF) {
                        endCol += 2
                        continue
                    }
                }
            }
            // Check for gender symbol after ZWJ
            if (line.charAt(endCol).code == 0x200D &&
                (nextChar.code == 0x2640 || nextChar.code == 0x2642)) {
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
            if (c1 == '\uD83C' && checkCol + 1 < width) {
                val c2 = line.charAt(checkCol + 1)
                if (c2.code in 0xDFFB..0xDFFF) {
                    return true
                }
            }
        }

        return false
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
            if (isMacOS) {
                val appleColorEmoji = FontMgr.default.matchFamilyStyle("Apple Color Emoji", org.jetbrains.skia.FontStyle.NORMAL)
                if (appleColorEmoji != null) {
                    FontFamily(androidx.compose.ui.text.platform.Typeface(appleColorEmoji))
                } else {
                    FontFamily.Default
                }
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
            if (useSystemFontForEmoji) {
                // macOS default or user chose system: use STIX Two Math → terminal font
                val skiaTypeface = FontMgr.default.matchFamilyStyle("STIX Two Math", org.jetbrains.skia.FontStyle.NORMAL)
                if (skiaTypeface != null) {
                    FontFamily(androidx.compose.ui.text.platform.Typeface(skiaTypeface))
                } else {
                    ctx.measurementFontFamily
                }
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
            val textToRender = if (isEmojiWithVariationSelector && nextChar != null &&
                (nextChar.code == 0xFE0F || nextChar.code == 0xFE0E)) {
                "$charTextToRender$nextChar"
            } else {
                charTextToRender
            }
            val measurement = ctx.textMeasurer.measure(textToRender, textStyle)
            val glyphWidth = measurement.size.width.toFloat()
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
            val textToRender = if (isEmojiWithVariationSelector && nextChar != null) {
                "$charTextToRender$nextChar"
            } else {
                charTextToRender
            }

            val measurement = ctx.textMeasurer.measure(textToRender, textStyle)
            val glyphWidth = measurement.size.width.toFloat()
            val glyphHeight = measurement.size.height.toFloat()

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
            val measurement = ctx.textMeasurer.measure(charTextToRender, textStyle)
            val glyphWidth = measurement.size.width.toFloat()
            val centeringOffset = ((ctx.cellWidth - glyphWidth) / 2f).coerceAtLeast(0f)
            drawText(
                textMeasurer = ctx.textMeasurer,
                text = charTextToRender,
                topLeft = Offset(x + centeringOffset, y),
                style = textStyle
            )
        } else if (isTechnicalSymbol) {
            val measurement = ctx.textMeasurer.measure(charTextToRender, textStyle)
            val glyphWidth = measurement.size.width.toFloat()
            val glyphBaseline = measurement.firstBaseline
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
