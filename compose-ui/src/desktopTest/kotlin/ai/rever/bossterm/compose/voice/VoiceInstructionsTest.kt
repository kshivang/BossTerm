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

    /**
     * send_input presses nothing on its own — the agent has to submit what it types.
     *
     * Its contract puts the newline on the caller, so text sent without one sits at the prompt unrun.
     * The agent then reads a scrollback with no result and reports the command as hung or failed, which
     * is a much more confusing outcome than a missing keystroke. Told to the agent wherever the tool is
     * available, not only in the fallback branch that first mentioned it.
     */
    @Test
    fun `the agent is told that send_input needs a newline to submit`() {
        for (surface in listOf(fullToolset, fullToolset - "run_command")) {
            val rules = voiceAgentRules(surface, confirmationWording = "verbal")
            assertTrue(rules.contains("does not press Enter"), rules)
            // Both characters, together: a bare \n submits in most shells but not every interactive
            // program, and CRLF is what a terminal actually sends for Enter.
            assertTrue(rules.contains("""\r\n"""), "the exact sequence must be named: $rules")
        }
    }

    @Test
    fun `a surface with no write tools is not told about submitting input`() {
        val readOnly = VoiceToolCatalog.ALL.filter { !it.write }.map { it.name }.toSet()
        val rules = voiceAgentRules(readOnly, confirmationWording = "verbal")
        assertFalse(rules.contains("does not press Enter"), rules)
    }

    /**
     * Heavy code work goes to Claude Code, not to a series of one-liners narrated over voice.
     *
     * The mechanics are part of the claim: interactive `claude` enters the alternate screen, which is
     * precisely what run_command refuses with "TUI detected", so an agent told only "use claude" would
     * hit that refusal and report the feature as broken.
     */
    @Test
    fun `the agent is pointed at Claude Code for real code work`() {
        val rules = voiceAgentRules(fullToolset, confirmationWording = "spoken")
        assertTrue(rules.contains("claude -p"), "the non-interactive form must be named: $rules")
        assertTrue(rules.contains("TUI detected"), "and why run_command will not drive it: $rules")
    }

    @Test
    fun `a surface that cannot run anything is not told about Claude Code`() {
        val readOnly = VoiceToolCatalog.ALL.filter { !it.write }.map { it.name }.toSet()
        val rules = voiceAgentRules(readOnly, confirmationWording = "verbal")
        assertFalse(rules.contains("claude"), "no way to launch it, so naming it sends it nowhere")
    }

    @Test
    fun `optional read tools are only named when the surface has them`() {
        val minimal = voiceAgentRules(setOf("read_scrollback"), confirmationWording = "verbal")
        assertFalse(minimal.contains("search_output"), minimal)
        assertFalse(minimal.contains("get_last_command"), minimal)
        assertTrue(voiceAgentRules(fullToolset, "verbal").contains("search_output"))
    }
}
