package ai.rever.bossterm.compose.voice.aec

import ai.rever.bossterm.compose.voice.VoiceAudioIo
import ai.rever.bossterm.compose.voice.pcm16Rms
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * [VoiceAudioIo] backed by the macOS Voice Processing I/O unit, so the OS cancels our echo.
 *
 * The JavaSound implementation hands raw microphone audio to
 * [ai.rever.bossterm.compose.voice.VoiceDuplexGate], which infers which parts of it are the agent
 * by comparing a level against a learned attenuation of the speaker reference. This one hands over
 * audio the OS has already subtracted the speaker signal from, using both streams sample-aligned
 * in one clock domain. Measured on a real call, the inference approach held its attenuation
 * estimate at 0.10-0.24 against a true coupling near 0.83, so the bar sat at a third of the echo
 * and the agent's own voice tripped a barge-in frame after frame.
 *
 * ## What changes for the gate
 *
 * [audiblePlaybackLevel] reports 0 here, deliberately, and that is not a stub. The reference exists
 * to tell echo from speech; with the echo already removed there is nothing to tell apart, and a
 * non-zero reference would only raise the bar against the user's real voice. A zero reference
 * leaves the gate's bar at the user-level floor, which is the correct behaviour when the
 * microphone signal is genuinely just the user.
 */
internal class VoiceProcessingAudioIo(
    private val unit: VoiceProcessingUnit = VoiceProcessingUnit(),
    /** How much audio each delivered chunk carries. Matches the JavaSound path's cadence. */
    private val chunkMs: Int = 20,
) : VoiceAudioIo {

    private val log = LoggerFactory.getLogger(VoiceProcessingAudioIo::class.java)

    private val capturing = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private var pump: Thread? = null

    private val chunkBytes = SAMPLE_RATE * 2 * chunkMs / 1000

    /**
     * Start the unit and begin delivering echo-cancelled chunks.
     *
     * Failure is reported through [onEnded] rather than thrown, matching the JavaSound path: the
     * caller decides whether a dead microphone ends the call, and it already has that logic.
     */
    override fun startCapture(
        onChunk: (ByteArray) -> Unit,
        onLevel: (Float) -> Unit,
        onEnded: (String) -> Unit,
    ) {
        if (disposed.get()) {
            onEnded("this VoiceProcessingAudioIo has already been stopped")
            return
        }
        if (!capturing.compareAndSet(false, true)) return

        val failure = unit.start()
        if (failure != null) {
            capturing.set(false)
            onEnded(failure)
            return
        }

        // A plain thread, not a coroutine: this drains a realtime producer at a fixed cadence and
        // must not share a dispatcher with work that can block it.
        pump = thread(start = true, isDaemon = true, name = "boss-voice-vpio-pump") {
            val chunk = ByteArray(chunkBytes)
            var filled = 0
            while (capturing.get()) {
                val got = unit.capture.read(chunk, filled, chunkBytes - filled)
                if (got <= 0) {
                    // Nothing yet. Sleeping a fraction of a chunk keeps latency well inside one
                    // frame without spinning a core while the user is silent.
                    runCatching { Thread.sleep(POLL_SLEEP_MS) }.onFailure { return@thread }
                    continue
                }
                filled += got
                if (filled < chunkBytes) continue
                filled = 0
                val level = pcm16Rms(chunk)
                onLevel(level)
                // Muting drops the SEND but leaves the unit running, so the platform's microphone
                // indicator still reflects reality: the OS really does still have the device open.
                // Stopping the unit would also tear down the echo canceller mid-call and force it
                // to re-converge on unmute, which is audible.
                if (!muted.get()) onChunk(chunk.copyOf())
            }
        }
    }

    override fun setCaptureMuted(muted: Boolean) {
        this.muted.set(muted)
    }

    /**
     * Queue audio for the speakers, WAITING for room rather than dropping.
     *
     * The realtime rule applies to the render callback, not to this. Callers are network threads,
     * and a local speech server hands over audio far faster than it can be played - measured at
     * RTF 3.1-3.35 - so the ring fills on any long reply. Dropping there deletes the middle of the
     * agent's sentence, which is precisely the artifact this whole audio path exists to remove;
     * the first version of this method did exactly that and logged "dropped 640 of 9216 bytes"
     * during a real call.
     *
     * Bounded, because a wedged device must not park a network thread for the rest of the call. On
     * timeout the remainder IS dropped, and said so.
     */
    override fun play(pcm: ByteArray) {
        if (!capturing.get()) return
        var offset = 0
        val deadline = System.nanoTime() + PLAY_WAIT_TIMEOUT_MS * 1_000_000
        while (offset < pcm.size && capturing.get()) {
            val accepted = unit.playback.write(pcm, offset, pcm.size - offset)
            offset += accepted
            if (offset >= pcm.size) return
            if (System.nanoTime() > deadline) {
                log.warn(
                    "Voice playback stalled; dropped {} of {} bytes after {}ms",
                    pcm.size - offset, pcm.size, PLAY_WAIT_TIMEOUT_MS,
                )
                return
            }
            // Sleep rather than spin: the drain rate is fixed by the audio device, so busy-waiting
            // buys nothing and competes with the callback for a core.
            runCatching { Thread.sleep(POLL_SLEEP_MS) }.onFailure { return }
        }
    }

    override fun flushPlayback() {
        unit.playback.clear()
    }

    override fun queuedPlaybackMs(): Int {
        val bytes = unit.playback.available()
        return bytes * 1000 / (SAMPLE_RATE * 2)
    }

    /**
     * Always 0: there is no echo left for a reference to describe.
     *
     * See the class note. This is a statement about this audio path, not a missing implementation.
     */
    override fun audiblePlaybackLevel(windowMs: Int): Float = 0f

    override fun stop() {
        if (!disposed.compareAndSet(false, true)) return
        capturing.set(false)
        pump?.let { runCatching { it.join(STOP_JOIN_MS) } }
        pump = null
        unit.stop()
    }

    internal companion object {
        const val SAMPLE_RATE = 24_000

        /** Well under one chunk, so an idle poll never costs a frame of latency. */
        const val POLL_SLEEP_MS = 4L

        const val STOP_JOIN_MS = 500L

        /**
         * How long [play] waits for speaker room before giving up on the rest of a chunk.
         *
         * Longer than any legitimate backlog: the ring holds eight seconds, so reaching this means
         * the device has stopped draining entirely rather than that a reply is simply long.
         */
        const val PLAY_WAIT_TIMEOUT_MS = 10_000L

        /**
         * Whether this implementation can be used on this machine.
         *
         * Checked before construction so the caller picks an implementation rather than building
         * one and handling its failure; [VoiceProcessingUnit.start] still reports a reason if the
         * unit turns out to be unusable at start time, which is a different and later question.
         */
        fun supported(): Boolean = CoreAudio.available
    }
}
