package ai.rever.bossterm.compose.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class McpRegistrationScannerTest {

    private fun tempHome(): File =
        createTempDirectory("scanner-test-home").toFile().apply { deleteOnExit() }

    private fun File.write(relative: String, content: String) {
        val f = File(this, relative)
        f.parentFile.mkdirs()
        f.writeText(content)
        f.deleteOnExit()
    }

    @Test
    fun `detects claude code loopback registration`() {
        val home = tempHome()
        home.write(
            ".claude.json",
            """{"someOtherKey": 1, "mcpServers": {"boss": {"type": "sse", "url": "http://127.0.0.1:7679"}}}"""
        )
        assertEquals(setOf(McpAttachTarget.CLAUDE_CODE), McpRegistrationScanner.scan("boss", home))
    }

    @Test
    fun `server name must match exactly`() {
        val home = tempHome()
        home.write(
            ".claude.json",
            """{"mcpServers": {"bossterm": {"type": "sse", "url": "http://127.0.0.1:7676"}}}"""
        )
        assertTrue(McpRegistrationScanner.scan("boss", home).isEmpty())
    }

    @Test
    fun `remote urls are never adopted`() {
        val home = tempHome()
        home.write(
            ".claude.json",
            """{"mcpServers": {"boss": {"type": "sse", "url": "https://mcp.example.com/sse"}}}"""
        )
        assertTrue(McpRegistrationScanner.scan("boss", home).isEmpty())
    }

    @Test
    fun `detects gemini httpUrl and opencode url`() {
        val home = tempHome()
        home.write(
            ".gemini/settings.json",
            """{"mcpServers": {"boss": {"httpUrl": "http://localhost:7677"}}}"""
        )
        home.write(
            ".config/opencode/opencode.json",
            """{"mcp": {"boss": {"type": "remote", "url": "http://127.0.0.1:7677", "enabled": true}}}"""
        )
        assertEquals(
            setOf(McpAttachTarget.GEMINI, McpAttachTarget.OPENCODE),
            McpRegistrationScanner.scan("boss", home)
        )
    }

    @Test
    fun `detects codex toml registration`() {
        val home = tempHome()
        home.write(
            ".codex/config.toml",
            """
            model = "gpt-5"

            [mcp_servers.boss]
            url = "http://127.0.0.1:7677"

            [other_section]
            url = "https://remote.example.com"
            """.trimIndent()
        )
        assertEquals(setOf(McpAttachTarget.CODEX), McpRegistrationScanner.scan("boss", home))
    }

    @Test
    fun `codex key must be exactly url and tolerates inline comments`() {
        val home = tempHome()
        home.write(
            ".codex/config.toml",
            """
            [mcp_servers.boss]
            url_extra = "http://127.0.0.1:1111"
            url = "http://127.0.0.1:7677" # written by attach
            """.trimIndent()
        )
        assertEquals(setOf(McpAttachTarget.CODEX), McpRegistrationScanner.scan("boss", home))

        val home2 = tempHome()
        home2.write(
            ".codex/config.toml",
            """
            [mcp_servers.boss]
            url_extra = "http://127.0.0.1:1111"
            """.trimIndent()
        )
        assertTrue(McpRegistrationScanner.scan("boss", home2).isEmpty())
    }

    @Test
    fun `codex url outside our section is ignored`() {
        val home = tempHome()
        home.write(
            ".codex/config.toml",
            """
            [mcp_servers.other]
            url = "http://127.0.0.1:9999"
            """.trimIndent()
        )
        assertTrue(McpRegistrationScanner.scan("boss", home).isEmpty())
    }

    @Test
    fun `missing empty or malformed files are safe`() {
        val home = tempHome()
        home.write(".claude.json", "")
        home.write(".gemini/settings.json", "{not json!!")
        assertTrue(McpRegistrationScanner.scan("boss", home).isEmpty())
    }

    @Test
    fun `loopback check parses host correctly`() {
        assertTrue(McpRegistrationScanner.isLoopbackUrl("http://127.0.0.1:7677"))
        assertTrue(McpRegistrationScanner.isLoopbackUrl("http://localhost:7677/sse"))
        assertTrue(McpRegistrationScanner.isLoopbackUrl("http://127.0.0.1"))
        assertFalse(McpRegistrationScanner.isLoopbackUrl("http://127.0.0.1.evil.com:7677"))
        assertFalse(McpRegistrationScanner.isLoopbackUrl("https://mcp.example.com"))
        assertFalse(McpRegistrationScanner.isLoopbackUrl("not a url"))
    }
}
