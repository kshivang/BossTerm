package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.ai.ToolCategory
import ai.rever.bossterm.compose.mcp.McpAttachTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two AI lists in `viewer.js` to the Kotlin registries they mirror.
 *
 * The share viewer is a static web asset, so it can't read the registry the way `TabBar` and
 * `TabbedTerminal` now do — it's the one place the "add a CLI in one edit" property leaks, and the
 * only defence is a test. `ShareProtocolTest` pins the attach-target key SET, but it compares
 * against a set hardcoded in Kotlin, so it catches a change to the enum and misses drift in
 * `viewer.js` itself. These assertions read the actual file.
 *
 * Both lists are compared as ordered lists, not sets: the viewer is supposed to present the same
 * open-source-first ordering the host does, and an out-of-order list is a real (if small) UX bug
 * that a set comparison would wave through.
 */
class ShareViewerAiListContractTest {

    private val viewerJs: String by lazy { NodeHarness.readResource("share-viewer/viewer.js") }

    /** Ids/keys from a `var NAME = [ { field: "value", … }, … ]` literal, in source order. */
    private fun literalValues(arrayName: String, field: String): List<String> {
        val block = Regex("""var\s+$arrayName\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(viewerJs)
            ?.groupValues
            ?.get(1)
        assertTrue(block != null, "could not find `var $arrayName = [ … ]` in viewer.js")
        return Regex("""\b$field\s*:\s*"([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `viewer AI assistant ids match the registry in open-source-first order`() {
        // BUILTIN, not AI_ASSISTANTS: a user's custom CLI exists only on their machine and can't be
        // baked into a shipped web asset.
        val expected = AIAssistants.BUILTIN
            .filter { it.category == ToolCategory.AI_ASSISTANT }
            .sortedWith(compareBy({ it.openness.ordinal }, { it.displayName }))
            .map { it.id }

        assertEquals(
            expected,
            literalValues("AI_ASSISTANTS", "id"),
            "viewer.js AI_ASSISTANTS is out of sync with AIAssistants.AI_ASSISTANTS_OSS_FIRST - " +
                "update the list in compose-ui/src/desktopMain/resources/share-viewer/viewer.js"
        )
    }

    @Test
    fun `viewer MCP target keys match the attach registry in the same order`() {
        assertEquals(
            McpAttachTarget.ossFirst.map { it.persistenceKey },
            literalValues("MCP_TARGETS", "key"),
            "viewer.js MCP_TARGETS is out of sync with McpAttachTarget.ossFirst - " +
                "update the list in compose-ui/src/desktopMain/resources/share-viewer/viewer.js"
        )
    }

    @Test
    fun `viewer labels match the display names the host uses`() {
        val expected = McpAttachTarget.ossFirst.map { it.displayName }
        assertEquals(
            expected,
            literalValues("MCP_TARGETS", "label"),
            "viewer.js MCP_TARGETS labels drifted from McpAttachTarget.displayName"
        )
    }
}
