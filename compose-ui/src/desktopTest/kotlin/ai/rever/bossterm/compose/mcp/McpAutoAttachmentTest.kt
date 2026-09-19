package ai.rever.bossterm.compose.mcp

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.*

class McpAutoAttachmentTest {
    private val configs = mapOf(
        McpAttachTarget.CLAUDE_CODE to ".claude.json",
        McpAttachTarget.GEMINI to ".gemini/settings.json",
        McpAttachTarget.OPENCODE to ".config/opencode/opencode.json",
        McpAttachTarget.KIMI_CODE to ".kimi-code/mcp.json",
        McpAttachTarget.OPENCLAW to ".openclaw/openclaw.json",
        McpAttachTarget.CODEX to ".codex/config.toml",
        McpAttachTarget.GROK to ".grok/config.toml",
        McpAttachTarget.HERMES to ".hermes/config.yaml"
    )

    private fun config(target: McpAttachTarget, remote: Boolean): String {
        val fields = if (remote) "\"url\":\"https://example.com/mcp\"" else "\"command\":\"custom-mcp\""
        return when (target) {
            McpAttachTarget.CODEX, McpAttachTarget.GROK ->
                "[mcp_servers.bossterm]\n" + if (remote) "url = \"https://example.com/mcp\"\n" else "command = \"custom-mcp\"\n"
            McpAttachTarget.HERMES ->
                "mcp_servers:\n  bossterm:\n    " + if (remote) "url: https://example.com/mcp\n" else "command: custom-mcp\n"
            McpAttachTarget.OPENCODE -> """{"mcp":{"bossterm":{$fields}}}"""
            McpAttachTarget.OPENCLAW -> """{"mcp":{"servers":{"bossterm":{$fields}}}}"""
            else -> """{"mcpServers":{"bossterm":{$fields}}}"""
        }
    }

    private fun File.writeConfig(target: McpAttachTarget, text: String): File =
        resolve(configs.getValue(target)).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun `automatic attachment never mutates remote or stdio registrations in any supported CLI`() = runBlocking {
        for (target in McpAttachTarget.entries) for (remote in listOf(true, false)) {
            val home = createTempDirectory("mcp-protected").toFile()
            try {
                val text = config(target, remote)
                val file = home.writeConfig(target, text)
                McpAutoAttachment.attachTargets(setOf(target), "bossterm", 7676, home = home,
                    probe = { error("Protected registration must not be probed") },
                    attach = { file.writeText("overwritten"); error("Protected registration must not be attached: $target") },
                    onSuccess = { error("Protected registration must not be marked attached") })
                assertEquals(text, file.readText(), "$target remote=$remote")
                assertFalse(McpRegistrationScanner.automaticRegistration(target, "bossterm", home).canAttach)
            } finally { home.deleteRecursively() }
        }
    }

    @Test fun `malformed config and failed CLI do not cancel healthy targets`() = runBlocking {
        val home = createTempDirectory("mcp-isolation").toFile()
        val successes = ConcurrentLinkedQueue<McpAttachTarget>()
        val attempted = ConcurrentLinkedQueue<McpAttachTarget>()
        try {
            val malformed = home.writeConfig(McpAttachTarget.CLAUDE_CODE, "{invalid json")
            McpAutoAttachment.attachTargets(
                setOf(McpAttachTarget.CLAUDE_CODE, McpAttachTarget.GEMINI, McpAttachTarget.CODEX),
                "bossterm", 7676, home = home,
                attach = { target ->
                    attempted.add(target)
                    if (target == McpAttachTarget.GEMINI) throw java.io.IOException("CLI unavailable")
                    McpAttachResult.Success(target, "attached")
                }, onSuccess = { successes.add(it) })
            assertEquals(setOf(McpAttachTarget.GEMINI, McpAttachTarget.CODEX), attempted.toSet())
            assertEquals(listOf(McpAttachTarget.CODEX), successes.toList())
            assertEquals("{invalid json", malformed.readText())
        } finally { home.deleteRecursively() }
    }

