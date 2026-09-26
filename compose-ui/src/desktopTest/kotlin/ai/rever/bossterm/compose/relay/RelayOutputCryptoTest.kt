package ai.rever.bossterm.compose.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelayOutputCryptoTest {
    @Test fun `native verifier reads browser crypto interoperability vector`() {
        val vector = javaClass.getResourceAsStream("/relay/output-vector.json")!!.bufferedReader().use { it.readText() }
        val json = Json.parseToJsonElement(vector).jsonObject
        val key = Json.decodeFromString<RelayOutputKey>(json.getValue("grant").toString())
        val frame = Json.decodeFromString<RelayOutput>(json.getValue("frame").toString())
        assertEquals(json.getValue("plaintext").jsonPrimitive.content,
            RelayOutputCrypto.Receiver(frame.room, frame.pane, key).decrypt(frame))
    }

    @Test fun `one encrypted frame serves independent viewers`() {
        val host = RelayOutputCrypto.Publisher("room", "pane", RelayOutputCrypto.newHostIdentity())
        val frame = host.encrypt("live", "hello terminal")
        repeat(3) { assertEquals("hello terminal", RelayOutputCrypto.Receiver("room", "pane", host.outputKey).decrypt(frame)) }
    }

    @Test fun `viewer cannot forge host output with its own signing identity`() {
        val host = RelayOutputCrypto.Publisher("room", "pane", RelayOutputCrypto.newHostIdentity())
        val other = RelayOutputCrypto.Publisher("room", "pane", RelayOutputCrypto.newHostIdentity())
        val receiver = RelayOutputCrypto.Receiver("room", "pane", host.outputKey)
        val legitimate = host.encrypt("live", "safe")
        val forged = other.encrypt("live", "forged").copy(epoch = legitimate.epoch)
        assertFailsWith<IllegalArgumentException> { receiver.decrypt(forged) }
        assertEquals("safe", receiver.decrypt(legitimate))
        assertFailsWith<IllegalArgumentException> { receiver.decrypt(legitimate) }
    }

    @Test fun `live gaps need snapshot boundary while complete previews may skip`() {
        val host = RelayOutputCrypto.Publisher("room", "pane", RelayOutputCrypto.newHostIdentity())
        val receiver = RelayOutputCrypto.Receiver("room", "pane", host.outputKey)
        host.encrypt("live", "one")
        val two = host.encrypt("live", "two")
        assertFailsWith<IllegalArgumentException> { receiver.decrypt(two) }
        receiver.applySnapshotBoundary(1)
        assertEquals("two", receiver.decrypt(two))
        host.encrypt("preview", "old screen")
        assertEquals("new screen", receiver.decrypt(host.encrypt("preview", "new screen")))
    }

    @Test fun `rotating stream key excludes revoked viewers and authenticates routing metadata`() {
        val identity = RelayOutputCrypto.newHostIdentity()
        val old = RelayOutputCrypto.Publisher("room", "pane", identity)
        val fresh = RelayOutputCrypto.Publisher("room", "pane", identity)
        val receiver = RelayOutputCrypto.Receiver("room", "pane", old.outputKey)
        assertFailsWith<IllegalArgumentException> { receiver.decrypt(fresh.encrypt("live", "private")) }
        val frame = old.encrypt("live", "original")
        assertFailsWith<IllegalArgumentException> { receiver.decrypt(frame.copy(pane = "another")) }
        assertEquals("original", receiver.decrypt(frame))
    }
}
