package ai.rever.bossterm.compose.voice.aec

import ai.rever.bossterm.compose.voice.JavaSoundVoiceAudioIo
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which audio stack a call gets, and - more importantly - that every way of answering "no" lands
 * on the working one.
 *
 * JavaSound is the fallback for everything, and it is not a degraded mode: it captures and plays
 * exactly as before, it simply leaves echo to [ai.rever.bossterm.compose.voice.VoiceDuplexGate]
 * rather than the OS. Getting the fallback wrong is therefore the expensive direction - a call
 * that cannot open a microphone at all - which is why each branch is pinned separately.
 */
class VoiceAudioIoSelectionTest {

    @Test
    fun `the OS canceller is used when it is supported and switched on`() {
        val io = VoiceAudioIoSelection.create(hardwareEchoCancellation = true, supported = { true })
        assertIs<VoiceProcessingAudioIo>(io)
        io.stop()
    }

    @Test
    fun `turning the setting off keeps JavaSound even where the OS could do it`() {
        val io = VoiceAudioIoSelection.create(hardwareEchoCancellation = false, supported = { true })
        assertIs<JavaSoundVoiceAudioIo>(io)
        io.stop()
    }

    @Test
    fun `an unsupported platform falls back rather than failing the call`() {
        val io = VoiceAudioIoSelection.create(hardwareEchoCancellation = true, supported = { false })
        assertIs<JavaSoundVoiceAudioIo>(io)
        io.stop()
    }

    /**
     * A support check that THROWS must fall back, not propagate.
     *
     * It reaches a native library load, so on an unexpected platform or a broken install it can
     * raise rather than return false. Letting that escape would turn "no hardware echo
     * cancellation" into "Boss Calling does not start", which is a much worse failure than the one
     * being avoided.
     */
    @Test
    fun `a support check that throws falls back instead of taking the call down`() {
        val io = VoiceAudioIoSelection.create(
            hardwareEchoCancellation = true,
            supported = { error("simulated native load failure") },
        )
        assertIs<JavaSoundVoiceAudioIo>(io)
        io.stop()
    }

    /** Each call gets its own device; the JavaSound path serves exactly one and latches after. */
    @Test
    fun `every call receives a fresh device`() {
        val first = VoiceAudioIoSelection.create(hardwareEchoCancellation = false, supported = { true })
        val second = VoiceAudioIoSelection.create(hardwareEchoCancellation = false, supported = { true })
        assertTrue(first !== second, "a reused device would serve a second call already disposed")
        first.stop()
        second.stop()
    }
}
