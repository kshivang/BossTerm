package ai.rever.bossterm.compose.voice

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shaping the agent's instructions — the seam an embedder (BossConsole) uses to put this agent
 * inside a product with its own vocabulary, and the seam a user uses to say what their machine is
 * for.
 */
class VoiceAgentCustomizationTest {

    private val tools = VoiceToolCatalog.ALL.map { it.name }.toSet()

    @BeforeTest
    fun reset() = VoiceAgentCustomization.resetForTest()

    @AfterTest
    fun clear() = VoiceAgentCustomization.resetForTest()

    @Test
    fun `with nothing configured the built-in rules are unchanged`() {
        val rules = voiceAgentRules(tools, "spoken")
        assertTrue(rules.startsWith("Rules:"))
        // The brevity rule is deliberately the last built-in — recency carries weight with these
        // models — so "ends on the brevity rule" and "nothing was appended" are the same assertion.
        assertTrue(rules.contains("FEWEST words"), rules.takeLast(200))
        assertTrue(rules.trimEnd().endsWith("required to get."), rules.takeLast(200))
    }

    @Test
    fun `an embedder's context is appended to the built-in rules`() {
        VoiceAgentCustomization.extraInstructions = "This is a Kubernetes console."
        val rules = voiceAgentRules(tools, "spoken")
        assertTrue(rules.contains("Run shell commands with run_command"), "the built-ins survive")
        assertTrue(rules.trimEnd().endsWith("- This is a Kubernetes console."), rules.takeLast(120))
    }

    /**
     * Ordering is the point: a person must be able to correct something the product got wrong
     * without editing the product, so their line comes last.
     */
    @Test
    fun `the user's own instruction comes after the embedder's`() {
        VoiceAgentCustomization.extraInstructions = "EMBEDDER"
        val rules = voiceAgentRules(tools, "spoken", userExtra = "USER")
        assertTrue(rules.indexOf("- EMBEDDER") < rules.indexOf("- USER"), rules.takeLast(120))
    }

    @Test
    fun `blank additions add nothing`() {
        VoiceAgentCustomization.extraInstructions = "   "
        val rules = voiceAgentRules(tools, "spoken", userExtra = "")
        assertEquals(voiceAgentRules(tools, "spoken"), rules)
    }

    @Test
    fun `an override replaces the rules but not the additions`() {
        VoiceAgentCustomization.rulesOverride = { names, wording ->
            "Custom rules for ${names.size} tools ($wording)."
        }
        VoiceAgentCustomization.extraInstructions = "EMBEDDER"
        val rules = voiceAgentRules(tools, "verbal", userExtra = "USER")

        assertFalse(rules.contains("Run shell commands with run_command"), "built-ins are replaced")
        assertTrue(rules.startsWith("Custom rules for ${tools.size} tools (verbal)."), rules)
        // An override must not silently discard what a person typed into Settings.
        assertTrue(rules.contains("- EMBEDDER"))
        assertTrue(rules.contains("- USER"))
    }

    /**
     * The override receives the ADVERTISED set, which varies by surface — the daemon drops guiOnly
     * tools, an embedder with allowWriteTools = false drops the write tools. An override that
     * ignored it would send the agent after tools that cannot be called.
     */
    @Test
    fun `the override sees only the tools the surface actually has`() {
        var seen: Set<String>? = null
        VoiceAgentCustomization.rulesOverride = { names, _ -> seen = names; "x" }
        val readOnly = VoiceToolCatalog.ALL.filterNot { it.write }.map { it.name }.toSet()
        voiceAgentRules(readOnly, "verbal")
        assertEquals(readOnly, seen)
        assertFalse(seen!!.contains("run_command"))
    }

    /**
     * Commands now land in the pane the user is watching, so the agent is told to look at it first
     * rather than treating it as a blank shell.
     */
    @Test
    fun `a surface with run_command is told to read the terminal it is typing into`() {
        val rules = voiceAgentRules(tools, "spoken")
        assertTrue(rules.contains("the terminal the user is looking at"), rules)
        assertTrue(rules.contains("read_scrollback first"), rules)
    }

    @Test
    fun `a read-only surface is not told that its commands land anywhere`() {
        val readOnly = VoiceToolCatalog.ALL.filterNot { it.write }.map { it.name }.toSet()
        val rules = voiceAgentRules(readOnly, "verbal")
        assertFalse(rules.contains("the terminal the user is looking at"), rules)
    }
}
