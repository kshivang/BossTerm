package ai.rever.bossterm.terminal.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalTextBufferBatchTest {

    @Test
    fun snapshotWaitsUntilImagePlaceholderBatchIsComplete() {
        val buffer = TerminalTextBuffer(width = 8, height = 2, styleState = StyleState())
        buffer.getLine(0) // Materialize the lazily allocated first screen row.
        val firstCellWritten = CountDownLatch(1)
        val finishBatch = CountDownLatch(1)
        val snapshotStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val writer = executor.submit {
                buffer.beginBatch()
                try {
                    buffer.writeImagePlaceholderCell(
                        row = 0,
                        col = 0,
                        imageId = 42,
                        cellX = 0,
                        cellY = 0,
                        cellWidth = 2,
                        cellHeight = 1
                    )
                    firstCellWritten.countDown()
                    assertTrue(finishBatch.await(2, TimeUnit.SECONDS))
                    buffer.writeImagePlaceholderCell(
                        row = 0,
                        col = 1,
                        imageId = 42,
                        cellX = 1,
                        cellY = 0,
                        cellWidth = 2,
                        cellHeight = 1
                    )
                } finally {
                    buffer.endBatch()
                }
            }

            assertTrue(firstCellWritten.await(2, TimeUnit.SECONDS))
            val snapshot = executor.submit<BufferSnapshot> {
                snapshotStarted.countDown()
                buffer.createSnapshot()
            }
            assertTrue(snapshotStarted.await(2, TimeUnit.SECONDS))

            // A redraw requested between the two writes must wait instead of exposing
            // the first cell by itself for one frame.
            assertFailsWith<TimeoutException> {
                snapshot.get(100, TimeUnit.MILLISECONDS)
            }

            finishBatch.countDown()
            writer.get(2, TimeUnit.SECONDS)
            assertEquals(
                setOf(0, 1),
                snapshot.get(2, TimeUnit.SECONDS).getLine(0).getAllImageCells().keys
            )
        } finally {
            finishBatch.countDown()
            executor.shutdownNow()
        }
    }
}
