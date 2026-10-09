package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TabController
import androidx.compose.runtime.mutableStateMapOf
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class HostedTerminalRuntimeTest {
    @Test
    fun `replaying an acknowledged open cannot execute a command twice even after exit`() {
        if (ShellCustomizationUtils.isWindows()) return
        val directory = Files.createTempDirectory("hosted-terminal-replay").toFile()
        val result = directory.resolve("ran")
        val host = SessionHost(TerminalSettings.DEFAULT)
        try {
            val args = listOf("-c", "printf x >> '${result.absolutePath}'; sleep 5")
            val original = host.openWindow(command = "/bin/sh", arguments = args, requestedId = "requested")
            assertEquals(original, host.openWindow(command = "/bin/sh", arguments = args, requestedId = "requested"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!result.exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(result.exists())
            assertEquals("x", result.readText())
            host.closeGroup(original.second)
            assertEquals(original, host.openWindow(command = "/bin/sh", arguments = args, requestedId = "requested"))
            assertEquals(0, host.count())
            assertEquals("x", result.readText())
        } finally { host.shutdownAll(); host.close(); directory.deleteRecursively() }
    }

    @Test
    fun `explicitly reopening a runner id uses a new operation while reconnect replays do not`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        try {
            val first = host.openWindow(command = "/bin/cat", requestedId = "runner", requestId = "first")
            host.closeGroup(first.second)
            assertEquals(first, host.openWindow(command = "/bin/cat", requestedId = "runner", requestId = "first"))
            assertEquals(0, host.count())
            host.openWindow(command = "/bin/cat", requestedId = "runner", requestId = "second")
            assertEquals(1, host.count())
        } finally { host.shutdownAll(); host.close() }
    }

    @Test
    fun `hosted runner id survives split and original pane exit`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = TabController(TerminalSettings.DEFAULT, {}, parentScope = scope)
        val splits = mutableStateMapOf<String, SplitViewState>()
        val bridge = DaemonSessionBridge(controller, splits, 1, "test", scope, Dispatchers.Unconfined, hosted = true)
        try {
            bridge.dispatch(DaemonAttachProtocol.Server.SessionList(listOf(DaemonAttachProtocol.SessionMeta("runner", "A"))))
            val root = GroupTreeDto.Pane("root", "runner")
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(listOf(GroupView("runner", root))))
            assertEquals("runner", controller.tabs.single().id)
            bridge.dispatch(DaemonAttachProtocol.Server.SessionList(listOf(
                DaemonAttachProtocol.SessionMeta("runner", "A"), DaemonAttachProtocol.SessionMeta("sibling", "B"))))
            val sibling = GroupTreeDto.Pane("second", "sibling")
            val tree = GroupTreeDto.Split("split", "v", 0.5f, root, sibling)
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(listOf(GroupView("runner", tree))))
            assertEquals("runner", controller.tabs.single().id)
            bridge.dispatch(DaemonAttachProtocol.Server.SessionList(listOf(DaemonAttachProtocol.SessionMeta("sibling", "B"))))
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(listOf(GroupView("runner", sibling))))
            assertEquals("runner", controller.tabs.single().id)
            assertEquals("sibling", (splits.getValue("runner").getFocusedSession() as ai.rever.bossterm.compose.tabs.TerminalTab).remotePaneId)
        } finally { bridge.stopForUnload(); controller.disposeAll(); scope.cancel() }
    }

    @Test
    fun `two hosted surfaces have separate attach endpoints and session namespaces`() {
        if (ShellCustomizationUtils.isWindows()) return
        val first = HostedTerminalRuntime()
        val second = HostedTerminalRuntime()
        try {
            val a = first.start()
            val b = second.start()
            assertNotEquals(a.port, b.port)
            assertNotEquals(a.token, b.token)
            assertEquals(a, first.start())
            val id = first.host.openWindow(command = "/bin/sh", arguments = listOf("-c", "sleep 5")).first
            assertTrue(first.host.get(id) != null)
            assertEquals(0, second.host.count())
        } finally { first.close(); second.close() }
    }

    @Test
    fun `detached attach clients reconnect to the same live PTY and unicode scrollback`() = runBlocking<Unit> {
        if (ShellCustomizationUtils.isWindows()) return@runBlocking
        val runtime = HostedTerminalRuntime()
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }
        try {
            val endpoint = runtime.start()
            val id = runtime.host.openSession(command = "/bin/cat", arguments = emptyList())
            val core = runtime.host.get(id)
            val marker = "RECONNECT_é_世界"
            kotlinx.coroutines.withTimeout(10_000) {
                client.webSocket("ws://127.0.0.1:${endpoint.port}/attach", request = {
                    header(DaemonAttachProtocol.TOKEN_HEADER, endpoint.token)
                }) {
                    send(io.ktor.websocket.Frame.Text(DaemonAttachProtocol.encodeClient(
                        DaemonAttachProtocol.Client.Input(id, "$marker\n"))))
                    for (frame in incoming) {
                        val msg = decodeFrame(frame)
                        if (msg is DaemonAttachProtocol.Server.Output && marker in msg.data) break
                    }
                }
            }
            assertSame(core, runtime.host.get(id), "disconnect must not replace the session")
            kotlinx.coroutines.withTimeout(10_000) {
                client.webSocket("ws://127.0.0.1:${endpoint.port}/attach", request = {
                    header(DaemonAttachProtocol.TOKEN_HEADER, endpoint.token)
                }) {
                    for (frame in incoming) {
                        val msg = decodeFrame(frame)
                        if (msg is DaemonAttachProtocol.Server.Snapshot && msg.id == id) {
                            val mirror = TerminalSessionCore(settings = TerminalSettings.DEFAULT, workingDir = null)
                            try {
                                mirror.dataStream.append(msg.data)
                                while (!mirror.dataStream.isEmpty) mirror.emulator.processChar(mirror.dataStream.char, mirror.terminal)
                                val text = mirror.textBuffer.createSnapshot().let { snapshot ->
                                    (0 until snapshot.height).joinToString("\n") { snapshot.getLine(it).text }
                                }
                                assertTrue(marker in text.filterNot { it == ai.rever.bossterm.terminal.util.CharUtils.DWC },
                                    "reconnect must repaint unicode content: $text")
                                assertEquals(core!!.textBuffer.createSnapshot().getLine(0).text,
                                    mirror.textBuffer.createSnapshot().getLine(0).text,
                                    "reattach must preserve wide-character columns without extra placeholders")
                            } finally { mirror.close() }
                            return@webSocket
                        }
                    }
                    error("No reconnect snapshot")
                }
            }
        } finally { client.close(); runtime.close() }
    }

    @Test
    fun `sharing directory and scoped voice span isolated hosted surfaces`() {
        if (ShellCustomizationUtils.isWindows()) return
        val first = HostedTerminalRuntime()
        val second = HostedTerminalRuntime()
        val directory = HostedSessionDirectory()
        try {
            directory.add(first.host)
            directory.add(second.host)
            val a = first.host.openWindow(command = "/bin/cat", arguments = emptyList()).first
            val b = second.host.openWindow(command = "/bin/cat", arguments = emptyList()).first
            assertEquals(setOf(a, b), directory.list().map { it.id }.toSet())
            assertEquals(2, directory.listGroups().size)
            val executor = ai.rever.bossterm.compose.voice.DaemonVoiceToolExecutor(directory, { setOf(a, b) }, { a })
            val snapshot = executor.contextSnapshot(a)
            assertTrue(a in snapshot)
            assertTrue(b in snapshot)
            directory.remove(first.host)
            assertEquals(listOf(b), directory.list().map { it.id })
        } finally { first.close(); second.close() }
    }

    private fun decodeFrame(frame: io.ktor.websocket.Frame): DaemonAttachProtocol.Server? = when (frame) {
        is io.ktor.websocket.Frame.Binary -> DaemonAttachProtocol.BinaryFrame.decode(frame.data)
        is io.ktor.websocket.Frame.Text -> DaemonAttachProtocol.decodeServer(frame.readText())
        else -> null
    }

    @Test
    fun `programmatic tab creation uses stable daemon mirrors and preserves background focus`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = TabController(TerminalSettings.DEFAULT, {}, parentScope = scope)
        val bridge = DaemonSessionBridge(controller, mutableStateMapOf<String, SplitViewState>(), 1, "test", scope,
            Dispatchers.Unconfined, hosted = true)
        try {
            bridge.start()
            val first = controller.createTab(tabId = "one")
            val second = controller.createTab(tabId = "two", command = "/bin/sh", arguments = listOf("-c", "echo hello"), activate = false)
            assertEquals("one", first.id)
            assertEquals("two", second.id)
            assertEquals("two", second.remotePaneId)
            assertSame(first, controller.activeTab)
            assertTrue(bridge.ownsSession("two"))
            bridge.dispatch(DaemonAttachProtocol.Server.SessionList(emptyList()))
            bridge.dispatch(DaemonAttachProtocol.Server.GroupList(emptyList()))
            assertEquals(2, controller.tabs.size, "stale state must retain unacknowledged opens")
            val oldPort = ai.rever.bossterm.compose.mcp.McpTerminalRegistry.runningPort.value
            try {
                ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setRunning(12345)
                bridge.dispatch(DaemonAttachProtocol.Server.McpState(null))
                assertEquals(12345, ai.rever.bossterm.compose.mcp.McpTerminalRegistry.runningPort.value)
            } finally {
                if (oldPort == null) ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setStopped()
                else ai.rever.bossterm.compose.mcp.McpTerminalRegistry.setRunning(oldPort)
            }
        } finally { bridge.stopForUnload(); controller.disposeAll(); scope.cancel() }
    }
}
