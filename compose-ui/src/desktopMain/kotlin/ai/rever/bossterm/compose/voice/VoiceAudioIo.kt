package ai.rever.bossterm.compose.voice

import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Microphone capture + speaker playback for an in-app call, abstracted so the call state machine
 * can be tested without touching audio hardware (CI has no mixer).
 *
 * The Realtime API's PCM format is 24 kHz, 16-bit signed little-endian mono, which is exactly what
 * [javax.sound.sampled] can hand us — so the whole audio path is JDK-only, no native dependency.
 */
internal interface VoiceAudioIo {

    /** Begin capturing; [onChunk] receives raw PCM16 frames, [onLevel] a 0..1 loudness estimate. */
    fun startCapture(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit)

    /** Queue decoded PCM16 for playback. */
    fun play(pcm: ByteArray)

    /**
     * Drop anything still queued — used for barge-in: when the user starts talking, the agent's
     * remaining audio has to stop immediately or they talk over each other.
     */
    fun flushPlayback()

    /** Stop both directions and release the lines. */
    fun stop()
}

/** The real thing: `javax.sound.sampled` lines on their own threads. */
internal class JavaSoundVoiceAudioIo : VoiceAudioIo {

    private val log = LoggerFactory.getLogger(JavaSoundVoiceAudioIo::class.java)

    @Volatile private var capture: TargetDataLine? = null
    @Volatile private var playback: SourceDataLine? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var running = false

    override fun startCapture(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit) {
        val line = runCatching {
            val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
            check(AudioSystem.isLineSupported(info)) { "no 24kHz PCM16 capture line" }
            (AudioSystem.getLine(info) as TargetDataLine).also {
                it.open(FORMAT, CHUNK_BYTES * 8)
                it.start()
            }
        }.getOrElse {
            log.warn("Microphone unavailable: {}", it.message)
            throw VoiceAudioException("No microphone available (${it.message})")
        }
        capture = line
        running = true
        // A dedicated thread, NOT a coroutine dispatcher: this loop blocks on line.read for the
        // whole call, and parking a shared pool worker for minutes is what starved BossTerm's IO
        // dispatcher once already.
        captureThread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            while (running) {
                val n = runCatching { line.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) break
                val chunk = buf.copyOf(n)
                onLevel(rms(chunk))
                runCatching { onChunk(chunk) }
            }
        }, "boss-voice-capture").apply { isDaemon = true; start() }
    }

    override fun play(pcm: ByteArray) {
        val line = playback ?: runCatching {
            val info = DataLine.Info(SourceDataLine::class.java, FORMAT)
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(FORMAT, CHUNK_BYTES * 16)
                it.start()
            }
        }.getOrElse {
            log.warn("Speaker unavailable: {}", it.message)
            return
        }.also { playback = it }
        // write() blocks when the buffer is full, which is the back-pressure we want — but it runs
        // on the socket's callback thread, so keep chunks small.
        runCatching { line.write(pcm, 0, pcm.size) }
    }

    override fun flushPlayback() {
        runCatching { playback?.flush() }
    }

    override fun stop() {
        running = false
        captureThread?.interrupt()
        captureThread = null
        runCatching { capture?.stop(); capture?.close() }
        capture = null
        runCatching { playback?.flush(); playback?.stop(); playback?.close() }
        playback = null
    }

    private fun rms(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var i = 0
        val samples = pcm.size / 2
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += (sample.toDouble() / Short.MAX_VALUE) * (sample.toDouble() / Short.MAX_VALUE)
            i += 2
        }
        // Speech sits well below full scale, so scale up before clamping or the meter barely moves.
        return min(1f, (sqrt(sum / samples) * 3.0).toFloat())
    }

    internal companion object {
        /** The Realtime API's PCM contract: 24 kHz, 16-bit signed little-endian, mono. */
        val FORMAT = AudioFormat(24_000f, 16, 1, true, false)

        /** ~40 ms per chunk: small enough that playback back-pressure stays responsive. */
        const val CHUNK_BYTES = 1920
    }
}

/** Audio hardware could not be opened — surfaced to the UI rather than failing the call silently. */
internal class VoiceAudioException(message: String) : Exception(message)
