package ai.rever.bossterm.compose.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class InProcessClientConnectionTest {
    @Test
    fun `local handlers receive the SDK connection context without a transport`() = runBlocking {
        val wrapper = BossTermMcpServer(McpTerminalRegistry)
        val server = wrapper.createServer()
        try {
            server.addTool("local_context", "Test local context", ToolSchema()) {
                CallToolResult(content = listOf(TextContent(sessionId)))
            }
            val handler = assertNotNull(wrapper.handlerFor("local_context"))
            val result = handler(CallToolRequest(CallToolRequestParams(name = "local_context")))
            assertEquals("bossterm-in-process", (result.content.single() as TextContent).text)

            server.addTool("requires_client", "Test unavailable client", ToolSchema()) {
                ping()
                CallToolResult(content = emptyList())
            }
            val clientHandler = assertNotNull(wrapper.handlerFor("requires_client"))
            assertFailsWith<IllegalStateException> {
                clientHandler(CallToolRequest(CallToolRequestParams(name = "requires_client")))
            }
            Unit
        } finally {
            wrapper.detachServer()
            server.close()
        }
    }
}
