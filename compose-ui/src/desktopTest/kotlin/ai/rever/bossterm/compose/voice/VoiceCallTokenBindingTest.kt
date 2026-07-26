package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.daemon.DaemonShareConnection
import ai.rever.bossterm.compose.share.ViewerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * The compare-and-clear a late mint failure uses must be ONE operation.
     *
     * Written as `if (token == stale) token = null` it was a read-then-write, and the defining
     * symptom is that several racers can all observe the token and all "win" — in production that
     * means a failing mint clears a token a redial installed in the gap, leaving the newer call
     * live and BILLED in `liveCalls` with no token on the connection to authorise its tools.
     *
     * Asserted as "exactly one caller may win", which is the property a CAS has and a read-then
     * -write does not. Checked against a deliberately non-atomic implementation before being
     * committed — the obvious version of this test (assert the final value) passes on the broken
     * code and would have been worthless.
     */
    @Test
    fun `only one of many concurrent clears may win`() {
        val racers = 8
        val pool = Executors.newFixedThreadPool(racers)
        try {
            repeat(500) {
                val vc = ViewerConnection(id = 1, canControl = true)
                vc.voiceCallToken = "A"
                val start = CountDownLatch(1)
                val wins = AtomicInteger(0)
                val done = CountDownLatch(racers)
                repeat(racers) {
                    pool.submit {
                        start.await()
                        if (vc.clearVoiceCallTokenIfCurrent("A")) wins.incrementAndGet()
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS), "racers finished")
                assertEquals(1, wins.get(), "a compare-and-clear admits exactly one winner")
                assertEquals(null, vc.voiceCallToken)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a compare-and-clear only fires for the token it was given`() {
        val vc = ViewerConnection(id = 1, canControl = true)
        vc.voiceCallToken = "B"
        assertFalse(vc.clearVoiceCallTokenIfCurrent("A"), "a stale token must not clear a newer one")
        assertEquals("B", vc.voiceCallToken)
        assertTrue(vc.clearVoiceCallTokenIfCurrent("B"))
        assertEquals(null, vc.voiceCallToken)
    }

    /** The daemon carries its own copy of the connection type, so it gets the same assertion. */
    @Test
    fun `the daemon connection compare-and-clears the same way`() {
        val vc = DaemonShareConnection(id = 1, canControl = true, name = "viewer")
        vc.voiceCallToken = "B"
        assertFalse(vc.clearVoiceCallTokenIfCurrent("A"))
        assertEquals("B", vc.voiceCallToken)
        assertTrue(vc.clearVoiceCallTokenIfCurrent("B"))
        assertEquals(null, vc.voiceCallToken)
    }
}
