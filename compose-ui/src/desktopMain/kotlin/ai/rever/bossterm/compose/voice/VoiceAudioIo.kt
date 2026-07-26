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

    /**
     * Begin capturing; [onChunk] receives raw PCM16 frames, [onLevel] a 0..1 loudness estimate.
     *
     * [onEnded] fires if capture stops on its OWN — the headset was unplugged, another app took the
     * device, the mixer hiccuped. Without it, losing the microphone mid-call was silent and
     * unrecoverable: the capture thread exited, the UI went on saying "Listening…" with the meter
     * frozen at its last value, and the call kept being billed for the rest of its ceiling while the
     * agent could no longer hear anything. Not called for a [stop] the caller asked for.
     */
    fun startCapture(
        onChunk: (ByteArray) -> Unit,
        onLevel: (Float) -> Unit,
        onEnded: (String) -> Unit = {},
    )

    /**
     * Mute/unmute the microphone at the LINE, not just at the send.
     *
     * Skipping the send is enough for correctness — nothing is transmitted either way — but it left
     * the capture line started, so the platform's "an app is using your microphone" indicator (the
     * orange dot on macOS, the tray icon on Windows) stayed lit for the whole muted stretch. For a
     * feature whose trust story is "you can see what it is doing", a Mute button that leaves the
     * indicator on invites exactly the conclusion it is meant to prevent.
     *
     * The share viewer deliberately does NOT match this: in a browser, releasing the track means
     * ending it and re-acquiring on unmute, which is a mid-call risk this argument does not justify.
     * See the comment on `voiceMuteEl.onclick` in viewer.js.
     */
    fun setCaptureMuted(muted: Boolean)

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

/**
 * Loudness of one PCM16 chunk, 0..1. Top-level and testable: this is the number behind "can it hear
 * me?", and the meter reading nothing is indistinguishable from a muted mic to the person watching.
 */
/**
 * Meter gain. Conversational speech sits well below full scale — RMS around 0.05-0.15 — so without
 * this the bars would barely leave the floor and the meter would read as "not hearing you".
 */
private const val SPEECH_METER_GAIN = 3.0

internal fun pcm16Rms(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    var sum = 0.0
    var i = 0
    val samples = pcm.size / 2
    while (i + 1 < pcm.size) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
        sum += (sample.toDouble() / Short.MAX_VALUE) * (sample.toDouble() / Short.MAX_VALUE)
        i += 2
    }
    return min(1f, (sqrt(sum / samples) * SPEECH_METER_GAIN).toFloat())
}

