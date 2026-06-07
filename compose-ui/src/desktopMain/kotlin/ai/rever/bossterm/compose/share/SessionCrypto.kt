package ai.rever.bossterm.compose.share

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for session sharing.
 *
 * The relay (e.g. a Cloudflare quick-tunnel) terminates TLS, so `wss` alone is not
 * end-to-end — the relay sees plaintext. We close that gap with a pre-shared secret that
 * travels in the share link's **URL fragment** (`#k=…`), which browsers never transmit to
 * any server, so the relay never sees it. From that secret each WebSocket connection
 * derives fresh AES-256-GCM keys (HKDF-SHA256) and every frame is encrypted.
 *
 * Wire format of an encrypted frame (binary WebSocket frame):
 *   nonce(12) || AES-256-GCM(ciphertext) || tag(16),  AAD = 1 direction byte.
 *
 * This mirrors exactly what the browser viewer does with WebCrypto (`crypto.subtle`):
 * HKDF with `SHA-256`, info labels `bossterm-{c2s,s2c,kc}-v1`, and `AES-GCM` with a 12-byte
 * IV / 128-bit tag / `additionalData` = the direction byte. Keep the two implementations in
 * lock-step — `SessionCryptoTest` pins a cross-impl vector to catch divergence.
 */
object SessionCrypto {
    const val NONCE_LEN = 12
    const val TAG_BITS = 128
    const val KEY_LEN = 32

    /** AAD direction tags — bind each ciphertext to its direction so it can't be reflected. */
    const val DIR_C2S: Byte = 0x00 // client → host
    const val DIR_S2C: Byte = 0x01 // host → client

    private val rng = SecureRandom()
    private val b64UrlEnc = Base64.getUrlEncoder().withoutPadding()
    private val b64UrlDec = Base64.getUrlDecoder()

    /** A fresh 32-byte session secret (the `#k=` fragment value), per share. */
    fun newSessionSecret(): ByteArray = ByteArray(KEY_LEN).also { rng.nextBytes(it) }

    fun encodeSecretB64Url(secret: ByteArray): String = b64UrlEnc.encodeToString(secret)
    fun decodeSecretB64Url(s: String): ByteArray = b64UrlDec.decode(s)

    fun randomSalt(): ByteArray = ByteArray(16).also { rng.nextBytes(it) }

    /**
     * HKDF-SHA256 (RFC 5869): extract then expand. Matches WebCrypto `deriveBits({name:"HKDF",
     * hash:"SHA-256", salt, info}, key, len*8)` — extract is deterministic, so deriving each
     * label separately on the JS side yields identical bytes.
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm) // extract
        val out = ByteArray(len)
        var pos = 0
        var counter = 1
        var prev = ByteArray(0)
        while (pos < len) {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            mac.update(prev); mac.update(info); mac.update(counter.toByte())
            prev = mac.doFinal()
            val n = minOf(prev.size, len - pos)
            System.arraycopy(prev, 0, out, pos, n)
            pos += n; counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            // An all-zero key is invalid for HmacSHA256 init; HKDF allows an empty salt, but we
            // always pass a non-empty salt here, so a plain SecretKeySpec is fine.
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        }.doFinal(msg)

    /** Per-connection keys + a key-confirmation tag, derived from the session secret + both salts. */
    class DerivedKeys(val kC2s: ByteArray, val kS2c: ByteArray, val confirm: ByteArray) {
        val confirmB64: String get() = b64UrlEnc.encodeToString(confirm)
    }

    fun deriveKeys(secret: ByteArray, saltC: ByteArray, saltS: ByteArray): DerivedKeys {
        val salt = saltC + saltS
        return DerivedKeys(
            kC2s = hkdf(secret, salt, "bossterm-c2s-v1".toByteArray(Charsets.UTF_8), KEY_LEN),
            kS2c = hkdf(secret, salt, "bossterm-s2c-v1".toByteArray(Charsets.UTF_8), KEY_LEN),
            confirm = hkdf(secret, salt, "bossterm-kc-v1".toByteArray(Charsets.UTF_8), KEY_LEN),
        )
    }

    /** Constant-time compare for the key-confirmation tag (b64url). */
    fun confirmMatches(expected: ByteArray, gotB64: String?): Boolean {
        val got = runCatching { b64UrlDec.decode(gotB64 ?: return false) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, got)
    }

    /**
     * Encrypts/decrypts one direction's frames with a fixed key + AAD direction byte. Not
     * thread-safe across directions — use one instance per direction per connection (the host
     * uses two, the clients each use two). Cipher is constructed per call (hardware AES → cheap).
     */
    class FrameCipher(key: ByteArray, private val dir: Byte) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val aad = byteArrayOf(dir)

        fun encrypt(plaintextUtf8: String): ByteArray {
            val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val body = cipher.doFinal(plaintextUtf8.toByteArray(Charsets.UTF_8)) // ciphertext||tag
            return nonce + body
        }

        /** @throws javax.crypto.AEADBadTagException on auth failure (wrong key / tamper). */
        fun decrypt(frame: ByteArray): String {
            require(frame.size > NONCE_LEN) { "frame too short" }
            val nonce = frame.copyOfRange(0, NONCE_LEN)
            val body = frame.copyOfRange(NONCE_LEN, frame.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            return String(cipher.doFinal(body), Charsets.UTF_8)
        }
    }

    /**
     * Short, human-comparable fingerprint of a session secret (first 8 hex of SHA-256). Shown
     * on the host + each endpoint; matching codes confirm the same untampered key end-to-end.
     */
    fun fingerprint(secret: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(secret)
            .take(4).joinToString("") { "%02x".format(it) }
}
