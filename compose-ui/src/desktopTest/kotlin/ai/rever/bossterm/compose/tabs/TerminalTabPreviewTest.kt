package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalTabPreviewTest {
    @Test fun wideGridFitsAllColumnsAndPreservesAspectRatio() {
        assertEquals(Size(320f, 96f), terminalPreviewSize(Size(1600f, 480f), Size(320f, 240f)))
    }

    @Test fun tallGridFitsAllRowsAndPreservesAspectRatio() {
        assertEquals(Size(160f, 240f), terminalPreviewSize(Size(800f, 1200f), Size(320f, 240f)))
    }

    @Test fun smallGridIsNotUpscaled() {
        assertEquals(Size(80f, 40f), terminalPreviewSize(Size(80f, 40f), Size(320f, 240f)))
    }

    @Test fun scaledDrawingRetainsLogicalGridAndBottomRightContent() {
        val bitmap = ImageBitmap(320, 96)
        val grid = Size(1600f, 480f)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(320f, 96f)) {
            drawTerminalPreviewGrid(grid) {
                assertEquals(grid, size)
                drawRect(Color.Red, Offset(1550f, 430f), Size(50f, 50f))
            }
        }
        assertEquals(Color.Red, bitmap.toPixelMap()[318, 94])
    }

    @Test fun previewKeepsCompleteFrameDuringSynchronizedRedraw() {
        val display = ComposeTerminalDisplay()
        try {
            val complete = captureTerminalPreviewFrame(display, null) { "complete" }
            assertEquals("complete", complete)
            display.setSynchronizedUpdate(true)
            assertEquals("complete", captureTerminalPreviewFrame(display, complete) { error("must not read partial buffer") })
            assertNull(captureTerminalPreviewFrame<String>(display, null) { error("must not seed partial frame") })
            display.setSynchronizedUpdate(false)
            assertEquals("updated", captureTerminalPreviewFrame(display, complete) { "updated" })
        } finally {
            display.setSynchronizedUpdate(false)
            display.dispose()
        }
    }

    @Test fun previewRejectsCaptureOverlappingSynchronizedRedraw() {
        val display = ComposeTerminalDisplay()
        try {
            assertEquals("complete", captureTerminalPreviewFrame(display, "complete") {
                display.setSynchronizedUpdate(true)
                display.setSynchronizedUpdate(false)
                "partial"
            })
        } finally {
            display.setSynchronizedUpdate(false)
            display.dispose()
        }
    }
}
