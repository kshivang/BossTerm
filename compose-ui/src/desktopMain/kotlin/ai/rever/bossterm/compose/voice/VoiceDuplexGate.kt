package ai.rever.bossterm.compose.voice

import kotlin.math.max

/** What to do with one microphone frame captured while the agent is talking. */
internal enum class Duplex {
    /** Echo of the agent's own voice. Dropping it is what stops the loop. */
    WITHHOLD,

    /** This frame confirms the user cut in: stop playback, and send. */
    INTERRUPTED,

    /** The user is already established as talking over this reply. */
    PASS,
}

/**
 * Decides, frame by frame, whether the microphone is hearing the user or the agent's own echo.
 *
 * The in-app surface has no acoustic echo cancellation — the browser gets AEC from WebRTC,
 * `javax.sound.sampled` hands over a raw capture line — so on speakers the microphone hears the reply,
 * the server's VAD calls it speech (it *is* speech), and the agent interrupts itself. Withholding the
 * microphone for the whole reply stops that and also removes barge-in entirely, since barge-in runs
 * off the server's `speech_started` and that cannot fire for frames never sent.
 *
 * Two attempts to split the difference on the microphone signal ALONE both failed on real calls, and
 * the measurements are why:
 *
 *  - a fixed absolute threshold (0.28 RMS) sat near shouting, so nothing cleared it;
 *  - a threshold derived from a tracked echo level fired constantly, because in the room it was
 *    measured in the reply came back at 0.06–0.10 RMS and ordinary speech measures 0.05–0.15. There is
 *    no level that divides those two. The microphone cannot tell you whose voice it is.
 *
 * So the reference is not the microphone, it is the SPEAKER: [VoiceAudioIo.audiblePlaybackLevel] says
 * how loud the audio currently leaving the device is. Echo is that signal, attenuated by the room —
 * a factor this gate learns ([attenuation]) rather than assumes. Anything arriving materially above
 * what the room could be returning is a second voice.
 *
 * The property that makes this feel responsive, which no static threshold has: the bar TRACKS the
 * agent's own envelope. In the gaps between its words the reference falls, the bar falls with it, and
 * an interruption lands immediately — which is exactly when people cut in.
 */
