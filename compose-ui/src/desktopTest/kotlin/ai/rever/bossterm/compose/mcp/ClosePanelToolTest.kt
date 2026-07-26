package ai.rever.bossterm.compose.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `close_panel` — the inverse of `run_in_panel`.
 *
 * Its failure modes are the interesting part. Everything this tool does is destructive and none of it
 * is recoverable: the pane's shell is disposed and whatever was running in it is killed. So the guards
 * are tested at least as carefully as the happy path, and one of them is load-bearing for the whole
 * app — `TabController.closeTab` calls `onLastTabClosed()` on a window's final tab, whose own comment
 * reads "exit application".
 */
class ClosePanelToolTest {

    @BeforeTest
    fun setUp() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    @AfterTest
    fun tearDown() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    private fun call(args: Map<String, String>): String {
        val server = BossTermMcpServer(McpTerminalRegistry).createServer()
        val tool = server.tools["close_panel"] ?: error("close_panel is not registered")
        val request = CallToolRequest(
            CallToolRequestParams(
                name = "close_panel",
                arguments = buildJsonObject { args.forEach { (k, v) -> put(k, v) } },
            )
        )
        val result = runBlocking { tool.handler(request) }
        return result.content.filterIsInstance<TextContent>().joinToString("") { it.text.orEmpty() }
    }

    @Test
    fun `close_panel is registered as a write tool`() {
        assertTrue(
            "close_panel" in BossTermMcpServer.BUILT_IN_WRITE_TOOLS,
            "closing a tab is a write, and the settings UI renders its toggle from this list",
        )
        val server = BossTermMcpServer(McpTerminalRegistry).createServer()
        assertTrue(server.tools.containsKey("close_panel"), "and it is registered by default")
    }

    /**
     * An unknown tab must be reported, not guessed at.
     *
     * With no window registered in a unit test, every tab id is unknown — which makes this the case
     * that proves the handler validates before it acts rather than closing something adjacent.
     */
    @Test
    fun `an unknown tab is refused`() {
        val out = call(mapOf("tab_id" to "no-such-tab"))
        assertTrue(out.contains("Unknown tab_id"), out)
    }

    @Test
    fun `tab_id is required`() {
        val out = call(mapOf("pane_id" to "p1"))
        assertTrue(out.contains("Missing required argument: tab_id"), out)
    }

    /**
     * The description has to carry the warnings, because it is all the caller sees before acting.
     *
     * An agent choosing between tools reads these strings and nothing else. The last-tab refusal in
     * particular is a behaviour it cannot discover except by trying it on the tab that quits the app.
     */
    @Test
    fun `the description states what is destroyed and what is refused`() {
        val server = BossTermMcpServer(McpTerminalRegistry).createServer()
        val description = server.tools["close_panel"]?.tool?.description.orEmpty()
        assertTrue(description.contains("kills"), description)
        assertTrue(description.contains("cannot be undone"), description)
        assertTrue(description.contains("last remaining tab"), description)
        // pane_id's meaning is the difference between closing one split and closing everything.
        assertTrue(description.contains("Omit to close the whole tab") ||
            server.tools["close_panel"]?.tool?.inputSchema.toString().contains("Omit to close"),
            "pane_id's omit-semantics must be documented: $description")
    }

    /**
     * The guard that keeps an agent from quitting the app.
     *
     * `TabController.closeTab` reaches `onLastTabClosed()` on a window's final tab — "exit
     * application". Over voice that would also kill the call that asked for it, so the refusal lives
     * in the tool rather than in a prompt that may or may not be followed.
     *
     * Tested through the extracted decision rather than the handler: driving the real path needs a
     * live window, and `TabbedTerminalState.createTab` returns null with no PTY available here, so a
     * handler-level test could only ever reach the unknown-tab branch.
     */
    @Test
    fun `a window's last tab is refused rather than closed`() {
        val refusal = BossTermMcpServer.lastTabRefusal("t1", tabCount = 1)
        assertTrue(refusal != null, "closing the only tab would quit the app")
        assertTrue(refusal.contains("only tab in its window"), refusal)
        assertTrue(refusal.contains("would quit BossTerm"), "the reason must be stated: $refusal")
        // And the way out is named, or the agent reports the request as impossible.
        assertTrue(refusal.contains("pane_id"), "the alternative must be offered: $refusal")
    }

    @Test
    fun `a tab with siblings may be closed`() {
        assertEquals(null, BossTermMcpServer.lastTabRefusal("t1", tabCount = 2))
        assertEquals(null, BossTermMcpServer.lastTabRefusal("t1", tabCount = 9))
    }

    /** Zero is not a state that should arise, and it must not read as permission either. */
    @Test
    fun `an empty window is refused too`() {
        assertTrue(BossTermMcpServer.lastTabRefusal("t1", tabCount = 0) != null)
    }

    /**
     * A read-only embedder has no business closing the user's tabs.
     *
     * The config is a CONSTRUCTOR argument — `McpTerminalRegistry.setMcpConfig` publishes it for the
     * settings UI and does not reach an already-built server, so gating has to be asserted through the
     * same channel the embedder uses.
     */
    @Test
    fun `a read-only embedder does not get close_panel`() {
        val server = BossTermMcpServer(
            McpTerminalRegistry,
            BossTermMcpConfig(allowWriteTools = false),
        ).createServer()
        assertEquals(null, server.tools["close_panel"], "it is a write tool")
        assertTrue(server.tools.containsKey("list_tabs"), "sanity: reads are still registered")
    }
}
