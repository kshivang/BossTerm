package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.*
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class RelayRemoteConnectionTest {
    @Test fun `native relay authenticates snapshots and group output and rejects replayed private traffic`() = runBlocking {
        val room = "11111111-1111-4111-8111-111111111111"
        val secret = SessionCrypto.newSessionSecret()
        val received = CopyOnWriteArrayList<ServerMessage>()
        val input = Channel<ClientMessage>(8)
        val inputVerified = CompletableDeferred<Unit>()
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing { webSocket("/v1/rooms/$room") {
                try {
                    suspend fun wire(): JsonObject = ShareProtocol.json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                    suspend fun peerFrame(): Frame {
                        val decoder = RelayPeerCodec.Decoder()
                        while (true) {
                            val m = wire()
                            if (m["op"]?.jsonPrimitive?.content != "signal") continue
                            val packet = ShareProtocol.json.decodeFromString(RelayPeerPacket.serializer(), m.getValue("payload").jsonPrimitive.content)
                            decoder.accept(packet)?.let { return it }
                        }
                    }
                    suspend fun privateFrame(frame: Frame, credited: Boolean = false) {
                        for (packet in RelayPeerCodec.packets(frame, credited = credited)) {
                            send(buildJsonObject {
                                put("op", "signal"); put("payload", RelayPeerCodec.json.encodeToString(RelayPeerPacket.serializer(), packet))
                            }.toString())
                            if (credited) {
                                val ack = wire()
                                assertEquals("peerCredit", ack["op"]?.jsonPrimitive?.content)
                                val receipt = ShareProtocol.json.parseToJsonElement(ack.getValue("payload").jsonPrimitive.content).jsonObject
                                assertEquals(packet.id, receipt["credit"]?.jsonPrimitive?.content)
                                assertEquals(packet.part, receipt["part"]?.jsonPrimitive?.int)
                            }
                        }
                    }
                    assertEquals("hello", wire()["op"]?.jsonPrimitive?.content)
                    send("""{"op":"welcome","v":1,"peer":"v"}""")
                    val kex = ShareProtocol.decodeKex((peerFrame() as Frame.Text).readText())!!
                    val saltS = SessionCrypto.randomSalt()
                    val keys = SessionCrypto.deriveKeys(secret, SessionCrypto.decodeSecretB64Url(kex.salt), saltS)
                    privateFrame(Frame.Text(ShareProtocol.encodeKex(Kex(v = 1, salt = SessionCrypto.encodeSecretB64Url(saltS), confirm = SessionCrypto.encodeSecretB64Url(keys.confirm)))))
                    val fromViewer = SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_C2S)
                    val toViewer = SessionCrypto.FrameCipher(keys.kS2c, SessionCrypto.DIR_S2C)
                    val hello = ShareProtocol.decodeClient(fromViewer.decrypt((peerFrame() as Frame.Binary).data)) as ClientMessage.Hello
                    assertTrue("relay-v1" in hello.capabilities)
                    assertTrue(PANE_GRAPHICS_CAPABILITY in hello.capabilities)
                    val pending = Frame.Binary(true, toViewer.encrypt(ShareProtocol.json.encodeToString(RelayPrivateEnvelope.serializer(), RelayPrivateEnvelope(1, ShareProtocol.encodeServer(ServerMessage.Pending)))))
                    privateFrame(pending)
                    send("""{"op":"grant","panes":["pane"]}""")
                    assertEquals("subscribe", wire()["op"]?.jsonPrimitive?.content)
                    val publisher = RelayOutputCrypto.Publisher(room, "pane", RelayOutputCrypto.newHostIdentity())
                    val screen = ServerMessage.PaneSnapshot("pane", "initial", 80, 24, 0, graphicsSequence = 1)
                    val snapshot = RelayPaneSnapshot(room, "pane", publisher.outputKey.epoch, 0, publisher.outputKey, ShareProtocol.encodeServer(screen))
                    val snapshotFrame = Frame.Binary(true, toViewer.encrypt(ShareProtocol.json.encodeToString(RelayPrivateEnvelope.serializer(),
                        RelayPrivateEnvelope(2, ShareProtocol.json.encodeToString(RelayPaneSnapshot.serializer(), snapshot)))))
                    send(buildJsonObject {
                        put("op", "snapshot"); put("pane", "pane"); put("epoch", snapshot.epoch); put("seq", 0)
                        put("payload", RelayPeerCodec.encode(snapshotFrame, single = true).single())
                    }.toString())
                    val graphics = ServerMessage.PaneGraphics("pane", 1, true,
                        images = listOf(SharedTerminalImage("image", "image/png", "x".repeat(2 * 1024 * 1024), "hash")), relaySequence = 1)
                    privateFrame(Frame.Binary(true, toViewer.encrypt(ShareProtocol.json.encodeToString(RelayPrivateEnvelope.serializer(),
                        RelayPrivateEnvelope(3, ShareProtocol.encodeServer(graphics))))), credited = true)
                    val output = publisher.encrypt("live", ShareProtocol.encodeServer(ServerMessage.PaneOutput("pane", " next")))
                    send(buildJsonObject {
                        put("op", "output")
                        ShareProtocol.json.encodeToJsonElement(RelayOutput.serializer(), output).jsonObject.forEach { (k, v) -> put(k, v) }
                    }.toString())
                    val envelope = ShareProtocol.json.decodeFromString(RelayPrivateEnvelope.serializer(), fromViewer.decrypt((peerFrame() as Frame.Binary).data))
                    assertEquals(1L, envelope.sequence)
                    assertEquals(ClientMessage.RequestControl(), ShareProtocol.decodeClient(envelope.payload))
                    inputVerified.complete(Unit)
                    privateFrame(pending) // Valid ciphertext from earlier must still fail the sequence check.
                    for (ignored in incoming) { }
                } catch (e: Throwable) { inputVerified.completeExceptionally(e); throw e }
            } }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val connection = RelayRemoteConnection("ws://127.0.0.1:$port", room, "share-token", secret,
            ClientMessage.Hello("test", "device"), {
                received += it
                if (it is ServerMessage.PaneOutput) input.trySend(ClientMessage.RequestControl())
            }, kotlinx.coroutines.flow.MutableStateFlow(RelayViewDemand(setOf("pane"), "pane")),
            kotlinx.coroutines.flow.MutableStateFlow(TerminalViewingPreferences()), allowLoopbackForTests = true)
        try {
            val result = async { runCatching { connection.run(input) } }
            withTimeout(10_000) {
                inputVerified.await()
                assertTrue(result.await().isFailure, "replayed private frame must terminate this connection")
            }
            assertEquals(1, received.filterIsInstance<ServerMessage.Pending>().size)
            assertEquals("initial", received.filterIsInstance<ServerMessage.PaneSnapshot>().single().data)
            assertEquals(" next", received.filterIsInstance<ServerMessage.PaneOutput>().single().data)
            assertEquals(2 * 1024 * 1024, received.filterIsInstance<ServerMessage.PaneGraphics>().single().images.single().data.length)
        } finally { connection.close(); input.close(); server.stop(0, 1000) }
    }

    @Test fun `peer fragmentation preserves bytes and refuses out of order parts`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val packets = RelayPeerCodec.encode(Frame.Binary(true, bytes), "token").map {
            RelayPeerCodec.json.decodeFromString(RelayPeerPacket.serializer(), it)
        }
        assertEquals("token", packets.first().token)
        assertTrue(packets.drop(1).all { it.token == null })
        val decoder = RelayPeerCodec.Decoder()
        var result: Frame? = null
        for (packet in packets) result = decoder.accept(packet)
        assertContentEquals(bytes, result!!.data)
        assertFailsWith<IllegalArgumentException> { RelayPeerCodec.Decoder().accept(packets[1]) }
        val reordered = RelayPeerCodec.Decoder()
        reordered.accept(packets[0])
        assertFailsWith<IllegalArgumentException> { reordered.accept(packets[2]) }
    }

    @Test fun `large credited graphics fragments remain bounded and cannot enter the viewer input decoder`() {
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val packets = RelayPeerCodec.packets(Frame.Binary(true, bytes), credited = true).toList()
        assertTrue(packets.all { it.credit && it.data.length <= 32 * 1024 })
        assertFailsWith<IllegalArgumentException> { RelayPeerCodec.Decoder().accept(packets.first()) }
        val decoder = RelayPeerCodec.Decoder(RelayPeerCodec.MAX_GRAPHICS_FRAME_BYTES)
        var frame: Frame? = null
        for (packet in packets) frame = decoder.accept(packet)
        assertContentEquals(bytes, frame!!.data)
    }
}
