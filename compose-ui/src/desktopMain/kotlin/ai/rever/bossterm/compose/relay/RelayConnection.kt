package ai.rever.bossterm.compose.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * One connection, one ordered writer, and a bounded outbound queue. A connection is single-use:
 * its owner must create fresh admission and crypto state to reconnect. Inputs are never replayed.
 * [onMessage] must finish processing a frame before returning; only then is delivery acknowledged.
 */
internal class RelayConnection(
    endpoint: String,
    room: String,
    private val ticket: suspend () -> String?,
    private val onMessage: suspend (JsonObject) -> Unit,
    private val allowLoopbackForTests: Boolean = false,
) : AutoCloseable {
    private val roomId = UUID.fromString(room).toString()
    private val url = validateEndpoint(endpoint, allowLoopbackForTests).trimEnd('/') + "/v1/rooms/$roomId"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = MAX_WIRE_BYTES.toLong() } }
    private val outgoing = Channel<String>(128)
    private val queuedBytes = AtomicLong()
    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile private var session: DefaultClientWebSocketSession? = null

    /** False means the connection must be replaced and synchronized; no frame was queued. */
    fun send(message: JsonObject): Boolean {
        if (closed || !started) return false
        val text = message.toString()
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > MAX_WIRE_BYTES || queuedBytes.addAndGet(size.toLong()) > MAX_QUEUE_BYTES) {
            if (size <= MAX_WIRE_BYTES) queuedBytes.addAndGet(-size.toLong())
            close()
            return false
        }
        if (outgoing.trySend(text).isFailure) {
            queuedBytes.addAndGet(-size.toLong())
            close()
            return false
        }
        return true
    }

    suspend fun run() {
        synchronized(this) { check(!started && !closed); started = true }
        try {
            val admission = ticket()
            require(admission == null || Regex("[A-Za-z0-9_-]{43}").matches(admission))
            client.webSocket(urlString = url) {
                session = this
                check(!closed)
                send(buildJsonObject {
                    put("op", "hello"); put("v", 1); put("room", roomId)
                    admission?.let { put("ticket", it) }
                }.toString())
                val first = withTimeout(10_000) { incoming.receive() }
                require(first is Frame.Text) { "Relay handshake failed" }
                val welcome = json.parseToJsonElement(first.readText()).jsonObject
                require(welcome["op"]?.jsonPrimitive?.content == "welcome" && welcome["v"]?.jsonPrimitive?.content == "1") {
                    "Unsupported relay protocol"
                }
                onMessage(welcome)
                coroutineScope {
                    val writer = launch {
                        for (text in this@RelayConnection.outgoing) {
                            queuedBytes.addAndGet(-text.toByteArray(Charsets.UTF_8).size.toLong())
                            send(text)
                        }
                    }
                    var delivery = 0L
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) error("Invalid relay frame")
                            val message = json.parseToJsonElement(frame.readText()).jsonObject
                            if (message["op"]?.jsonPrimitive?.content == "frames") {
                                val next = message.getValue("delivery").jsonPrimitive.long
                                require(next == delivery + 1) { "Relay delivery gap" }
                                val messages = message.getValue("messages").jsonArray
                                require(messages.size in 1..2048)
                                for (item in messages) onMessage(item.jsonObject)
                                delivery = next
                                check(this@RelayConnection.send(buildJsonObject { put("op", "ack"); put("through", next) }))
                            } else onMessage(message)
                        }
                    } finally { writer.cancel() }
                }
            }
        } finally { close() }
    }

    override fun close() {
        closed = true
        outgoing.cancel()
        session?.cancel()
        session = null
        client.close()
    }

    companion object {
        const val MAX_WIRE_BYTES = 1024 * 1024
        const val MAX_QUEUE_BYTES = 2L * 1024 * 1024

        internal fun validateEndpoint(endpoint: String, allowLoopback: Boolean = false): String {
            val uri = URI(endpoint)
            require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Relay endpoint must be an origin" }
            val loopback = uri.host in setOf("localhost", "127.0.0.1", "[::1]")
            require(uri.scheme == "wss" || (allowLoopback && loopback && uri.scheme == "ws")) { "Relay requires TLS" }
            return endpoint
        }
    }
}
