package ai.rever.bossterm.compose.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import javax.crypto.AEADBadTagException

/**
 * Covers [SessionCrypto]: the HKDF derivation (pinned to an RFC 5869 vector so it can't silently
 * diverge from the browser's WebCrypto HKDF), AES-GCM round-trips, and the wrong-key / wrong-
 * direction failure paths the handshake relies on.
 */
class SessionCryptoTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        // The canonical HKDF-SHA256 vector — WebCrypto's deriveBits({name:"HKDF",hash:"SHA-256"})
        // produces the same OKM, so this pins Kotlin↔JS key derivation parity.
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = SessionCrypto.hkdf(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `deriveKeys are distinct, deterministic, and salt-sensitive`() {
        val secret = SessionCrypto.newSessionSecret()
        val sc = SessionCrypto.randomSalt()
        val ss = SessionCrypto.randomSalt()
        val a = SessionCrypto.deriveKeys(secret, sc, ss)
        val b = SessionCrypto.deriveKeys(secret, sc, ss)
        // c2s != s2c (different info labels)
        assertFalse(a.kC2s.contentEquals(a.kS2c))
        // deterministic for the same inputs
        assertTrue(a.kC2s.contentEquals(b.kC2s) && a.kS2c.contentEquals(b.kS2c) && a.confirm.contentEquals(b.confirm))
        // different salts → different keys
        val c = SessionCrypto.deriveKeys(secret, SessionCrypto.randomSalt(), ss)
        assertFalse(a.kC2s.contentEquals(c.kC2s))
    }

    @Test
    fun `frame cipher round-trips both directions incl multibyte`() {
        val keys = SessionCrypto.deriveKeys(SessionCrypto.newSessionSecret(), SessionCrypto.randomSalt(), SessionCrypto.randomSalt())
        val payload = "{\"t\":\"paneOutput\",\"data\":\"héllo [31mworld[0m 🌍 \"}"
        // c2s
        val c2s = SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_C2S)
        assertEquals(payload, c2s.decrypt(c2s.encrypt(payload)))
        // s2c
        val s2c = SessionCrypto.FrameCipher(keys.kS2c, SessionCrypto.DIR_S2C)
        assertEquals(payload, s2c.decrypt(s2c.encrypt(payload)))
        // nonce is random → two encryptions of the same text differ
        assertFalse(c2s.encrypt(payload).contentEquals(c2s.encrypt(payload)))
    }

    @Test
    fun `wrong key fails authentication`() {
        val k1 = SessionCrypto.deriveKeys(SessionCrypto.newSessionSecret(), SessionCrypto.randomSalt(), SessionCrypto.randomSalt())
        val k2 = SessionCrypto.deriveKeys(SessionCrypto.newSessionSecret(), SessionCrypto.randomSalt(), SessionCrypto.randomSalt())
        val frame = SessionCrypto.FrameCipher(k1.kS2c, SessionCrypto.DIR_S2C).encrypt("secret output")
        assertFailsWith<AEADBadTagException> {
            SessionCrypto.FrameCipher(k2.kS2c, SessionCrypto.DIR_S2C).decrypt(frame)
        }
    }

    @Test
    fun `wrong direction AAD fails authentication`() {
        val keys = SessionCrypto.deriveKeys(SessionCrypto.newSessionSecret(), SessionCrypto.randomSalt(), SessionCrypto.randomSalt())
        // Encrypt as c2s, try to decrypt with a c2s-keyed cipher but s2c direction byte.
        val frame = SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_C2S).encrypt("x")
        assertFailsWith<AEADBadTagException> {
            SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_S2C).decrypt(frame)
        }
    }

    @Test
    fun `confirm tag matches only for the right secret`() {
        val secret = SessionCrypto.newSessionSecret()
        val sc = SessionCrypto.randomSalt(); val ss = SessionCrypto.randomSalt()
        val host = SessionCrypto.deriveKeys(secret, sc, ss)
        val client = SessionCrypto.deriveKeys(secret, sc, ss)
        assertTrue(SessionCrypto.confirmMatches(client.confirm, host.confirmB64))
        val wrong = SessionCrypto.deriveKeys(SessionCrypto.newSessionSecret(), sc, ss)
        assertFalse(SessionCrypto.confirmMatches(wrong.confirm, host.confirmB64))
        assertFalse(SessionCrypto.confirmMatches(client.confirm, null))
    }

    @Test
    fun `secret b64url round-trips and fingerprint is stable`() {
        val s = SessionCrypto.newSessionSecret()
        assertTrue(s.contentEquals(SessionCrypto.decodeSecretB64Url(SessionCrypto.encodeSecretB64Url(s))))
        assertEquals(SessionCrypto.fingerprint(s), SessionCrypto.fingerprint(s))
        assertEquals(8, SessionCrypto.fingerprint(s).length)
        assertNotEquals(SessionCrypto.fingerprint(s), SessionCrypto.fingerprint(SessionCrypto.newSessionSecret()))
    }
}
