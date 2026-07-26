package ai.rever.bossterm.compose.voice

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

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

    /** @return false only when a GUARANTEED frame could not be queued. */
    fun offer(json: String, evictable: Boolean): Boolean {
        if (evictable) {
            // Drop the oldest audio when the socket can't keep up: stale mic chunks are worth less
            // than an unbounded queue, and losing one is inaudible.
            if (!audio.offer(json)) {
                audio.poll()
                audio.offer(json)
            }
            return true
        }
        return protocol.offer(json)
    }

    /** Protocol first, then audio: a backlog of mic chunks must not delay a tool result. */
    fun poll(timeout: Long, unit: TimeUnit): String? = protocol.poll() ?: audio.poll(timeout, unit)

    fun clear() {
        protocol.clear()
        audio.clear()
    }

    /** Snapshot for assertions: (protocol, audio). */
    fun snapshot(): Pair<List<String>, List<String>> = protocol.toList() to audio.toList()

    companion object {
        /** Protocol frames in flight; small because they are answered promptly. */
        const val PROTOCOL_CAPACITY = 64

        /** ~10s of mic frames; beyond that the socket is stalled and old audio is worthless. */
        const val AUDIO_CAPACITY = 256
    }
}
