package ai.rever.bossterm.compose.ui

import androidx.compose.ui.graphics.Color
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
}
