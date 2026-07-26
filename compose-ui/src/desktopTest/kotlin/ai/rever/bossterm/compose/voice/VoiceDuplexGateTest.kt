package ai.rever.bossterm.compose.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate between "the agent talks to itself" and "you cannot interrupt it".
 *
 * Levels here are RMS as [pcm16Rms] reports it, and the numbers are chosen to match what that scale
 * actually means: `VoiceAudioIo`'s own comment puts conversational speech at 0.05–0.15, so 0.10 is a
 * normally-spoken word and 0.04 is a reply coming back off the far wall. Testing this with levels
 * near 1.0 would prove nothing about a real room.
 */
class VoiceDuplexGateTest {

    private fun gate() = VoiceDuplexGate()

    /** Feed n frames at one level; returns the frame number that interrupted, or null. */
    private fun VoiceDuplexGate.feed(level: Float, n: Int): Int? {
        for (i in 1..n) if (classify(level) == Duplex.INTERRUPTED) return i
        return null
    }

    /** Settle the echo estimate the way a first reply would, past warm-up. */
    private fun VoiceDuplexGate.settleEcho(level: Float) {
        feed(level, 30)
    }

    @Test
    fun `steady echo never interrupts, however long the reply runs`() {
        val g = gate()
        assertEquals(null, g.feed(0.04f, 400), "16 seconds of its own voice must not read as the user")
    }

    @Test
    fun `the first frames of a call cannot trigger`() {
        val g = gate()
        // Nothing measured yet: an echo estimate of zero makes any ratio clearable, which is exactly
        // how the previous detector fired on the agent's own first syllable.
        assertEquals(null, g.feed(0.12f, 4))
    }

    /**
     * The bug that made the last attempt useless.
     *
     * It required an absolute RMS of 0.28 before calling anything a voice. Normal speech is 0.05-0.15,
     * so the bar sat above anything a person says at a keyboard and the gate silently behaved like
     * "send nothing" — which is why interruption appeared to be broken outright.
     */
    @Test
    fun `a normally-spoken interruption gets through over a quiet echo`() {
        val g = gate()
        repeat(20) { g.observeUserSpeech(0.10f) } // what this user's voice measures
        g.settleEcho(0.04f)
        val at = g.feed(0.10f, 6)
        assertTrue(at != null, "normal speech must clear the bar; bar was ${g.lastBar}")
        assertTrue(g.lastBar < 0.10f, "the bar must sit below the user's own level, was ${g.lastBar}")
        assertTrue(g.lastBar > 0.04f, "and above the echo's, was ${g.lastBar}")
    }

    /**
     * The reported bug: the agent cut itself off mid-sentence on every reply.
     *
     * `play()` QUEUES audio, so nothing leaves the speaker for a moment after `speaking` becomes
     * true. The frames captured in that gap are room silence, not echo — and the first version of
     * this gate measured exactly them, anchored the echo estimate near zero, and let a bar derived
     * from that estimate fall to the absolute floor. The real echo then arrived at a perfectly
     * ordinary 0.05 and read as a voice.
     *
     * The gate has no clock and cannot see the latency, so it must not be able to conclude "quiet, so
     * anything louder is the user" in the first place.
     */
    @Test
    fun `playback latency does not turn the echo estimate into a hair trigger`() {
        val g = gate()
        repeat(20) { g.observeUserSpeech(0.10f) }
        g.beginAgentTurn()
        g.feed(0.0f, 8) // audio is queued but not yet audible: silence, mistaken for a quiet room
        assertEquals(null, g.feed(0.05f, 40), "the reply arriving is not an interruption; bar ${g.lastBar}")
        assertTrue(g.lastBar >= 0.07f, "the bar must not fall under the user's own level: ${g.lastBar}")
    }

    @Test
    fun `a quiet echo estimate never lowers the bar below the user's voice`() {
        val g = gate()
        repeat(20) { g.observeUserSpeech(0.20f) } // a loud talker
        g.settleEcho(0.01f) // headphones, or a very quiet room
        assertEquals(null, g.feed(0.10f, 20), "half their usual level is not them; bar ${g.lastBar}")
        assertTrue(g.feed(0.20f, 4) != null, "their actual level is")
    }

    @Test
    fun `before the user has ever been measured nothing interrupts`() {
        val g = gate()
        // Only reachable when the agent speaks first. There is no evidence of what this user sounds
        // like, so any bar would be a guess, and guessing is what cut replies off.
        assertEquals(null, g.feed(0.5f, 40), "with nothing to compare against, withhold")
    }

