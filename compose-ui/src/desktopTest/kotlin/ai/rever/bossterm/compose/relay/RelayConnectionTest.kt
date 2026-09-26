package ai.rever.bossterm.compose.relay

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RelayConnectionTest {
    @Test fun `relay endpoint refuses plaintext remote origins and credential-bearing URLs`() {
        for (url in listOf("ws://example.test", "wss://user:pass@example.test", "wss://example.test/?ticket=secret", "wss://example.test/#secret", "wss://example.test/path")) {
            assertFailsWith<IllegalArgumentException> { RelayConnection.validateEndpoint(url) }
        }
        assertEquals("wss://example.test", RelayConnection.validateEndpoint("wss://example.test"))
        assertFailsWith<IllegalArgumentException> { RelayConnection.validateEndpoint("ws://example.test", true) }
    }

    @Test fun `ticket uses first frame and delivery is acknowledged only after application`() = runBlocking {
        val applied = CompletableDeferred<Unit>()
        val acknowledged = CompletableDeferred<Unit>()
        val room = "11111111-1111-4111-8111-111111111111"
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing { webSocket("/v1/rooms/$room") {
                val hello = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("x".repeat(43), hello.getValue("ticket").jsonPrimitive.content)
                send("""{"op":"welcome","v":1,"peer":"viewer"}""")
                send("""{"op":"frames","delivery":1,"messages":[{"op":"output","payload":"opaque"}]}""")
                val ack = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals(true, applied.isCompleted)
                assertEquals("ack", ack.getValue("op").jsonPrimitive.content)
                assertEquals("1", ack.getValue("through").jsonPrimitive.content)
                acknowledged.complete(Unit)
            } }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val connection = RelayConnection("ws://127.0.0.1:$port", room, { "x".repeat(43) }, {
            if (it["op"]?.jsonPrimitive?.content == "output") applied.complete(Unit)
        }, allowLoopbackForTests = true)
        try {
            val job = launch { connection.run() }
            withTimeout(10_000) { acknowledged.await(); job.join() }
            assertFalse(connection.send(Json.parseToJsonElement("""{"op":"input"}""").jsonObject))
        } finally { connection.close(); server.stop(0, 1000) }
    }
}
