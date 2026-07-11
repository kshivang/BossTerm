package ai.rever.bossterm.compose.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression tests for the live-resize / update-banner crash: the window died
 * with "maxWidth must be >= than minWidth, maxHeight must be >= than minHeight,
 * minWidth and minHeight must be >= 0" whenever a glyph's topLeft ended up past
 * the canvas edge, because Compose's drawText derives text-layout constraints
 * from `canvasSize - topLeft` without clamping.
 *
 * [TerminalCanvasRenderer.drawTextClipped] guards every glyph draw against
 * this; these tests pin both the framework behavior being guarded against and
 * the guard itself.
 */
class DrawTextClippedTest {

    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr
    )
    private val style = TextStyle(fontSize = 12.sp)

    private fun draw(width: Int, height: Int, block: DrawScope.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
            block = block
        )
        return bitmap
    }

    // Documents the Compose behavior the guard exists for: drawText with a
    // topLeft right of the canvas throws from Constraints(). If this test ever
    // fails, Compose has started clamping internally and drawTextClipped can go.
    @Test
    fun frameworkDrawTextThrowsWhenTopLeftIsRightOfCanvas() {
        assertFailsWith<IllegalArgumentException> {
            draw(100, 50) {
                drawText(textMeasurer = measurer, text = "A", topLeft = Offset(150f, 0f), style = style)
            }
        }
    }

    @Test
    fun frameworkDrawTextThrowsWhenTopLeftIsBelowCanvas() {
        assertFailsWith<IllegalArgumentException> {
            draw(100, 50) {
                drawText(textMeasurer = measurer, text = "A", topLeft = Offset(0f, 90f), style = style)
            }
        }
    }

    @Test
    fun drawTextClippedSkipsOutOfBoundsGlyphsInsteadOfCrashing() {
        draw(100, 50) {
            with(TerminalCanvasRenderer) {
                drawTextClipped(measurer, "A", Offset(150f, 0f), style)   // right of canvas
                drawTextClipped(measurer, "A", Offset(0f, 90f), style)    // below canvas
                drawTextClipped(measurer, "A", Offset(150f, 90f), style)  // both
                drawTextClipped(measurer, "A", Offset(100f, 0f), style)   // exactly on the edge
            }
        }
    }

    @Test
    fun drawTextClippedStillDrawsInBoundsGlyphs() {
        val bitmap = draw(100, 50) {
            with(TerminalCanvasRenderer) {
                drawTextClipped(measurer, "██", Offset(2f, 2f), style.copy(color = Color.White))
            }
        }
        val pixels = bitmap.toPixelMap()
        var drewSomething = false
        outer@ for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha > 0f) {
                    drewSomething = true
                    break@outer
                }
            }
        }
        assertTrue(drewSomething, "an in-bounds glyph must still be rendered")
    }

    // Negative topLeft must keep drawing (partially visible glyphs while
    // scrolled): constraints only go invalid past the right/bottom edge.
    @Test
    fun drawTextClippedKeepsNegativeTopLeft() {
        draw(100, 50) {
            with(TerminalCanvasRenderer) {
                drawTextClipped(measurer, "A", Offset(-5f, -5f), style)
            }
        }
    }
}
