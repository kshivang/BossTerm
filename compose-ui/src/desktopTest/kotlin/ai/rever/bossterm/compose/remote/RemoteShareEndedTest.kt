package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class RemoteShareEndedTest {
    @Test fun `ending share removes mirrored tabs splits and remote group`() = runBlocking<Unit> {
        val end = CompletableDeferred<Unit>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/ws/test") {
                    incoming.receive()
                    send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Control(true))))
                    send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Layout(listOf(
                        TabNode("tab", "Remote", true, PaneTreeNode.Pane("pane", "Remote", "/tmp", true))
                    ), "tab"))))
                    end.await()
                    close(CloseReason(ShareProtocol.SHARE_ENDED_CLOSE_CODE, "Sharing ended"))
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val state = TabbedTerminalState()
        val manager = RemoteSessionManager(state)
        try {
            withContext(Dispatchers.Main) {
                state.initialize(TerminalSettings(), {}, { true })
                manager.connect("http://127.0.0.1:$port/?t=test", "Test")
            }
            withTimeout(5000) {
                while (!withContext(Dispatchers.Main) { state.tabs.isNotEmpty() && state.splitStates.isNotEmpty() }) delay(20)
            }
            end.complete(Unit)
            withTimeout(5000) {
                while (!withContext(Dispatchers.Main) { manager.sessions.isEmpty() }) delay(20)
            }
            withContext(Dispatchers.Main) {
                assertTrue(state.tabs.isEmpty())
                assertTrue(state.splitStates.isEmpty())
            }
        } finally {
            end.complete(Unit)
            withContext(Dispatchers.Main) { manager.disconnectAll(); state.dispose() }
            server.stop(0, 1000)
        }
    }

    @Test fun `transient socket closure remains reconnectable`() = runBlocking<Unit> {
        val end = CompletableDeferred<Unit>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/ws/test") {
                    incoming.receive()
                    send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Control(true))))
                    end.await()
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Temporary failure"))
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val connection = RemoteSessionConnection("http://127.0.0.1:$port/?t=test", "test", "test", { null }, { _, _ -> }, {})
        try {
            connection.start()
            withTimeout(5000) { connection.status.first { it is RemoteStatus.Connected } }
            end.complete(Unit)
            withTimeout(5000) { connection.status.first { it is RemoteStatus.Connecting } }
            assertFalse(connection.status.value is RemoteStatus.Closed)
        } finally { connection.close(); end.complete(Unit); server.stop(0, 1000) }
    }

    @Test fun `expired share closes even before encrypted handshake completes`() = runBlocking<Unit> {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/ws/expired") {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unknown or expired share token"))
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val secret = SessionCrypto.encodeSecretB64Url(SessionCrypto.newSessionSecret())
        val connection = RemoteSessionConnection("http://127.0.0.1:$port/?t=expired#k=$secret", "test", "test", { null }, { _, _ -> }, {})
        try {
            connection.start()
            withTimeout(5000) { connection.status.first { it is RemoteStatus.Closed } }
        } finally { connection.close(); server.stop(0, 1000) }
    }

    @Test fun `host stop discards pending output and also rejects late admissions`() = runBlocking<Unit> {
        val share = MirrorShare("test", ShareScope.TAB, onEnded = {})
        val viewer = share.addViewer(false)
        viewer.outbox.trySend("stale output")
        share.stop()
        assertTrue(viewer.sharingEnded)
        val frames = mutableListOf<String>()
        withTimeout(1000) { viewer.outbox.drainTo { frames.add(it) } }
        assertTrue(frames.isEmpty())
        val late = share.addViewer(false)
        assertTrue(late.sharingEnded)
        assertEquals(0, share.viewerCount)
    }
}
