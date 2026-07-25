package ai.rever.bossterm.compose.share

import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.image.ImageDataCache
import ai.rever.bossterm.terminal.model.image.ImageFormat
import ai.rever.bossterm.terminal.model.image.TerminalImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaneGraphicsTrackerTest {
    @Test
    fun `cell runs group adjacent slices and omit images missing from cache`() {
        val buffer = TerminalTextBuffer(8, 4, StyleState())
        buffer.getLine(3) // Materialize the lazily allocated screen rows.
        buffer.writeImageCellRow(
            row = 1,
            startCol = 2,
            imageId = 11,
            cellY = 0,
            cellWidth = 3,
            cellHeight = 2,
        )
        buffer.writeImageCellRow(
            row = 2,
            startCol = 0,
            imageId = 99,
            cellY = 1,
            cellWidth = 2,
            cellHeight = 2,
        )

        val runs = PaneGraphicsTracker.cellRuns(buffer.createIncrementalSnapshot(), setOf(11))

        assertEquals(
            listOf(SharedImageCellRun("11", row = 1, col = 2, cellX = 0, cellY = 0,
                length = 3, totalCellsX = 3, totalCellsY = 2)),
            runs,
        )
    }

    @Test
    fun `tracker sends raster bytes once then full placements and removals`() {
        val buffer = TerminalTextBuffer(8, 4, StyleState())
        buffer.getLine(3) // Materialize the lazily allocated screen rows.
        val cache = ImageDataCache()
        val image = TerminalImage(
            id = 42,
            data = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            ),
            format = ImageFormat.PNG,
            intrinsicWidth = 1,
            intrinsicHeight = 1,
        )
        cache.storeImage(image)
        buffer.writeImageCellRow(0, 1, image.id, 0, 2, 1)
        val tracker = PaneGraphicsTracker("pane", buffer, cache)

        val initial = tracker.fullMessage()
        assertTrue(initial.full)
        assertEquals(listOf("42"), initial.requiredImageIds)
        assertEquals(1, initial.images.size)
        assertEquals("image/png", initial.images.single().mimeType)

        // fullMessage commits its captured state, so attach does not immediately double-send it.
        assertNull(tracker.pollUpdate())

        buffer.clearImageCells(image.id)
        val removalUpdate = tracker.pollUpdate()!!
        assertFalse(removalUpdate.requiresTextSnapshot)
        val removal = removalUpdate.message
        assertEquals(2L, removal.revision)
        assertEquals(listOf("42"), removal.removedImageIds)
        assertTrue(removal.requiredImageIds.isEmpty())
        assertTrue(removal.cells.isEmpty())
        assertFalse(tracker.hasVisibleGraphics())
    }

    @Test
    fun `ordinary scrolling keeps absolute image rows stable and emits no graphics delta`() {
        val buffer = TerminalTextBuffer(8, 4, StyleState())
        buffer.getLine(3)
        val cache = ImageDataCache()
        val image = TerminalImage(
            id = 7,
            data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            format = ImageFormat.PNG,
            intrinsicWidth = 1,
            intrinsicHeight = 1,
        )
        cache.storeImage(image)
        buffer.writeImageCellRow(1, 0, image.id, 0, 1, 1)
        val tracker = PaneGraphicsTracker("pane", buffer, cache)
        val initial = tracker.fullMessage()
        assertEquals(1, initial.cells.single().row)

        buffer.scrollArea(scrollRegionTop = 1, dy = -1, scrollRegionBottom = 4)

        assertNull(tracker.pollUpdate())
        assertEquals(1, tracker.fullMessage().cells.single().row)
    }

    @Test
    fun `unknown image bytes are not sent to browsers`() {
        val buffer = TerminalTextBuffer(8, 4, StyleState())
        buffer.getLine(3)
        val cache = ImageDataCache()
        val image = TerminalImage(
            id = 9,
            data = byteArrayOf(1, 2, 3),
            format = ImageFormat.UNKNOWN,
            intrinsicWidth = 1,
            intrinsicHeight = 1,
        )
        cache.storeImage(image)
        buffer.writeImageCellRow(0, 0, image.id, 0, 1, 1)

        val full = PaneGraphicsTracker("pane", buffer, cache).fullMessage()

        assertTrue(full.images.isEmpty())
        assertTrue(full.cells.isEmpty())
        assertTrue(full.requiredImageIds.isEmpty())
    }

    @Test
    fun `sequence detector recognizes fragmented Sixel Kitty and placeholders`() {
        val sixel = GraphicsSequenceDetector()
        assertFalse(sixel.inspect("\u001b"))
        assertFalse(sixel.inspect("P0;"))
        assertTrue(sixel.inspect("0qpayload"))

        val kitty = GraphicsSequenceDetector()
        assertFalse(kitty.inspect("\u001b_"))
        assertTrue(kitty.inspect("Ga=t;payload"))

        val placeholder = GraphicsSequenceDetector()
        assertTrue(placeholder.inspect(String(Character.toChars(0x10EEEE))))

        val query = GraphicsSequenceDetector()
        assertFalse(query.inspect("\u001bP\$qm\u001b\\"))

        val oneShot = GraphicsSequenceDetector()
        assertTrue(oneShot.inspect("\u001bP0;0qpayload"))
        assertFalse(oneShot.inspect("ordinary output"))
    }

    @Test
    fun `graphics resync limiter is per pane and rate limited`() {
        val limiter = GraphicsResyncLimiter(minimumIntervalNanos = 500)
        assertTrue(limiter.tryAcquire("a", nowNanos = 1_000))
        assertFalse(limiter.tryAcquire("a", nowNanos = 1_499))
        assertTrue(limiter.tryAcquire("b", nowNanos = 1_499))
        assertTrue(limiter.tryAcquire("a", nowNanos = 1_500))
    }
}
