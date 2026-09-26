package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class RelayViewDemand(val visible: Set<String> = emptySet(), val focused: String? = null) {
    fun mode(pane: String, preferences: TerminalViewingPreferences): String = when {
        pane !in visible -> "hidden"
        pane == focused -> "live"
        else -> preferences.unfocused_mode
    }
}

/** Single-use viewer: all handshake, private counters, pane keys and input queues reset on reconnect. */
internal class RelayRemoteConnection(
    endpoint: String,
    private val room: String,
    private val token: String,
    private val secret: ByteArray,
    private val hello: ClientMessage.Hello,
    private val handle: suspend (ServerMessage) -> Unit,
    private val visibility: StateFlow<RelayViewDemand>,
    private val preferences: StateFlow<TerminalViewingPreferences>,
    allowLoopbackForTests: Boolean = false,
) : AutoCloseable {
    private val salt = SessionCrypto.randomSalt()
    private val decoder = RelayPeerCodec.Decoder(RelayPeerCodec.MAX_GRAPHICS_FRAME_BYTES)
    private val ready = CompletableDeferred<Unit>()
    private val sendLock = Mutex()
    private val stateLock = Mutex()
    private val subscriptionChanges = Channel<Unit>(Channel.CONFLATED)
    private val subscriptions = mutableMapOf<String, Pair<String, Int>>()
    private var clientCipher: SessionCrypto.FrameCipher? = null
    private var serverCipher: SessionCrypto.FrameCipher? = null
    private var sent = 0L
    private var received = 0L
    private val receivers = mutableMapOf<String, RelayOutputCrypto.Receiver>()
    private val waiting = mutableSetOf<String>()
    private var granted = emptySet<String>()
    private val graphicsGate = RelayGraphicsGate(handle, ::resync, requestGraphics = { pane, sequence ->
        sendClient(ClientMessage.GraphicsResync(pane, relaySequence = sequence))
    })
    private val connection = RelayConnection(endpoint, room, { null }, ::message, allowLoopbackForTests)

    suspend fun run(input: ReceiveChannel<ClientMessage>) = coroutineScope {
        val writer = launch {
            withTimeout(10_000) { ready.await() }
            for (message in input) sendClient(message)
        }
        val observer = launch {
            combine(visibility, preferences) { _, _ -> Unit }.collect { subscriptionChanges.trySend(Unit) }
        }
        val subscriber = launch {
            for (ignored in subscriptionChanges) {
                while (true) {
                    val changed = stateLock.withLock {
                        val next = granted.firstOrNull { pane ->
                            val desired = visibility.value.mode(pane, preferences.value) to preferences.value.unfocused_fps
                            (subscriptions[pane] ?: ("hidden" to desired.second)) != desired
                        } ?: return@withLock false
                        val mode = visibility.value.mode(next, preferences.value)
                        val fps = preferences.value.unfocused_fps
                        subscriptions[next] = mode to fps
                        waiting.add(next)
                        graphicsGate.reset(next)
                        send(buildJsonObject { put("op", "subscribe"); put("pane", next); put("mode", mode); put("fps", fps) })
                        true
                    }
                    if (!changed) break
                    delay(25) // Stay below the relay's control-frame rate limit even with 100 panes.
                }
            }
        }
        val graphicsTimeout = launch {
            while (isActive) { delay(1_000); stateLock.withLock { graphicsGate.expire() } }
        }
        try { connection.run() } finally { writer.cancel(); observer.cancel(); subscriber.cancel(); graphicsTimeout.cancel(); close() }
    }

    private suspend fun sendClient(message: ClientMessage) = sendLock.withLock {
        val envelope = RelayPrivateEnvelope(++sent, ShareProtocol.encodeClient(message))
        privateFrame(Frame.Binary(true, clientCipher!!.encrypt(ShareProtocol.json.encodeToString(RelayPrivateEnvelope.serializer(), envelope))))
    }

    private fun send(message: JsonObject) { check(connection.send(message)) { "Relay connection unavailable" } }
    private fun privateFrame(frame: Frame, first: Boolean = false) {
        for (packet in RelayPeerCodec.encode(frame, if (first) token else null)) send(buildJsonObject {
            put("op", "signal"); put("payload", packet)
        })
    }

    private suspend fun message(message: JsonObject): Unit = stateLock.withLock {
        when (message["op"]?.jsonPrimitive?.content) {
            "welcome" -> privateFrame(Frame.Text(ShareProtocol.encodeKex(Kex(v = 1, salt = SessionCrypto.encodeSecretB64Url(salt)))), first = true)
            "signal", "snapshot" -> {
                val packet = ShareProtocol.json.decodeFromString(RelayPeerPacket.serializer(), message.getValue("payload").jsonPrimitive.content)
                val decoded = decoder.accept(packet)
                if (packet.credit) send(buildJsonObject {
                    put("op", "peerCredit")
                    put("payload", buildJsonObject { put("credit", packet.id); put("part", packet.part) }.toString())
                })
                val frame = decoded ?: return
                val cipher = serverCipher
                if (cipher == null) {
                    require(message["op"]?.jsonPrimitive?.content == "signal" && frame is Frame.Text)
                    val reply = ShareProtocol.decodeKex(frame.readText()) ?: error("Encrypted handshake failed")
                    require(reply.v == 1) { "Unsupported encryption version" }
                    val keys = SessionCrypto.deriveKeys(secret, salt, SessionCrypto.decodeSecretB64Url(reply.salt))
                    require(SessionCrypto.confirmMatches(keys.confirm, reply.confirm)) { "Encrypted handshake failed" }
                    clientCipher = SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_C2S)
                    serverCipher = SessionCrypto.FrameCipher(keys.kS2c, SessionCrypto.DIR_S2C)
                    privateFrame(Frame.Binary(true, clientCipher!!.encrypt(ShareProtocol.encodeClient(
                        hello.copy(capabilities = (hello.capabilities + listOf("relay-v1", PANE_GRAPHICS_CAPABILITY)).distinct()),
                    ))))
                    ready.complete(Unit)
                    return
                }
                require(frame is Frame.Binary) { "Unencrypted relay message" }
                val envelope = ShareProtocol.json.decodeFromString(RelayPrivateEnvelope.serializer(), cipher.decrypt(frame.data))
                require(envelope.sequence == received + 1) { "Replayed or missing private message" }
                received = envelope.sequence
                if (message["op"]?.jsonPrimitive?.content == "snapshot") {
                    val snapshot = ShareProtocol.json.decodeFromString(RelayPaneSnapshot.serializer(), envelope.payload)
                    require(snapshot.room == room && snapshot.pane in granted && snapshot.key.epoch == snapshot.epoch)
                    require(message["pane"]?.jsonPrimitive?.content == snapshot.pane &&
                        message["epoch"]?.jsonPrimitive?.content == snapshot.epoch &&
                        message["seq"]?.jsonPrimitive?.long == snapshot.sequence) { "Snapshot boundary mismatch" }
                    val screen = ShareProtocol.decodeServer(snapshot.screen)
                    require(screen is ServerMessage.PaneSnapshot && screen.paneId == snapshot.pane)
                    val receiver = RelayOutputCrypto.Receiver(room, snapshot.pane, snapshot.key)
                    receiver.applySnapshotBoundary(snapshot.sequence)
                    graphicsGate.snapshot(screen)
                    receivers[snapshot.pane] = receiver
                    waiting.remove(snapshot.pane)
                } else {
                    val privateMessage = ShareProtocol.decodeServer(envelope.payload)
                    if (privateMessage is ServerMessage.PaneGraphics) {
                        if (privateMessage.paneId in granted && privateMessage.paneId !in waiting)
                            graphicsGate.graphics(privateMessage)
                    } else handle(privateMessage)
                }
            }
            "grant" -> {
                val panes = message.getValue("panes").jsonArray.map { it.jsonPrimitive.content }.toSet()
                require(panes.size <= 1000)
                (granted - panes).forEach(graphicsGate::reset)
                receivers.keys.retainAll(panes)
                waiting.retainAll(panes)
                subscriptions.keys.retainAll(panes)
                granted = panes
                waiting.addAll(panes)
                subscriptionChanges.trySend(Unit)
            }
            "output" -> {
                val frame = ShareProtocol.json.decodeFromJsonElement(RelayOutput.serializer(), message)
                require(frame.pane in granted && frame.room == room)
                if (frame.pane in waiting) return
                val receiver = receivers[frame.pane] ?: return resync(frame.pane)
                val plaintext = try { receiver.decrypt(frame) } catch (_: Exception) { return resync(frame.pane) }
                val output = ShareProtocol.decodeServer(plaintext)
                require((frame.kind == "live" && ((output is ServerMessage.PaneOutput && output.paneId == frame.pane) ||
                    (output is ServerMessage.PaneRepaint && output.paneId == frame.pane))) ||
                    (frame.kind == "preview" && output is ServerMessage.PaneSnapshot && output.paneId == frame.pane))
                if (output is ServerMessage.PaneSnapshot) graphicsGate.snapshot(output, requestMissingGraphics = true) else graphicsGate.output(output)
            }
            "resync" -> message["pane"]?.jsonPrimitive?.content?.let(::resync)
            "leave" -> error("Relay host disconnected")
        }
    }

    private fun resync(pane: String) {
        if (pane !in granted || !waiting.add(pane)) return
        graphicsGate.reset(pane)
        send(buildJsonObject { put("op", "resync"); put("pane", pane); put("payload", "") })
    }

    override fun close() { connection.close(); ready.cancel() }
}
