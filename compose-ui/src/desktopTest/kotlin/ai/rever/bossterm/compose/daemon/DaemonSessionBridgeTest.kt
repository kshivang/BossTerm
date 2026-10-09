package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TabController
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DaemonSessionBridgeTest {
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
