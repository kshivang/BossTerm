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

    private fun transport() = JdkRealtimeTransport().apply { openForTest() }

    @Test
    fun `audio is dropped oldest-first once its lane is full`() {
        val t = transport()
        val overflow = JdkRealtimeTransport.OUTGOING_AUDIO_CAPACITY + 5
        repeat(overflow) { t.send("audio-$it", evictable = true) }

        val (protocol, audio) = t.queuedForTest()
        assertTrue(protocol.isEmpty(), "audio must not land in the protocol lane")
        assertEquals(JdkRealtimeTransport.OUTGOING_AUDIO_CAPACITY, audio.size, "the lane stays bounded")
        assertFalse(audio.contains("audio-0"), "the OLDEST chunk is the one dropped")
        assertTrue(audio.contains("audio-${overflow - 1}"), "the newest chunk is kept")
    }

    @Test
    fun `a flood of audio never evicts a queued protocol frame`() {
        val t = transport()
        t.send("""{"type":"conversation.item.create"}""")
        repeat(JdkRealtimeTransport.OUTGOING_AUDIO_CAPACITY * 3) { t.send("audio-$it", evictable = true) }

        val (protocol, _) = t.queuedForTest()
        assertEquals(
            listOf("""{"type":"conversation.item.create"}"""),
            protocol,
            "the tool result must survive any amount of audio behind it",
        )
    }

    @Test
    fun `protocol frames past their bound are refused rather than silently replacing each other`() {
        val t = transport()
        val over = JdkRealtimeTransport.OUTGOING_CAPACITY + 3
        repeat(over) { t.send("""{"type":"n$it"}""") }

        val (protocol, _) = t.queuedForTest()
        assertEquals(JdkRealtimeTransport.OUTGOING_CAPACITY, protocol.size)
        // Oldest-first retention: unlike audio, an early frame is NOT thrown away for a later one.
        assertTrue(protocol.first().contains("n0"), "the first frame is still queued")
    }

    @Test
    fun `nothing is queued before the socket is up`() {
        val t = JdkRealtimeTransport() // deliberately not openForTest()
        t.send("""{"type":"session.update"}""")
        t.send("audio", evictable = true)
        val (protocol, audio) = t.queuedForTest()
        assertTrue(protocol.isEmpty() && audio.isEmpty(), "a closed transport drops sends")
    }
}
