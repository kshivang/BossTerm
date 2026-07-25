package ai.rever.bossterm.compose.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-connection token binding — the check that stops viewer B replaying viewer A's call token
 * and driving the read tools without ever passing the control gate.
 *
 * It lives in both servers' message handlers rather than in [VoiceCallService], which is precisely
 * why it needs its own test: a refactor that pushes token handling down into the service could drop
 * it without a single existing test noticing.
 */
class VoiceCallTokenBindingTest {

    @Test
    fun `only the token issued to this connection is accepted`() {
        assertTrue(voiceCallTokenMatches("tok-a", "tok-a"), "its own token works")
        assertFalse(voiceCallTokenMatches("tok-a", "tok-b"), "another viewer's token must not")
    }

    @Test
    fun `a connection with no call of its own accepts nothing`() {
        // The replay case: viewer B never started a call, so whatever it presents is refused —
        // including a token that is genuinely live on the share for viewer A.
        assertFalse(voiceCallTokenMatches(null, "tok-a"), "no call here → no tools")
        assertFalse(voiceCallTokenMatches(null, null))
    }

    @Test
    fun `an absent token is refused even on a connection that has a call`() {
        // Older viewers, or a hand-rolled client omitting the field, must not fall through.
        assertFalse(voiceCallTokenMatches("tok-a", null))
        assertFalse(voiceCallTokenMatches("tok-a", ""), "empty is not a token")
    }
}
