package ai.rever.bossterm.compose.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the agent types into the pane a human is actively using.
 *
 * Every branch here is a decision about someone's live shell, and until this was pulled out of a
 * registry lookup none of them were reachable in a test — which is how the `hasSeenPrompt` case
 * shipped inverted for the commonest scenario there is.
 */
class FocusedPaneDecisionTest {

    private fun decide(
        mayUse: Boolean = true,
        setting: Boolean = true,
        seenPrompt: Boolean = true,
        running: Boolean = false,
    ) = shouldUseFocusedPane(mayUse, setting, seenPrompt, running)

    /**
     * The regression this test exists for: a terminal you just opened has announced a prompt and run
     * nothing. It is idle by any reasonable reading, and it was being refused.
     */
    @Test
    fun `a fresh pane that has run nothing is usable`() {
        assertTrue(decide(seenPrompt = true, running = false))
    }

    @Test
    fun `a pane with a command in flight is left alone`() {
        // A build, a REPL, an ssh session, a pager — typing into it would interleave.
        assertFalse(decide(running = true))
    }

    @Test
    fun `without shell integration we cannot tell, so we do not risk it`() {
        assertFalse(decide(seenPrompt = false), "no OSC 133 means busy and idle are indistinguishable")
        // And that holds even though nothing is known to be running.
        assertFalse(decide(seenPrompt = false, running = false))
    }

    /** A remote caller never takes the host's pane, whatever the host's own setting says. */
    @Test
    fun `a share never uses the focused pane`() {
        assertFalse(decide(mayUse = false))
        assertFalse(decide(mayUse = false, setting = true, seenPrompt = true, running = false))
    }

    @Test
    fun `the user can turn it off`() {
        assertFalse(decide(setting = false))
    }
}
