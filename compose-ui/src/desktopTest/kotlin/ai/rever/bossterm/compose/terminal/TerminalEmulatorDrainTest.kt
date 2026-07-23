package ai.rever.bossterm.compose.terminal

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalEmulatorDrainTest {

    @Test
    fun eofDuringSynchronizedUpdateRestoresStableCapture() {
        val display = ComposeTerminalDisplay()
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(width = 8, height = 2, styleState = styleState)
        val terminal = BossTerminal(display, textBuffer, styleState)
        val dataStream = BlockingTerminalDataStream()
        val emulator = BossEmulator(dataStream, terminal)
        val synchronizedUpdateConsumed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        dataStream.onChunkStart = textBuffer::beginBatch
        dataStream.onChunkEnd = {
            textBuffer.endBatch()
            synchronizedUpdateConsumed.countDown()
        }
        dataStream.append("\u001B[?2026h")

        val drain = executor.submit {
            drainTerminalEmulator(
                emulator = emulator,
                dataStream = dataStream,
                terminal = terminal,
                shouldContinue = { true },
            )
        }

        try {
            assertTrue(synchronizedUpdateConsumed.await(2, TimeUnit.SECONDS))
            assertNull(display.captureStableRenderFrame { "mid-sync" })

            dataStream.close()
            drain.get(2, TimeUnit.SECONDS)

            assertEquals("after-disconnect", display.captureStableRenderFrame { "after-disconnect" })
            assertEquals(false, display.cursorVisibleSnapshot)
        } finally {
            dataStream.close()
            runCatching { drain.get(2, TimeUnit.SECONDS) }
            executor.shutdownNow()
            display.dispose()
        }
    }
}
