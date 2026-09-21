package ai.rever.bossterm.compose.voice.aec

import com.sun.jna.Memory
import com.sun.jna.ptr.PointerByReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the OS echo canceller can be driven from this JVM before anything is built on top of it.
 *
 * Opt-in: needs a real audio device, and CI has no mixer. Run it deliberately with
 * `BOSSTERM_AEC_PROBE=1 ./gradlew :compose-ui:desktopTest --tests '*VoiceProcessingProbeTest*'`.
 *
 * What it establishes, in order of how likely each was to sink the idea:
 *  1. `AudioToolbox` loads through JNA at all.
 *  2. The `vpio` component exists and instantiates - this is the echo canceller itself.
 *  3. It accepts the same PCM16 mono 24 kHz format the rest of the voice path already speaks,
 *     so no resampling layer is needed between it and [ai.rever.bossterm.compose.voice.VoiceAudioIo].
 *
 * It deliberately stops short of starting the unit. Starting means a realtime callback on a JNA
 * proxy, which is the next question and a separate one; there is no point paying for that
 * investigation if the unit cannot be created.
 */
class VoiceProcessingProbeTest {

    private fun enabled(): Boolean = System.getenv("BOSSTERM_AEC_PROBE") == "1"

    @Test
    fun `the OS voice processing unit instantiates and accepts our audio format`() {
        if (!enabled()) return

        assertTrue(CoreAudio.available, "AudioToolbox did not load; AEC would fall back to JavaSound")
        val toolbox = assertNotNull(CoreAudio.library, "AudioToolbox unavailable")

        val description = AudioComponentDescription().apply {
            componentType = CoreAudio.KAUDIO_UNIT_TYPE_OUTPUT
            componentSubType = CoreAudio.KAUDIO_UNIT_SUBTYPE_VOICE_PROCESSING_IO
            componentManufacturer = CoreAudio.KAUDIO_UNIT_MANUFACTURER_APPLE
            write()
        }

        val component = toolbox.AudioComponentFindNext(null, description)
        assertNotNull(component, "no Voice Processing I/O component on this system")

        val instance = PointerByReference()
        assertEquals(0, toolbox.AudioComponentInstanceNew(component, instance), "AudioComponentInstanceNew failed")
        val unit = assertNotNull(instance.value, "instantiated a null audio unit")

        try {
            // Enable the microphone bus. Without this the unit is output-only and there is nothing
            // to cancel echo out of.
            val enable = Memory(4).apply { setInt(0, 1) }
            assertEquals(
                0,
                toolbox.AudioUnitSetProperty(
                    unit, CoreAudio.PROP_ENABLE_IO, CoreAudio.SCOPE_INPUT, CoreAudio.BUS_INPUT, enable, 4,
                ),
                "could not enable the input bus",
            )

            // The format the rest of the voice path already uses, so a success here means no
            // resampling between the canceller and VoiceAudioIo.
            val format = AudioStreamBasicDescription().apply {
                mSampleRate = 24_000.0
                mFormatID = CoreAudio.KAUDIO_FORMAT_LINEAR_PCM
                mFormatFlags = CoreAudio.PCM_SIGNED_PACKED
                mFramesPerPacket = 1
                mChannelsPerFrame = 1
                mBitsPerChannel = 16
                mBytesPerFrame = 2
                mBytesPerPacket = 2
                write()
            }
            assertEquals(
                0,
                toolbox.AudioUnitSetProperty(
                    unit, CoreAudio.PROP_STREAM_FORMAT, CoreAudio.SCOPE_OUTPUT, CoreAudio.BUS_INPUT,
                    format.pointer, format.size(),
                ),
                "the unit rejected PCM16 mono 24 kHz on the microphone bus",
            )
        } finally {
            toolbox.AudioComponentInstanceDispose(unit)
        }
    }
}
