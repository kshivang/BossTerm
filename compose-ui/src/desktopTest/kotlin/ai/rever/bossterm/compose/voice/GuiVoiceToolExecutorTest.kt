package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GUI executor is the higher-risk half of the pair — it carries `run_command` and enforces the
 * share's scope — so the scope gate and the embedder-config plumbing are pinned here.
 *
 * No windows are registered: these paths (scope rejection, tool advertisement, the locally-answered
 * tools) are reachable without a live [ai.rever.bossterm.compose.TabbedTerminalState], and the ones
 * that do need one are covered against a real host in [DaemonVoiceToolExecutorTest].
 */
class GuiVoiceToolExecutorTest {

    @AfterTest
    fun resetConfig() {
        // The registry is process-wide; don't leak a prefix into other tests.
        McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())
    }

    private fun executor(
        scope: Set<String>,
        anchor: String? = "t1",
    ) = GuiVoiceToolExecutor(inScopeTabIds = { scope }, anchorTabId = { anchor })

    /**
     * The single most security-relevant line in the feature: a tab the share doesn't cover must be
     * refused however it is named — explicitly, or by the viewer claiming it as its current tab.
     */
    @Test
    fun `a tab outside the share is refused however it arrives`() {
        val exec = executor(scope = setOf("t1"))

        assertFailsWith<VoiceToolException> {
            runBlocking {
                exec.execute("read_scrollback", buildJsonObject { put("tab_id", "foreign") }, null)
            }
        }
        assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("read_scrollback", buildJsonObject { }, defaultTabId = "foreign") }
        }
        // The locally-answered tools are gated too — they used to bypass the check entirely.
        assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("get_active_tab", buildJsonObject { }, defaultTabId = "foreign") }
        }
        assertFailsWith<VoiceToolException> {
            runBlocking {
                exec.execute("get_active_tab", buildJsonObject { put("tab_id", "foreign") }, null)
            }
        }
        assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("list_tabs", buildJsonObject { }, defaultTabId = "foreign") }
        }
    }

    /** An unknown tool never reaches a handler. */
    @Test
    fun `an unadvertised tool is rejected`() {
        val exec = executor(scope = setOf("t1"))
        assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("rm_rf", buildJsonObject { }, "t1") }
        }
    }

    /**
     * list_tabs must answer even with nothing focused: an empty list is the truthful reply, whereas
     * the earlier eager target resolution turned "no anchor yet" into an error.
     */
    @Test
    fun `list_tabs answers without a focused or anchor tab`() {
        val exec = executor(scope = setOf("t1"), anchor = null)
        val json = runBlocking { exec.execute("list_tabs", buildJsonObject { }, defaultTabId = null) }
        assertTrue(json.contains("\"tabs\""), json)
        // No window is registered in this test, so the scope-filtered list is legitimately empty.
        assertFalse(json.contains("viewingTabId"), "nothing is being viewed, so don't claim one")
    }

    /** get_active_tab has nothing to answer with when there is no target at all. */
    @Test
    fun `get_active_tab without any target fails cleanly`() {
        val exec = executor(scope = setOf("t1"), anchor = null)
        assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("get_active_tab", buildJsonObject { }, null) }
        }
    }

    /**
     * The advertised surface comes from the EMBEDDER's config, not a fabricated one: a read-only MCP
     * surface must not hand write tools to a voice caller.
     */
    @Test
    fun `a read-only embedder config withholds the write tools`() {
        McpTerminalRegistry.setMcpConfig(BossTermMcpConfig(allowWriteTools = false))
        val names = executor(scope = setOf("t1")).tools().map { it.name }
        assertTrue("read_scrollback" in names, "reads stay available: $names")
        assertFalse("run_command" in names, "a read-only host must not expose run_command: $names")
        assertFalse("send_input" in names, names.toString())
        assertFalse("send_signal" in names, names.toString())
    }

    /** With writes allowed (the default), the full curated surface is advertised. */
    @Test
    fun `the default config advertises the write tools`() {
        McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())
        val names = executor(scope = setOf("t1")).tools().map { it.name }
        assertTrue("run_command" in names, names.toString())
        assertTrue("send_input" in names, names.toString())
    }

    /**
     * A prefixing embedder registers its handlers as `<prefix><tool>`; resolving by the bare name
     * would silently collapse the surface to the two locally-answered tools.
     */
    @Test
    fun `a tool name prefix still resolves the handlers`() {
        McpTerminalRegistry.setMcpConfig(BossTermMcpConfig(toolNamePrefix = "bossconsole_"))
        val names = executor(scope = setOf("t1")).tools().map { it.name }
        assertTrue("read_scrollback" in names, "prefixed handlers must still be found: $names")
        assertTrue("run_command" in names, names.toString())
        // The catalog names stay unprefixed — the prefix is a handler-lookup detail, and the model
        // is told the plain names.
        assertEquals(names, names.map { it.removePrefix("bossconsole_") })
    }
}
