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

        // A tab the MODEL names is the boundary: refused outright, for every tool including the two
        // answered locally (they used to bypass the check entirely).
        assertFailsWith<VoiceToolException> {
            runBlocking {
                exec.execute("read_scrollback", buildJsonObject { put("tab_id", "foreign") }, null)
            }
        }
        assertFailsWith<VoiceToolException> {
            runBlocking {
                exec.execute("get_active_tab", buildJsonObject { put("tab_id", "foreign") }, null)
            }
        }
        assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("list_tabs", buildJsonObject { put("tab_id", "foreign") }, null) }
        }

        // A STALE viewer-reported focus is not an attack — the tab was in scope when stored and
        // then closed — so it falls back to the anchor rather than bricking the surface. What must
        // never happen is the foreign tab's data coming back.
        val info = runBlocking { exec.execute("get_active_tab", buildJsonObject { }, defaultTabId = "foreign") }
        assertFalse(info.contains("foreign"), info)
        val tabs = runBlocking { exec.execute("list_tabs", buildJsonObject { }, defaultTabId = "foreign") }
        assertFalse(tabs.contains("foreign"), tabs)
        val read = runBlocking { exec.execute("read_scrollback", buildJsonObject { }, defaultTabId = "foreign") }
        assertFalse(read.contains("foreign"), read)
    }

    /**
     * The viewer's focused tab is validated when stored, but the tab can close afterwards. A stale
     * id must not break the whole surface: the two argument-less tools still answer, so the agent
     * can discover a live id instead of getting "not part of this share" from everything.
     */
    @Test
    fun `a focused tab that closed falls back instead of breaking every tool`() {
        val exec = executor(scope = setOf("t1"), anchor = "t1")

        // list_tabs takes no target at all — it must answer.
        val tabs = runBlocking { exec.execute("list_tabs", buildJsonObject { }, defaultTabId = "closed-tab") }
        assertTrue(tabs.contains("\"tabs\""), tabs)
        // get_active_tab falls back to the in-scope anchor rather than refusing.
        val active = runBlocking { exec.execute("get_active_tab", buildJsonObject { }, defaultTabId = "closed-tab") }
        assertTrue(active.contains("t1") || active.contains("no longer open"), active)
        // But a tab the MODEL names is still refused — that's the actual boundary.
        assertFailsWith<VoiceToolException> {
            runBlocking {
                exec.execute("get_active_tab", buildJsonObject { put("tab_id", "closed-tab") }, null)
            }
        }
    }

    /**
     * The arg allowlist is a security control, not tidiness: run_command's real MCP schema also
     * accepts `panel`, and `panel: "new_tab"` would run the command in a tab the share doesn't
     * mirror. Only keys the voice catalog advertises may reach a handler.
     */
    @Test
    fun `arguments outside the advertised schema are dropped`() {
        val declared = VoiceToolCatalog.declaredParameters(
            VoiceToolCatalog.ALL.first { it.name == "run_command" }
        )
        assertTrue("script" in declared, "the advertised keys survive: $declared")
        assertTrue("timeout_ms" in declared, declared.toString())
        assertFalse("panel" in declared, "panel is NOT advertised, so it must never be forwarded")
        assertFalse("working_dir" in declared, declared.toString())
        assertFalse("split_ratio" in declared, declared.toString())

        // And the executor really does filter on it rather than passing the map through: an
        // out-of-scope target is still refused even when extra keys ride along.
        val exec = executor(scope = setOf("t1"))
        assertFailsWith<VoiceToolException> {
            runBlocking {
                exec.execute(
                    "run_command",
                    buildJsonObject {
                        put("script", "echo hi")
                        put("panel", "new_tab")
                        put("tab_id", "foreign")
                    },
                    null,
                )
            }
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
