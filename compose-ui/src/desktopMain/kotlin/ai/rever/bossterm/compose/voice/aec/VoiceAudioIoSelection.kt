package ai.rever.bossterm.compose.voice.aec

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.voice.JavaSoundVoiceAudioIo
import ai.rever.bossterm.compose.voice.VoiceAudioIo
import org.slf4j.LoggerFactory

/**
 * Picks the audio device implementation for a call.
 *
 * Kept apart from [ai.rever.bossterm.compose.voice.HostVoiceCallController] because "which audio
 * stack" is a platform question, not a call-state one, and the controller already carries enough.
 *
 * Selection is deliberately conservative: anything unexpected falls back to JavaSound, which is a
 * working audio path rather than a degraded one - it simply leaves echo to
 * [ai.rever.bossterm.compose.voice.VoiceDuplexGate] instead of the OS.
 */
internal object VoiceAudioIoSelection {

    private val log = LoggerFactory.getLogger(VoiceAudioIoSelection::class.java)

    /**
     * A fresh audio device for one call.
     *
     * Fresh per call by contract: JavaSoundVoiceAudioIo latches `disposed` and serves exactly one
     * call, and this path keeps that promise rather than quietly relaxing it.
     */
    fun create(
        hardwareEchoCancellation: Boolean =
            runCatching { SettingsManager.instance.settings.value.voiceHardwareEchoCancellation }
                .getOrDefault(true),
        supported: () -> Boolean = VoiceProcessingAudioIo::supported,
    ): VoiceAudioIo {
        if (!hardwareEchoCancellation) return JavaSoundVoiceAudioIo()
        val usable = runCatching(supported).getOrDefault(false)
        if (!usable) return JavaSoundVoiceAudioIo()
        log.info("Boss Calling: using the OS echo canceller for this call")
        return VoiceProcessingAudioIo()
    }
}
