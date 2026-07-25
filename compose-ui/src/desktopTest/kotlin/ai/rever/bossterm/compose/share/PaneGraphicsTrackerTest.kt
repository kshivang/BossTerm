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
import kotlin.test.assertSame
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
    fun `cache replacement sends one raster delta without a text reanchor`() {
        val buffer = TerminalTextBuffer(8, 4, StyleState())
        buffer.getLine(3)
        val cache = ImageDataCache()
        val first = TerminalImage(
            id = 5,
            data = byteArrayOf(1),
            format = ImageFormat.PNG,
            intrinsicWidth = 1,
            intrinsicHeight = 1,
        )
        cache.storeImage(first)
        buffer.writeImageCellRow(0, 0, first.id, 0, 1, 1)
        val tracker = PaneGraphicsTracker("pane", buffer, cache)
        val firstFull = tracker.fullMessage()

        cache.storeImage(TerminalImage(
            id = 5,
            data = byteArrayOf(2),
            format = ImageFormat.PNG,
            intrinsicWidth = 1,
            intrinsicHeight = 1,
        ))
        val replacement = tracker.pollUpdate()!!

        assertFalse(replacement.requiresTextSnapshot)
        assertEquals(1, replacement.message.images.size)
        assertTrue(replacement.message.images.single().contentHash != firstFull.images.single().contentHash)
    }

    @Test
    fun `content fingerprint is stable across tracker lifetimes`() {
        val buffer = TerminalTextBuffer(8, 4, StyleState())
        buffer.getLine(3)
        val cache = ImageDataCache()
        val first = TerminalImage(
            id = 55,
            data = byteArrayOf(1, 2, 3),
            format = ImageFormat.PNG,
        )
        cache.storeImage(first)
        buffer.writeImageCellRow(0, 0, first.id, 0, 1, 1)

        val firstWire = PaneGraphicsTracker("pane", buffer, cache)
            .fullMessage().images.single()
        val reconnectWire = PaneGraphicsTracker("pane", buffer, cache)
            .fullMessage().images.single()
        val firstHash = firstWire.contentHash
        val reconnectHash = reconnectWire.contentHash
        assertEquals(firstHash, reconnectHash)
        assertSame(firstWire.data, reconnectWire.data, "base64 must be shared across viewer trackers")

        cache.storeImage(first.copy(data = byteArrayOf(3, 2, 1)))
        val replacedHash = PaneGraphicsTracker("pane", buffer, cache)
            .fullMessage().images.single().contentHash
        assertTrue(replacedHash != firstHash)
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
    fun `capped history trim shifts placements without a full raster resend`() {
        val buffer = TerminalTextBuffer(8, 2, StyleState(), maxHistoryLinesCount = 1)
        buffer.getLine(1)
        val cache = ImageDataCache()
        val image = TerminalImage(
            id = 8,
            data = byteArrayOf(1),
            format = ImageFormat.PNG,
            intrinsicWidth = 1,
            intrinsicHeight = 1,
        )
        cache.storeImage(image)
        buffer.writeImageCellRow(0, 0, image.id, 0, 1, 1)
        val tracker = PaneGraphicsTracker("pane", buffer, cache)
        tracker.fullMessage()

        buffer.scrollArea(scrollRegionTop = 1, dy = -1, scrollRegionBottom = 2)
        assertNull(tracker.pollUpdate(), "moving into uncapped history preserves the absolute row")

        buffer.scrollArea(scrollRegionTop = 1, dy = -1, scrollRegionBottom = 2)
        val trimmed = tracker.pollUpdate()!!
        assertFalse(trimmed.requiresTextSnapshot)
        assertTrue(trimmed.message.images.isEmpty(), "a row trim must not resend raster bytes")
        assertEquals(listOf("8"), trimmed.message.removedImageIds)
        assertTrue(trimmed.message.cells.isEmpty())
    }

    @Test
    fun `web history cap shifts rows before the larger host history trims`() {
        val buffer = TerminalTextBuffer(8, 2, StyleState(), maxHistoryLinesCount = 3)
        buffer.getLine(1)
        val cache = ImageDataCache()
        val image = TerminalImage(id = 81, data = byteArrayOf(1), format = ImageFormat.PNG)
        cache.storeImage(image)
        buffer.writeImageCellRow(0, 0, image.id, 0, 1, 1)
        val tracker = PaneGraphicsTracker("pane", buffer, cache, scrollbackLines = 1)
        tracker.fullMessage()

        buffer.scrollArea(scrollRegionTop = 1, dy = -1, scrollRegionBottom = 2)
        assertNull(tracker.pollUpdate(), "the first retained history row keeps its absolute position")

        buffer.scrollArea(scrollRegionTop = 1, dy = -1, scrollRegionBottom = 2)
        val shifted = tracker.pollUpdate()!!
        assertEquals(listOf("81"), shifted.message.removedImageIds)
        assertTrue(shifted.message.cells.isEmpty())
        assertEquals(1, shifted.message.historyLines)
    }

    @Test
    fun `web raster budget skips oversized and excess images`() {
        val buffer = TerminalTextBuffer(8, 4, StyleState())
        buffer.getLine(3)
        val cache = ImageDataCache()
        val oversized = TerminalImage(
            id = 90,
            data = ByteArray(MAX_WEB_IMAGE_RASTER_BYTES + 1),
            format = ImageFormat.PNG,
        )
        cache.storeImage(oversized)
        buffer.writeImageCellRow(0, 0, oversized.id, 0, 1, 1)
        val oversizedFrame = PaneGraphicsTracker("pane", buffer, cache).fullMessage()
        assertTrue(oversizedFrame.images.isEmpty())
        assertTrue(oversizedFrame.cells.isEmpty())

        buffer.clearImageCells(oversized.id)
        cache.removeImage(oversized.id)
        repeat(3) { index ->
            val image = TerminalImage(
                id = 91L + index,
                data = ByteArray(6 * 1024 * 1024) { index.toByte() },
                format = ImageFormat.PNG,
            )
            cache.storeImage(image)
            buffer.writeImageCellRow(index, 0, image.id, 0, 1, 1)
        }
        val cappedFrame = PaneGraphicsTracker("pane", buffer, cache).fullMessage()
        assertEquals(2, cappedFrame.images.size)
        assertEquals(2, cappedFrame.cells.size)
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
    fun `graphics filter strips fragmented payloads and preserves ordinary output`() {
        val sixel = GraphicsOutputFilter()
        assertEquals("", sixel.filter("\u001b").output)
        assertEquals("", sixel.filter("P0;").output)
        val sixelEnd = sixel.filter("0qpayload\u001b\\tail")
        assertTrue(sixelEnd.detectedGraphics)
        assertEquals("tail", sixelEnd.output)

        val kitty = GraphicsOutputFilter()
        assertEquals("", kitty.filter("\u001b_").output)
        val kittyEnd = kitty.filter("Ga=t;payload\u009ctail")
        assertTrue(kittyEnd.detectedGraphics)
        assertEquals("tail", kittyEnd.output)

        val iterm = GraphicsOutputFilter()
        val itermEnd = iterm.filter("\u001b]1337;File=name=x:base64\u0007tail")
        assertTrue(itermEnd.detectedGraphics)
        assertEquals("tail", itermEnd.output)

        val placeholder = GraphicsOutputFilter()
        val placeholderResult = placeholder.filter(String(Character.toChars(0x10EEEE)))
        assertTrue(placeholderResult.detectedGraphics)
        assertEquals(" ", placeholderResult.output)

        val query = GraphicsOutputFilter()
        val queryResult = query.filter("\u001bP\$qm\u001b\\")
        assertFalse(queryResult.detectedGraphics)
        assertEquals("\u001bP\$qm\u001b\\", queryResult.output)

        assertEquals("ordinary output", sixel.filter("ordinary output").output)
    }

    @Test
    fun `graphics filter recovers from emulator abort conditions`() {
        val cancelled = GraphicsOutputFilter()
        assertEquals("hello", cancelled.filter("\u001bPqpayload\u0018hello").output)

        val substituted = GraphicsOutputFilter()
        assertEquals("hello", substituted.filter("\u001bPqpayload\u001ahello").output)

        val strayEscape = GraphicsOutputFilter()
        assertEquals("hello", strayEscape.filter("\u001bPqpayload\u001bXhello").output)

        val truncated = GraphicsOutputFilter(maxDiscardChars = 3)
        assertEquals("hello", truncated.filter("\u001bPqabchello").output)

        val repeatedEscape = GraphicsOutputFilter()
        assertEquals("\u001bhello", repeatedEscape.filter("\u001b\u001bPqpayload\u009chello").output)

        val kittyPlacement = GraphicsOutputFilter()
        val placeholder = String(Character.toChars(0x10EEEE))
        assertEquals(" tail", kittyPlacement.filter("$placeholder\u0301\u0302tail").output)
        assertEquals(" ", kittyPlacement.filter(placeholder).output)
        assertEquals("tail", kittyPlacement.filter("\u0301\u0302tail").output)
    }

    @Test
    fun `graphics resync limiter is per pane and rate limited`() {
        val limiter = GraphicsResyncLimiter(minimumIntervalNanos = 500)
        assertTrue(limiter.tryAcquire("a", nowNanos = 1_000))
        assertFalse(limiter.tryAcquire("a", nowNanos = 1_499))
        assertTrue(limiter.tryAcquire("b", nowNanos = 1_499))
        assertTrue(limiter.tryAcquire("a", nowNanos = 1_500))
        limiter.remove("a")
        assertTrue(limiter.tryAcquire("a", nowNanos = 1_501))
    }

    @Test
    fun `graphics resync limiter also enforces a rolling byte budget`() {
        val limiter = GraphicsResyncLimiter(
            minimumIntervalNanos = 0,
            maximumBytesPerWindow = 100,
            windowNanos = 1_000,
        )
        assertTrue(limiter.tryAcquire("pane", estimatedBytes = 60, nowNanos = 1_000))
        assertFalse(limiter.tryAcquire("pane", estimatedBytes = 41, nowNanos = 1_001))
        assertTrue(limiter.tryAcquire("pane", estimatedBytes = 100, nowNanos = 2_000))
    }

    @Test
    fun `denied resync does not advance the rolling window`() {
        val limiter = GraphicsResyncLimiter(
            minimumIntervalNanos = 0,
            maximumBytesPerWindow = 100,
            windowNanos = 1_000,
        )
        assertTrue(limiter.tryAcquire("pane", estimatedBytes = 60, nowNanos = 1_000))
        assertFalse(limiter.tryAcquire("pane", estimatedBytes = 101, nowNanos = 2_000))
        assertTrue(limiter.tryAcquire("pane", estimatedBytes = 60, nowNanos = 2_500))
        assertFalse(
            limiter.tryAcquire("pane", estimatedBytes = 60, nowNanos = 3_001),
            "the denied request must not have started a new window at t=2000",
        )
    }

    @Test
    fun `web scrollback cap matches the supported settings ceiling`() {
        val buffer = TerminalTextBuffer(
            width = 8,
            height = 2,
            styleState = StyleState(),
            maxHistoryLinesCount = 500_000,
        )

        assertEquals(MAX_WEB_VIEWER_SCROLLBACK_LINES, webViewerScrollbackLines(buffer))
    }
}
