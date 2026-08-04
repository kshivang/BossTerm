package ai.rever.bossterm.compose.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The capture loop and the level maths, without a mixer.
 *
 * Both branches of the loop's `read` handling have been bugs: a muted (stopped) line's empty read
 * was treated as the device dying, killing capture for the rest of the call and presenting as
 * "Unmute did nothing"; and real device death — an unplugged headset, another app taking the
 * microphone — was reported nowhere, so the call went deaf while the bar said "Listening…" and the
 * billing carried on.
 */
class VoiceAudioIoTest {

    /** A [TargetDataLine] that hands out whatever the test queues, and can be made to "die". */
    private class FakeLine : TargetDataLine {
        val chunks = ConcurrentLinkedQueue<ByteArray>()
        val started = AtomicBoolean(false)
        val startStopCalls = ConcurrentLinkedQueue<String>()
        @Volatile var dead = false
        val reads = AtomicInteger(0)
        @Volatile var closed = false
        /** Mixer calls really can throw; stop() used to skip close() when one did. */
        @Volatile var throwOnStop = false

        /**
         * Faithful to [TargetDataLine]: a live line BLOCKS until samples are available, a stopped
         * one hands back nothing, and a device that has gone away reports a negative read. Returning
         * 0 from a healthy line — which an earlier version of this fake did — is not something a real
         * mixer does, and testing against it would have justified treating 0 as death.
         */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads.incrementAndGet()
            while (true) {
                if (dead) return -1
                if (!started.get()) return 0
                val next = chunks.poll()
                if (next != null) {
                    val n = minOf(len, next.size)
                    System.arraycopy(next, 0, b, off, n)
                    return n
                }
                Thread.sleep(2) // a live line waiting for the next frame
            }
        }

