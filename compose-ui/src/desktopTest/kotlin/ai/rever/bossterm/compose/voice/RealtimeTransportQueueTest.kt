package ai.rever.bossterm.compose.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transport's two-lane queue. Protocol frames must never be evicted to make room for microphone
 * audio: losing a `function_call_output` wedges that tool round permanently, because it was already
 * settled and `response.create` then fires against a call the model never got an output for. Audio
 * is the opposite — a stale chunk is worth less than an unbounded queue.
 *
 * Correctness-critical and invisible at runtime, which is exactly the kind of invariant that
 * regresses silently, so it is pinned here rather than left to a live call.
 */
class RealtimeTransportQueueTest {

    private fun transport() = OutgoingLanes()

    @Test
    fun `audio is dropped oldest-first once its lane is full`() {
        val t = transport()
        val overflow = OutgoingLanes.AUDIO_CAPACITY + 5
        repeat(overflow) { t.offer("audio-$it", evictable = true) }

        val (protocol, audio) = t.snapshot()
        assertTrue(protocol.isEmpty(), "audio must not land in the protocol lane")
        assertEquals(OutgoingLanes.AUDIO_CAPACITY, audio.size, "the lane stays bounded")
        assertFalse(audio.contains("audio-0"), "the OLDEST chunk is the one dropped")
        assertTrue(audio.contains("audio-${overflow - 1}"), "the newest chunk is kept")
    }

    @Test
    fun `a flood of audio never evicts a queued protocol frame`() {
        val t = transport()
        t.offer("""{"type":"conversation.item.create"}""", evictable = false)
        repeat(OutgoingLanes.AUDIO_CAPACITY * 3) { t.offer("audio-$it", evictable = true) }

        val (protocol, _) = t.snapshot()
        assertEquals(
            listOf("""{"type":"conversation.item.create"}"""),
            protocol,
            "the tool result must survive any amount of audio behind it",
        )
    }

    @Test
    fun `protocol frames past their bound are refused rather than silently replacing each other`() {
        val t = transport()
        val over = OutgoingLanes.PROTOCOL_CAPACITY + 3
        repeat(over) { t.offer("""{"type":"n$it"}""", evictable = false) }

        val (protocol, _) = t.snapshot()
        assertEquals(OutgoingLanes.PROTOCOL_CAPACITY, protocol.size)
        // Oldest-first retention: unlike audio, an early frame is NOT thrown away for a later one.
        assertTrue(protocol.first().contains("n0"), "the first frame is still queued")
    }

    /**
     * The writer's exit condition, which carries the longest comment in the transport: a lingering
     * writer from a previous connect must not adopt the next socket.
     */
    @Test
    fun `only the current generation keeps writing`() {
        assertTrue(JdkRealtimeTransport.keepWriting(open = true, myGeneration = 3, currentGeneration = 3))
        assertFalse(
            JdkRealtimeTransport.keepWriting(open = true, myGeneration = 2, currentGeneration = 3),
            "a superseded writer must stop even while the socket is open",
        )
        assertFalse(JdkRealtimeTransport.keepWriting(open = false, myGeneration = 3, currentGeneration = 3))
    }
}