internal class VoiceDuplexGate(
    /** One loud frame is a door closing; several in a row is a voice. ~120ms at 40ms frames. */
    private val framesToTrigger: Int = 3,
    /**
     * How far above the room's expected echo a frame must sit to count as a second voice.
     *
     * Guards the attenuation estimate rather than the signal: reverb and the estimate's own lag mean
     * the echo overshoots its prediction routinely, and every overshoot would otherwise be a voice.
     */
    private val headroom: Float = 1.8f,
    /**
     * Ceiling on the learned attenuation.
     *
     * Without it a microphone positioned so that the echo arrives near unity would let the estimate
     * climb until the bar exceeded anything a person could produce, silently disabling barge-in. Above
     * this the honest answer is that the speakers are too loud for the microphone — reported through
     * [indistinguishable] instead of hidden in a threshold.
     */
    private val maxAttenuation: Float = 0.85f,
    /**
     * Absolute floor while the agent is audible, so a silent room cannot trigger.
     *
     * Well below the 0.05 figure for quiet speech: this excludes a room, not a person.
     */
    private val absoluteFloor: Float = 0.03f,
    /**
     * The fraction of the user's own measured level the bar may fall to once the agent goes quiet.
     *
     * Speech is not level — it has consonants, pauses, and trails off — so a bar at 100% of the
     * average would only catch the loudest syllables.
     */
    private val userReach: Float = 0.6f,
    /**
     * Below this a frame carries no information about anyone's voice.
     *
     * Load-bearing for the user estimate. Server VAD only declares a turn over after ~700ms of
     * SILENCE, and all of it falls inside `speech_started`..`speech_stopped`; averaging it in drove the
     * estimate to 0.004 — silence — on a real call, so the user term contributed nothing to the bar and
     * self-interruption followed.
     */
    private val silence: Float = 0.02f,
    /** Rise rate of the user estimate: reach their level within a word or two. */
    private val userAttack: Float = 0.35f,
    /** Fall rate. Far slower than [userAttack], so pauses do not erase what their voice measures. */
    private val userRelease: Float = 0.02f,
    /** Rise rate of the attenuation estimate — must be quick, it gates the very next frame. */
    private val attenuationAttack: Float = 0.4f,
    /** Fall rate. Slow, so a quiet moment in the reply does not drop the guard. */
    private val attenuationRelease: Float = 0.02f,
    /**
     * Frames of real playback needed before any interruption is possible — ~400ms, once per call.
     *
     * Only the first reply pays it: the estimate is a property of the room, not of the reply.
     */
    private val minLearnFrames: Int = 10,
) {

    private var userLevel = 0f
    private var attenuation = 0f
    private var consecutive = 0
    private var latched = false

    /** Frames of real playback the attenuation estimate is built from — see [classify]. */
    private var learnedFrames = 0

    /** The bar the last decision was taken against, for logging: this is what tuning reads. */
    var lastBar: Float = 0f
        private set

    /** True when the echo returns loud enough that no bar can admit the user and reject it. */
    var indistinguishable: Boolean = false
        private set

    fun userEstimate(): Float = userLevel

    fun attenuationEstimate(): Float = attenuation

    /**
     * A frame captured while the SERVER agrees the user is speaking — the calibration signal.
     *
     * Silent frames are skipped; see [silence] for what including them cost.
     */
    fun observeUserSpeech(level: Float) {
        if (level < silence) return
        userLevel = when {
            userLevel <= 0f -> level
            level > userLevel -> userLevel + userAttack * (level - userLevel)
            else -> userLevel + userRelease * (level - userLevel)
        }
    }

    /** The agent has started a new reply. Clears the latch; keeps what the room has taught us. */
    fun beginAgentTurn() {
        consecutive = 0
        latched = false
    }

    /**
     * One microphone frame captured while the agent is talking.
     *
     * [reference] is how loud the audio leaving the speaker is right now — see
     * [VoiceAudioIo.audiblePlaybackLevel].
     */
    fun classify(level: Float, reference: Float): Duplex {
        if (latched) return Duplex.PASS

        if (learnedFrames < minLearnFrames) {
            // Measure the room before judging anything against it. Both previous versions of this gate
            // shipped with an estimate that started at zero and was only updated by frames it had
            // already decided were echo — so the first frames of a reply were judged against nothing,
            // the bar sat at its floor, and the reply's own echo cleared it every time.
            //
            // Only frames with real playback behind them count, so this cannot be satisfied by silence.
            // The estimate then persists for the whole call: a room's acoustics do not change between
            // replies, and re-measuring each one would leave a deaf window at the start of every reply.
            learnAttenuation(level, reference)
            return Duplex.WITHHOLD
        }

        val bar = barFor(reference)
        lastBar = bar
        if (level >= bar) {
            consecutive++
            if (consecutive >= framesToTrigger) {
                latched = true
                return Duplex.INTERRUPTED
            }
            // Deliberately not learned from: a frame we suspect is the user must not raise the
            // attenuation estimate, or an interruption talks itself out of being heard as the bar
            // climbs to meet it.
            return Duplex.WITHHOLD
        }
        consecutive = 0
        learnAttenuation(level, reference)
        return Duplex.WITHHOLD
    }

    /**
     * What fraction of the speaker's output the microphone gets back.
     *
     * Only from frames believed to be echo, and only while there is a reference loud enough for the
     * ratio to mean anything — dividing by near-silence yields noise, and one such frame would poison
     * the estimate for the rest of the reply.
     */
    private fun learnAttenuation(level: Float, reference: Float) {
        if (reference < silence) return
        learnedFrames++
        val observed = (level / reference).coerceAtMost(1f)
        attenuation = when {
            attenuation <= 0f -> observed
            observed > attenuation -> attenuation + attenuationAttack * (observed - attenuation)
            else -> attenuation + attenuationRelease * (observed - attenuation)
        }
    }

    /**
     * The bar for this frame.
     *
     * Anchored to what the room is expected to be returning at this instant. The user term is a floor
     * for the quiet moments: with the reference near zero — between the agent's words, or once the
     * reply ends — an expected echo of nearly nothing would make any sound at all an interruption.
     */
    private fun barFor(reference: Float): Float {
        if (userLevel <= 0f) {
            // Nothing is known about what this user sounds like, so any bar is a guess — and guessing
            // is what cut replies off twice. Distinct from the loud-speaker case below and reported as
            // such, or the log would blame the speakers for a call where the user has not yet spoken.
            // In practice they speak first, so this is only an opening greeting.
            indistinguishable = false
            return Float.MAX_VALUE
        }
        val expectedEcho = reference * attenuation.coerceAtMost(maxAttenuation) * headroom
        indistinguishable = attenuation >= maxAttenuation
        return max(max(expectedEcho, userLevel * userReach), absoluteFloor)
    }
}
