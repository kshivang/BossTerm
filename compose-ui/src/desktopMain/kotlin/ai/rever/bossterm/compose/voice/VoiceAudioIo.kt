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

    /**
     * How much decoded audio is still waiting for the speaker, in MILLISECONDS.
     *
     * The Realtime API says `response.output_audio.done` when it has finished SENDING, which is not
     * when the user has finished HEARING: up to PLAY_QUEUE_CHUNKS × ~40ms (≈4s) can still be queued.
     * Clearing "speaking" on that event flipped the bar to "Listening…" while Boss was audibly
     * mid-sentence. The viewer has no equivalent problem — `output_audio_buffer.stopped` is
     * playback-accurate — so this is what lets the two surfaces agree.
     */
    fun queuedPlaybackMs(): Int

    /**
     * How loud the audio LEAVING THE SPEAKER right now is, 0..1 — the echo canceller's reference.
     *
     * This is the one thing that reliably separates the agent's voice from the user's, and no amount
     * of measuring the microphone alone can substitute for it: in a real room the reply comes back at
     * 0.10 RMS, which is exactly what ordinary speech measures, so a static level threshold has
     * nothing to divide. What we do know for certain is what we sent to the speaker.
     *
     * "Right now" is not "what we last wrote". The line is opened with a buffer of about 0.6s, so a
     * chunk handed to `write()` becomes audible up to that much later — keying the reference off write
     * time would mis-align it by more than the whole echo. [javax.sound.sampled.DataLine.getLongFramePosition]
     * reports frames the device has ACTUALLY played, so the level is looked up against that.
     *
     * [windowMs] looks back a little, because the microphone hears the room's delay and reverb rather
     * than the speaker cone directly; the maximum over that window is what could still be arriving.
     */
    fun audiblePlaybackLevel(windowMs: Int = JavaSoundVoiceAudioIo.ECHO_REFERENCE_WINDOW_MS): Float

    /** Stop both directions and release the lines. */
    fun stop()
}

/**
 * Meter gain. Conversational speech sits well below full scale — RMS around 0.05-0.15 — so without
 * this the bars would barely leave the floor and the meter would read as "not hearing you".
 */
private const val SPEECH_METER_GAIN = 3.0

/**
 * Loudness of one PCM16 chunk, 0..1. Top-level and testable: this is the number behind "can it hear
 * me?", and the meter reading nothing is indistinguishable from a muted mic to the person watching.
 */
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

