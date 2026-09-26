package ai.rever.bossterm.compose.relay

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RelayHostSessionTest {
    @Test fun `host rejects unknown shares without losing room and reconnects with fresh admission`() = runBlocking {
        val room = "11111111-1111-4111-8111-111111111111"
        val tickets = CopyOnWriteArrayList<String>()
        val ready = CopyOnWriteArrayList<Boolean>()
        val verified = List(2) { CompletableDeferred<Unit>() }
        val attempts = AtomicInteger()
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing { webSocket("/v1/rooms/$room") {
                val attempt = attempts.getAndIncrement()
                try {
                    suspend fun wire() = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                    val hello = wire()
                    assertEquals("hello", hello["op"]?.jsonPrimitive?.content)
                    assertEquals(room, hello["room"]?.jsonPrimitive?.content)
                    tickets += hello.getValue("ticket").jsonPrimitive.content
                    send("""{"op":"welcome","v":1,"peer":"host"}""")
                    val packet = RelayPeerCodec.encode(Frame.Text("invalid handshake"), "expired-share-token").single()
                    send(buildJsonObject { put("op", "signal"); put("peer", "guest"); put("payload", packet) }.toString())
                    assertEquals("revoke", wire()["op"]?.jsonPrimitive?.content)
                    // A late leave and another invalid peer must affect only that peer.
                    send("""{"op":"leave","peer":"guest"}""")
                    send(buildJsonObject { put("op", "signal"); put("peer", "second"); put("payload", packet) }.toString())
                    var response: JsonObject
                    do { response = wire() } while (response["peer"]?.jsonPrimitive?.content != "second")
                    assertEquals("revoke", response["op"]?.jsonPrimitive?.content)
                    verified[attempt].complete(Unit)
                    close(CloseReason(1012, "test reconnect"))
                } catch (error: Throwable) { verified[attempt].completeExceptionally(error); throw error }
            } }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        try {
            repeat(2) { attempt ->
                val session = RelayHostSession("ws://127.0.0.1:$port", room, "owner",
                    ticketProvider = { ('a' + attempt).toString().repeat(43) }, allowLoopbackForTests = true,
                    readyChanged = { ready += it })
                try {
                    withTimeout(10_000) {
                        val run = async { session.run() }
                        verified[attempt].await()
                        run.await()
                    }
                } finally { session.close() }
            }
            assertEquals(listOf("a".repeat(43), "b".repeat(43)), tickets)
            assertEquals(listOf(true, false, true, false), ready)
        } finally { server.stop(0, 1000) }
    }
}
