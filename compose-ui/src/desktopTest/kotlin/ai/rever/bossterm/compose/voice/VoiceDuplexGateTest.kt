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

    @Test
    fun `without a user measurement it still rejects steady echo`() {
        val g = gate()
        g.settleEcho(0.04f) // no observeUserSpeech at all — the first reply of a call
        assertEquals(null, g.feed(0.04f, 50))
        assertFalse(g.indistinguishable)
        assertTrue(g.feed(0.15f, 4) != null, "and a clear voice still gets through")
    }
}
