package ai.rever.bossterm.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.rever.bossterm.compose.rendering.CursorGlyph
import ai.rever.bossterm.compose.rendering.GlyphBlink
import ai.rever.bossterm.compose.rendering.isBlinkingShape
import ai.rever.bossterm.compose.rendering.TerminalCanvasRenderer
import ai.rever.bossterm.compose.rendering.imageCellSlice
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.model.image.ImageCell
import kotlin.math.ceil

/**
 * Everything the cursor overlay draws, as ONE structurally-comparable value.
 *
 * Structural equality is the point rather than a nicety. The incremental snapshot builder reuses
 * the line instance for an unchanged row, so a frame of streamed output that does not touch the
 * caret produces an equal [CursorFrame], `mutableStateOf`'s structural equality policy drops the
 * write, and the overlay is not invalidated at all.
 *
 * Everything needing the buffer is resolved before it gets here - only the occlusion slice needs
 * `DrawScope.size`, so only that stays in the draw lambda.
 */
@Immutable
internal data class CursorFrame(
  val visible: Boolean,
  val shape: CursorShape?,
  val x: Int,
  /** 1-indexed, as the emulator reports it. */
  val y: Int,
  val scrollOffset: Int,
  val cellWidth: Float,
  val cellHeight: Float,
  val bufferWidth: Int,
  val isFocused: Boolean,
  val color: Color,
  val focusedAlpha: Float,
  val unfocusedAlpha: Float,
  val glyph: CursorGlyph?,
  val glyphColor: Color?,
  val glyphFontFamily: FontFamily?,
  val glyphFontSize: Float,
  val glyphMeasurer: TextMeasurer?,
  val imageCell: ImageCell?,
  val imageBitmap: ImageBitmap?,
)

/**
 * Identity-stable holder for everything the PAINT phase reads without going through composition.
 *
 * Two jobs, both about subscription phase:
 *
 * 1. `Canvas(modifier) { }` is `Spacer(modifier.drawBehind(onDraw))`, and `drawBehind`'s element
 *    compares the lambda instance - a new instance invalidates the draw node. Kotlin only
 *    memoizes a lambda when every capture is stable, and the overlay used to capture the buffer
 *    snapshot, the render frame, the settings, the theme and more, several unstable. So every
 *    recomposition of the parent, including one per wheel event, rebuilt it. One stable capture
 *    plus a skippable composable means the parent recomposing does not touch that layer.
 * 2. The three blink clocks live here rather than as composition locals so BOTH canvases read
 *    them in their own draw lambdas. That is the only phase where the read subscribes: the
 *    caret's [frame] is published from a `SideEffect`, which runs in the apply phase outside
 *    `observeReads`, so a clock read there would subscribe nothing and the glyph would keep
 *    painting after the text pass had blinked it away.
 */
@Stable
internal class TerminalPaintState {
  /** The caret, republished whenever anything it draws changes. */
  var cursorFrame by mutableStateOf<CursorFrame?>(null)

  /** The caret's own timer (`caretBlinkMs`), read only by the cursor overlay. */
  var caretVisible by mutableStateOf(true)

  /** SGR 5, read by the text canvas for its cells and by the overlay for the caret's glyph. */
  var slowBlinkVisible by mutableStateOf(true)

  /** SGR 6, likewise. */
  var rapidBlinkVisible by mutableStateOf(true)
}

/**
 * The caret, in its own Canvas stacked on the text canvas (same modifier chain, so coordinates
 * line up exactly).
 *
 * Both state reads happen inside the draw lambda, which subscribes the draw node and nothing
 * else - so a blink or a cursor move repaints this layer alone, and neither can be published
 * from composition into a recomposition loop.
 */
@Composable
internal fun CursorOverlay(state: TerminalPaintState) {
  Canvas(modifier = Modifier.padding(start = 4.dp, top = 4.dp).fillMaxSize().clipToBounds()) {
    val f = state.cursorFrame ?: return@Canvas
    if (size.width < f.cellWidth || size.height < f.cellHeight) return@Canvas

    // EVERY read below is a subscription, so bail on anything that means nothing will be
    // painted BEFORE taking one. `renderCursorOverlay` makes the same two decisions, but by
    // then the clock has already been read - so a hidden caret (CSI ?25l, which a full-screen
    // TUI holds for long stretches) or a caret scrolled off-screen would invalidate this layer
    // twice a period, forever, to draw nothing. `cursorFrame` is read first either way, so a
    // visibility flip still republishes and repaints.
    if (!f.visible) return@Canvas
    val screenRow = (f.y - 1).coerceAtLeast(0) + f.scrollOffset
    if (screenRow < 0 || screenRow >= ceil(size.height / f.cellHeight).toInt()) return@Canvas

    // A steady caret shape ignores the caret clock entirely (see renderCursorOverlay), and
    // reading it anyway would repaint a pixel-identical frame twice a period for the life of
    // the tab - DECSCUSR 2/4/6 is what plenty of shells set.
    val blink = if (f.shape.isBlinkingShape()) state.caretVisible else true
    // NONE first, so ordinary text keeps this layer out of the SGR clocks entirely: isVisible
    // evaluates both arguments, and an unattributed cell follows neither. Gating HERE rather
    // than at resolve time is what keeps the caret's glyph in step with the text pass, since
    // resolution runs in the apply phase where a read subscribes nothing.
    val glyph = f.glyph?.takeIf { g ->
      g.blink == GlyphBlink.NONE ||
        g.blink.isVisible(state.slowBlinkVisible, state.rapidBlinkVisible)
    }
    // If the caret sits on an inline image, that cell's alpha masks it, so animated sprite
    // pixels stay above it - matching browser compositing.
    val occlusion = if (f.imageCell != null && f.imageBitmap != null) {
      imageCellSlice(
        imageCell = f.imageCell,
        bitmap = f.imageBitmap,
        visualColumn = f.x,
        screenRow = screenRow,
        visibleColumns = (size.width / f.cellWidth).toInt().coerceAtMost(f.bufferWidth),
        cellWidth = f.cellWidth,
        cellHeight = f.cellHeight,
      )
    } else {
      null
    }

    with(TerminalCanvasRenderer) {
      renderCursorOverlay(
        cursorVisible = f.visible,
        cursorBlinkVisible = blink,
        cursorShape = f.shape,
        cursorX = f.x,
        cursorY = f.y,
        scrollOffset = f.scrollOffset,
        cellWidth = f.cellWidth,
        cellHeight = f.cellHeight,
        isFocused = f.isFocused,
        cursorColor = f.color,
        focusedAlpha = f.focusedAlpha,
        unfocusedAlpha = f.unfocusedAlpha,
        imageOcclusion = occlusion,
        cursorGlyph = glyph,
        cursorTextColor = f.glyphColor,
        glyphFontFamily = f.glyphFontFamily,
        glyphFontSize = f.glyphFontSize,
        glyphMeasurer = f.glyphMeasurer,
      )
    }
  }
}
