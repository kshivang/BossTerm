package ai.rever.bossterm.compose.voice

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The Realtime socket's two outbound lanes.
 *
 * Protocol frames are guaranteed; microphone audio is droppable. One mixed drop-oldest queue would
 * evict a queued `function_call_output` as readily as a stale mic chunk — and losing that wedges the
 * tool round permanently, because it was already settled locally, so `response.create` fires against
 * a call the model never got an output for.
 *
 * Its own class so the invariant is testable without a socket (it was previously only reachable
 * through test-only seams on the transport itself).
 */
internal class OutgoingLanes {

    private val protocol = ArrayBlockingQueue<String>(PROTOCOL_CAPACITY)
    private val audio = ArrayBlockingQueue<String>(AUDIO_CAPACITY)

    /**
     * One monitor across both lanes, so a waiting consumer wakes on whichever lane fills.
     *
     * Waiting on the audio queue instead (`protocol.poll() ?: audio.poll(timeout)`) was correct on
     * priority but slow in exactly the wrong case: while MUTED nothing enqueues audio, so the writer
     * parked for the full timeout and a protocol frame that arrived a moment later waited it out —
     * ~400ms for a `function_call_output` + `response.create` round, with the user sitting there
     * waiting for the agent. The queues stay separate; only the blocking does not.
     */
    private val lock = ReentrantLock()
    private val arrival = lock.newCondition()

    /** @return false only when a GUARANTEED frame could not be queued. */
    fun offer(json: String, evictable: Boolean): Boolean = lock.withLock {
        if (evictable) {
            // Drop the oldest audio when the socket can't keep up: stale mic chunks are worth less
            // than an unbounded queue, and losing one is inaudible.
            if (!audio.offer(json)) {
                audio.poll()
                audio.offer(json)
            }
            arrival.signal()
            return true
        }
        val queued = protocol.offer(json)
        if (queued) arrival.signal()
        return queued
    }

    /** Protocol first, then audio: a backlog of mic chunks must not delay a tool result. */
    fun poll(timeout: Long, unit: TimeUnit): String? {
        var remaining = unit.toNanos(timeout)
        lock.withLock {
            while (true) {
                protocol.poll()?.let { return it }
                audio.poll()?.let { return it }
                if (remaining <= 0L) return null
                remaining = arrival.awaitNanos(remaining)
            }
        }
    }

    fun clear() = lock.withLock {
        protocol.clear()
        audio.clear()
    }

    /** Snapshot for assertions: (protocol, audio). */
    fun snapshot(): Pair<List<String>, List<String>> =
        lock.withLock { protocol.toList() to audio.toList() }

    companion object {
        /** Protocol frames in flight; small because they are answered promptly. */
        const val PROTOCOL_CAPACITY = 64

        /** ~10s of mic frames; beyond that the socket is stalled and old audio is worthless. */
        const val AUDIO_CAPACITY = 256
    }
}
