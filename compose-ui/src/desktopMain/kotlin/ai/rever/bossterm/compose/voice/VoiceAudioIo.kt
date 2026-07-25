package ai.rever.bossterm.compose.voice

import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
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
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var running = false
    /** Set once the speaker line has failed to open, so we stop probing hardware per chunk. */
    @Volatile private var playbackUnavailable = false

    /**
     * Set by [stop] and never cleared: this object serves exactly one call.
     *
     * end() stops audio before closing the socket, so a `response.output_audio.delta` already in the
     * listener's hands can call [play] afterwards. Without this, ensurePlayback() saw a clean slate
     * (playback nulled, playbackUnavailable reset), opened a NEW line, set `running = true` again —
     * resurrecting the capture loop's exit condition — and started a second playback thread that
     * nothing stopped. Every end-during-agent-speech leaked a line and a thread.
     */
    @Volatile private var disposed = false

    /**
     * Decoded audio waiting for the speaker. Playback is drained on its own thread because
     * `SourceDataLine.write` blocks when the line is full, and [play] is called from the WebSocket
     * callback thread — a backed-up speaker would otherwise stall event dispatch, including the
     * `input_audio_buffer.speech_started` that triggers barge-in. Bounded so a stalled line drops
     * audio instead of growing without limit.
     */
    private val playQueue = ArrayBlockingQueue<ByteArray>(PLAY_QUEUE_CHUNKS)

    override fun startCapture(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit) {
        check(!disposed) { "this VoiceAudioIo has already been stopped" }
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
        if (disposed) return
        ensurePlayback() ?: return
        // Never block the caller (the socket thread): drop the oldest chunk if the speaker is behind.
        if (!playQueue.offer(pcm)) {
            playQueue.poll()
            playQueue.offer(pcm)
        }
    }

    override fun flushPlayback() {
        playQueue.clear()
        runCatching { playback?.flush() }
    }

    /** Open the speaker line + its drain thread on first use. Null when there is no output device. */
    private fun ensurePlayback(): SourceDataLine? {
        playback?.let { return it }
        if (playbackUnavailable || disposed) return null
        val line = runCatching {
            val info = DataLine.Info(SourceDataLine::class.java, FORMAT)
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(FORMAT, CHUNK_BYTES * 16)
                it.start()
            }
        }.getOrElse {
            log.warn("Speaker unavailable: {}", it.message)
            playbackUnavailable = true
            return null
        }
        playback = line
        // Set here as well as in startCapture: if play() ever lands first, the drain loop would see
        // running == false with an empty queue, exit immediately, and — since `playback` is now
        // non-null — never be restarted, leaving the agent inaudible for the whole call.
        running = true
        playbackThread = Thread({
            while (running || playQueue.isNotEmpty()) {
                val chunk = runCatching { playQueue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                runCatching { line.write(chunk, 0, chunk.size) }
            }
        }, "boss-voice-playback").apply { isDaemon = true; start() }
        return line
    }

    override fun stop() {
        disposed = true
        running = false
        captureThread?.interrupt()
        captureThread = null
        playQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
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

        /** ~4 s of queued speech; beyond that the speaker is hopelessly behind, so drop. */
        const val PLAY_QUEUE_CHUNKS = 100
    }
}

/** Audio hardware could not be opened — surfaced to the UI rather than failing the call silently. */
internal class VoiceAudioException(message: String) : Exception(message)
