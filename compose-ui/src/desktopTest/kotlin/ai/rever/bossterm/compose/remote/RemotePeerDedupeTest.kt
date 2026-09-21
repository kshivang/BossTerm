package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.PaneTreeNode
import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.ShareProtocol
import ai.rever.bossterm.compose.share.TabNode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Three devices on one account: host A mirrors host B (its Layout carries B's tabs stamped with
 * B's origin hash). When this client has B attached directly, A's nested copy of B is skipped;
 * when it does not, the nested copy shows (a hand-made share's nested groups stay visible).
 */
class RemotePeerDedupeTest {

    private suspend fun host(token: String, tabs: List<TabNode>) = run {
        val stop = CompletableDeferred<Unit>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/ws/$token") {
                    incoming.receive()
                    send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Control(true))))
                    send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Layout(tabs, tabs.first().id))))
                    stop.await()
                }
            }
        }.start(wait = false)
        Triple(server, "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}/?t=$token", stop)
    }

    private fun tab(id: String, title: String, origin: String? = null) =
        TabNode(id, title, false, PaneTreeNode.Pane("$id-pane", title, "/tmp", true), origin = origin, originName = origin?.let { "B via A" })

    private suspend fun titles(state: TabbedTerminalState) = withContext(Dispatchers.Main) { state.tabs.map { it.title.value }.sorted() }

    private suspend fun awaitTitles(state: TabbedTerminalState, expected: List<String>) {
        val deadline = System.currentTimeMillis() + 5_000
        while (titles(state) != expected.sorted()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("expected $expected, saw ${titles(state)}")
            delay(20)
        }
    }

    @Test fun `a nested copy of a directly attached session is skipped, and returns when the direct one goes`() = runBlocking<Unit> {
        val (b, bLink, bStop) = host("tokB", listOf(tab("b1", "B own")))
        val (a, aLink, aStop) = host("tokA", listOf(tab("a1", "A own"), tab("a2", "B nested", origin = ShareProtocol.sha256Hex("tokB"))))
        val state = TabbedTerminalState()
        val manager = RemoteSessionManager(state)
        try {
            withContext(Dispatchers.Main) { state.initialize(TerminalSettings(), {}, { true }) }
            // B first, then A: A's nested B is filtered from the start.
            val bSession = withContext(Dispatchers.Main) { manager.connect(bLink, "B")!! }
            awaitTitles(state, listOf("B own"))
            withContext(Dispatchers.Main) { manager.connect(aLink, "A") }
            awaitTitles(state, listOf("A own", "B own"))
            delay(300)
            assertEquals(listOf("A own", "B own"), titles(state), "nested copy must not appear later either")
            // Direct B disconnected: the copy nested under A is the only B we have, so it shows.
            withContext(Dispatchers.Main) { manager.disconnect(bSession) }
            awaitTitles(state, listOf("A own", "B nested"))
        } finally {
            aStop.complete(Unit); bStop.complete(Unit)
            withContext(Dispatchers.Main) { manager.disconnectAll(); state.dispose() }
            a.stop(0, 1000); b.stop(0, 1000)
        }
    }

    @Test fun `attaching a session directly removes the copy already nested under a peer`() = runBlocking<Unit> {
        val (b, bLink, bStop) = host("tokB", listOf(tab("b1", "B own")))
        val (a, aLink, aStop) = host("tokA", listOf(tab("a1", "A own"), tab("a2", "B nested", origin = ShareProtocol.sha256Hex("tokB"))))
        val state = TabbedTerminalState()
        val manager = RemoteSessionManager(state)
        try {
            withContext(Dispatchers.Main) { state.initialize(TerminalSettings(), {}, { true }) }
            withContext(Dispatchers.Main) { manager.connect(aLink, "A") }
            awaitTitles(state, listOf("A own", "B nested"))
            withContext(Dispatchers.Main) { manager.connect(bLink, "B") }
            awaitTitles(state, listOf("A own", "B own"))
        } finally {
            aStop.complete(Unit); bStop.complete(Unit)
            withContext(Dispatchers.Main) { manager.disconnectAll(); state.dispose() }
            a.stop(0, 1000); b.stop(0, 1000)
        }
    }
}
