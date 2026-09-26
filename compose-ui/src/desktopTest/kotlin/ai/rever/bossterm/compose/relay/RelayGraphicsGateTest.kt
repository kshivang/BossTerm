package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.SharedTerminalImage
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RelayGraphicsGateTest {
    private fun screen(sequence: Long, text: String = "screen") = ServerMessage.PaneSnapshot("p", text, 80, 24, 0, graphicsSequence = sequence)
    private fun image(sequence: Long) = ServerMessage.PaneGraphics("p", sequence, true, relaySequence = sequence)

    @Test fun `graphics arriving after text hold later deltas until placements are applied`() = runBlocking {
        val applied = mutableListOf<ServerMessage>()
        val gate = RelayGraphicsGate({ applied += it }, { fail("unexpected resync") })
        gate.snapshot(screen(1))
        gate.output(ServerMessage.PaneOutput("p", "after"))
        assertEquals(listOf<ServerMessage>(screen(1)), applied)
        gate.graphics(image(1))
        assertEquals(listOf(screen(1), image(1), ServerMessage.PaneOutput("p", "after")), applied)
        gate.graphics(image(1))
        assertEquals(3, applied.size, "duplicate graphics cannot rewind text")
    }

    @Test fun `preview snapshot preserves already arrived matching graphics and discards older queued deltas`() = runBlocking {
        val applied = mutableListOf<ServerMessage>()
        val gate = RelayGraphicsGate({ applied += it }, { fail("unexpected resync") })
        gate.snapshot(screen(1))
        gate.output(ServerMessage.PaneOutput("p", "superseded"))
        gate.graphics(image(2))
        gate.snapshot(screen(2, "new preview"))
        gate.graphics(image(1))
        assertEquals(listOf(screen(1), screen(2, "new preview"), image(2)), applied)
        gate.output(ServerMessage.PaneOutput("p", "new delta"))
        assertEquals(ServerMessage.PaneOutput("p", "new delta"), applied.last())
    }

    @Test fun `only received preview markers request matching private graphics`() = runBlocking {
        val requested = mutableListOf<Long>()
        val gate = RelayGraphicsGate({}, { fail("unexpected resync") }, requestGraphics = { _, sequence -> requested += sequence })
        gate.snapshot(screen(1)) // Initial/private snapshot graphics is sent proactively.
        gate.graphics(image(1))
        assertTrue(requested.isEmpty())
        gate.snapshot(screen(5), requestMissingGraphics = true) // Downsampled preview skipped 2..4.
        assertEquals(listOf(5L), requested)
        gate.graphics(image(5))
        gate.graphics(image(6))
        gate.snapshot(screen(6), requestMissingGraphics = true)
        assertEquals(listOf(5L), requested, "already-arrived matching graphics is not requested again")
    }

    @Test fun `slow preview raster completes before the latest coalesced preview is requested`() = runBlocking {
        val requested = mutableListOf<Long>()
        val applied = mutableListOf<ServerMessage>()
        val gate = RelayGraphicsGate({ applied += it }, { fail("unexpected resync") }, requestGraphics = { _, sequence -> requested += sequence })
        gate.snapshot(screen(1), requestMissingGraphics = true)
        gate.snapshot(screen(2), requestMissingGraphics = true)
        gate.snapshot(screen(3), requestMissingGraphics = true)
        assertEquals(listOf(1L), requested)
        assertEquals(listOf<ServerMessage>(screen(1)), applied)
        gate.graphics(image(1))
        assertEquals(listOf(1L, 3L), requested)
        assertEquals(listOf(screen(1), image(1), screen(3)), applied)
        gate.graphics(image(3))
        assertEquals(image(3), applied.last())
        assertFalse(applied.contains(screen(2)), "only the latest queued complete preview matters")
    }

    @Test fun `text-only complete snapshot clears old image state without a private request`() = runBlocking {
        val applied = mutableListOf<ServerMessage>()
        val gate = RelayGraphicsGate({ applied += it }, { fail("unexpected resync") }, requestGraphics = { _, _ -> fail("text-only preview needs no private raster") })
        gate.snapshot(screen(1)); gate.graphics(image(1))
        val textOnly = screen(2).copy(graphicsSequence = null)
        gate.snapshot(textOnly, requestMissingGraphics = true)
        assertEquals(textOnly, applied[applied.lastIndex - 1])
        assertEquals(ServerMessage.PaneGraphics("p", 0, true), applied.last())
    }

    @Test fun `repaint barrier orders buffered graphics before later text`() = runBlocking {
        val applied = mutableListOf<ServerMessage>()
        val gate = RelayGraphicsGate({ applied += it }, { fail("unexpected resync") })
        gate.graphics(image(3))
        gate.output(ServerMessage.PaneRepaint("p", "repaint", graphicsSequence = 3))
        gate.output(ServerMessage.PaneOutput("p", "after"))
        assertEquals(listOf(ServerMessage.PaneRepaint("p", "repaint", graphicsSequence = 3), image(3), ServerMessage.PaneOutput("p", "after")), applied)
    }

    @Test fun `bounded backlog and raster timeout request a fresh snapshot independently per pane`() = runBlocking {
        var now = 0L
        val requested = mutableListOf<String>()
        val applied = mutableListOf<ServerMessage>()
        val gate = RelayGraphicsGate({ applied += it }, { requested += it }, { now })
        gate.snapshot(screen(1))
        gate.output(ServerMessage.PaneOutput("p", "x".repeat(512 * 1024 + 1)))
        assertEquals(listOf("p"), requested)
        gate.output(ServerMessage.PaneOutput("q", "unrelated"))
        assertEquals(ServerMessage.PaneOutput("q", "unrelated"), applied.last())
        gate.snapshot(screen(2))
        now = 119_999; gate.expire()
        assertEquals(1, requested.size)
        now = 120_000; gate.expire()
        assertEquals(listOf("p", "p"), requested)
        gate.snapshot(screen(3)); gate.graphics(image(3))
        assertEquals(image(3), applied.last())
    }

    @Test fun `unmatched raster memory is bounded and cleared by scope reset`() = runBlocking {
        val requested = mutableListOf<String>()
        val gate = RelayGraphicsGate({}, { requested += it })
        val large = image(1).copy(images = listOf(SharedTerminalImage("1", "image/png", "x".repeat(17 * 1024 * 1024), "hash")))
        gate.graphics(large)
        gate.graphics(large.copy(paneId = "q", relaySequence = 2))
        assertEquals(listOf("q"), requested)
        gate.reset("p")
        gate.graphics(large.copy(paneId = "q", relaySequence = 3))
        assertEquals(1, requested.size, "reset releases buffered raster credit")
    }
}
