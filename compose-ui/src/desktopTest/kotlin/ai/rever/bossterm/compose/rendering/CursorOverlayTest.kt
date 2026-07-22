package ai.rever.bossterm.compose.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import ai.rever.bossterm.terminal.CursorShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CursorOverlayTest {

    private fun draw(
        width: Int,
        height: Int,
        density: Density = Density(1f),
        block: DrawScope.() -> Unit,
    ): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
            block = block,
        )
        return bitmap
    }

    @Test
    fun focusedCursorDefaultsToBrowserLikeOpaqueColor() {
        val bitmap = draw(width = 10, height = 20) {
            with(TerminalCanvasRenderer) {
                renderCursorOverlay(
                    cursorVisible = true,
                    cursorBlinkVisible = true,
                    cursorShape = CursorShape.STEADY_BLOCK,
                    cursorX = 0,
                    cursorY = 1,
                    scrollOffset = 0,
                    cellWidth = 10f,
                    cellHeight = 20f,
                    isFocused = true,
                    cursorColor = Color.Red,
                )
            }
        }

        val cursorPixel = bitmap.toPixelMap()[5, 10]
        assertEquals(1f, cursorPixel.alpha, 0.01f)
        assertTrue(cursorPixel.red > 0.99f)
    }

    @Test
    fun underlineUsesOneLogicalPixelAndStaysInsideItsCell() {
        val bitmap = draw(width = 30, height = 20, density = Density(2f)) {
            with(TerminalCanvasRenderer) {
                renderCursorOverlay(
                    cursorVisible = true,
                    cursorBlinkVisible = true,
                    cursorShape = CursorShape.STEADY_UNDERLINE,
                    cursorX = 1,
                    cursorY = 1,
                    scrollOffset = 0,
                    cellWidth = 7.5f,
                    cellHeight = 20f,
                    isFocused = true,
                    cursorColor = Color.Red,
                )
            }
        }.toPixelMap()

        // 1.dp at 2x density is exactly two device pixels, on floor-aligned cell edges [7, 15).
        assertEquals(0f, bitmap[10, 17].alpha, 0.01f)
        assertTrue(bitmap[10, 18].alpha > 0.99f)
        assertTrue(bitmap[10, 19].alpha > 0.99f)
        assertEquals(0f, bitmap[6, 19].alpha, 0.01f)
        assertEquals(0f, bitmap[15, 19].alpha, 0.01f)
    }

    @Test
    fun inlineImageAlphaOccludesCursorWithoutErasingTheImage() {
        val image = draw(width = 10, height = 20) {
            drawRect(
                color = Color.Green,
                topLeft = Offset.Zero,
                size = Size(5f, 20f),
            )
        }
        val slice = ImageCellSlice(
            bitmap = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(10, 20),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(10, 20),
        )

        val bitmap = draw(width = 10, height = 20) {
            drawImage(image)
            with(TerminalCanvasRenderer) {
                renderCursorOverlay(
                    cursorVisible = true,
                    cursorBlinkVisible = true,
                    cursorShape = CursorShape.STEADY_BLOCK,
                    cursorX = 0,
                    cursorY = 1,
                    scrollOffset = 0,
                    cellWidth = 10f,
                    cellHeight = 20f,
                    isFocused = true,
                    cursorColor = Color.Red,
                    imageOcclusion = slice,
                )
            }
        }.toPixelMap()

        val petPixel = bitmap[2, 10]
        assertTrue(petPixel.green > 0.99f, "opaque sprite pixels must remain unchanged")
        assertTrue(petPixel.red < 0.01f, "the cursor must not tint opaque sprite pixels")

        val transparentPixel = bitmap[7, 10]
        assertTrue(transparentPixel.red > 0.99f, "transparent sprite pixels should reveal the cursor")
        assertTrue(transparentPixel.green < 0.01f)
    }
}
