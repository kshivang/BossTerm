package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.mcp.McpRegistrationScanner.Presence
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

    private fun present(scan: Map<McpAttachTarget, Presence>): Set<McpAttachTarget> =
        scan.filterValues { it == Presence.PRESENT }.keys

    @Test
    fun `detects claude code loopback registration`() {
        val home = tempHome()
        home.write(
            ".claude.json",
            """{"someOtherKey": 1, "mcpServers": {"boss": {"type": "sse", "url": "http://127.0.0.1:7679"}}}"""
        )
        assertEquals(setOf(McpAttachTarget.CLAUDE_CODE), present(McpRegistrationScanner.scan("boss", home)))
    }

    @Test
    fun `server name must match exactly`() {
        val home = tempHome()
        home.write(
            ".claude.json",
            """{"mcpServers": {"bossterm": {"type": "sse", "url": "http://127.0.0.1:7676"}}}"""
        )
        assertTrue(present(McpRegistrationScanner.scan("boss", home)).isEmpty())
    }

    @Test
    fun `remote urls are never adopted`() {
        val home = tempHome()
        home.write(
            ".claude.json",
            """{"mcpServers": {"boss": {"type": "sse", "url": "https://mcp.example.com/sse"}}}"""
        )
        // A cleanly parsed remote entry is ABSENT (not ours to manage), so the
        // manager also prunes any persisted target that pointed at it.
        assertEquals(
            Presence.ABSENT,
            McpRegistrationScanner.scan("boss", home)[McpAttachTarget.CLAUDE_CODE]
        )
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
            present(McpRegistrationScanner.scan("boss", home))
        )
    }

    @Test
    fun `detects kimi code json registration`() {
        val home = tempHome()
        home.write(
            ".kimi-code/mcp.json",
            """{"mcpServers": {"boss": {"url": "http://127.0.0.1:7677", "transport": "sse"}}}"""
        )
        assertEquals(setOf(McpAttachTarget.KIMI_CODE), present(McpRegistrationScanner.scan("boss", home)))
    }

    @Test
    fun `detects openclaw nested mcp servers registration`() {
        val home = tempHome()
        // OpenClaw nests one level deeper than the others: mcp → servers → <name>.
        home.write(
            ".openclaw/openclaw.json",
            """{"gateway":{"port":18789},"mcp":{"servers":{"boss":{"url":"http://127.0.0.1:7677","transport":"sse","enabled":true}}}}"""
        )
        assertEquals(setOf(McpAttachTarget.OPENCLAW), present(McpRegistrationScanner.scan("boss", home)))
    }

    @Test
    fun `openclaw entry at the wrong nesting level is not adopted`() {
        val home = tempHome()
        // A flat `mcp.<name>` (OpenCode's shape) must NOT read as an OpenClaw registration —
        // otherwise the nested walk would silently accept the wrong file layout.
        home.write(
            ".openclaw/openclaw.json",
            """{"mcp":{"boss":{"url":"http://127.0.0.1:7677"}}}"""
        )
        assertEquals(
            Presence.ABSENT,
            McpRegistrationScanner.scan("boss", home)[McpAttachTarget.OPENCLAW]
        )
    }

    @Test
    fun `detects grok toml registration in the same shape as codex`() {
        val home = tempHome()
        // Exactly what `grok mcp add boss --url … --type sse` writes (verified against grok 0.2.3).
        home.write(
            ".grok/config.toml",
            """
            [mcp_servers.boss]
            url = "http://127.0.0.1:7677"
            type = "sse"
            enabled = true
            """.trimIndent()
        )
        assertEquals(setOf(McpAttachTarget.GROK), present(McpRegistrationScanner.scan("boss", home)))
    }

    @Test
    fun `detects hermes yaml registration`() {
        val home = tempHome()
        home.write(
            ".hermes/config.yaml",
            """
            model: hermes-4
            mcp_servers:
              other:
                command: npx
                args: ["-y", "something"]
              boss:
                url: "http://127.0.0.1:7677"
                enabled: true
            logging:
              level: info
            """.trimIndent()
        )
        assertEquals(setOf(McpAttachTarget.HERMES), present(McpRegistrationScanner.scan("boss", home)))
    }

    @Test
    fun `hermes url outside our server block is ignored`() {
        val home = tempHome()
        home.write(
            ".hermes/config.yaml",
            """
            mcp_servers:
              other:
                url: "http://127.0.0.1:9999"
            gateway:
              url: "http://127.0.0.1:7677"
            """.trimIndent()
        )
        assertTrue(present(McpRegistrationScanner.scan("boss", home)).isEmpty())
    }

    @Test
    fun `hermes name nested deeper than a direct child is not adopted`() {
        val home = tempHome()
        // `boss` here is a KEY INSIDE another server's env block, not a server, and the `url:` sits
        // DEEPER than it. The depth matters: matching the name at any indent made the scanner treat
        // this as our block and return that url — a false PRESENT, which suppresses the real
        // re-attach. (A fixture with `url:` at the same indent as the nested name passes either way,
        // so it would not have caught this.)
        home.write(
            ".hermes/config.yaml",
            """
            mcp_servers:
              other:
                env:
                  boss:
                    url: "http://127.0.0.1:7677"
            """.trimIndent()
        )
        assertEquals(
            Presence.ABSENT,
            McpRegistrationScanner.scan("boss", home)[McpAttachTarget.HERMES]
        )
    }

    @Test
    fun `hermes still finds a direct child after a deeper sibling block`() {
        val home = tempHome()
        home.write(
            ".hermes/config.yaml",
            """
            mcp_servers:
              other:
                env:
                  NESTED: "1"
                command: npx
              boss:
                url: "http://127.0.0.1:7677"
            """.trimIndent()
        )
        assertEquals(setOf(McpAttachTarget.HERMES), present(McpRegistrationScanner.scan("boss", home)))
    }

    @Test
    fun `hermes remote url is not adopted`() {
        val home = tempHome()
        home.write(
            ".hermes/config.yaml",
            """
            mcp_servers:
              boss:
                url: "https://mcp.example.com/sse"
            """.trimIndent()
        )
        assertEquals(
            Presence.ABSENT,
            McpRegistrationScanner.scan("boss", home)[McpAttachTarget.HERMES]
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
            url = "http://127.0.0.1:7677/mcp"

            [other_section]
            url = "https://remote.example.com"
            """.trimIndent()
        )
        assertEquals(setOf(McpAttachTarget.CODEX), present(McpRegistrationScanner.scan("boss", home)))
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
        assertEquals(setOf(McpAttachTarget.CODEX), present(McpRegistrationScanner.scan("boss", home)))

        val home2 = tempHome()
        home2.write(
            ".codex/config.toml",
            """
            [mcp_servers.boss]
            url_extra = "http://127.0.0.1:1111"
            """.trimIndent()
        )
        assertTrue(present(McpRegistrationScanner.scan("boss", home2)).isEmpty())
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
        assertTrue(present(McpRegistrationScanner.scan("boss", home)).isEmpty())
    }

    @Test
    fun `tri-state - missing and empty are ABSENT, malformed is UNKNOWN`() {
        val home = tempHome()
        home.write(".claude.json", "")
        home.write(".gemini/settings.json", "{not json!!")
        val scan = McpRegistrationScanner.scan("boss", home)
        // Empty file parses to "no entry" — a clean ABSENT the manager may prune on.
        assertEquals(Presence.ABSENT, scan[McpAttachTarget.CLAUDE_CODE])
        // Malformed JSON must be UNKNOWN — pruning on a transient read failure
        // would silently disable auto-reattach.
        assertEquals(Presence.UNKNOWN, scan[McpAttachTarget.GEMINI])
        // Files that don't exist are a clean ABSENT.
        assertEquals(Presence.ABSENT, scan[McpAttachTarget.CODEX])
        assertEquals(Presence.ABSENT, scan[McpAttachTarget.OPENCODE])
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
