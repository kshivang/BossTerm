package ai.rever.bossterm.compose.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The machine an agent's tab runs on. These are the decisions that keep an agent from acting on the
 * wrong computer, so each one is pinned on its own; [McpRemoteMachinesIntegrationTest] proves the
 * wiring against a real share connection.
 */
class McpMachinesTest {

    @BeforeTest
    fun setUp() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    @AfterTest
    fun tearDown() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    @Test
    fun `a direct share is that machine`() {
        val m = McpMachines.remote("hashA", "alice", directCanControl = true, directConnected = true)
        assertEquals(MachineRef("hashA", "alice", remote = true, via = null, canControl = true, connected = true), m)
    }

    @Test
    fun `a chained share is the deeper machine, reached via the direct one, and only as good as every hop`() {
        val viaB = McpMachines.remote(
            "hashB", "bob", directCanControl = true, directConnected = true,
            deeperKey = "hashC", deeperName = "carol",
        )
        assertEquals("hashC", viaB.id)
        assertEquals("carol", viaB.name)
        assertEquals("bob", viaB.via)
        assertTrue(viaB.canControl && viaB.connected)

        val readOnlyHop = McpMachines.remote(
            "hashB", "bob", directCanControl = true, directConnected = true,
            deeperKey = "hashC", deeperName = "carol", deeperReadOnly = true,
        )
        assertFalse(readOnlyHop.canControl, "view-only anywhere on the chain is view-only")
        val offlineHop = McpMachines.remote(
            "hashB", "bob", directCanControl = true, directConnected = true,
            deeperKey = "hashC", deeperName = null, deeperOffline = true,
        )
        assertFalse(offlineHop.connected)
        assertEquals("remote", offlineHop.name, "an unnamed origin still gets a name an agent can say")
    }

    @Test
    fun `writes are refused when view-only or disconnected, and never on this machine`() {
        assertNull(McpMachines.writeRefusal(McpMachines.LOCAL, "send_input"))
        val ok = McpMachines.remote("h", "alice", directCanControl = true, directConnected = true)
        assertNull(McpMachines.writeRefusal(ok, "send_input"))

        val viewOnly = ok.copy(canControl = false)
        val refusal = assertNotNull(McpMachines.writeRefusal(viewOnly, "send_input"))
        assertTrue("alice" in refusal && "view-only" in refusal && "Nothing was sent" in refusal, refusal)

        val down = ok.copy(connected = false)
        val downRefusal = assertNotNull(McpMachines.writeRefusal(down, "send_signal"))
        assertTrue("disconnected" in downRefusal, downRefusal)

        val chained = McpMachines.remote("b", "bob", true, true, deeperKey = "c", deeperName = "carol", deeperReadOnly = true)
        assertTrue("carol (via bob)" in assertNotNull(McpMachines.writeRefusal(chained, "send_input")))
    }

    @Test
    fun `panes and images are never made inside a remote tab`() {
        assertNull(McpMachines.localPaneRefusal(McpMachines.LOCAL, "run_command", "its scratch pane"))
        val remote = McpMachines.remote("h", "alice", true, true)
        val refusal = assertNotNull(McpMachines.localPaneRefusal(remote, "run_command", "its scratch pane"))
        assertTrue("this machine instead" in refusal && "alice" in refusal && "pane_id" in refusal, refusal)
    }

    @Test
    fun `machines are grouped with this machine always first`() {
        val alice = McpMachines.remote("hA", "alice", true, true)
        val bob = McpMachines.remote("hB", "bob", false, true)
        val grouped = McpMachines.group(
            listOf("t1" to alice, "t2" to McpMachines.LOCAL, "t3" to bob, "t4" to alice),
        )
        assertEquals(listOf("local", "hA", "hB"), grouped.map { it.id })
        assertEquals(listOf("t2"), grouped[0].tabIds)
        assertEquals(listOf("t1", "t4"), grouped[1].tabIds)
        assertFalse(grouped[2].canControl)

        val nothingOpen = McpMachines.group(emptyList())
        assertEquals(listOf(McpMachines.LOCAL_ID), nothingOpen.map { it.id }, "this machine is listed with no tabs")
    }

    @Test
    fun `list_machines is a read tool, registered by default`() {
        assertTrue("list_machines" in BossTermMcpServer.BUILT_IN_READ_TOOLS)
        val server = BossTermMcpServer(McpTerminalRegistry).createServer()
        val tool = assertNotNull(server.tools["list_machines"])
        val out = runBlocking {
            tool.handler(
                InProcessClientConnection,
                CallToolRequest(CallToolRequestParams(name = "list_machines", arguments = buildJsonObject { })),
            )
        }.content.filterIsInstance<TextContent>().joinToString("") { it.text.orEmpty() }
        val parsed = Json.decodeFromString(BossTermMcpServer.ListMachinesResult.serializer(), out)
        assertEquals(McpMachines.LOCAL_ID, parsed.machines.first().id)
    }

    /** An agent decides from descriptions alone; each one must say that tabs can be elsewhere. */
    @Test
    fun `tool descriptions tell the agent that tabs may run on other machines`() {
        val server = BossTermMcpServer(McpTerminalRegistry).createServer()
        fun describe(name: String) = assertNotNull(server.tools[name], name).tool.description.orEmpty()
        assertTrue("machine" in describe("list_tabs") && "list_machines" in describe("list_tabs"))
        assertTrue("machine" in describe("get_active_tab"))
        for (writer in listOf("send_input", "send_signal", "run_command", "run_in_panel", "close_panel")) {
            assertTrue("other machine" in describe(writer) || "another machine" in describe(writer), writer)
        }
    }
}
