package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How the agent decides you have started and stopped talking — the noise-sensitivity knob.
 *
 * Both surfaces shipped `semantic_vad` with no options, and semantic VAD deliberately exposes NO
 * activation threshold: it is a model deciding whether you have finished a thought, which is good
 * for natural turn-taking and useless when the problem is a fan, a keyboard, or a room. There was
 * nothing to turn down. `server_vad` is the mode with `threshold`, so the tunable levels use it.
 *
 * One definition for both call surfaces, for the same reason [voiceAgentRules] is: the mint body and
 * `session.update` are two places that must not drift, and this pair already has a history of it.
 */
internal object VoiceTurnDetection {

    /** Setting values, lowest sensitivity first. Stored as strings so settings.json stays readable. */
    const val NOISY = "noisy"
    const val NORMAL = "normal"
    const val QUIET = "quiet"
    const val AUTOMATIC = "automatic"

    val ALL = listOf(NOISY, NORMAL, QUIET, AUTOMATIC)

    /** What the settings dropdown shows, in the same order as [ALL]. */
    fun label(value: String): String = when (value) {
        NOISY -> "Noisy room - least sensitive"
        QUIET -> "Quiet room - most sensitive"
        AUTOMATIC -> "Automatic (model decides)"
        else -> "Normal"
    }

    /**
     * The `audio.input.turn_detection` object for a session.
     *
     * Thresholds are the API's 0.0–1.0 activation level: higher means louder audio is required
     * before it counts as speech. `silence_duration_ms` is how long a gap has to be before your turn
     * is considered over — longer suits a noisy room too, since a stray sound during your pause
     * won't restart the clock as often.
     */
    fun json(sensitivity: String): JsonObject = when (sensitivity) {
        AUTOMATIC -> buildJsonObject {
            // The original behaviour, kept because it is genuinely better at not cutting someone off
            // mid-sentence — it just cannot be tuned for a noisy room.
            put("type", "semantic_vad")
        }
        NOISY -> serverVad(threshold = 0.8, silenceMs = 900)
        QUIET -> serverVad(threshold = 0.35, silenceMs = 400)
        else -> serverVad(threshold = 0.6, silenceMs = 700)
    }

    /**
     * [threshold] slightly above OpenAI's 0.5 default at NORMAL, deliberately: the reported problem
     * was over-triggering, and a default nobody complains about beats one that matches the API's.
     */
    private fun serverVad(threshold: Double, silenceMs: Int): JsonObject = buildJsonObject {
        put("type", "server_vad")
        put("threshold", threshold)
        put("silence_duration_ms", silenceMs)
        // The API default. Stated rather than omitted so the whole shape is visible in one place.
        put("prefix_padding_ms", 300)
    }
}
