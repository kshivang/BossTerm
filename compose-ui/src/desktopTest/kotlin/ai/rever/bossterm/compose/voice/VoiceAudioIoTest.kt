package ai.rever.bossterm.compose.voice

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
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
        override fun stop() { started.set(false); startStopCalls.add("stop") }
        override fun open(format: AudioFormat, bufferSize: Int) {}
        override fun open(format: AudioFormat) {}
        override fun open() {}
        override fun close() {}
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
        override fun isOpen(): Boolean = true
        override fun getLineInfo(): Line.Info = Line.Info(TargetDataLine::class.java)
        override fun addLineListener(listener: LineListener?) {}
        override fun removeLineListener(listener: LineListener?) {}
        override fun getControls(): Array<Control> = emptyArray()
        override fun getControl(type: Control.Type?): Control = throw IllegalArgumentException()
        override fun isControlSupported(type: Control.Type?): Boolean = false
    }

    private var io: JavaSoundVoiceAudioIo? = null

    /** The factory contract is "already open and started" — the real one opens and starts the line. */
    private fun audioFor(line: FakeLine) =
        JavaSoundVoiceAudioIo { line.also { it.start() } }.also { io = it }

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
}
