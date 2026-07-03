package ai.rever.bossterm.compose.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpRegistrationUrlTest {

    @Test
    fun `claude code registers the env-expanded url form`() {
        assertEquals(
            "http://127.0.0.1:\${BOSS_MCP_PORT:-7677}",
            McpAttachTarget.CLAUDE_CODE.registrationUrl("boss", 7677)
        )
        assertEquals(
            "http://127.0.0.1:\${BOSSTERM_MCP_PORT:-7676}",
            McpAttachTarget.CLAUDE_CODE.registrationUrl("bossterm", 7676)
        )
    }

    @Test
    fun `other clis keep the plain url`() {
        for (target in McpAttachTarget.entries - McpAttachTarget.CLAUDE_CODE) {
            assertEquals("http://127.0.0.1:7677", target.registrationUrl("boss", 7677))
        }
    }

    @Test
    fun `port env var name is derived and sanitized from the server name`() {
        assertEquals("BOSS_MCP_PORT", McpTerminalRegistry.portEnvVarName("boss"))
        assertEquals("BOSSTERM_MCP_PORT", McpTerminalRegistry.portEnvVarName("bossterm"))
        assertEquals(
            "BOSSTERM_EMBEDDED_EXAMPLE_MCP_PORT",
            McpTerminalRegistry.portEnvVarName("bossterm-embedded-example")
        )
    }

    @Test
    fun `scanner treats the env-expanded url as loopback so adoption keeps working`() {
        assertTrue(McpRegistrationScanner.isLoopbackUrl("http://127.0.0.1:\${BOSS_MCP_PORT:-7677}"))
    }

    @Test
    fun `claude clipboard fallback quotes the url against shell pre-expansion`() {
        val snippet = McpAttachTarget.CLAUDE_CODE.resolvedClipboard(
            "boss",
            McpAttachTarget.CLAUDE_CODE.registrationUrl("boss", 7677)
        )
        assertTrue("'http://127.0.0.1:\${BOSS_MCP_PORT:-7677}'" in snippet)
    }
}