    @Test fun `live sibling owner is preserved and stale loopback endpoint can refresh`() = runBlocking {
        val home = createTempDirectory("mcp-owner").toFile()
        var attached = 0
        try {
            home.writeConfig(McpAttachTarget.CLAUDE_CODE,
                """{"mcpServers":{"bossterm":{"url":"http://localhost:7677/mcp"}}}""")
            for (owner in listOf("bossterm", null)) {
                McpAutoAttachment.attachTargets(setOf(McpAttachTarget.CLAUDE_CODE), "bossterm", 7676,
                    home = home, probe = { assertEquals(7677, it); owner },
                    attach = { attached++; McpAttachResult.Success(it, "attached") }, onSuccess = {})
                assertEquals(if (owner == null) 1 else 0, attached)
            }
        } finally { home.deleteRecursively() }
    }

    @Test fun `cancellation stops attachment without recording success`() = runBlocking {
        val home = createTempDirectory("mcp-cancel").toFile()
        val started = CompletableDeferred<Unit>()
        var succeeded = false
        try {
            withTimeout(5000) {
                val job = launch {
                    McpAutoAttachment.attachTargets(setOf(McpAttachTarget.CODEX), "bossterm", 7676,
                        home = home, attach = { started.complete(Unit); awaitCancellation() },
                        onSuccess = { succeeded = true })
                }
                started.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertFalse(succeeded)
            }
        } finally { home.deleteRecursively() }
    }

    @Test fun `parallel CLI completion does not lose successful registry entries`() = runBlocking {
        val home = createTempDirectory("mcp-publish").toFile()
        val ready = java.util.concurrent.atomic.AtomicInteger()
        val release = CompletableDeferred<Unit>()
        var persisted = emptySet<McpAttachTarget>()
        try {
            withTimeout(5000) {
                McpAutoAttachment.attachTargets(McpAttachTarget.entries.toSet(), "bossterm", 7676,
                    home = home, attach = { target ->
                        if (ready.incrementAndGet() == McpAttachTarget.entries.size) release.complete(Unit)
                        release.await()
                        McpAttachResult.Success(target, "attached")
                    }, onSuccess = { target ->
                        val previous = persisted
                        // Model a registry's non-atomic read/persist operation.
                        Thread.sleep(5)
                        persisted = previous + target
                    })
            }
            assertEquals(McpAttachTarget.entries.toSet(), persisted)
        } finally { home.deleteRecursively() }
    }

    @Test fun `Claude env-expanded loopback registration keeps its default port`() {
        val home = createTempDirectory("mcp-env-port").toFile()
        try {
            home.writeConfig(McpAttachTarget.CLAUDE_CODE,
                """{"mcpServers":{"bossterm":{"url":"http://127.0.0.1:${'$'}{BOSSTERM_MCP_PORT:-7677}"}}}""")
            val registration = McpRegistrationScanner.automaticRegistration(McpAttachTarget.CLAUDE_CODE, "bossterm", home)
            assertTrue(registration.canAttach)
            assertEquals(7677, registration.port)
        } finally { home.deleteRecursively() }
    }

    @Test fun `ambiguous existing formats and misleading localhost authorities are protected`() {
        val home = createTempDirectory("mcp-ambiguous").toFile()
        try {
            val cases = listOf(
                McpAttachTarget.CODEX to "mcp_servers = { bossterm = { url = 'https://example.com' } }",
                McpAttachTarget.CODEX to "[mcp_servers]\nbossterm = { command = 'custom-mcp' }",
                McpAttachTarget.HERMES to "mcp_servers: {bossterm: {url: https://example.com}}",
                McpAttachTarget.HERMES to "mcp_servers:\n  bossterm: {command: custom-mcp}",
                McpAttachTarget.CLAUDE_CODE to """{"mcpServers":{"bossterm":{"url":"http://localhost:7676@example.com/mcp"}}}""",
                McpAttachTarget.CLAUDE_CODE to """{"mcpServers":{"bossterm":{"url":"http://localhost:99999/mcp"}}}"""
            )
            for ((target, text) in cases) {
                home.writeConfig(target, text)
                assertFalse(McpRegistrationScanner.automaticRegistration(target, "bossterm", home).canAttach, text)
            }
        } finally { home.deleteRecursively() }
    }
}
