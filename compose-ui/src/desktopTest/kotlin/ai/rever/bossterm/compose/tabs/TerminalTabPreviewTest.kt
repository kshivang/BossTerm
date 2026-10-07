package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalTabPreviewTest {
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
