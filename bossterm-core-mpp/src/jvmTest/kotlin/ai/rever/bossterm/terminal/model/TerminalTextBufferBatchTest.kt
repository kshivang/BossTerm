package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalTextBufferBatchTest {

    @Test
    fun snapshotsStayResponsiveAndDisconnectClearsAbandonedBatch() {
        val styleState = StyleState()
        val buffer = TerminalTextBuffer(width = 8, height = 2, styleState = styleState)
        val display = NoopTerminalDisplay()
        val terminal = BossTerminal(display, buffer, styleState)
        buffer.getLine(0) // Materialize the lazily allocated first screen row.
        val modelChanges = AtomicInteger()
        buffer.addModelListener(object : TerminalModelListener {
            override fun modelChanged() {
                modelChanges.incrementAndGet()
            }
        })
        val firstCellWritten = CountDownLatch(1)
        val disconnect = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val emulator = executor.submit {
                buffer.beginBatch()
                buffer.writeImagePlaceholderCell(
                    row = 0,
                    col = 0,
                    imageId = 42,
                    cellX = 0,
                    cellY = 0,
                    cellWidth = 1,
                    cellHeight = 1
                )
                firstCellWritten.countDown()
                assertTrue(disconnect.await(2, TimeUnit.SECONDS))

                // Mirrors TerminalStarter's finally path after processChar() throws.
                terminal.disconnected()
            }

            assertTrue(firstCellWritten.await(2, TimeUnit.SECONDS))
            val snapshotDuringBatch = buffer.createSnapshot()
            assertEquals(setOf(0), snapshotDuringBatch.getLine(0).getAllImageCells().keys)
            assertEquals(0, modelChanges.get(), "batch changes stay suppressed until teardown")

            disconnect.countDown()
            emulator.get(2, TimeUnit.SECONDS)

            assertEquals(1, modelChanges.get(), "disconnect publishes the partial final state once")
            assertEquals(false, display.synchronizedUpdateEnabled)
            assertEquals(setOf(0), buffer.createSnapshot().getLine(0).getAllImageCells().keys)
        } finally {
            disconnect.countDown()
            executor.shutdownNow()
        }
    }

    private class NoopTerminalDisplay : TerminalDisplay {
        var synchronizedUpdateEnabled: Boolean? = null
        override var windowTitle: String? = null
        override var iconTitle: String? = null
        override val selection: TerminalSelection? = null

        override fun setCursor(x: Int, y: Int) = Unit
        override fun setCursorShape(cursorShape: CursorShape?) = Unit
        override fun beep() = Unit
        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) = Unit
        override fun setCursorVisible(isCursorVisible: Boolean) = Unit
        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) = Unit
        override fun terminalMouseModeSet(mouseMode: MouseMode) = Unit
        override fun setMouseFormat(mouseFormat: MouseFormat) = Unit
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
        override fun setSynchronizedUpdate(enabled: Boolean) {
            synchronizedUpdateEnabled = enabled
        }
    }
}
