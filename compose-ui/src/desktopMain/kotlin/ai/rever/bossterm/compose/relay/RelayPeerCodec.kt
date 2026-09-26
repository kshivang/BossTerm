package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.SessionCrypto
import io.ktor.websocket.Frame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Opaque pairwise frames are fragmented below encryption, never interpreted by the relay. */
@Serializable
internal data class RelayPeerPacket(
    val id: String,
    val part: Int,
    val count: Int,
    val binary: Boolean,
    val data: String,
    val token: String? = null,
    val credit: Boolean = false,
)

internal object RelayPeerCodec {
    const val MAX_FRAME_BYTES = 1024 * 1024
    const val MAX_GRAPHICS_FRAME_BYTES = 32 * 1024 * 1024
    private const val CHUNK_BYTES = 24 * 1024
    val json = Json { ignoreUnknownKeys = true }

    fun encode(frame: Frame, token: String? = null, single: Boolean = false): List<String> {
        return packets(frame, token, single).map { json.encodeToString(RelayPeerPacket.serializer(), it) }.toList()
    }

    fun packets(frame: Frame, token: String? = null, single: Boolean = false, credited: Boolean = false): Sequence<RelayPeerPacket> {
        require(frame is Frame.Binary || frame is Frame.Text)
        require(frame.data.size <= if (credited) MAX_GRAPHICS_FRAME_BYTES else MAX_FRAME_BYTES)
        val chunk = if (single) MAX_FRAME_BYTES else CHUNK_BYTES
        val count = maxOf(1, (frame.data.size + chunk - 1) / chunk)
        val id = UUID.randomUUID().toString()
        return (0 until count).asSequence().map { part ->
            val data = frame.data.copyOfRange(part * chunk, minOf(frame.data.size, (part + 1) * chunk))
            RelayPeerPacket(id, part, count, frame is Frame.Binary,
                SessionCrypto.encodeSecretB64Url(data), if (part == 0) token else null, credited)
        }
    }

    class Decoder(private val maximumBytes: Int = MAX_FRAME_BYTES) {
        private var current: RelayPeerPacket? = null
        private var next = 0
        private var bytes = ByteArrayOutputStream()
        fun accept(packet: RelayPeerPacket): Frame? {
            require(packet.id.length <= 64 && packet.count in 1..((maximumBytes + CHUNK_BYTES - 1) / CHUNK_BYTES) && packet.part in 0 until packet.count)
            require(packet.data.length <= (MAX_FRAME_BYTES * 4 / 3) + 4)
            if (current == null) { require(packet.part == 0); current = packet; next = 0 }
            val first = current!!
            require(first.id == packet.id && first.count == packet.count && first.binary == packet.binary && packet.part == next)
            val chunk = SessionCrypto.decodeSecretB64Url(packet.data)
            require(bytes.size() + chunk.size <= maximumBytes)
            bytes.write(chunk); next++
            if (next != packet.count) return null
            val data = bytes.toByteArray()
            current = null; bytes = ByteArrayOutputStream()
            return if (first.binary) Frame.Binary(true, data) else Frame.Text(String(data, Charsets.UTF_8))
        }
    }
}
