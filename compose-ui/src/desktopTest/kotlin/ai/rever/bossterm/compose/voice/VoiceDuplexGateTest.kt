package ai.rever.bossterm.compose.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate between "the agent talks to itself" and "you cannot interrupt it".
 *
 * Levels are RMS as [pcm16Rms] reports it, and the numbers come from a real call rather than being
 * picked to make the tests work: `VoiceAudioIo` puts conversational speech at 0.05–0.15, and the room
 * this was debugged in returned the agent's own reply at 0.06–0.10 while playing it at about 0.30.
 * That overlap is the whole difficulty — two earlier versions of this gate tried to find a level that
 * divided echo from voice, and there isn't one. Hence a reference signal.
 */
class VoiceDuplexGateTest {

    private fun gate() = VoiceDuplexGate()

    /** A user whose voice has been measured, as it is by the time the agent first replies. */
    private fun calibrated(level: Float = 0.10f) = gate().apply { repeat(30) { observeUserSpeech(level) } }

    /** Feed n frames; returns the frame number that interrupted, or null. */
    private fun VoiceDuplexGate.feed(level: Float, reference: Float, n: Int): Int? {
        for (i in 1..n) if (classify(level, reference) == Duplex.INTERRUPTED) return i
        return null
    }

    /**
     * The reported bug, with the numbers it was reported at.
     *
     * The agent plays at 0.30 and the room returns 0.08 — indistinguishable from speech by level, and
     * both previous versions cut the reply off here. The reference is what separates them: 0.08 is
     * exactly what this room does with 0.30, so it is the agent.
     */
    @Test
    fun `the agent's own echo does not interrupt it`() {
        val g = calibrated()
        assertEquals(null, g.feed(level = 0.08f, reference = 0.30f, n = 400), "bar was ${g.lastBar}")
    }

    @Test
    fun `a second voice over the same echo does interrupt`() {
        val g = calibrated()
        g.feed(level = 0.08f, reference = 0.30f, n = 60) // learn what the room returns
        // The user talking while the agent still is: their voice ON TOP of the echo.
        assertTrue(g.feed(level = 0.22f, reference = 0.30f, n = 6) != null, "bar was ${g.lastBar}")
    }

    /**
     * The property no static threshold has.
     *
     * People cut in during the gaps between words. The reference falls in those gaps, so the bar falls
     * with it and an ordinary interruption lands immediately instead of having to out-shout a reply
     * that is not currently making any sound.
     */
    @Test
    fun `the bar follows the agent's envelope into the gaps between words`() {
        val g = calibrated()
        g.feed(level = 0.08f, reference = 0.30f, n = 60)
        val barWhileTalking = g.lastBar

        // A pause in the reply: nothing is leaving the speaker, so nothing can be coming back.
        assertTrue(g.feed(level = 0.10f, reference = 0.0f, n = 6) != null, "a pause is an opening")
        assertTrue(g.lastBar < barWhileTalking, "the bar must fall with the reference")
    }

    @Test
    fun `one loud frame is not a voice`() {
        val g = calibrated()
        g.feed(level = 0.08f, reference = 0.30f, n = 60)
        assertEquals(null, g.feed(0.30f, 0.30f, 2), "a key press or a door is one or two frames")
        assertTrue(g.feed(0.30f, 0.30f, 3) != null, "sustained is a voice")
    }

    @Test
    fun `an interruption latches for the rest of the reply`() {
        val g = calibrated()
        g.feed(level = 0.08f, reference = 0.30f, n = 60)
        assertTrue(g.feed(0.22f, 0.30f, 6) != null)
        // Speech has pauses and trailing consonants far below its average. Re-deciding per frame would
        // chop the utterance into fragments and send the model half a sentence.
        assertEquals(Duplex.PASS, g.classify(0.001f, 0.30f), "a pause mid-word must not close the gate")
        assertEquals(Duplex.PASS, g.classify(0.22f, 0.30f))
    }

    @Test
    fun `a new reply clears the latch but keeps what the room taught us`() {
        val g = calibrated()
        g.feed(level = 0.08f, reference = 0.30f, n = 60)
        assertTrue(g.feed(0.22f, 0.30f, 6) != null)
        val learned = g.attenuationEstimate()

        g.beginAgentTurn()
        assertEquals(Duplex.WITHHOLD, g.classify(0.08f, 0.30f), "the latch must not survive the reply")
        // Not exact equality: the frame above is itself echo and refines the estimate slightly. The
        // claim is that the room is still KNOWN, so there is no deaf window at the start of this reply.
        assertTrue(g.attenuationEstimate() > learned * 0.9f, "re-learning every reply is a dead window")
        assertEquals(null, g.feed(0.08f, 0.30f, 40), "and the echo is still recognised immediately")
    }

    /**
     * The user estimate must measure their VOICE, not their pauses.
     *
     * Server VAD only calls a turn over after ~700ms of silence, and all of it arrives inside
     * `speech_started`..`speech_stopped`. Averaging it in drove this to 0.004 on a real call — silence —
     * which removed the user term from the bar entirely and let self-interruption through.
     */
    @Test
    fun `the trailing silence of a turn does not erase the user's level`() {
        val g = gate()
        repeat(25) { g.observeUserSpeech(0.10f) } // talking
        repeat(20) { g.observeUserSpeech(0.001f) } // the VAD waiting out the gap afterwards
        assertTrue(g.userEstimate() > 0.05f, "must still describe a voice, was ${g.userEstimate()}")
    }

    @Test
    fun `one shouted word does not redefine their voice`() {
        val g = calibrated()
        g.observeUserSpeech(0.9f)
        assertTrue(g.userEstimate() < 0.45f, "one outlier is not their level: ${g.userEstimate()}")
    }

    @Test
    fun `a silent room does not trigger on its own noise floor`() {
        val g = calibrated()
        // Headphones, or the speakers off: no reference, and nothing coming back either.
        assertEquals(null, g.feed(level = 0.02f, reference = 0.0f, n = 100), "fan noise is not a voice")
    }

    /**
     * Speakers loud enough that the echo returns at nearly the level it was played.
     *
     * No bar admits the user and rejects that. Rejecting wins — a self-sustaining loop wrecks the whole
     * call where a missed interruption costs one sentence — and it is reported rather than buried, so
     * the log can say the speakers are too loud instead of "barge-in is flaky".
     */
    @Test
    fun `an echo returning at full volume is reported rather than hidden`() {
        val g = calibrated()
        assertEquals(null, g.feed(level = 0.30f, reference = 0.30f, n = 80))
        assertTrue(g.indistinguishable, "the cause must reach the log")
    }

    @Test
    fun `before the user has ever been measured nothing interrupts`() {
        val g = gate()
        // Only reachable when the agent speaks first. Nothing is known about this user's voice, so any
        // bar would be a guess, and guessing is what cut replies off.
        assertEquals(null, g.feed(level = 0.5f, reference = 0.30f, n = 40))
        assertFalse(g.indistinguishable, "this is the no-evidence case, not the loud-speaker one")
    }

    /** A near-silent reference cannot be divided into: one such frame would poison the estimate. */
    @Test
    fun `attenuation is not learned from silence`() {
        val g = calibrated()
        g.feed(level = 0.08f, reference = 0.30f, n = 60)
        val learned = g.attenuationEstimate()
        g.feed(level = 0.001f, reference = 0.0f, n = 10)
        assertEquals(learned, g.attenuationEstimate(), "dividing by silence yields noise")
    }
}