    @Test
    fun `one loud frame is not a voice`() {
        val g = gate()
        repeat(20) { g.observeUserSpeech(0.10f) }
        g.settleEcho(0.04f)
        assertEquals(null, g.feed(0.20f, 2), "a key press or a door is one or two frames")
        assertTrue(g.feed(0.20f, 3) != null, "sustained is a voice")
    }

    @Test
    fun `an interruption latches for the rest of the reply`() {
        val g = gate()
        repeat(20) { g.observeUserSpeech(0.10f) }
        g.settleEcho(0.04f)
        assertTrue(g.feed(0.10f, 6) != null)
        // Speech has pauses and trailing consonants far below its average. Re-deciding per frame
        // would chop the utterance into fragments and send the model half a sentence.
        assertEquals(Duplex.PASS, g.classify(0.001f), "a pause mid-word must not close the gate")
        assertEquals(Duplex.PASS, g.classify(0.10f))
    }

    @Test
    fun `a new reply clears the latch but keeps what the room taught us`() {
        val g = gate()
        repeat(20) { g.observeUserSpeech(0.10f) }
        g.settleEcho(0.04f)
        assertTrue(g.feed(0.10f, 6) != null)
        val echoBefore = g.echoEstimate()

        g.beginAgentTurn()
        assertEquals(Duplex.WITHHOLD, g.classify(0.04f), "the latch must not survive into the reply")
        assertEquals(echoBefore, g.echoEstimate(), "re-measuring the room every reply would be a dead window")
        // Which means reply two is interruptible immediately, with no warm-up to sit through.
        assertTrue(g.feed(0.10f, 4) != null, "no dead window at the start of a later reply")
    }

    @Test
    fun `a silent room does not trigger on its own noise floor`() {
        val g = gate()
        g.settleEcho(0.0f) // speakers off, or headphones: nothing comes back
        assertEquals(null, g.feed(0.02f, 50), "fan noise is not an interruption")
    }

    /**
     * Speakers loud enough that the echo returns above the user's own level.
     *
     * No bar both rejects it and admits them. The gate prefers rejecting, because a self-sustaining
     * loop wrecks the whole call where a missed interruption costs one sentence — and it says so, so
     * the log names the cause instead of the user seeing "barge-in is flaky".
     */
    @Test
    fun `when the speakers drown the user it prefers rejecting the loop`() {
        val g = gate()
        repeat(20) { g.observeUserSpeech(0.06f) } // quiet talker
        g.settleEcho(0.20f) // loud speakers
        assertEquals(null, g.feed(0.06f, 20), "their own level must not be enough here")
        assertTrue(g.indistinguishable, "and the reason must be recorded")
        assertTrue(g.feed(0.40f, 4) != null, "shouting still gets through")
    }

    @Test
    fun `the user estimate follows the user rather than one loud word`() {
        val g = gate()
        repeat(30) { g.observeUserSpeech(0.10f) }
        g.observeUserSpeech(0.9f) // one shouted word
        assertTrue(g.userEstimate() < 0.30f, "one outlier must not redefine their voice: ${g.userEstimate()}")
    }

    /**
     * Superseded by [before the user has ever been measured nothing interrupts].
     *
     * This used to assert that a clear voice got through even with no measurement of the user, off a
     * bar of echo × 2. That bar was the self-interruption bug: with the echo estimate anchored to the
     * silence of playback latency, "echo × 2" is near zero and the reply clears its own bar. There is
     * no safe threshold to pick without knowing what the user sounds like, so the gate no longer
     * pretends there is.
     */
    @Test
    fun `an unmeasured user does not get a guessed threshold`() {
        val unmeasured = gate()
        unmeasured.settleEcho(0.04f)
        assertEquals(null, unmeasured.feed(0.15f, 20))
        assertFalse(
            unmeasured.indistinguishable,
            "this is not the loud-speaker case, it is the no-evidence case",
        )

        // One confirmed turn is all it takes. A separate gate on purpose: while nothing is known about
        // the user every frame is presumed echo, so feeding the loud frames above to this one would
        // teach it that 0.15 is what the room does — which is the correct reading of them, and not
        // what this half of the claim is about.
        val measured = gate()
        measured.settleEcho(0.04f)
        repeat(20) { measured.observeUserSpeech(0.15f) }
        assertTrue(measured.feed(0.15f, 4) != null, "once measured, their voice works")
    }
}
