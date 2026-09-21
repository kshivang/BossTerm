package ai.rever.bossterm.compose.voice.aec

import java.util.concurrent.atomic.AtomicLong

/**
 * A fixed-capacity byte ring for exactly one producer and one consumer.
 *
 * Exists because CoreAudio's callbacks run on a realtime thread: anything that allocates, blocks,
 * or waits on a lock the JVM might hold will glitch the audio device, which is the exact class of
 * artifact this AEC work is meant to remove. So a callback's only job is to copy bytes into, or out
 * of, storage that already exists, and a normal thread does everything else.
 *
 * ## Why the writer refuses instead of overwriting
 *
 * The obvious policy for live audio is drop-oldest: overwrite history so the ring always holds the
 * newest sound. Two attempts at that here were wrong, and the concurrency test in this package
 * caught both:
 *
 *  1. The writer evicted by advancing the read cursor, so BOTH threads assigned it. The reader's
 *     own update could land after the writer's and move the cursor backwards onto slots already
 *     overwritten, returning bytes from two different writes (observed: 76170 then 75915).
 *  2. Moving eviction to the reader fixed the cursor ownership but not the tear. A writer that
 *     overwrites unread slots races the reader's in-progress copy no matter who accounts for the
 *     loss, and detecting it afterwards still means the copy already happened.
 *
 * So the writer never overwrites bytes the reader has not taken. When the ring is full the NEW
 * bytes are dropped and counted. For a capture ring this is the same loss either way - a consumer
 * that cannot keep up loses audio regardless - but it makes tearing impossible by construction
 * rather than by argument, on a path where the failure mode is a click in someone's ear.
 *
 * Each cursor has exactly one writer: [writeIndex] belongs to the producer, [readIndex] to the
 * consumer. That is the entire invariant, and it is why the two directions get separate rings.
 */
internal class AudioRing(val capacity: Int) {

    private val buffer = ByteArray(capacity)
    private val writeIndex = AtomicLong(0)
    private val readIndex = AtomicLong(0)
    private val dropped = AtomicLong(0)

    /** Bytes available to read. */
    fun available(): Int = (writeIndex.get() - readIndex.get()).toInt().coerceAtLeast(0)

    /** Free space, in bytes. */
    fun free(): Int = capacity - available()

    /** Bytes refused for want of space, for the whole life of this ring. */
    fun droppedBytes(): Long = dropped.get()

    /**
     * Copy [length] bytes from [source]. Called from the producer only.
     *
     * Never blocks and never allocates. Bytes that do not fit are dropped and counted, so a slow
     * consumer costs audio rather than stalling a realtime thread.
     *
     * @return how many bytes were accepted.
     */
    fun write(source: ByteArray, offset: Int = 0, length: Int = source.size): Int {
        if (length <= 0) return 0
        val writePos = writeIndex.get()
        val room = capacity - (writePos - readIndex.get()).toInt().coerceAtLeast(0)
        val count = minOf(length, room)
        if (count <= 0) {
            dropped.addAndGet(length.toLong())
            return 0
        }
        val start = (writePos % capacity).toInt()
        val firstChunk = minOf(count, capacity - start)
        System.arraycopy(source, offset, buffer, start, firstChunk)
        if (firstChunk < count) {
            System.arraycopy(source, offset + firstChunk, buffer, 0, count - firstChunk)
        }
        // Publish only after the bytes are in place, so the consumer cannot observe a slot that is
        // still being filled.
        writeIndex.set(writePos + count)
        if (count < length) dropped.addAndGet((length - count).toLong())
        return count
    }

    /**
     * Copy up to [length] bytes into [destination]. Called from the consumer only.
     *
     * @return how many bytes were copied, which is 0 when the ring is empty.
     */
    fun read(destination: ByteArray, offset: Int = 0, length: Int = destination.size): Int {
        val readPos = readIndex.get()
        // Snapshot the producer's cursor once. Anything it publishes after this point is simply
        // read next time; what matters is never reading past what it has published.
        val count = minOf(length, (writeIndex.get() - readPos).toInt().coerceAtLeast(0))
        if (count <= 0) return 0
        val start = (readPos % capacity).toInt()
        val firstChunk = minOf(count, capacity - start)
        System.arraycopy(buffer, start, destination, offset, firstChunk)
        if (firstChunk < count) {
            System.arraycopy(buffer, 0, destination, offset + firstChunk, count - firstChunk)
        }
        // Release the space only after the bytes are out, or the producer could refill slots this
        // copy is still reading.
        readIndex.set(readPos + count)
        return count
    }

    /** Discard everything buffered, for a barge-in flush. Consumer side. */
    fun clear() {
        readIndex.set(writeIndex.get())
    }
}
