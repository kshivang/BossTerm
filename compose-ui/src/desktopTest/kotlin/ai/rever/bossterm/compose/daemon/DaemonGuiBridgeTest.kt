package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonGuiBridgeTest {
    @Test
    fun `tray activation survives zero terminal windows and reconnects after server restart`() = runBlocking {
        val host = SessionHost(TerminalSettings.DEFAULT)
        val opens = AtomicInteger()
        val activations = AtomicInteger()
        val server = DaemonAttachServer(host, "activation", activateGui = { activations.incrementAndGet() })
        val port = server.start(0)
        assertTrue(port > 0)
        val bridge = DaemonGuiBridge(port, "activation", this, { opens.incrementAndGet() }, Dispatchers.Unconfined).start()
        try {
            await { server.guiClientCount == 1 }
            assertEquals(0, server.clientCount)
            assertEquals(0, host.count())
            assertEquals(1, server.focusClients())
            await { opens.get() == 1 }
            assertEquals(1, activations.get())

            server.stop()
            await { server.guiClientCount == 0 }
            assertEquals(port, server.start(port))
            await { server.guiClientCount == 1 }
            assertEquals(1, server.focusClients())
            await { opens.get() == 2 }
            assertEquals(0, host.count(), "activation must not create a terminal session on the daemon")

            bridge.cancelAndJoin()
            await { server.guiClientCount == 0 }
            assertEquals(0, server.focusClients())
        } finally {
            bridge.cancelAndJoin()
            server.stop()
            host.close()
        }
    }

    @Test
    fun `app activation takes precedence over window mirrors`() = runBlocking {
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonAttachServer(host, "activation", activateGui = {})
        val port = server.start(0)
        val opens = AtomicInteger()
        val windowFocus = AtomicInteger()
        val client = HttpClient(CIO) { install(WebSockets) }
        val window = launch(Dispatchers.IO) {
            client.webSocket("ws://127.0.0.1:$port/attach", request = { header(DaemonAttachProtocol.TOKEN_HEADER, "activation") }) {
                for (frame in incoming) {
                    if (frame is Frame.Text && DaemonAttachProtocol.decodeServer(frame.readText()) is DaemonAttachProtocol.Server.Focus) {
                        windowFocus.incrementAndGet()
                    }
                }
            }
        }
        val bridge = DaemonGuiBridge(port, "activation", this, { opens.incrementAndGet() }, Dispatchers.Unconfined).start()
        try {
            await { server.guiClientCount == 1 && server.clientCount == 1 }
            assertEquals(1, server.focusClients(), "one activation per app, not one per window")
            await { opens.get() == 1 }
            assertEquals(0, windowFocus.get())
            bridge.cancelAndJoin()
            await { server.guiClientCount == 0 }
            assertEquals(1, server.focusClients())
            await { windowFocus.get() == 1 }
        } finally {
            bridge.cancelAndJoin()
            window.cancelAndJoin()
            client.close()
            server.stop()
            host.close()
        }
    }

    @Test
    fun `lifecycle registration requires the same token and protocol as terminal attach`() = runBlocking {
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonAttachServer(host, "activation", activateGui = {})
        val port = server.start(0)
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            for ((token, version) in listOf("wrong" to DaemonAttachProtocol.PROTOCOL_VERSION, "activation" to -1)) {
                withTimeout(5000) {
                    client.webSocket("ws://127.0.0.1:$port/attach?lifecycle=1&v=$version", request = {
                        header(DaemonAttachProtocol.TOKEN_HEADER, token)
                    }) {
                        for (frame in incoming) {
                            assertTrue(frame !is Frame.Text, "rejected registration must not receive application messages")
                        }
                    }
                }
                assertEquals(0, server.guiClientCount)
                assertEquals(0, server.focusClients())
            }
        } finally {
            client.close()
            server.stop()
            host.close()
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(5000) {
        while (!predicate()) delay(10)
    }
}
