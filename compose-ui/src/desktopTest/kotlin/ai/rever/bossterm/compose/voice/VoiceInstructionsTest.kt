package ai.rever.bossterm.compose.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules block both surfaces send the model.
 *
 * It was duplicated verbatim in the two instruction builders, with each KDoc asking the next person
 * not to let them drift — so the value of testing it is mostly that the surfaces now provably agree.
 */
class VoiceInstructionsTest {

    private val fullToolset = VoiceToolCatalog.ALL.map { it.name }.toSet()

    @Test
    fun `both surfaces get identical rules apart from the confirmation wording`() {
        val share = voiceAgentRules(fullToolset, confirmationWording = "verbal")
        val host = voiceAgentRules(fullToolset, confirmationWording = "spoken")
        assertEquals(
            share.replace("get verbal confirmation", "CONFIRM"),
            host.replace("get spoken confirmation", "CONFIRM"),
            "the two surfaces must differ only in how they ask for confirmation",
        )
    }

    @Test
    fun `a surface with run_command is told about the shell-integration fallback`() {
        val rules = voiceAgentRules(fullToolset, confirmationWording = "verbal")
        assertTrue(rules.contains("run_command"))
        assertTrue(
            rules.contains("shell integration is missing"),
            "without this the agent reports the feature as broken instead of falling back",
        )
        assertTrue(rules.contains("send_signal ctrl_c"))
    }

    @Test
    fun `a read-only surface is not told to run commands`() {
        // An embedder with allowWriteTools = false: describing write tools it does not have would
        // send the agent after tools that will be refused.
        val readOnly = VoiceToolCatalog.ALL.filter { !it.write }.map { it.name }.toSet()
        val rules = voiceAgentRules(readOnly, confirmationWording = "verbal")
        assertFalse(rules.contains("run_command"), rules)
        assertFalse(rules.contains("send_input"), rules)
        assertTrue(rules.contains("read_scrollback"), "reads are still described")
    }

    @Test
    fun `a surface without run_command falls back to describing send_input`() {
        val noRunCommand = fullToolset - "run_command"
        val rules = voiceAgentRules(noRunCommand, confirmationWording = "verbal")
        assertFalse(rules.contains("with run_command"))
        assertTrue(rules.contains("typing them with send_input"), rules)
    }

    @Test
    fun `optional read tools are only named when the surface has them`() {
        val minimal = voiceAgentRules(setOf("read_scrollback"), confirmationWording = "verbal")
        assertFalse(minimal.contains("search_output"), minimal)
        assertFalse(minimal.contains("get_last_command"), minimal)
        assertTrue(voiceAgentRules(fullToolset, "verbal").contains("search_output"))
    }
}
