package ai.rever.bossterm.compose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import ai.rever.bossterm.compose.rendering.CursorGlyph
import ai.rever.bossterm.compose.rendering.GlyphBlink
import ai.rever.bossterm.terminal.CursorShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [CursorFrame] equality decides whether the overlay repaints.
 *
 * The frame is published from a `SideEffect` into `mutableStateOf`, whose default structural
 * equality policy drops a write that equals the current value. So "an output frame that does
 * not touch the caret must not invalidate the cursor layer" is exactly the predicate below -
 * which is why it can be pinned with a plain assertion instead of a UI harness.
 */
class CursorFrameEqualityTest {

    private fun frame(
        x: Int = 4,
        y: Int = 7,
        scrollOffset: Int = 0,
        visible: Boolean = true,
        shape: CursorShape? = CursorShape.BLINK_BLOCK,
    ) = CursorFrame(
        visible = visible,
        shape = shape,
        x = x,
        y = y,
        scrollOffset = scrollOffset,
        cellWidth = 8f,
        cellHeight = 16f,
        bufferWidth = 80,
        isFocused = true,
        color = Color.White,
        focusedAlpha = 1f,
        unfocusedAlpha = 0.3f,
        glyph = null,
        glyphColor = Color.Black,
        glyphFontFamily = null,
        glyphFontSize = 12f,
        glyphMeasurer = null,
        imageCell = null,
        imageBitmap = null,
    )

    @Test
    fun rebuildingFromTheSameInputsProducesAnEqualFrame() {
        assertEquals(
            frame(),
            frame(),
            "an output frame that leaves the caret alone must not invalidate the overlay",
        )
    }

    @Test
    fun movingTheCaretProducesADifferentFrame() {
        assertNotEquals(frame(), frame(x = 5))
        assertNotEquals(frame(), frame(y = 8))
    }

    @Test
    fun scrollingProducesADifferentFrame() {
        // The caret's screen row is `y - 1 + scrollOffset`, so a scroll genuinely moves it and
        // the layer does have to repaint.
        assertNotEquals(frame(), frame(scrollOffset = 3))
    }

    @Test
    fun hidingOrReshapingTheCaretProducesADifferentFrame() {
        assertNotEquals(frame(), frame(visible = false))
        assertNotEquals(frame(), frame(shape = CursorShape.STEADY_UNDERLINE))
    }

    /**
     * Focus and theme colour reach the caret through the frame, so they have to invalidate it.
     *
     * The gap this closes is not equality itself - it is that nothing pinned "a focus change
     * updates the caret" at all, which is how a regression that dropped their subscription got
     * through. Both are now read at composable scope so the publishing effect re-runs; this is
     * the half of that contract a unit test can hold.
     */
    @Test
    fun focusAndColourInvalidateTheOverlay() {
        assertNotEquals(frame(), frame().copy(isFocused = false))
        assertNotEquals(frame(), frame().copy(color = Color.Red))
        assertNotEquals(frame(), frame().copy(glyphColor = Color.Red))
        // The alphas are what `isFocused` actually selects between in the renderer.
        assertNotEquals(frame(), frame().copy(focusedAlpha = 0.5f))
        assertNotEquals(frame(), frame().copy(unfocusedAlpha = 0.5f))
    }

    /**
     * The glyph is a data class, so two frames built from the same cell compare equal and the
     * overlay is not invalidated - and a different character, style or blink attribute does
     * invalidate it. Left uncovered originally, which is why the blink-gate regression got
     * through: every case used `glyph = null`.
     */
    @Test
    fun theRepaintedGlyphParticipatesInEquality() {
        val glyph = CursorGlyph("a", isBold = false, isItalic = false, isUnderline = false)
        assertEquals(frame().copy(glyph = glyph), frame().copy(glyph = glyph))
        assertEquals(
            frame().copy(glyph = glyph),
            frame().copy(glyph = CursorGlyph("a", isBold = false, isItalic = false, isUnderline = false)),
            "an equal glyph must not invalidate, even as a distinct instance",
        )
        assertNotEquals(
            frame().copy(glyph = glyph),
            frame().copy(glyph = glyph.copy(text = "b")),
        )
        assertNotEquals(
            frame().copy(glyph = glyph),
            frame().copy(glyph = glyph.copy(isBold = true)),
        )
        assertNotEquals(
            frame().copy(glyph = glyph),
            frame().copy(glyph = glyph.copy(blink = GlyphBlink.SLOW)),
            "the blink attribute is what the draw-time gate keys on",
        )
        assertNotEquals(frame().copy(glyph = glyph), frame().copy(glyph = null))
    }

    /**
     * `ImageBitmap` has no structural equality, so it falls back to identity. That is correct
     * here only because `ImageRenderer.getOrDecodeImage` caches, handing back the same instance
     * for an unchanged image - pinned so a change to that caching is caught here rather than as
     * a caret that repaints every frame over an inline image.
     */
    @Test
    fun theImageOcclusionParticipatesInEquality() {
        val bitmap = ImageBitmap(2, 2)
        assertEquals(frame().copy(imageBitmap = bitmap), frame().copy(imageBitmap = bitmap))
        assertNotEquals(
            frame().copy(imageBitmap = bitmap),
            frame().copy(imageBitmap = ImageBitmap(2, 2)),
        )
        assertNotEquals(frame().copy(imageBitmap = bitmap), frame().copy(imageBitmap = null))
    }
}
