package ai.rever.bossterm.compose.voice.aec

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring is the only piece of the AEC path that can be tested without an audio device, and it is
 * also the piece a realtime callback depends on being correct: a bug here surfaces as a click or a
 * repeated syllable, never as an exception.
 */
class AudioRingTest {

    @Test
    fun `bytes come back in the order they went in`() {
        val ring = AudioRing(64)
        ring.write(byteArrayOf(1, 2, 3, 4))
        val out = ByteArray(4)
        assertEquals(4, ring.read(out))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), out)
    }

    @Test
    fun `an empty ring reads nothing rather than blocking`() {
        val ring = AudioRing(64)
        assertEquals(0, ring.read(ByteArray(8)))
    }

    /** Wrapping is where an off-by-one turns into a click, so exercise it explicitly. */
    @Test
    fun `data that wraps the end of the buffer is reassembled`() {
        val ring = AudioRing(8)
        ring.write(ByteArray(6) { (it + 1).toByte() })
        assertEquals(6, ring.read(ByteArray(6)))
        // Now the cursors sit near the end; this write straddles the boundary.
        ring.write(byteArrayOf(10, 11, 12, 13, 14))
        val out = ByteArray(5)
        assertEquals(5, ring.read(out))
        assertContentEquals(byteArrayOf(10, 11, 12, 13, 14), out)
    }

    /**
     * A full ring refuses new bytes rather than overwriting unread ones, and says how many it lost.
     *
     * Drop-newest rather than drop-oldest is a deliberate reversal: two drop-oldest designs here
     * both tore under concurrency, because a writer that reuses unread slots races the reader's
     * in-progress copy however the loss is accounted. The audio lost is the same either way when a
     * consumer falls behind; what differs is that this cannot return bytes from two writes.
     */
    @Test
    fun `a full ring refuses new bytes and reports the loss`() {
        val ring = AudioRing(8)
        assertEquals(8, ring.write(ByteArray(8) { it.toByte() }))
        assertEquals(0, ring.write(byteArrayOf(100, 101, 102, 103)), "a full ring must accept nothing")
        assertEquals(4, ring.droppedBytes(), "the loss must be visible, not silent")
        val out = ByteArray(8)
        assertEquals(8, ring.read(out))
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), out, "unread audio must survive intact")
    }

    /** A partly full ring takes what fits and counts the rest. */
    @Test
    fun `a partial write fills the space that exists`() {
        val ring = AudioRing(8)
        ring.write(ByteArray(6))
        assertEquals(2, ring.write(byteArrayOf(9, 9, 9, 9)), "only two bytes of room were left")
        assertEquals(2, ring.droppedBytes())
    }

    @Test
    fun `clear drops what is buffered so a flush is immediate`() {
        val ring = AudioRing(16)
        ring.write(ByteArray(8))
        ring.clear()
        assertEquals(0, ring.available())
        assertEquals(0, ring.read(ByteArray(8)))
    }

    /**
     * One producer and one consumer at speed, which is the arrangement the callbacks actually use.
     *
     * The payload is a stream of 32-bit counters rather than a repeating byte ramp. That matters:
     * a ramp cannot distinguish a legitimate DROP that happens to wrap 255->0 from bytes
     * interleaved by a half-published write, and an earlier version of this test failed on exactly
     * that false positive rather than on a real defect. A globally increasing counter has no such
     * ambiguity - a drop is a forward jump, and any backwards step is genuine corruption.
     *
     * Alignment survives because every size here is a multiple of four: writes, the ring itself,
     * and therefore the drop amounts, which are computed from those two.
     */
    @Test
    fun `a concurrent producer and consumer never observe a torn write`() {
        val ring = AudioRing(1024)
        val started = CountDownLatch(1)
        // AtomicReference rather than a @Volatile local, which Kotlin does not allow: the value is
        // written on the consumer thread and asserted on the test thread.
        val corruption = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val consumer = thread(start = true, name = "ring-consumer") {
            started.await()
            val buffer = ByteArray(96)
            var previous = -1L
            repeat(6_000) {
                val n = ring.read(buffer)
                var i = 0
                while (i + 3 < n) {
                    val word = (buffer[i].toLong() and 0xFF) or
                        ((buffer[i + 1].toLong() and 0xFF) shl 8) or
                        ((buffer[i + 2].toLong() and 0xFF) shl 16) or
                        ((buffer[i + 3].toLong() and 0xFF) shl 24)
                    if (word <= previous) {
                        corruption.compareAndSet(null, "counter went backwards: $previous then $word")
                    }
                    previous = word
                    i += 4
                }
            }
        }

        val producer = thread(start = true, name = "ring-producer") {
            started.await()
            val chunk = ByteArray(60)
            var next = 1L
            repeat(6_000) {
                var i = 0
                while (i + 3 < chunk.size) {
                    chunk[i] = (next and 0xFF).toByte()
                    chunk[i + 1] = ((next shr 8) and 0xFF).toByte()
                    chunk[i + 2] = ((next shr 16) and 0xFF).toByte()
                    chunk[i + 3] = ((next shr 24) and 0xFF).toByte()
                    next++
                    i += 4
                }
                ring.write(chunk)
            }
        }

        started.countDown()
        producer.join(30_000)
        consumer.join(30_000)
        assertTrue(
            corruption.get() == null,
            "a reader observed bytes from a half-finished write: ${corruption.get()}",
        )
    }

    @Test
    fun `a partial read leaves the remainder for next time`() {
        val ring = AudioRing(32)
        ring.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        val first = ByteArray(2)
        assertEquals(2, ring.read(first))
        assertContentEquals(byteArrayOf(1, 2), first)
        val rest = ByteArray(8)
        assertEquals(4, ring.read(rest))
        assertContentEquals(byteArrayOf(3, 4, 5, 6), rest.copyOf(4))
    }
}
