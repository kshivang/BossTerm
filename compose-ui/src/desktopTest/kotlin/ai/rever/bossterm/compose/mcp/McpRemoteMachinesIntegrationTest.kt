package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.ClientMessage
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
import io.ktor.websocket.readText
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent's view of tabs that run on another machine, driven through a REAL share connection: a
 * loopback host speaking the share protocol, a real window that joins it the way "Add remote" does,
 * and the real MCP tool handlers. The host records every message the window sends, so each test
 * checks what actually reached the other machine rather than what the tool claimed.
 */
class McpRemoteMachinesIntegrationTest {

    @BeforeTest
    fun setUp() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    @AfterTest
    fun tearDown() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    private class FakeHost(val received: Channel<ClientMessage>, val control: Channel<Boolean>)

    private val layout = ServerMessage.Layout(
        tabs = listOf(
            TabNode("rt1", "build", true, PaneTreeNode.Pane("rp1", "build", "/srv/api", true)),
            TabNode("rt2", "logs", false, PaneTreeNode.Pane("rp2", "logs", "/var/log", false)),
        ),
        activeTabId = "rt1",
        sessionName = "machine-A",
    )

    /** Runs [block] against a window that mirrors a host named machine-A ([granted] = control). */
    private fun withRemote(granted: Boolean, block: suspend (FakeHost, TabbedTerminalState) -> Unit) = runBlocking<Unit> {
        val received = Channel<ClientMessage>(Channel.UNLIMITED)
        val control = Channel<Boolean>(Channel.UNLIMITED)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/ws/machineA") {
                    incoming.receive() // Hello
                    send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Control(granted))))
                    send(Frame.Text(ShareProtocol.encodeServer(layout)))
                    val pump = launch {
                        for (g in control) send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Control(g))))
                    }
                    for (frame in incoming) {
                        if (frame is Frame.Text) runCatching { received.send(ShareProtocol.decodeClient(frame.readText())) }
                    }
                    pump.cancel()
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val state = TabbedTerminalState()
        try {
            withContext(Dispatchers.Main) {
                state.initialize(TerminalSettings(), {}, { true })
                state.remoteSessions.connect("http://127.0.0.1:$port/?t=machineA", "agent-machine")
            }
            withTimeout(5000) {
                while (!withContext(Dispatchers.Main) { state.tabs.size == 2 && state.splitStates.size == 2 }) delay(20)
            }
            McpTerminalRegistry.register(state)
            block(FakeHost(received, control), state)
        } finally {
            McpTerminalRegistry.unregister(state)
            withContext(Dispatchers.Main) { state.remoteSessions.disconnectAll(); state.dispose() }
            server.stop(0, 1000)
        }
    }

    private suspend fun call(tool: String, vararg args: Pair<String, String>): String {
        val server = BossTermMcpServer(McpTerminalRegistry).createServer()
        val handler = server.tools[tool] ?: error("$tool is not registered")
        val request = CallToolRequest(
            CallToolRequestParams(name = tool, arguments = buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
        )
        return handler.handler(InProcessClientConnection, request)
            .content.filterIsInstance<TextContent>().joinToString("") { it.text.orEmpty() }
    }

    /** The next message of type [T] the host receives, skipping focus/resize chatter. */
    private suspend inline fun <reified T : ClientMessage> FakeHost.next(): T = withTimeout(5000) {
        while (true) {
            val msg = received.receive()
            if (msg is T) return@withTimeout msg
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Asserts the host receives no message of type [T] within a grace period. */
    private suspend inline fun <reified T : ClientMessage> FakeHost.receivesNo() {
        val got = withTimeoutOrNull(600) {
            while (true) {
                val msg = received.receive()
                if (msg is T) return@withTimeoutOrNull msg
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        assertNull(got, "the other machine must not receive ${T::class.simpleName}")
    }

    private suspend fun mirrorTabIds(state: TabbedTerminalState): List<String> =
        withContext(Dispatchers.Main) { state.tabs.map { it.id } }

    // The server omits null fields (a mirror has no pid), so absent means null here.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `list_tabs and list_machines name the machine every tab runs on`() = withRemote(granted = true) { _, state ->
        val tabs = json.decodeFromString(BossTermMcpServer.ListTabsResult.serializer(), call("list_tabs")).tabs
        val ids = mirrorTabIds(state)
        val mirrors = tabs.filter { it.id in ids }
        assertEquals(2, mirrors.size)
        for (t in mirrors) {
            assertEquals("machine-A", t.machine)
            assertTrue(t.remote && t.canControl && t.connected, t.toString())
            assertNull(t.pid, "a mirror has no local process")
        }

        val machines = json.decodeFromString(BossTermMcpServer.ListMachinesResult.serializer(), call("list_machines")).machines
        val a = machines.single { it.name == "machine-A" }
        assertEquals(ids.toSet(), a.tabIds.toSet())
        assertEquals(mirrors.first().machineId, a.id, "list_tabs and list_machines agree on the key")
        assertEquals(McpMachines.LOCAL_ID, machines.first().id)
    }

    @Test
    fun `input and signals reach the machine that runs the tab`() = withRemote(granted = true) { host, state ->
        val tabId = mirrorTabIds(state).first()
        assertEquals("""{"ok":true}""", call("send_input", "tab_id" to tabId, "text" to "make test\r"))
        val typed = host.next<ClientMessage.Input>()
        assertEquals("make test\r", typed.data)

        // Before this change Ctrl+C went to a local PTY queue the mirror does not have.
        assertEquals("""{"ok":true}""", call("send_signal", "tab_id" to tabId, "signal" to "ctrl_c"))
        assertEquals("\u0003", host.next<ClientMessage.Input>().data)
    }

    @Test
    fun `view-only writes are refused and nothing reaches the other machine`() = withRemote(granted = false) { host, state ->
        val tabId = mirrorTabIds(state).first()
        val tab = json.decodeFromString(BossTermMcpServer.ListTabsResult.serializer(), call("list_tabs")).tabs.first { it.id == tabId }
        assertTrue(tab.remote && !tab.canControl)

        val out = call("send_input", "tab_id" to tabId, "text" to "rm -rf build\r")
        assertTrue("view-only" in out && "machine-A" in out, out)
        assertTrue("view-only" in call("send_signal", "tab_id" to tabId, "signal" to "ctrl_c"))
        host.receivesNo<ClientMessage.Input>()
        withContext(Dispatchers.Main) {
            assertNull(state.remoteSessions.blockedInput.value, "no request-control prompt popped on this screen")
        }
    }

    @Test
    fun `control granted later is picked up on the next call`() = withRemote(granted = false) { host, state ->
        val tabId = mirrorTabIds(state).first()
        assertTrue("view-only" in call("send_input", "tab_id" to tabId, "text" to "x"))
        host.control.send(true)
        withTimeout(5000) {
            while (!json.decodeFromString(BossTermMcpServer.ListTabsResult.serializer(), call("list_tabs"))
                    .tabs.first { it.id == tabId }.canControl) delay(20)
        }
        assertEquals("""{"ok":true}""", call("send_input", "tab_id" to tabId, "text" to "x"))
        assertEquals("x", host.next<ClientMessage.Input>().data)
    }

    @Test
    fun `nothing is created on this machine in place of the remote one`() = withRemote(granted = true) { host, state ->
        val tabId = mirrorTabIds(state).first()
        suspend fun paneCount() = withContext(Dispatchers.Main) { state.splitStates[tabId]!!.getAllPanes().size }
        val before = paneCount()

        val run = call("run_command", "tab_id" to tabId, "script" to "hostname")
        assertTrue("this machine instead" in run && "pane_id" in run, run)
        val panel = call("run_in_panel", "tab_id" to tabId, "panel" to "horizontal_split", "script" to "hostname")
        assertTrue("this machine instead" in panel, panel)
        val image = call("show_image", "tab_id" to tabId, "data_base64" to ONE_PIXEL_PNG)
        assertTrue("this machine instead" in image, image)

        assertEquals(before, paneCount(), "no local shell was split into the remote tab")
        host.receivesNo<ClientMessage.Input>()
    }

    @Test
    fun `close_panel asks the other machine to close the tab`() = withRemote(granted = true) { host, state ->
        val ids = mirrorTabIds(state)
        val out = call("close_panel", "tab_id" to ids[1])
        assertTrue(""""ok":true""" in out, out)
        assertEquals("rt2", host.next<ClientMessage.CloseTab>().tabId, "the close was asked of machine-A")
        assertEquals(2, mirrorTabIds(state).size, "the mirror stays until machine-A's layout removes it")
    }

    @Test
    fun `the last tab a machine shares is not closed`() = runBlocking<Unit> {
        // A one-tab share: closing it may close the other machine's last tab, which quits BossTerm there.
        val single = layout.copy(tabs = layout.tabs.take(1))
        val received = Channel<ClientMessage>(Channel.UNLIMITED)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/ws/one") {
                    incoming.receive()
                    send(Frame.Text(ShareProtocol.encodeServer(ServerMessage.Control(true))))
                    send(Frame.Text(ShareProtocol.encodeServer(single)))
                    for (frame in incoming) {
                        if (frame is Frame.Text) runCatching { received.send(ShareProtocol.decodeClient(frame.readText())) }
                    }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val state = TabbedTerminalState()
        try {
            withContext(Dispatchers.Main) {
                state.initialize(TerminalSettings(), {}, { true })
                state.remoteSessions.connect("http://127.0.0.1:$port/?t=one", "agent-machine")
            }
            withTimeout(5000) { while (!withContext(Dispatchers.Main) { state.tabs.size == 1 }) delay(20) }
            McpTerminalRegistry.register(state)
            val out = call("close_panel", "tab_id" to mirrorTabIds(state).single())
            assertTrue("last tab" in out && "machine-A" in out, out)
            val host = FakeHost(received, Channel())
            host.receivesNo<ClientMessage.CloseTab>()
        } finally {
            McpTerminalRegistry.unregister(state)
            withContext(Dispatchers.Main) { state.remoteSessions.disconnectAll(); state.dispose() }
            server.stop(0, 1000)
        }
    }

    private companion object {
        /** A valid 1x1 PNG, so show_image gets past image validation to the machine check. */
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    }
}
