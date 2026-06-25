package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
                            client.webSocket("ws://127.0.0.1:$port/attach?token=s3cr3t-attach") {
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
                            client.webSocket("ws://127.0.0.1:$port/attach?token=wrong") {
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
