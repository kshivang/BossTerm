package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.ui.StableRenderFrameHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SynchronizedRenderFrameTest {

    @Test
    fun captureIsRejectedWhileSynchronizedUpdateIsActive() {
        val display = ComposeTerminalDisplay()
        try {
            display.setSynchronizedUpdate(true)

            assertNull(display.captureStableRenderFrame { "partial" })
        } finally {
            display.setSynchronizedUpdate(false)
            display.dispose()
        }
    }

    @Test
    fun captureIsRejectedWhenSynchronizedUpdateOverlapsIt() {
        val display = ComposeTerminalDisplay()
        try {
            val captured = display.captureStableRenderFrame {
                display.setSynchronizedUpdate(true)
                display.setSynchronizedUpdate(false)
                "partial"
            }

            assertNull(captured)
            assertEquals("complete", display.captureStableRenderFrame { "complete" })
        } finally {
            display.dispose()
        }
    }

    @Test
    fun rejectedInitialCaptureDoesNotSeedFallbackFrame() {
        val display = ComposeTerminalDisplay()
        val holder = StableRenderFrameHolder<String>()
        try {
            display.setSynchronizedUpdate(true)
            assertNull(holder.frameFor(display.captureStableRenderFrame { "partial initial" }))

            display.setSynchronizedUpdate(false)
            val complete = assertNotNull(display.captureStableRenderFrame { "complete" })
            assertEquals("complete", holder.frameFor(complete))
            holder.commit(complete)

            display.setSynchronizedUpdate(true)
            assertEquals(
                "complete",
                holder.frameFor(display.captureStableRenderFrame { "later partial" }),
            )
        } finally {
            display.setSynchronizedUpdate(false)
            display.dispose()
        }
    }

    @Test
    fun uncommittedCandidateDoesNotBecomeFallback() {
        val holder = StableRenderFrameHolder<String>()

        assertEquals("abandoned", holder.frameFor("abandoned"))
        assertNull(holder.frameFor(null))

        holder.commit("committed")
        assertEquals("committed", holder.frameFor(null))
    }
}