        override fun start() { started.set(true); startStopCalls.add("start") }
        override fun stop() {
            startStopCalls.add("stop")
            if (throwOnStop) throw IllegalStateException("mixer says no")
            started.set(false)
        }
        override fun open(format: AudioFormat, bufferSize: Int) {}
        override fun open(format: AudioFormat) {}
        override fun open() {}
        override fun close() { closed = true }
        override fun drain() {}
        override fun flush() { chunks.clear() }
        override fun available(): Int = chunks.size
        override fun getBufferSize(): Int = 4096
        override fun getFormat(): AudioFormat = JavaSoundVoiceAudioIo.FORMAT
        override fun getFramePosition(): Int = 0
        override fun getLongFramePosition(): Long = 0
        override fun getMicrosecondPosition(): Long = 0
        override fun getLevel(): Float = 0f
        override fun isActive(): Boolean = started.get()
        override fun isRunning(): Boolean = started.get()
        override fun isOpen(): Boolean = !closed
        override fun getLineInfo(): Line.Info = Line.Info(TargetDataLine::class.java)
        override fun addLineListener(listener: LineListener?) {}
        override fun removeLineListener(listener: LineListener?) {}
        override fun getControls(): Array<Control> = emptyArray()
        override fun getControl(type: Control.Type?): Control = throw IllegalArgumentException()
        override fun isControlSupported(type: Control.Type?): Boolean = false
    }

    private var io: JavaSoundVoiceAudioIo? = null

    /**
     * The factory contract is "already open and started" — the real one opens and starts the line.
     * Named arguments, not a trailing lambda: there are two factories now, and a trailing lambda
     * binds to the LAST parameter.
     */
    private fun audioFor(line: FakeLine, speaker: FakeSpeaker = FakeSpeaker()) =
        JavaSoundVoiceAudioIo(
            openCaptureLine = { line.also { it.start() } },
            openPlaybackLine = { speaker.also { it.start() } },
        ).also { io = it }

    /** A [SourceDataLine] that records what was written and can be made to fail on demand. */
    private class FakeSpeaker : SourceDataLine {
        val written = ConcurrentLinkedQueue<ByteArray>()
        val started = AtomicBoolean(false)
        @Volatile var flushes = 0
        @Volatile var closed = false
        /** Mixer calls really can throw; stop() used to skip close() when one did. */
        @Volatile var throwOnFlush = false
        @Volatile var throwOnStop = false

        override fun write(b: ByteArray, off: Int, len: Int): Int {
            written.add(b.copyOfRange(off, off + len))
            return len
        }
        override fun open(format: AudioFormat, bufferSize: Int) {}
        override fun open(format: AudioFormat) {}
        override fun open() {}
        override fun close() { closed = true }
        override fun start() { started.set(true) }
        override fun stop() { if (throwOnStop) throw IllegalStateException("mixer says no"); started.set(false) }
        override fun drain() {}
        override fun flush() { if (throwOnFlush) throw IllegalStateException("mixer says no"); flushes++ }
        /**
         * A real line reports how much SPACE is free. This fake writes through immediately, so its
         * buffer is always empty — available() == bufferSize. Returning 0 meant "completely full",
         * which made queuedPlaybackMs count a phantom 85ms of audio that had already played.
         */
        override fun available(): Int = bufferSize
        override fun getBufferSize(): Int = 4096
        override fun getFormat(): AudioFormat = JavaSoundVoiceAudioIo.FORMAT
        override fun getFramePosition(): Int = 0

        /**
         * Frames the device has actually PLAYED, which a real line advances on its own.
         *
         * Settable because the distinction between this and "frames we have written" is the whole
         * point of the echo reference: the line buffer holds ~0.6s, so keying the reference off write
         * time mis-aligns it by more than the echo it is meant to predict.
         */
        @Volatile var playedFrames = 0L
        override fun getLongFramePosition(): Long = playedFrames
        override fun getMicrosecondPosition(): Long = 0
        override fun getLevel(): Float = 0f
        override fun isActive(): Boolean = started.get()
        override fun isRunning(): Boolean = started.get()
        override fun isOpen(): Boolean = !closed
        override fun getLineInfo(): Line.Info = Line.Info(SourceDataLine::class.java)
        override fun addLineListener(listener: LineListener?) {}
        override fun removeLineListener(listener: LineListener?) {}
        override fun getControls(): Array<Control> = emptyArray()
        override fun getControl(type: Control.Type?): Control = throw IllegalArgumentException()
        override fun isControlSupported(type: Control.Type?): Boolean = false
    }

    @AfterTest
    fun tearDown() {
        runCatching { io?.stop() }
    }

    private fun await(timeoutMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { predicate() }.getOrDefault(false)) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun `a dead capture line is reported, not swallowed`() {
        val line = FakeLine()
        val audio = audioFor(line)
        val ended = ConcurrentLinkedQueue<String>()
        audio.startCapture(onChunk = {}, onLevel = {}, onEnded = { ended.add(it) })

        repeat(3) { line.chunks.add(ByteArray(64)) }
        assertTrue(await { line.reads.get() >= 3 }, "the loop is running")

        line.dead = true // the headset was unplugged
        assertTrue(await { ended.isNotEmpty() }, "losing the device must reach the caller")
        assertTrue(ended.first().contains("microphone", ignoreCase = true), ended.first())
    }

    @Test
    fun `a deliberate stop is not reported as a loss`() {
        val line = FakeLine()
        val audio = audioFor(line)
        val ended = ConcurrentLinkedQueue<String>()
        val chunks = ConcurrentLinkedQueue<ByteArray>()
        audio.startCapture(onChunk = { chunks.add(it) }, onLevel = {}, onEnded = { ended.add(it) })
        repeat(3) { line.chunks.add(ByteArray(64)) }
        assertTrue(await { chunks.size >= 3 }, "the loop is running")

        audio.stop()
        Thread.sleep(200)
        assertEquals(0, ended.size, "the caller asked for this; it is not a failure")
    }

    /**
     * Mute stops the line, so `read` legitimately returns nothing. Treating that as death ended
     * capture permanently — the bug this pins is that UNMUTE still delivers audio afterwards.
     */
    @Test
    fun `muting stops the line and unmuting resumes capture`() {
        val line = FakeLine()
        val audio = audioFor(line)
        val chunks = ConcurrentLinkedQueue<ByteArray>()
        val ended = ConcurrentLinkedQueue<String>()
        audio.startCapture(onChunk = { chunks.add(it) }, onLevel = {}, onEnded = { ended.add(it) })

        line.chunks.add(ByteArray(64) { 5 })
        assertTrue(await { chunks.isNotEmpty() }, "audio flows before muting")

        audio.setCaptureMuted(true)
        assertTrue(await { !line.started.get() }, "mute stops the LINE, so the OS indicator clears")
        val whileMuted = chunks.size
        line.chunks.add(ByteArray(64) { 7 })
        Thread.sleep(200)
        assertEquals(whileMuted, chunks.size, "nothing is captured while muted")
        assertEquals(0, ended.size, "and a muted line is never mistaken for a dead one")

        audio.setCaptureMuted(false)
        assertTrue(await { line.started.get() }, "unmute restarts the line")
        line.chunks.add(ByteArray(64) { 9 })
        assertTrue(await { chunks.size > whileMuted }, "audio flows again after unmute")
    }

    /**
     * The queue is bounded by DURATION, not entry count — but the bound is a safety net, not a
     * budget.
     *
     * It was 100 entries documented as "~4s of speech", which only held if every entry were one
     * 40ms frame; play() enqueues whatever a server-chosen delta decodes to. Making the number
     * literally four seconds then broke playback, because the producer is faster than real time by
     * design. So this asserts the two things that actually matter: it is bounded, and the bound is
     * far above any real reply.
     */
    @Test
    fun `the play queue is bounded, generously`() {
        val line = FakeLine()
        val speaker = FakeSpeaker()
        val audio = audioFor(line, speaker)
        audio.startCapture(onChunk = {}, onLevel = {})
        audio.setCaptureMuted(true) // hold the drain so this measures the QUEUE

        val oneSecond = ByteArray(JavaSoundVoiceAudioIo.BYTES_PER_SECOND)
        repeat(200) { audio.play(oneSecond) } // far more than any plausible answer

        val queuedMs = audio.queuedPlaybackMs()
        assertTrue(queuedMs <= 62_000, "unbounded growth would be a leak; saw ${queuedMs}ms")
        assertTrue(
            queuedMs >= 30_000,
            "the bound must not chop real replies - a 30s answer has to fit; saw ${queuedMs}ms",
        )
    }

    /**
     * A whole reply must survive the queue.
     *
     * The Realtime API streams a response's audio as fast as it generates it while the speaker
     * drains in real time, so the queue routinely holds most of an answer. A four-second bound
     * dropped the middle of every longer reply chunk by chunk — the caller heard the start, then
     * jumps, with unrelated audio spliced together.
     */
    @Test
    fun `a thirty-second reply is not chopped to fit the queue`() {
        val line = FakeLine()
        val speaker = FakeSpeaker()
        val audio = audioFor(line, speaker)
        audio.startCapture(onChunk = {}, onLevel = {})
        // Block the drain so this measures the QUEUE, not how fast the fake speaker consumes.
        audio.setCaptureMuted(true)

        val oneSecond = ByteArray(JavaSoundVoiceAudioIo.BYTES_PER_SECOND)
        repeat(30) { audio.play(oneSecond) }

        val queuedMs = audio.queuedPlaybackMs()
        assertTrue(
            queuedMs >= 20_000,
            "a 30s answer must not be trimmed to a few seconds; only ${queuedMs}ms survived",
        )
    }

    @Test
    fun `queued audio is reported in milliseconds`() {
        val line = FakeLine()
        val speaker = FakeSpeaker()
        val audio = audioFor(line, speaker)
        audio.startCapture(onChunk = {}, onLevel = {})
        audio.flushPlayback()
        assertEquals(0, audio.queuedPlaybackMs())

        audio.play(ByteArray(JavaSoundVoiceAudioIo.BYTES_PER_SECOND / 2)) // 500ms
        // The drain thread may already have taken it; either way it is never reported as chunks.
        assertTrue(audio.queuedPlaybackMs() in 0..500, audio.queuedPlaybackMs().toString())
    }

    // ---- the level maths behind "can it hear me?" ----

    @Test
    fun `silence reads as zero and full scale clamps to one`() {
        assertEquals(0f, pcm16Rms(ByteArray(64)))
        val fullScale = ByteArray(64)
        var i = 0
        while (i + 1 < fullScale.size) {
            fullScale[i] = 0xFF.toByte()
            fullScale[i + 1] = 0x7F // Short.MAX_VALUE little-endian
            i += 2
        }
        assertEquals(1f, pcm16Rms(fullScale), "the meter must not exceed its track")
    }

    @Test
    fun `a louder signal reads higher`() {
        fun tone(amplitude: Short): ByteArray {
            val out = ByteArray(64)
            var i = 0
            while (i + 1 < out.size) {
                out[i] = (amplitude.toInt() and 0xFF).toByte()
                out[i + 1] = (amplitude.toInt() shr 8).toByte()
                i += 2
            }
            return out
        }
        val quiet = pcm16Rms(tone(1_000))
        val loud = pcm16Rms(tone(8_000))
        assertTrue(quiet < loud, "$quiet should be below $loud")
        assertTrue(quiet > 0f, "speech well below full scale must still move the meter")
    }

    @Test
    fun `a negative sample is as loud as its positive twin`() {
        fun tone(amplitude: Int): ByteArray {
            val out = ByteArray(64)
            var i = 0
            while (i + 1 < out.size) {
                out[i] = (amplitude and 0xFF).toByte()
                out[i + 1] = (amplitude shr 8).toByte()
                i += 2
            }
            return out
        }
        // Sign handling is easy to get wrong when reassembling little-endian PCM16 by hand.
        assertTrue(abs(pcm16Rms(tone(4_000)) - pcm16Rms(tone(-4_000))) < 0.001f)
    }

    @Test
    fun `a truncated frame is not a crash`() {
        assertEquals(0f, pcm16Rms(ByteArray(1)), "an odd trailing byte must not index past the end")
        assertEquals(0f, pcm16Rms(ByteArray(0)))
    }

    // ---- playback: the path where both of this class's leaks lived ----

    @Test
    fun `audio reaches the speaker and barge-in drops what is queued`() {
        val line = FakeLine()
        val speaker = FakeSpeaker()
        val audio = audioFor(line, speaker)
        audio.startCapture(onChunk = {}, onLevel = {})

        audio.play(ByteArray(8) { 1 })
        assertTrue(await { speaker.written.isNotEmpty() }, "the agent is audible")

        // Barge-in: the user starts talking, so whatever is still queued must not play over them.
        audio.play(ByteArray(8) { 2 })
        audio.flushPlayback()
        assertTrue(speaker.flushes > 0, "the line itself is flushed, not just our queue")
    }

    /**
     * A throwing mixer call must not take the ones after it with it. Grouped in one runCatching, a
     * failing capture.stop() skipped close() and left the microphone line open — the OS indicator
     * lit after the call ended, with no second chance because `disposed` is latched.
     */
    @Test
    fun `a throwing mixer call still closes both lines`() {
        val line = FakeLine()
        val speaker = FakeSpeaker()
        line.throwOnStop = true
        speaker.throwOnFlush = true
        val audio = audioFor(line, speaker)
        audio.startCapture(onChunk = {}, onLevel = {})
        audio.play(ByteArray(4))
        assertTrue(await { speaker.written.isNotEmpty() })

        audio.stop()

        assertTrue(line.closed, "the capture line must close even though stop() threw")
        assertTrue(speaker.closed, "and the speaker even though flush() threw")
    }

    /**
     * play() runs on the WebSocket reader thread while stop() runs on IO. Opening a line takes real
     * milliseconds — long enough to pass the `disposed` check, have stop() complete, and then set
     * `running = true` again behind it, leaving a drain thread nothing can stop.
     */
    @Test
    fun `a play racing teardown cannot resurrect the drain loop`() {
        repeat(200) {
            val line = FakeLine()
            val speaker = FakeSpeaker()
            val audio = audioFor(line, speaker)
            audio.startCapture(onChunk = {}, onLevel = {})

            val pool = Executors.newFixedThreadPool(2)
            try {
                val go = CountDownLatch(1)
                val p = pool.submit { go.await(); audio.play(ByteArray(4)) }
                val q = pool.submit { go.await(); audio.stop() }
                go.countDown()
                p.get(5, TimeUnit.SECONDS)
                q.get(5, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }

            // Whatever the interleaving, teardown wins: nothing is left started.
            assertTrue(
                await(2_000) { !threadAlive("boss-voice-playback") },
                "a drain thread outlived stop() on iteration $it",
            )
        }
    }

    private fun threadAlive(name: String): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == name && it.isAlive }

    /** 960 frames (one 40ms chunk at 24kHz) of constant amplitude. */
    private fun chunk(amplitude: Short): ByteArray {
        val out = ByteArray(1920)
        for (i in out.indices step 2) {
            out[i] = (amplitude.toInt() and 0xFF).toByte()
            out[i + 1] = ((amplitude.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * The echo reference reports what is COMING OUT of the speaker, not what was last handed to it.
     *
     * This is the distinction the whole gate rests on. The line buffer holds around 0.6s, so audio
     * written now becomes audible far later; a reference keyed on write time would raise the gate's bar
     * before the sound it is predicting exists, and drop it while the echo was still arriving.
     */
    @Test
    fun `the echo reference follows the line's play position, not what was written`() {
        val speaker = FakeSpeaker()
        val audio = audioFor(FakeLine(), speaker)
        // Quiet first, loud second, so "the loud one is not audible yet" is a distinguishable claim —
        // with the loud chunk first, a max() over everything written would look identical to a correct
        // answer and the test would prove nothing.
        audio.play(chunk(600))    // quiet
        audio.play(chunk(12_000)) // loud
        assertTrue(await { speaker.written.size == 2 }, "both chunks must reach the line")

        speaker.playedFrames = 0
        val early = audio.audiblePlaybackLevel(windowMs = 100)
        assertTrue(early > 0f, "the chunk at the play position is audible")
        assertTrue(early < 0.2f, "the loud chunk is written but NOT yet playing: $early")

        // The device advances into the loud chunk (which starts at frame 960).
        speaker.playedFrames = 1_200
        assertTrue(audio.audiblePlaybackLevel(windowMs = 100) > 0.5f, "now it is what the room hears")

        // Long past both, beyond the look-back: nothing of it can still be in the room.
        speaker.playedFrames = 100_000
        assertEquals(0f, audio.audiblePlaybackLevel(windowMs = 100))
    }

    /**
     * A flush discards buffered frames, so written-frames and played-frames stop agreeing.
     *
     * Without re-anchoring, the reference stays permanently ahead of the device for the rest of the
     * call — and flushPlayback is on exactly the path barge-in takes, so the failure would appear only
     * after the first interruption.
     */
    @Test
    fun `flushing re-anchors the echo reference`() {
        val speaker = FakeSpeaker()
        val audio = audioFor(FakeLine(), speaker)
        audio.play(chunk(12_000))
        assertTrue(await { speaker.written.size == 1 })
        speaker.playedFrames = 100

        audio.flushPlayback()
        assertEquals(0f, audio.audiblePlaybackLevel(windowMs = 100), "discarded audio is not audible")

        // And the mapping still works afterwards, rather than being permanently skewed.
        audio.play(chunk(12_000))
        assertTrue(await { speaker.written.size == 2 })
        speaker.playedFrames = 150
        assertTrue(audio.audiblePlaybackLevel(windowMs = 100) > 0.5f, "post-flush audio must register")
    }
}
