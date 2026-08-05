package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.ai.OpennessTier
import ai.rever.bossterm.compose.ai.ToolCategory
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the seam between the two registries — the tool registry ([AIAssistants]) and the MCP
 * attach registry ([McpAttachTarget]).
 *
 * A CLI used to have to be registered in seven places, and the failure mode was always silent: an
 * assistant would launch fine from the menu but have no Attach button, or an attach target would
 * point at an id nothing else knew about. These assertions make that a red test instead.
 */
class AttachTargetCoverageTest {

    /**
     * A temp home that actually gets cleaned up. `File.deleteOnExit()` only removes empty
     * directories, so the in-process round-trip — which writes a config inside — used to leak a
     * directory tree per run.
     */
    private fun tempHome(prefix: String): File =
        createTempDirectory(prefix).toFile().also { dir ->
            Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        }

    @Test
    fun `every attach target points at a real registry entry`() {
        for (target in McpAttachTarget.entries) {
            val assistant = AIAssistants.findById(target.assistantId)
            assertNotNull(
                assistant,
                "${target.name} declares assistantId '${target.assistantId}', " +
                    "which is not in AIAssistants.ALL"
            )
            assertEquals(
                ToolCategory.AI_ASSISTANT,
                assistant.category,
                "${target.name} attaches to '${target.assistantId}', which is not an AI assistant"
            )
        }
    }

    /**
     * Scoped to BUILTIN on purpose. `AI_ASSISTANTS` is `BUILTIN + _customAssistants`, and
     * `_customAssistants` is process-wide mutable state: a user-registered custom CLI legitimately
     * has no attach target, so asserting over `AI_ASSISTANTS` would pass only for as long as no test
     * calls `setCustomAssistants` — then fail depending on execution order. The invariant we
     * actually mean is about the CLIs we ship.
     */
    @Test
    fun `every built-in AI assistant has an attach target`() {
        val missing = AIAssistants.BUILTIN
            .filter { it.category == ToolCategory.AI_ASSISTANT }
            .filter { McpAttachTarget.forAssistant(it.id) == null }
            .map { it.id }
        assertTrue(
            missing.isEmpty(),
            "built-in AI assistants with no McpAttachTarget (add one, or move the CLI out of the " +
                "AI_ASSISTANT category if it can't consume MCP): $missing"
        )
    }

    /** A custom assistant with no attach target must not break the coverage invariant. */
    @Test
    fun `a custom assistant without an attach target is allowed`() {
        try {
            AIAssistants.setCustomAssistants(
                listOf(
                    ai.rever.bossterm.compose.ai.AIAssistantDefinition(
                        id = "my-private-cli",
                        displayName = "My Private CLI",
                        command = "mycli"
                    )
                )
            )
            assertTrue(AIAssistants.AI_ASSISTANTS.any { it.id == "my-private-cli" })
            assertTrue(
                McpAttachTarget.forAssistant("my-private-cli") == null,
                "a custom CLI should not be expected to have an attach target"
            )
            // The built-in invariant still holds with custom entries registered.
            val missing = AIAssistants.BUILTIN
                .filter { it.category == ToolCategory.AI_ASSISTANT }
                .filter { McpAttachTarget.forAssistant(it.id) == null }
            assertTrue(missing.isEmpty(), "built-in coverage must not depend on custom state")
        } finally {
            AIAssistants.setCustomAssistants(emptyList())
        }
    }

    @Test
    fun `local model runtimes have no attach target`() {
        // Ollama serves models; it is not an MCP client. An Attach button for it would be a
        // permanently broken button, which is why it lives in its own category.
        for (runtime in AIAssistants.LOCAL_MODEL_RUNTIMES) {
            assertTrue(
                McpAttachTarget.forAssistant(runtime.id) == null,
                "${runtime.id} is a local model runtime but has an attach target"
            )
        }
    }

    @Test
    fun `persistence keys and assistant ids are unique`() {
        val keys = McpAttachTarget.entries.map { it.persistenceKey }
        assertEquals(keys.size, keys.toSet().size, "duplicate persistenceKey in McpAttachTarget: $keys")

        val ids = McpAttachTarget.entries.map { it.assistantId }
        assertEquals(ids.size, ids.toSet().size, "two attach targets share one assistantId: $ids")
    }

    @Test
    fun `ossFirst orders open stacks before open clients before proprietary`() {
        val tiers = McpAttachTarget.ossFirst.map {
            AIAssistants.findById(it.assistantId)?.openness ?: OpennessTier.PROPRIETARY
        }
        val rendered = McpAttachTarget.ossFirst.zip(tiers) { target, tier -> "${target.displayName}=$tier" }
        assertEquals(
            tiers.sortedBy { it.ordinal },
            tiers,
            "McpAttachTarget.ossFirst is not grouped by openness: $rendered"
        )
        // Every target still shows up exactly once — a sort, not a filter.
        assertEquals(McpAttachTarget.entries.toSet(), McpAttachTarget.ossFirst.toSet())
        assertEquals(McpAttachTarget.entries.size, McpAttachTarget.ossFirst.size)
    }

    @Test
    fun `every target can produce either a command or an in-process write`() {
        val home = tempHome("attach-coverage-home")
        for (target in McpAttachTarget.entries) {
            val hasCommand = target.resolvedAddCommand("boss", "http://127.0.0.1:7677") != null
            // Probe rather than assume: a target with no add command MUST have an in-process path,
            // otherwise clicking Attach can only ever reach the clipboard fallback.
            val inProcess = target.attachInProcess("boss", "http://127.0.0.1:7677", File(home, target.name))
            assertTrue(
                hasCommand || inProcess != null,
                "${target.name} has neither an `mcp add` command nor an in-process attach path"
            )
        }
    }

    @Test
    fun `in-process targets round-trip a registration`() {
        for (target in McpAttachTarget.entries) {
            val home = tempHome("attach-roundtrip")
            val wrote = target.attachInProcess("boss", "http://127.0.0.1:7677", home) ?: continue

            assertTrue(wrote, "${target.name} failed to write its config into a fresh home")
            assertEquals(
                McpRegistrationScanner.Presence.PRESENT,
                McpRegistrationScanner.scan("boss", home)[target],
                "${target.name} wrote a config the scanner doesn't recognize - the writer and the " +
                    "reader disagree about the file path or shape"
            )

            assertTrue(
                target.detachInProcess("boss", home) == true,
                "${target.name} failed to remove its own registration"
            )
            assertEquals(
                McpRegistrationScanner.Presence.ABSENT,
                McpRegistrationScanner.scan("boss", home)[target]
            )
        }
    }
}
