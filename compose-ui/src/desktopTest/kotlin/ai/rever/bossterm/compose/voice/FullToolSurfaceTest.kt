package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exposing the whole MCP surface to the in-app agent.
 *
 * The curated catalog stays for SHARES, where the declaredParameters allowlist is doing real
 * security work — a guest must not be able to pass `panel: "new_tab"` and leave the shared session.
 * In-app the caller is the machine's owner, who already reaches every one of these tools from their
 * own MCP endpoint.
 */
class FullToolSurfaceTest {

    @BeforeTest
    fun setUp() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    @AfterTest
    fun tearDown() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    private fun executor(mayUseFocusedPane: Boolean, exposeAll: Boolean) = GuiVoiceToolExecutor(
        inScopeTabIds = { setOf("t1") },
        anchorTabId = { "t1" },
        mayUseFocusedPane = mayUseFocusedPane,
        settings = { TerminalSettings.DEFAULT.copy(voiceExposeAllTools = exposeAll) },
    )

    @Test
    fun `the in-app agent gets tools the curated catalog withholds`() {
        val names = executor(mayUseFocusedPane = true, exposeAll = true).tools().map { it.name }
        val curated = VoiceToolCatalog.ALL.map { it.name }.toSet()

        assertTrue(names.isNotEmpty(), "the server registered nothing")
        // The catalog deliberately excludes these; the owner should still have them.
        for (extra in listOf("run_in_panel", "show_image", "read_debug_console")) {
            assertTrue(extra in names, "$extra should reach the in-app agent; got $names")
            assertFalse(extra in curated, "sanity: $extra is outside the curated catalog")
        }
    }

    /** A share is a different trust boundary and keeps the curated set, whatever the setting says. */
    @Test
    fun `a share is unaffected by the setting`() {
        val names = executor(mayUseFocusedPane = false, exposeAll = true).tools().map { it.name }
        val curated = VoiceToolCatalog.ALL.map { it.name }.toSet()
        assertTrue(names.all { it in curated }, "a share must not gain tools: ${names - curated}")
    }

    @Test
    fun `turning it off returns the in-app agent to the curated set`() {
        val names = executor(mayUseFocusedPane = true, exposeAll = false).tools().map { it.name }
        val curated = VoiceToolCatalog.ALL.map { it.name }.toSet()
        assertTrue(names.all { it in curated }, "unexpected: ${names - curated}")
    }

    /**
     * `toolNames()` exists only to skip building a definition per tool, so the one thing it must
     * never do is disagree with `tools()`.
     *
     * It is the base-name set the merge computes collisions from, so a disagreement is not a
     * cosmetic drift: names missing from it stop being recognised as BossTerm's, and an embedder's
     * tool of the same name is then advertised alongside — the shadowing this seam exists to
     * prevent. Both modes, because the override branches the same way `tools()` does.
     */
    @Test
    fun `toolNames agrees with tools in every mode`() {
        for (exposeAll in listOf(true, false)) {
            for (inApp in listOf(true, false)) {
                val exec = executor(mayUseFocusedPane = inApp, exposeAll = exposeAll)
                assertEquals(
                    exec.tools().mapTo(mutableSetOf()) { it.name },
                    exec.toolNames(),
                    "exposeAll=$exposeAll inApp=$inApp",
                )
            }
        }
    }

    /**
     * Schemas come from the server, so the agent is told what each tool really takes — and the
     * argument allowlist, which filters on exactly these keys, stays meaningful.
     */
    @Test
    fun `every exposed tool carries a real schema and a write classification`() {
        val tools = executor(mayUseFocusedPane = true, exposeAll = true).tools()
        for (def in tools) {
            assertEquals("object", def.parameters["type"]?.toString()?.trim('"'), "${def.name} schema")
            assertTrue(def.description.isNotBlank(), "${def.name} has no description")
        }
        // Classified by the server's own read/write split, not guessed by the voice layer.
        assertTrue(tools.first { it.name == "run_command" }.write, "run_command must be a write tool")
        assertFalse(tools.first { it.name == "read_scrollback" }.write, "read_scrollback is a read")
    }
}
