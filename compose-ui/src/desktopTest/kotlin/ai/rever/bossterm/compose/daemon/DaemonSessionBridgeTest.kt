package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TabController
import androidx.compose.runtime.mutableStateMapOf
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DaemonSessionBridgeTest {
    @Test
    fun `ungrouped tab close survives stale state and is replayed on connection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = TabController(TerminalSettings.DEFAULT, {}, parentScope = scope)
        val splits = mutableStateMapOf<String, SplitViewState>()
        val port = ServerSocket(0).use { it.localPort }
        val closeRequest = CompletableDeferred<String>()
        val acknowledgeClose = CompletableDeferred<Unit>()
        val sessions = DaemonAttachProtocol.Server.SessionList(listOf(DaemonAttachProtocol.SessionMeta("mcp-session", "MCP shell")))
        val groups = DaemonAttachProtocol.Server.GroupList(emptyList())
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(WebSockets)
            routing {
                webSocket("/attach") {
                    send(Frame.Text(DaemonAttachProtocol.encodeServer(sessions)))
                    send(Frame.Text(DaemonAttachProtocol.encodeServer(groups)))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val request = DaemonAttachProtocol.decodeClient(frame.readText())
                        if (request is DaemonAttachProtocol.Client.ClosePane) {
                            closeRequest.complete(request.sessionId)
                            acknowledgeClose.await()
                            send(Frame.Text(DaemonAttachProtocol.encodeServer(DaemonAttachProtocol.Server.SessionList(emptyList()))))
                            send(Frame.Text(DaemonAttachProtocol.encodeServer(groups)))
                        }
                    }
                }
            }
        }
        val bridge = DaemonSessionBridge(controller, splits, port, "test", scope, Dispatchers.Unconfined)
        try {
            bridge.dispatch(sessions)
            bridge.dispatch(groups)
            val tab = controller.tabs.single()
            assertEquals(null, bridge.groupIdForTab(tab.id), "MCP-created sessions have no group")
            assertTrue(bridge.closeTab(tab.id))
            controller.closeTab(0)
            bridge.dispatch(sessions)
            bridge.dispatch(groups)
            assertTrue(controller.tabs.isEmpty(), "stale topology must not resurrect a closed flat session")
            assertFalse(bridge.closeTab("local-tab"), "unrelated tabs must not send daemon closes")
            server.start(wait = false)
            bridge.start()
            assertEquals("mcp-session", withTimeout(5000) { closeRequest.await() }, "the disconnected close must reach the next socket")
            assertTrue(controller.tabs.isEmpty(), "the reconnect's stale SessionList must remain hidden before acknowledgement")
            acknowledgeClose.complete(Unit)
            Unit
        } finally {
            acknowledgeClose.complete(Unit)
            bridge.stop()
            server.stop(0, 500)
            controller.disposeAll()
            scope.cancel()
        }
    }

    @Test
    fun `split expansion resend and collapse preserve the surviving stream and tab position`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var lastTabClosed = 0
        val controller = TabController(TerminalSettings.DEFAULT, { lastTabClosed++ }, parentScope = scope)
        val splits = mutableStateMapOf<String, SplitViewState>()
        val bridge = DaemonSessionBridge(controller, splits, 1, "test", scope, Dispatchers.Unconfined)
        val first = GroupTreeDto.Pane("pane-a", "session-a")
        val second = GroupTreeDto.Pane("pane-b", "session-b")
        val expanded = GroupTreeDto.Split("split", "v", 0.5f, first, second)
        try {
            bridge.dispatch(DaemonAttachProtocol.Server.SessionList(listOf(DaemonAttachProtocol.SessionMeta("session-a", "A", "/a"))))
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(listOf(GroupView("group", first))))
            val original = controller.tabs.single()
            assertEquals("A", original.title.value)
            assertEquals("/a", original.workingDirectory.value)
            bridge.dispatch(DaemonAttachProtocol.Server.SessionList(listOf(
                DaemonAttachProtocol.SessionMeta("session-a", "A", "/a"),
                DaemonAttachProtocol.SessionMeta("session-b", "B", "/b"),
            )))
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(listOf(GroupView("group", expanded))))
            val wrapper = controller.tabs.single()
            assertFalse(wrapper === original)
            assertSame(original, splits.getValue(wrapper.id).getAllPanes().first().session)
            assertEquals("group", bridge.groupIdForTab(wrapper.id))
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(listOf(GroupView("group", expanded))))
            assertSame(wrapper, controller.tabs.single(), "no-op GroupList must not remove the split tab")
            bridge.dispatch(DaemonAttachProtocol.Server.SessionList(listOf(DaemonAttachProtocol.SessionMeta("session-a", "A2", "/new"))))
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(listOf(GroupView("group", first))))
            assertSame(original, controller.tabs.single(), "collapsing a split must reuse the surviving stream")
            assertFalse(splits.containsKey(wrapper.id), "old split tree must be discarded")
            assertEquals("A2", original.title.value)
            assertEquals(0, lastTabClosed, "topology changes must not call last-tab shutdown")
            bridge.dispatch(DaemonAttachProtocol.Server.Snapshot("session-a", "", 120, 40))
            kotlinx.coroutines.withTimeout(5000) {
                while (original.textBuffer.width != 120 || original.textBuffer.height != 40) kotlinx.coroutines.delay(5)
            }
            assertEquals(120, original.textBuffer.width)
            assertEquals(40, original.textBuffer.height)
        } finally {
            bridge.stop()
            controller.disposeAll()
            scope.cancel()
        }
    }

    @Test
    fun `close while disconnected does not resurrect the group from stale state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = TabController(TerminalSettings.DEFAULT, {}, parentScope = scope)
        val splits = mutableStateMapOf<String, SplitViewState>()
        val bridge = DaemonSessionBridge(controller, splits, 1, "test", scope, Dispatchers.Unconfined)
        val sessions = DaemonAttachProtocol.Server.SessionList(listOf(DaemonAttachProtocol.SessionMeta("s", "shell")))
        val groups = DaemonAttachProtocol.Server.GroupList(listOf(GroupView("g", GroupTreeDto.Pane("p", "s"))))
        try {
            bridge.dispatch(sessions)
            bridge.dispatch(groups)
            assertTrue(bridge.closeGroupForTab(controller.tabs.single().id))
            controller.closeTab(0)
            bridge.dispatch(sessions)
            bridge.dispatch(groups)
            assertTrue(controller.tabs.isEmpty(), "an unacknowledged close must hide stale reconnect topology")
            bridge.dispatch(DaemonAttachProtocol.Server.SessionList(emptyList()))
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(emptyList()))
            assertTrue(controller.tabs.isEmpty())
        } finally {
            bridge.stop()
            controller.disposeAll()
            scope.cancel()
        }
    }
}
