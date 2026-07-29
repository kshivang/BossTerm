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
    fun `codex launches with danger full access sandbox`() {
        assertEquals(
            "codex --sandbox danger-full-access\n",
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

    /**
     * The open-source CLIs' auto-mode flags, verified against the installed binaries in July 2026:
     * `grok 0.2.3 --help` lists `--always-approve`, and the Kimi Code / Hermes docs both use
     * `--yolo`. Pinned here because each is a different word for the same thing and a wrong guess
     * fails only when a user clicks Launch.
     */
    @Test
    fun `open source assistants launch with their own auto-mode flags`() {
        assertEquals(
            "grok --always-approve\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.GROK_BUILD))
        )
        assertEquals(
            "kimi --yolo\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.KIMI_CODE))
        )
        assertEquals(
            "hermes --yolo\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.HERMES))
        )
    }

    @Test
    fun `ollama has no auto-mode flag to add`() {
        // Ollama runs models; there are no tool calls to approve. A stray flag here would be
        // appended to every launch and break it.
        val ollama = builtin(AIAssistantIds.OLLAMA)
        assertEquals("", ollama.yoloFlag)
        assertEquals("ollama\n", provider.getLaunchCommand(ollama))
    }

    @Test
    fun `every AI assistant declares an auto-mode flag`() {
        // A blank flag would make the menu's "(Auto)" label a lie.
        AIAssistants.AI_ASSISTANTS.forEach { assistant ->
            assertFalse(
                assistant.yoloFlag.isBlank(),
                "${assistant.id} has no yoloFlag — the menu would show an auto-mode it can't enable"
            )
        }
    }

    @Test
    fun `open source assistants carry their license and source`() {
        // The openness ordering and the UI badges both read these, so an entry that forgets them
        // silently sorts as proprietary.
        AIAssistants.AI_ASSISTANTS.filter { it.openSource }.forEach { assistant ->
            assertFalse(
                assistant.license.isBlank(),
                "${assistant.id} is marked openSource but declares no license"
            )
            assertFalse(
                assistant.sourceUrl.isBlank(),
                "${assistant.id} is marked openSource but declares no sourceUrl"
            )
        }
    }
}
