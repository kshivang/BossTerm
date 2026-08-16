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
    fun `the agent is told that send_input needs a carriage return to submit`() {
        for (surface in listOf(fullToolset, fullToolset - "run_command")) {
            val rules = voiceAgentRules(surface, confirmationWording = "verbal")
            assertTrue(rules.contains("does not press Enter"), rules)
            // CR, and only CR. This assertion used to demand \r\n on the theory that CRLF is what a
            // terminal sends for Enter; it isn't. Enter is CR, and the trailing LF is delivered to
            // the next prompt as Ctrl+J, leaving the shell in continuation mode after every command
            // the agent runs. Measured on Windows PowerShell over ConPTY.
            assertTrue(rules.contains("""\r"""), "the exact character must be named: $rules")
            assertFalse(
                rules.contains("""\r\n"""),
                "CRLF poisons the following prompt - the rule must ask for a bare CR: $rules",
            )
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

    /**
     * Terseness is the default, and it is told what to STOP doing.
     *
     * "Keep replies short. This is a voice conversation." — the previous wording — named a preference
     * without naming a behaviour, and changed nothing: the agent still restated the request, narrated
     * its plan, and closed with an offer of further help, all before answering. The rule now lists the
     * omissions explicitly and gives one-word examples, and it sits LAST because recency carries
     * weight with these models.
     */
    @Test
    fun `brevity is the default and says what to stop doing`() {
        val rules = voiceAgentRules(fullToolset, confirmationWording = "spoken")
        assertTrue(rules.contains("FEWEST words"), rules.takeLast(300))
        assertTrue(rules.trimEnd().endsWith("required to get."), "it must come last: ${rules.takeLast(200)}")
        for (habit in listOf("do not restate", "do not narrate", "anything else")) {
            assertTrue(
                rules.contains(habit, ignoreCase = true),
                "the rule must name what to stop: '$habit' missing",
            )
        }
    }

    /**
     * The old rule asked for a preamble before EVERY slow call, and the preamble usually outran the
     * answer. Silence only needs explaining when it lasts long enough to look broken.
     */
    @Test
    fun `narration is limited to genuinely slow work`() {
        val rules = voiceAgentRules(fullToolset, confirmationWording = "spoken")
        assertTrue(rules.contains("genuinely slow"), rules)
        assertTrue(rules.contains("just do it"), "quick work needs no announcement: $rules")
        // Dropped by accident once while rewriting this rule; without it, build logs get read aloud.
        assertTrue(rules.contains("Never read raw terminal output aloud"), rules)
    }

    @Test
    fun `optional read tools are only named when the surface has them`() {
        val minimal = voiceAgentRules(setOf("read_scrollback"), confirmationWording = "verbal")
        assertFalse(minimal.contains("search_output"), minimal)
        assertFalse(minimal.contains("get_last_command"), minimal)
        assertTrue(voiceAgentRules(fullToolset, "verbal").contains("search_output"))
    }
}