/** The real thing: `javax.sound.sampled` lines on their own threads. */
internal class JavaSoundVoiceAudioIo(
    /**
     * How the capture line is obtained. MUST return a line that is already open and STARTED — the
     * loop below reads from it immediately.
     *
     * Injected so the capture LOOP is testable without a mixer — CI has none, and both of its
     * branches have been bugs: a muted line's empty read was treated as device death (killing
     * capture for the rest of the call), and real device death was reported nowhere at all.
     */
    private val openCaptureLine: () -> TargetDataLine = {
        val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
        check(AudioSystem.isLineSupported(info)) { "no 24kHz PCM16 capture line" }
        (AudioSystem.getLine(info) as TargetDataLine).also {
            it.open(FORMAT, CHUNK_BYTES * 8)
            it.start()
        }
    },
) : VoiceAudioIo {

    private val log = LoggerFactory.getLogger(JavaSoundVoiceAudioIo::class.java)

    @Volatile private var capture: TargetDataLine? = null
    @Volatile private var playback: SourceDataLine? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var running = false
    /** Set once the speaker line has failed to open, so we stop probing hardware per chunk. */
    @Volatile private var playbackUnavailable = false

    /** Mute state, read by the capture loop — see [setCaptureMuted]. */
    @Volatile private var captureMuted = false

    /** The level sink, kept so muting can zero the meter without waiting for the next chunk. */
    @Volatile private var onLevelRef: ((Float) -> Unit)? = null

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

    override fun startCapture(
        onChunk: (ByteArray) -> Unit,
        onLevel: (Float) -> Unit,
        onEnded: (String) -> Unit,
    ) {
        check(!disposed) { "this VoiceAudioIo has already been stopped" }
        val line = runCatching { openCaptureLine() }.getOrElse {
            log.warn("Microphone unavailable: {}", it.message)
            throw VoiceAudioException("No microphone available (${it.message})")
        }
        capture = line
        onLevelRef = onLevel
        captureMuted = false
        running = true
        // A dedicated thread, NOT a coroutine dispatcher: this loop blocks on line.read for the
        // whole call, and parking a shared pool worker for minutes is what starved BossTerm's IO
        // dispatcher once already.
        captureThread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            while (running) {
                val n = runCatching { line.read(buf, 0, buf.size) }.getOrDefault(-1)
                // ZERO is not death. A stopped line — which is what mute does — hands back nothing,
                // and so does a line with no samples ready; ending capture on that killed the mic for
                // the rest of the call and presented as "Unmute did nothing". Only a NEGATIVE read
                // (or a throw, mapped to -1 above) means the device is gone: unplugged headset,
                // another app taking it, a closed line.
                if (n == 0) {
                    // Park longer while MUTED: the line is stopped for the whole muted stretch, so a
                    // 20ms poll is ~50 pointless wakeups a second for as long as it lasts. Unmuting
                    // restarts the line, and the worst case is one extra park before audio resumes.
                    runCatching { Thread.sleep(if (captureMuted) MUTED_IDLE_MS else IDLE_READ_MS) }
                    continue
                }
                if (n < 0) {
                    // Deliberate teardown is not a loss — stop() clears `running` before interrupting.
                    if (running) {
                        log.warn("Voice capture ended unexpectedly (read returned {})", n)
                        runCatching { onEnded("The microphone stopped working") }
                    }
                    break
                }
                // Belt and braces: even if a buffered chunk arrives after the line stopped, a muted
                // call must not transmit, and the meter must read silent rather than freeze.
                if (captureMuted) {
                    onLevel(0f)
                    continue
                }
                val chunk = buf.copyOf(n)
                onLevel(pcm16Rms(chunk))
                runCatching { onChunk(chunk) }
            }
        }, "boss-voice-capture").apply { isDaemon = true; start() }
    }

    override fun setCaptureMuted(muted: Boolean) {
        if (disposed) return
        captureMuted = muted
        val line = capture ?: return
        // Best-effort: if a mixer refuses stop/start, the flag above still guarantees silence — the
        // indicator just stays lit, which is the behaviour this replaces rather than a regression.
        runCatching {
            if (muted) {
                line.stop()
                line.flush()
            } else {
                line.start()
            }
        }.onFailure { log.warn("Could not {} the capture line: {}", if (muted) "stop" else "restart", it.message) }
        if (muted) onLevelRef?.invoke(0f)
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
        captureMuted = false
        onLevelRef = null
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

    internal companion object {
        /** The Realtime API's PCM contract: 24 kHz, 16-bit signed little-endian, mono. */
        val FORMAT = AudioFormat(24_000f, 16, 1, true, false)

        /** ~40 ms per chunk: small enough that playback back-pressure stays responsive. */
        const val CHUNK_BYTES = 1920

        /** ~4 s of queued speech; beyond that the speaker is hopelessly behind, so drop. */
        const val PLAY_QUEUE_CHUNKS = 100

        /** How long the capture loop parks when a read yields nothing and the mic is live. */
        const val IDLE_READ_MS = 20L

        /** The same, while muted — nothing can arrive until unmute restarts the line. */
        const val MUTED_IDLE_MS = 200L
    }
}

/** Audio hardware could not be opened — surfaced to the UI rather than failing the call silently. */
internal class VoiceAudioException(message: String) : Exception(message)
