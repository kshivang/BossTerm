package ai.rever.bossterm.compose.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
 * Both blanket answers were tried on real calls and both failed:
 *
 *  - Send everything, and the agent talks to itself. The in-app surface has no acoustic echo
 *    cancellation — the browser gets AEC from WebRTC, `javax.sound.sampled` hands over a raw capture
 *    line — so on speakers the microphone hears the reply, the server's VAD calls it speech (it *is*
 *    speech), and the agent interrupts itself mid-sentence.
 *  - Send nothing while the agent talks, and interruption stops working entirely. Barge-in runs off
 *    the server's own `speech_started`, which cannot fire for frames that were never sent.
 *
 * So the gate has to be a judgement about LEVEL, and the previous attempt at one failed for a
 * reason worth recording: it required an absolute RMS of 0.28 before it would call anything a voice.
 * [SPEECH_METER_GAIN]'s own comment puts conversational speech at "RMS around 0.05-0.15" — the bar
 * was set near shouting, so in practice nothing ever cleared it and the gate was indistinguishable
 * from sending nothing at all.
 *
 * This one measures both sides instead of assuming either:
 *
 *  - the ECHO level, from the frames arriving while the agent speaks — that is what the room does to
 *    the agent's voice on the way back;
 *  - the USER level, from frames the SERVER has confirmed as speech during their turn — that is what
 *    the room does to the user's voice, at their distance and their volume.
 *
 * The bar sits between the two. Neither number is guessed, so it adapts to a laptop speaker at 30%
 * and a desk speaker at full just as well, which no fixed threshold does.
 */
internal class VoiceDuplexGate(
    /** One loud frame is a door closing; several in a row is a voice. ~120ms at 40ms frames. */
    private val framesToTrigger: Int = 3,
    /** How far above the measured echo the bar must sit before a frame counts as the user. */
    private val minRatio: Float = 1.6f,
    /** Used until the user's own level is known — no second measurement to place the bar between. */
    private val fallbackRatio: Float = 2.0f,
    /**
     * Absolute floor, so a silent room cannot trigger.
     *
     * With the speakers off the echo estimate falls to near zero, and any ratio of near-zero is
     * clearable by fan noise. Well below [SPEECH_METER_GAIN]'s 0.05 figure for quiet speech: this is
     * meant to exclude a room, not a person.
     */
    private val absoluteFloor: Float = 0.03f,
    /**
     * The fraction of the user's measured speech level the bar may reach.
     *
     * Speech is not level — it has consonants and pauses and trails off — so a bar at 100% of the
     * average would only catch the loudest syllables. At 70% a normally-spoken interruption clears
     * it within [framesToTrigger].
     */
    private val userReach: Float = 0.7f,
    /** How fast the echo estimate falls when frames come in quieter. */
    private val echoDecay: Float = 0.97f,
    /** Weight of each new confirmed-speech frame in the user estimate. */
    private val userAlpha: Float = 0.2f,
    /**
     * Frames at the very start of the CALL spent measuring before anything can trigger.
     *
     * Only the first reply pays this. The room's echo does not change between replies, so the
     * estimate carries across them — which also removes the dead window at the start of every reply
     * that an unconditional warm-up would create.
     */
    private val warmupFrames: Int = 5,
) {

    private var echoLevel = 0f
    private var userLevel = 0f
    private var consecutive = 0
    private var latched = false
    private var warmup = warmupFrames

    /** The bar the last decision was taken against, for logging: this is what we tune on. */
    var lastBar: Float = 0f
        private set

    /** True when the speakers are loud enough that echo and voice cannot be told apart. */
    var indistinguishable: Boolean = false
        private set

    fun echoEstimate(): Float = echoLevel

    fun userEstimate(): Float = userLevel

    /**
     * A frame captured while the SERVER agrees the user is speaking — the calibration signal.
     *
     * Only called between `speech_started` and `speech_stopped` with the agent silent, so it is the
     * user's voice by the server's judgement rather than by ours. That is the whole point: the gate
     * never has to decide what a voice sounds like, it only has to compare.
     */
    fun observeUserSpeech(level: Float) {
        userLevel = if (userLevel <= 0f) level else userLevel + userAlpha * (level - userLevel)
    }

    /** The agent has started a new reply. Clears the latch; keeps what the room has taught us. */
    fun beginAgentTurn() {
        consecutive = 0
        latched = false
    }

    /** One microphone frame captured while the agent is talking. */
    fun classify(level: Float): Duplex {
        if (latched) return Duplex.PASS

        if (warmup > 0) {
            // Nothing has been measured yet, so every bar would be arbitrary. Without this the first
            // frames of the first reply arrive against an echo estimate of zero and trigger on the
            // echo itself — the failure mode that made the previous detector fire constantly.
            warmup--
            echoLevel = max(level, echoLevel)
            return Duplex.WITHHOLD
        }

        val bar = barFor()
        lastBar = bar
        if (level >= bar) {
            consecutive++
            if (consecutive >= framesToTrigger) {
                latched = true
                return Duplex.INTERRUPTED
            }
            // Deliberately not folded into the echo estimate: a frame we suspect is the user must not
            // raise the bar against the frames after it, or an interruption talks itself out of being
            // heard as the bar climbs to meet it.
            return Duplex.WITHHOLD
        }
        consecutive = 0
        echoLevel = max(level, echoLevel * echoDecay)
        return Duplex.WITHHOLD
    }

    /**
     * Where the bar sits between the two measurements.
     *
     * The geometric mean rather than the arithmetic one because these are loudnesses spanning an
     * order of magnitude: halfway between 0.04 and 0.20 should be 0.09, not 0.12.
     */
    private fun barFor(): Float {
        val floor = max(echoLevel * minRatio, absoluteFloor)
        if (userLevel <= 0f) {
            indistinguishable = false
            return max(echoLevel * fallbackRatio, absoluteFloor)
        }
        val ceiling = userLevel * userReach
        // When the echo comes back louder than the user's own voice there is no bar that both
        // rejects it and admits them, and something has to give. Rejecting wins: a self-sustaining
        // loop derails the call completely, where a missed interruption costs one sentence. Recorded
        // so the cause is visible in the log rather than presenting as "barge-in is flaky".
        indistinguishable = ceiling < floor
        return if (indistinguishable) floor else max(floor, min(sqrt(echoLevel * userLevel), ceiling))
    }
}
