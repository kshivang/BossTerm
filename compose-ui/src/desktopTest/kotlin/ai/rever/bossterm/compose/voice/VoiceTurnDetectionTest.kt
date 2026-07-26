package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The noise-sensitivity knob.
 *
 * Both surfaces shipped `semantic_vad` with no options, and semantic VAD exposes no activation
 * threshold by design — so when a room turned out to be too noisy there was nothing to turn down.
 * These assertions are about the SHAPE the API accepts, which is the part no fake can verify.
 */
class VoiceTurnDetectionTest {

    private fun typeOf(value: String) =
        VoiceTurnDetection.json(value)["type"]?.jsonPrimitive?.content

    private fun thresholdOf(value: String) =
        VoiceTurnDetection.json(value)["threshold"]?.jsonPrimitive?.content?.toDouble()

    @Test
    fun `the tunable levels use the mode that has a threshold`() {
        for (level in listOf(VoiceTurnDetection.NOISY, VoiceTurnDetection.NORMAL, VoiceTurnDetection.QUIET)) {
            assertEquals("server_vad", typeOf(level), "$level must be tunable")
            val threshold = thresholdOf(level)
            assertTrue(threshold != null && threshold in 0.0..1.0, "$level: $threshold out of range")
        }
    }

    @Test
    fun `automatic keeps semantic VAD and sends no threshold`() {
        assertEquals("semantic_vad", typeOf(VoiceTurnDetection.AUTOMATIC))
        // semantic_vad rejects server_vad-only fields; sending one would fail the whole session.
        val json = VoiceTurnDetection.json(VoiceTurnDetection.AUTOMATIC)
        assertNull(json["threshold"])
        assertNull(json["silence_duration_ms"])
        assertNull(json["prefix_padding_ms"])
    }

    /** The whole point: "noisy" must actually be less willing to hear speech than "quiet". */
    @Test
    fun `sensitivity is ordered`() {
        val noisy = thresholdOf(VoiceTurnDetection.NOISY)!!
        val normal = thresholdOf(VoiceTurnDetection.NORMAL)!!
        val quiet = thresholdOf(VoiceTurnDetection.QUIET)!!
        assertTrue(noisy > normal, "noisy ($noisy) must need louder audio than normal ($normal)")
        assertTrue(normal > quiet, "normal ($normal) must need louder audio than quiet ($quiet)")
    }

    /** A longer silence window suits a noisy room too — a stray sound mid-pause restarts it less. */
    @Test
    fun `a noisier room waits longer before deciding you stopped`() {
        fun silence(v: String) =
            VoiceTurnDetection.json(v)["silence_duration_ms"]?.jsonPrimitive?.content?.toInt()!!
        assertTrue(silence(VoiceTurnDetection.NOISY) > silence(VoiceTurnDetection.QUIET))
    }

    @Test
    fun `an unknown or empty setting falls back to normal rather than failing a call`() {
        // settings.json is user-editable, and a typo must not produce a session the API rejects.
        assertEquals(VoiceTurnDetection.json(VoiceTurnDetection.NORMAL), VoiceTurnDetection.json("nonsense"))
        assertEquals(VoiceTurnDetection.json(VoiceTurnDetection.NORMAL), VoiceTurnDetection.json(""))
    }

    @Test
    fun `every level has a distinct label for the dropdown`() {
        val labels = VoiceTurnDetection.ALL.map { VoiceTurnDetection.label(it) }
        assertEquals(labels.size, labels.toSet().size, "duplicate labels would be unselectable: $labels")
    }
}
