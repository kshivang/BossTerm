package ai.rever.bossterm.compose.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `run_in_panel` reuses the pane it already owns instead of splitting again.
 *
 * The behaviour itself needs a live `TabbedTerminalState` (a real window, a real PTY), so what
 * is pinned here is everything that decides whether the norm can take effect at all: the
 * opt-out argument has to exist or a caller passing `force_new` is silently ignored, the
 * description has to say so because that text is what actually steers a model, and the
 * scratch-pane bookkeeping the reuse path reads has to round-trip.
 */
class RunInPanelReuseTest {

    @BeforeTest
    fun setUp() = McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())

    @AfterTest
    fun tearDown() {
        McpTerminalRegistry.clearScratchPane(TAB)
        McpTerminalRegistry.setMcpConfig(BossTermMcpConfig())
    }

    private fun schema() = BossTermMcpServer(McpTerminalRegistry).createServer()
        .tools["run_in_panel"]
        ?: error("run_in_panel is not registered")

    @Test
    fun forceNewIsDeclaredSoCallersCanOptOut() {
        val props = schema().tool.inputSchema.properties.orEmpty()
        assertTrue("force_new" in props, "force_new missing: an opt-out nobody can pass is not one")
    }

    @Test
    fun forceNewIsOptional() {
        // Required would invert the norm: every caller would have to think about panes.
        val required = schema().tool.inputSchema.required.orEmpty()
        assertTrue("force_new" !in required, "force_new must default, not be demanded")
    }

    @Test
    fun theDescriptionStatesTheNorm() {
        // The tool description is the only thing steering a model's choice here, so an
        // implementation that reuses while the text still promises a fresh split every call
        // would keep producing the pane sprawl this exists to stop.
        val description = schema().tool.description.orEmpty().lowercase()
        assertTrue("reuse" in description, "description does not mention reuse: $description")
        assertTrue("force_new" in description, "description does not mention the opt-out")
    }

    @Test
    fun scratchPaneRoundTripsSoTheReusePathCanFindIt() {
        assertNull(McpTerminalRegistry.getScratchPane(TAB))
        McpTerminalRegistry.setScratchPane(TAB, PANE)
        assertEquals(PANE, McpTerminalRegistry.getScratchPane(TAB))
        McpTerminalRegistry.clearScratchPane(TAB, PANE)
        assertNull(McpTerminalRegistry.getScratchPane(TAB))
    }

    @Test
    fun resultCarriesWhetherAPaneWasReused() {
        // A caller that cannot tell reuse from creation has to diff list_panes to find out.
        val json = Json { encodeDefaults = true }
        val reused = json.encodeToString(
            BossTermMcpServer.RunInPanelResult.serializer(),
            BossTermMcpServer.RunInPanelResult(ok = true, tabId = TAB, paneId = PANE, reused = true)
        )
        assertTrue("\"reused\":true" in reused.replace(" ", ""), reused)

        val fresh = json.parseToJsonElement(
            json.encodeToString(
                BossTermMcpServer.RunInPanelResult.serializer(),
                BossTermMcpServer.RunInPanelResult(ok = true, tabId = TAB, paneId = PANE)
            )
        ).jsonObject
        assertEquals("false", fresh["reused"].toString(), "a fresh split must not claim reuse")
    }

    private companion object {
        const val TAB = "reuse-test-tab"
        const val PANE = "reuse-test-pane"
    }
}
