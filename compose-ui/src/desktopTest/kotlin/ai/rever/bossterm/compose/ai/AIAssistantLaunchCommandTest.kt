package ai.rever.bossterm.compose.ai

import ai.rever.bossterm.compose.settings.AIAssistantConfigData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Pins the launch commands the AI Assistant context menu writes to the PTY.
 * The yolo flags live in [AIAssistants.BUILTIN] as plain data, so a silent
 * upstream CLI change (e.g. Codex dropping --full-auto) only surfaces at
 * launch time — these tests document the expected flag strings instead.
 */
class AIAssistantLaunchCommandTest {

    private val provider = ToolCommandProvider()

    private fun builtin(id: String): AIAssistantDefinition {
        val assistant = AIAssistants.BUILTIN.find { it.id == id }
        assertNotNull(assistant, "missing built-in assistant: $id")
        return assistant
    }

    @Test
    fun `codex launches with sandboxed auto flags`() {
        // Codex CLI rejects the old --full-auto; the replacement keeps the
        // sandbox --full-auto implied instead of a full approval bypass.
        assertEquals(
            "codex --sandbox workspace-write --ask-for-approval never\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.CODEX))
        )
    }

    @Test
    fun `no built-in assistant uses the removed full-auto flag`() {
        AIAssistants.BUILTIN.forEach { assistant ->
            assertFalse(
                assistant.yoloFlag.contains("--full-auto"),
                "${assistant.id} still uses --full-auto, which Codex CLI removed"
            )
        }
    }

    @Test
    fun `yolo disabled launches the bare command`() {
        assertEquals(
            "codex\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.CODEX), AIAssistantConfigData(yoloEnabled = false))
        )
    }

    @Test
    fun `custom yolo flag overrides the built-in default`() {
        assertEquals(
            "codex --my-flag\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.CODEX), AIAssistantConfigData(customYoloFlag = "--my-flag"))
        )
    }
}