/** Run a mixer call whose failure must not stop the ones after it. */
private inline fun quietly(block: () -> Unit) {
    runCatching(block)
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
    /**
     * How the speaker line is obtained. MUST return an open, started line.
     *
     * Injectable for the same reason as [openCaptureLine]: the playback path — ensurePlayback's
     * one-shot construction, the drain loop, play()'s drop-oldest, and flushPlayback for barge-in —
     * had no direct test because the line came straight from AudioSystem. Two of the bugs this class
     * has had lived exactly there, which is not a coincidence.
     */
    private val openPlaybackLine: () -> SourceDataLine = {
        val info = DataLine.Info(SourceDataLine::class.java, FORMAT)
        (AudioSystem.getLine(info) as SourceDataLine).also {
            it.open(FORMAT, CHUNK_BYTES * 16)
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

    /** One warning per call: a chopped reply must be diagnosable without flooding the log. */
    private val droppedWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Bytes currently in [playQueue] — see [PLAY_QUEUE_BYTES]. */
    private val queuedBytes = java.util.concurrent.atomic.AtomicInteger(0)

    private fun bytesToMs(bytes: Int): Int = (bytes.toLong() * 1000 / BYTES_PER_SECOND).toInt()

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
        // Never block the caller (the socket thread): drop the OLDEST audio until this fits.
        // Reaching here means the speaker is not keeping up with real time, which should not happen
        // — so say so once, rather than silently chopping the agent's speech.
        var dropped = 0
        while (queuedBytes.get() + pcm.size > PLAY_QUEUE_BYTES || playQueue.remainingCapacity() == 0) {
            val gone = playQueue.poll() ?: break
            queuedBytes.addAndGet(-gone.size)
            dropped += gone.size
        }
        if (dropped > 0 && droppedWarned.compareAndSet(false, true)) {
            log.warn(
                "Voice playback fell behind: dropped {}ms of agent audio at the {}s queue bound",
                bytesToMs(dropped),
                PLAY_QUEUE_BYTES / BYTES_PER_SECOND,
            )
        }
        if (playQueue.offer(pcm)) queuedBytes.addAndGet(pcm.size)
    }

    override fun flushPlayback() {
        playQueue.clear()
        queuedBytes.set(0)
        runCatching { playback?.flush() }
        // The written-frame counter and the line's played-frame position only agree while everything
        // written is eventually played. flush() discards buffered frames, so without re-anchoring here
        // the reference envelope stays permanently ahead of the device and reports the wrong level for
        // the rest of the call — on the exact path barge-in takes.
        synchronized(playedLog) {
            playedLog.clear()
            framesWritten = runCatching { playback?.longFramePosition ?: 0L }.getOrDefault(0L)
        }
    }

    /** One written chunk and where it lands on the line's own frame clock. */
    private class PlayedChunk(val startFrame: Long, val endFrame: Long, val level: Float)

    private val playedLog = ArrayDeque<PlayedChunk>()

    /** Frames handed to the line so far, on the same clock as [javax.sound.sampled.DataLine.getLongFramePosition]. */
    private var framesWritten = 0L

    override fun audiblePlaybackLevel(windowMs: Int): Float {
        val line = playback ?: return 0f
        val position = runCatching { line.longFramePosition }.getOrDefault(0L)
        val from = position - windowMs.toLong() * SAMPLE_RATE / 1000
        var loudest = 0f
        synchronized(playedLog) {
            val entries = playedLog.iterator()
            while (entries.hasNext()) {
                val chunk = entries.next()
                // Already played out and past the look-back: it can no longer be in the room.
                if (chunk.endFrame < from) {
                    entries.remove()
                    continue
                }
                // Written but not yet played. Counting it would raise the bar before the sound exists.
                if (chunk.startFrame > position) break
                if (chunk.level > loudest) loudest = chunk.level
            }
        }
        return loudest
    }

    override fun queuedPlaybackMs(): Int {
        // The LINE's buffer counts too. It is opened at CHUNK_BYTES * 16 — roughly 0.6s at 24kHz
        // PCM16 — so measuring only our queue said "drained" while up to that much was still on its
        // way to the speaker, and the bar cleared "Speaking" ahead of the audio by exactly the
        // amount this feature is trying not to be wrong by.
        val inLine = runCatching { playback?.let { it.bufferSize - it.available() } ?: 0 }.getOrDefault(0)
        return bytesToMs((queuedBytes.get() + inLine).coerceAtLeast(0))
    }

    /**
     * Open the speaker line + its drain thread on first use. Null when there is no output device.
     *
     * @Synchronized with [stop]. play() runs on the JDK WebSocket reader thread while stop() runs on
     * IO, and opening a line takes real milliseconds — long enough for this to pass the `disposed`
     * check, stop() to run to completion, and this to then set `running = true` again and start a
     * drain thread nothing will ever stop. The `disposed` latch closes the SEQUENTIAL ordering its
     * KDoc describes; it cannot close a concurrent one, and these really are two threads.
     */
    @Synchronized
    private fun ensurePlayback(): SourceDataLine? {
        playback?.let { return it }
        if (playbackUnavailable || disposed) return null
        val line = runCatching { openPlaybackLine() }.getOrElse {
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
                // Floored: flushPlayback() can reset the counter between this thread's poll and its
                // subtraction, and a negative total then under-reports against AUDIBLE_TAIL_MS —
                // clearing "Speaking" early, which is the one thing queuedPlaybackMs exists to stop.
                queuedBytes.updateAndGet { (it - chunk.size).coerceAtLeast(0) }
                // Recorded BEFORE the write, which blocks once the line's buffer is full: the capture
                // thread must be able to see this chunk's level as soon as it can possibly be audible.
                val frames = chunk.size / 2
                synchronized(playedLog) {
                    playedLog.addLast(PlayedChunk(framesWritten, framesWritten + frames, pcm16Rms(chunk)))
                    framesWritten += frames
                    // Bounded regardless of how long a call runs. audiblePlaybackLevel prunes by frame
                    // position, but only while something is asking — nothing does between replies.
                    while (playedLog.size > PLAYED_LOG_MAX) playedLog.removeFirst()
                }
                runCatching { line.write(chunk, 0, chunk.size) }
            }
        }, "boss-voice-playback").apply { isDaemon = true; start() }
        return line
    }

    @Synchronized
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
        // ONE runCatching per call. Grouped, a throwing capture.stop() skipped close() and left the
        // TargetDataLine open — the OS microphone indicator lit after the call ended, with no second
        // chance because `disposed` is latched and this object serves exactly one call. The rest of
        // the file already assumes mixer calls can throw (setCaptureMuted wraps its stop/start for
        // precisely that reason); this was the one place that assumed they could not.
        quietly { capture?.stop() }
        quietly { capture?.close() }
        capture = null
        quietly { playback?.flush() }
        quietly { playback?.stop() }
        quietly { playback?.close() }
        playback = null
    }

    internal companion object {
        /** The Realtime API's PCM contract: 24 kHz, 16-bit signed little-endian, mono. */
        val FORMAT = AudioFormat(24_000f, 16, 1, true, false)

        /** ~40 ms per chunk: small enough that playback back-pressure stays responsive. */
        const val SAMPLE_RATE = 24_000

        /**
         * How far back [audiblePlaybackLevel] looks for sound that could still be in the room.
         *
         * Covers the speaker-to-microphone path and the reverb behind it. Too short and the reference
         * goes quiet while the echo of it is still arriving — which is the self-interruption; too long
         * and the bar stays raised into the silence after a reply, blocking a legitimate barge-in.
         */
        const val ECHO_REFERENCE_WINDOW_MS = 250

        /** ~20s of chunks. Only a bound; [audiblePlaybackLevel] prunes by position. */
        const val PLAYED_LOG_MAX = 512

        const val CHUNK_BYTES = 1920

        /** The format's own rate: 24 kHz, 16-bit, mono. */
        const val BYTES_PER_SECOND = 24_000 * 2

        /**
         * Queue bound, in BYTES rather than entries — and sized for a WHOLE REPLY, not a few seconds.
         *
         * The producer is faster than the consumer BY DESIGN: the Realtime API streams a response's
         * audio as fast as it generates it, while the speaker drains in real time. A thirty-second
         * answer arrives in a couple of seconds and waits here to be heard.
         *
         * A four-second bound — what this was, briefly — therefore dropped the middle of every reply
         * longer than four seconds, chunk by chunk, to make room for the next: the caller hears the
         * start, then jumps, with unrelated audio spliced together. The 100-ENTRY limit it replaced
         * was wrong to describe as "~4s", but with server-sized deltas it was generous enough never
         * to bite in practice. Fixing the description by making the number true made the bug real.
         *
         * Sixty seconds is ~2.9 MB, and exists only so a genuinely stalled line cannot grow without
         * limit. In normal use it is never reached — if it is, [play] says so.
         */
        const val PLAY_QUEUE_BYTES = 60 * BYTES_PER_SECOND

        /** Entry cap as a backstop, so a flood of tiny deltas cannot grow the deque without bound. */
        const val PLAY_QUEUE_CHUNKS = 512

        /** How long the capture loop parks when a read yields nothing and the mic is live. */
        const val IDLE_READ_MS = 20L

        /** The same, while muted — nothing can arrive until unmute restarts the line. */
        const val MUTED_IDLE_MS = 200L
    }
}

/** Audio hardware could not be opened — surfaced to the UI rather than failing the call silently. */
internal class VoiceAudioException(message: String) : Exception(message)
