package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless WS-client test of [DaemonAttachServer] (the GUI-independent half of the thin-client):
 * token auth, the initial SessionList + styled Snapshot, live Output, and Input round-trip — all
 * against a real PTY-backed [SessionHost]. The GUI bridge needs Compose's Main dispatcher and is
 * exercised at app runtime instead.
 */
class DaemonAttachServerTest {

    @Test
    fun `attach streams snapshot + output and accepts input`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonAttachServer(host, secret = "s3cr3t-attach")
        val marker = "ATTACH_OK_3030"
        try {
            val port = server.start(desiredPort = 7720)
            assertTrue(port > 0, "attach server should bind")
            // A cat session so we can also test input echo.
            val id = host.openSession(command = "/bin/cat", arguments = emptyList())

            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                val sawSession = runBlocking {
                    var ok = false
                    runCatching {
                        withTimeout(10_000) {
                            client.webSocket("ws://127.0.0.1:$port/attach", request = { header(DaemonAttachProtocol.TOKEN_HEADER, "s3cr3t-attach") }) {
                                // wait until the session is alive, then send input to cat
                                var sentInput = false
                                for (frame in incoming) {
                                    if (frame !is Frame.Text) continue
                                    val msg = DaemonAttachProtocol.decodeServer(frame.readText())
                                    if (msg is DaemonAttachProtocol.Server.SessionList && msg.sessions.any { it.id == id }) {
                                        ok = true
                                        if (!sentInput) {
                                            sentInput = true
                                            send(Frame.Text(DaemonAttachProtocol.encodeClient(
                                                DaemonAttachProtocol.Client.Input(id, "$marker\n")
                                            )))
                                        }
                                    }
                                    val text = when (msg) {
                                        is DaemonAttachProtocol.Server.Snapshot -> msg.data
                                        is DaemonAttachProtocol.Server.Output -> msg.data
                                        else -> ""
                                    }
                                    if (text.contains(marker)) return@webSocket // echoed back → done
                                }
                            }
                        }
                    }
                    ok
                }
                assertTrue(sawSession, "client should receive a SessionList naming the session (and the input echo)")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
            host.shutdownAll()
        }
    }

    @Test
    fun `attach echoes a client resize back as Resized`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonAttachServer(host, secret = "rz")
        try {
            val port = server.start(desiredPort = 7760)
            assertTrue(port > 0)
            val id = host.openSession(command = "/bin/cat", arguments = emptyList())
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                val resized = runBlocking {
                    var ok = false
                    runCatching {
                        withTimeout(10_000) {
                            client.webSocket("ws://127.0.0.1:$port/attach", request = { header(DaemonAttachProtocol.TOKEN_HEADER, "rz") }) {
                                var sent = false
                                for (frame in incoming) {
                                    if (frame !is Frame.Text) continue
                                    val msg = DaemonAttachProtocol.decodeServer(frame.readText())
                                    if (!sent && msg is DaemonAttachProtocol.Server.SessionList && msg.sessions.any { it.id == id }) {
                                        sent = true
                                        send(Frame.Text(DaemonAttachProtocol.encodeClient(
                                            DaemonAttachProtocol.Client.Resize(id, 100, 40)
                                        )))
                                    }
                                    if (msg is DaemonAttachProtocol.Server.Resized && msg.id == id && msg.cols == 100 && msg.rows == 40) {
                                        ok = true; return@webSocket
                                    }
                                }
                            }
                        }
                    }
                    ok
                }
                assertTrue(resized, "a client Resize should drive the session and echo back as Resized(100,40)")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
            host.shutdownAll()
        }
    }

    @Test
    fun `attach rejects an incompatible protocol version`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonAttachServer(host, secret = "ver")
        try {
            val port = server.start(desiredPort = 7770)
            assertTrue(port > 0)
            val client = HttpClient(CIO) { install(WebSockets) }
            val gotData = try {
                runBlocking {
                    var received = false
                    runCatching {
                        withTimeout(2500) {
                            // Correct token, but a present-but-mismatched ?v= must be refused (wire-skew).
                            client.webSocket("ws://127.0.0.1:$port/attach?v=999999", request = { header(DaemonAttachProtocol.TOKEN_HEADER, "ver") }) {
                                for (frame in incoming) { received = true; break }
                            }
                        }
                    }
                    received
                }
            } finally {
                client.close()
            }
            assertTrue(!gotData, "an incompatible protocol version must not receive any session frames")
        } finally {
            server.stop()
            host.shutdownAll()
        }
    }

    @Test
    fun `attach survives malformed and unknown client frames`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonAttachServer(host, secret = "mal")
        val marker = "STILL_ALIVE_5151"
        try {
            val port = server.start(desiredPort = 7775)
            assertTrue(port > 0)
            val id = host.openSession(command = "/bin/cat", arguments = emptyList())
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                val echoed = runBlocking {
                    var ok = false
                    runCatching {
                        withTimeout(10_000) {
                            client.webSocket("ws://127.0.0.1:$port/attach", request = { header(DaemonAttachProtocol.TOKEN_HEADER, "mal") }) {
                                var poked = false
                                for (frame in incoming) {
                                    if (frame !is Frame.Text) continue
                                    val msg = DaemonAttachProtocol.decodeServer(frame.readText())
                                    if (!poked && msg is DaemonAttachProtocol.Server.SessionList && msg.sessions.any { it.id == id }) {
                                        poked = true
                                        // Garbage + unknown discriminator must be skipped, not crash the loop…
                                        send(Frame.Text("this is not valid json at all"))
                                        send(Frame.Text("{\"t\":\"NoSuchClientMessage\",\"x\":1}"))
                                        // …and a valid Input right after must still drive the session.
                                        send(Frame.Text(DaemonAttachProtocol.encodeClient(
                                            DaemonAttachProtocol.Client.Input(id, "$marker\n")
                                        )))
                                    }
                                    val text = when (msg) {
                                        is DaemonAttachProtocol.Server.Snapshot -> msg.data
                                        is DaemonAttachProtocol.Server.Output -> msg.data
                                        else -> ""
                                    }
                                    if (text.contains(marker)) { ok = true; return@webSocket }
                                }
                            }
                        }
                    }
                    ok
                }
                assertTrue(echoed, "connection must survive malformed/unknown frames and still echo a later valid Input")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
            host.shutdownAll()
        }
    }

    @Test
    fun `disconnect removes the attached client`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonAttachServer(host, secret = "dc")
        try {
            val port = server.start(desiredPort = 7780)
            assertTrue(port > 0)
            host.openSession(command = "/bin/cat", arguments = emptyList())
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                runBlocking {
                    withTimeout(10_000) {
                        client.webSocket("ws://127.0.0.1:$port/attach", request = { header(DaemonAttachProtocol.TOKEN_HEADER, "dc") }) {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val msg = DaemonAttachProtocol.decodeServer(frame.readText())
                                if (msg is DaemonAttachProtocol.Server.SessionList) {
                                    assertTrue(server.clientCount >= 1, "client must be registered while attached")
                                    return@webSocket // closes the socket → server finally runs
                                }
                            }
                        }
                    }
                }
                // The finally block (clients.remove) runs shortly after the socket closes.
                val cleared = runBlocking {
                    withTimeoutOrNull(5_000) { while (server.clientCount != 0) kotlinx.coroutines.delay(20); true } ?: false
                }
                assertTrue(cleared, "client count must return to 0 after disconnect (was ${server.clientCount})")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
            host.shutdownAll()
        }
    }

    @Test
    fun `attach rejects a bad token`() {
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonAttachServer(host, secret = "right")
        try {
            val port = server.start(desiredPort = 7740)
            assertTrue(port > 0)
            val client = HttpClient(CIO) { install(WebSockets) }
            val gotData = try {
                runBlocking {
                    var received = false
                    runCatching {
                        withTimeout(2500) {
                            client.webSocket("ws://127.0.0.1:$port/attach", request = { header(DaemonAttachProtocol.TOKEN_HEADER, "wrong") }) {
                                for (frame in incoming) { received = true; break }
                            }
                        }
                    }
                    received
                }
            } finally {
                client.close()
            }
            assertTrue(!gotData, "a bad token must not receive any session frames")
        } finally {
            server.stop()
            host.shutdownAll()
        }
    }
}
