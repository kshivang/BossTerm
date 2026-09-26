package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.ShareProtocol
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.drainTerminalEmulator
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RelayPanePublisherTest {
    @Test fun `large graphics use private state while repaint stays ordered in the shared text stream`() = runBlocking {
        val display = ComposeTerminalDisplay()
        val style = StyleState()
        val buffer = TerminalTextBuffer(80, 24, style)
        val terminal = BossTerminal(display, buffer, style)
        val stream = BlockingTerminalDataStream()
        val executor = Executors.newSingleThreadExecutor()
        val drain = executor.submit { drainTerminalEmulator(BossEmulator(stream, terminal), stream, terminal, { true }) }
        val frames = CopyOnWriteArrayList<RelayOutput>()
        val order = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Exception>()
        var captured = false
        val publisher = RelayPanePublisher("room", "pane", stream, RelayOutputCrypto.newHostIdentity(), {
            ShareProtocol.encodeServer(ServerMessage.PaneSnapshot("pane", buffer.getScreenLines(), 80, 24, 0))
        }, { frames += it; order += "group"; true }, { failures += it }, captureGraphics = { full ->
            when {
                full -> RelayGraphicsCapture(ServerMessage.PaneGraphics("pane", 0, true))
                !captured && buffer.getScreenLines().contains("beforeafter") -> {
                    captured = true
                    RelayGraphicsCapture(ServerMessage.PaneGraphics("pane", 1, false), "authoritative screen")
                }
                else -> null
            }
        }, publishGraphics = { _, _ -> order += "graphics" })
        try {
            publisher.start()
            val snapshot = publisher.snapshot()
            assertNull(snapshot.graphics, "text-only snapshots need no private raster transfer")
            assertFalse(ShareProtocol.json.encodeToString(RelayPaneSnapshot.serializer(), snapshot).contains("\"graphics\":"),
                "large graphics must not inflate the single-frame encrypted text snapshot")
            publisher.demand(true, 0)
            stream.append("before\u001b_G" + "x".repeat(2 * 1024 * 1024) + "\u001b\\after")
            withTimeout(5000) { while (order.lastOrNull() != "graphics") delay(5) }
            val receiver = RelayOutputCrypto.Receiver("room", "pane", snapshot.key)
            val messages = frames.map { ShareProtocol.decodeServer(receiver.decrypt(it)) }
            assertEquals("beforeafter", messages.filterIsInstance<ServerMessage.PaneOutput>().joinToString("") { it.data })
            assertEquals("authoritative screen", (messages.last() as ServerMessage.PaneRepaint).data)
            assertEquals("graphics", order.last())
            assertTrue(failures.isEmpty())
            publisher.demand(false, 0)
            val count = frames.size
            stream.append(" hidden")
            withTimeout(2000) { while (!buffer.getScreenLines().contains("hidden")) delay(5) }
            delay(150)
            assertEquals(count, frames.size)
        } finally {
            publisher.close(); stream.close()
            try { drain.get(3, TimeUnit.SECONDS) } finally { executor.shutdownNow(); display.dispose() }
        }
    }

    @Test fun `applied output is published once and snapshots carry a consistent sequence boundary`() = runBlocking {
        val display = ComposeTerminalDisplay()
        val style = StyleState()
        val buffer = TerminalTextBuffer(80, 24, style)
        val terminal = BossTerminal(display, buffer, style)
        val stream = BlockingTerminalDataStream()
        val executor = Executors.newSingleThreadExecutor()
        val drain = executor.submit { drainTerminalEmulator(BossEmulator(stream, terminal), stream, terminal, { true }) }
        val frames = CopyOnWriteArrayList<RelayOutput>()
        val failures = CopyOnWriteArrayList<Exception>()
        val publisher = RelayPanePublisher("room", "pane", stream, RelayOutputCrypto.newHostIdentity(), {
            display.captureStableRenderFrame { ShareProtocol.encodeServer(ServerMessage.PaneSnapshot("pane", buffer.getScreenLines(), 80, 24, 0)) }
        }, { frames += it; true }, { failures += it })
        try {
            publisher.start()
            val initial = publisher.snapshot()
            val receivers = List(3) { RelayOutputCrypto.Receiver("room", "pane", initial.key) }
            publisher.demand(true, 0)
            stream.append("hello")
            withTimeout(2000) { while (frames.isEmpty()) delay(5) }
            assertEquals(1, frames.size, "one publication for every viewer")
            for (receiver in receivers) {
                val message = ShareProtocol.decodeServer(receiver.decrypt(frames.single())) as ServerMessage.PaneOutput
                assertEquals("hello", message.data)
            }
            val snapshot = publisher.snapshot()
            assertEquals(frames.last().seq, snapshot.sequence)
            assertTrue((ShareProtocol.decodeServer(snapshot.screen) as ServerMessage.PaneSnapshot).data.contains("hello"))
            val captured = CompletableDeferred<Unit>()
            val deliver = CompletableDeferred<Unit>()
            val heldSnapshot = async {
                publisher.withSnapshot {
                    captured.complete(Unit)
                    deliver.await()
                    it
                }
            }
            captured.await()
            stream.append(" during delivery")
            withTimeout(2000) { while (!buffer.getScreenLines().contains("during delivery")) delay(5) }
            delay(50)
            assertEquals(1, frames.size, "deltas must wait until the snapshot is queued")
            deliver.complete(Unit)
            assertEquals(1L, heldSnapshot.await().sequence)
            withTimeout(2000) { while (frames.size < 2) delay(5) }
            assertEquals(" during delivery", (ShareProtocol.decodeServer(receivers.first().decrypt(frames.last())) as ServerMessage.PaneOutput).data)
            publisher.demand(false, 0)
            stream.append(" hidden")
            withTimeout(2000) { while (!buffer.getScreenLines().contains("hidden")) delay(5) }
            assertEquals(2, frames.size)
            val restored = publisher.snapshot()
            assertEquals(2L, restored.sequence)
            assertTrue((ShareProtocol.decodeServer(restored.screen) as ServerMessage.PaneSnapshot).data.contains("hello during delivery hidden"))
            publisher.rotateKey()
            val rotated = publisher.snapshot()
            assertNotEquals(initial.epoch, rotated.epoch)
            assertEquals(0L, rotated.sequence)
            publisher.demand(true, 0)
            stream.append(" fresh")
            withTimeout(2000) { while (frames.size < 3) delay(5) }
            assertFailsWith<IllegalArgumentException> { receivers.first().decrypt(frames.last()) }
            val rotatedReceiver = RelayOutputCrypto.Receiver("room", "pane", rotated.key)
            rotatedReceiver.decrypt(frames.last())
            publisher.demand(true, 0, outputFps = 1)
            stream.append(" batch-one")
            withTimeout(2000) { while (!buffer.getScreenLines().contains("batch-one")) delay(5) }
            delay(30)
            stream.append(" batch-two")
            withTimeout(2000) { while (!buffer.getScreenLines().contains("batch-two")) delay(5) }
            delay(50)
            assertEquals(3, frames.size, "batch-only demand must not flush at focused-pane cadence")
            publisher.demand(true, 0, outputFps = 60)
            withTimeout(500) { while (frames.size < 4) delay(5) }
            assertEquals(" batch-one batch-two", (ShareProtocol.decodeServer(rotatedReceiver.decrypt(frames.last())) as ServerMessage.PaneOutput).data)
            assertTrue(failures.isEmpty())
        } finally {
            publisher.close(); stream.close()
            try { drain.get(3, TimeUnit.SECONDS) } finally { executor.shutdownNow(); display.dispose() }
        }
    }
}
