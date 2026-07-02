package ai.rever.bossterm.compose.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Roundtrip + malformed-input coverage for [DaemonAttachProtocol.BinaryFrame] — the v3 binary
 * transport for Output/Snapshot. Payload fidelity matters most for escape-dense terminal bytes
 * (the whole point of leaving JSON) and multi-byte UTF-8.
 */
class DaemonAttachBinaryFrameTest {

    private val sessionId = "550e8400-e29b-41d4-a716-446655440000" // 36-char UUID, like real ids

    @Test
    fun `output frame roundtrips escape-dense and multi-byte payloads`() {
        val payloads = listOf(
            "plain text",
            "\u001b[31mred\u001b[0m\r\n\u001b]0;title\u0007", // SGR + OSC + control chars
            "emoji 👩‍💻 and combining éü", // multi-byte UTF-8 + ZWJ sequence
            "", // empty payload is legal on the wire (producers filter it, decoder must not care)
        )
        for (p in payloads) {
            val decoded = DaemonAttachProtocol.BinaryFrame.decode(
                DaemonAttachProtocol.BinaryFrame.encodeOutput(sessionId, p),
            )
            assertEquals(DaemonAttachProtocol.Server.Output(sessionId, p), decoded, "payload: ${p.take(20)}")
        }
    }

    @Test
    fun `snapshot frame roundtrips id, grid and payload`() {
        val data = "\u001b[0mhello\r\nworld\u001b[5;10H"
        val decoded = DaemonAttachProtocol.BinaryFrame.decode(
            DaemonAttachProtocol.BinaryFrame.encodeSnapshot(sessionId, cols = 1999, rows = 3, data = data),
        )
        assertEquals(DaemonAttachProtocol.Server.Snapshot(sessionId, data, cols = 1999, rows = 3), decoded)
    }

    @Test
    fun `malformed or unknown frames decode to null, not an exception`() {
        assertNull(DaemonAttachProtocol.BinaryFrame.decode(ByteArray(0)), "empty")
        assertNull(DaemonAttachProtocol.BinaryFrame.decode(byteArrayOf(1)), "type only")
        assertNull(DaemonAttachProtocol.BinaryFrame.decode(byteArrayOf(99, 4, 1, 2, 3, 4)), "unknown type")
        // Output frame whose declared id length exceeds the frame.
        assertNull(DaemonAttachProtocol.BinaryFrame.decode(byteArrayOf(1, 50, 65, 66)), "truncated id")
        // Snapshot frame with a valid id but missing the 4 grid bytes.
        val id = "ab".toByteArray()
        assertNull(
            DaemonAttachProtocol.BinaryFrame.decode(byteArrayOf(2, 2) + id + byteArrayOf(0, 80)),
            "truncated snapshot grid",
        )
    }
}
