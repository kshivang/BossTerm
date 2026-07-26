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
        override fun available(): Int = 0
        override fun getBufferSize(): Int = 4096
        override fun getFormat(): AudioFormat = JavaSoundVoiceAudioIo.FORMAT
        override fun getFramePosition(): Int = 0
        override fun getLongFramePosition(): Long = 0
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
}
