package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.SessionCrypto
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Public routing metadata is authenticated together with the opaque terminal payload. */
@Serializable
data class RelayOutput(
    val room: String,
    val pane: String,
    val epoch: String,
    val kind: String,
    val seq: Long,
    val payload: String,
    val signature: String,
)

/** Deliver only over an authenticated pairwise channel to an admitted viewer. */
@Serializable
data class RelayOutputKey(val epoch: String, val key: String, val hostPublicKey: String)

/**
 * One encryption and host signature per publication, regardless of viewer count. A viewer
 * knows the pane encryption key but cannot impersonate its host without the signing key.
 * Create a fresh Publisher after every revocation; never reuse an epoch's key/counters.
 */
object RelayOutputCrypto {
    private val identifier = Regex("[A-Za-z0-9_.:-]{1,128}")
    private const val MAX_PAYLOAD_BYTES = 512 * 1024
    private const val MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L

    fun newHostIdentity(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    class Publisher(private val room: String, private val pane: String, private val identity: KeyPair) {
        private val epoch = UUID.randomUUID().toString()
        private val secret = SessionCrypto.newSessionSecret()
        private val counters = mutableMapOf("live" to 0L, "preview" to 0L)
        val liveSequence: Long get() = synchronized(this) { counters.getValue("live") }
        val outputKey get() = RelayOutputKey(epoch, encode(secret), encode(identity.public.encoded))

        init { require(identifier.matches(room) && identifier.matches(pane)) }

        @Synchronized
        fun encrypt(kind: String, plaintext: String): RelayOutput {
            require(kind in counters)
            val bytes = plaintext.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_PAYLOAD_BYTES)
            val seq = counters.getValue(kind) + 1
            check(seq <= MAX_SAFE_SEQUENCE) { "Rotate stream key before sequence exhaustion" }
            counters[kind] = seq
            val meta = RelayOutput(room, pane, epoch, kind, seq, "", "")
            val aad = associatedData(meta)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(secret, epoch, kind), GCMParameterSpec(128, nonce(seq)))
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(bytes)
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(identity.private)
            signer.update(aad)
            signer.update(encrypted)
            return meta.copy(payload = encode(encrypted), signature = encode(signer.sign()))
        }
    }

    class Receiver(private val room: String, private val pane: String, private val grant: RelayOutputKey) {
        private val secret = decode(grant.key).also { require(it.size == 32) }
        private val host: PublicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(decode(grant.hostPublicKey)))
        private val counters = mutableMapOf("live" to 0L, "preview" to 0L)

        init { require(identifier.matches(room) && identifier.matches(pane) && identifier.matches(grant.epoch)) }

        /** Called only after a pairwise-authenticated snapshot and its sequence boundary apply. */
        @Synchronized
        fun applySnapshotBoundary(sequence: Long) {
            require(sequence in counters.getValue("live")..MAX_SAFE_SEQUENCE)
            counters["live"] = sequence
        }

        @Synchronized
        fun decrypt(frame: RelayOutput): String {
            require(frame.room == room && frame.pane == pane && frame.epoch == grant.epoch)
            require(frame.kind in counters && frame.seq in 1..MAX_SAFE_SEQUENCE)
            require(frame.payload.length <= (MAX_PAYLOAD_BYTES + 16) * 4 / 3 + 4)
            val previous = counters.getValue(frame.kind)
            require(frame.seq > previous) { "Replayed output" }
            require(frame.kind == "preview" || frame.seq == previous + 1) { "Output gap; snapshot required" }
            val aad = associatedData(frame)
            val encrypted = decode(frame.payload)
            require(encrypted.size in 16..MAX_PAYLOAD_BYTES + 16)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(host)
            verifier.update(aad)
            verifier.update(encrypted)
            require(verifier.verify(decode(frame.signature))) { "Invalid host signature" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(secret, grant.epoch, frame.kind), GCMParameterSpec(128, nonce(frame.seq)))
            cipher.updateAAD(aad)
            val plaintext = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            counters[frame.kind] = frame.seq
            return plaintext
        }
    }

    private fun key(secret: ByteArray, epoch: String, kind: String) = SecretKeySpec(
        SessionCrypto.hkdf(secret, epoch.toByteArray(Charsets.UTF_8), "bossterm-relay-v1/$kind".toByteArray(Charsets.UTF_8), 32), "AES",
    )
    private fun nonce(sequence: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()
    private fun associatedData(frame: RelayOutput) =
        "bossterm-relay-v1\n${frame.room}\n${frame.pane}\n${frame.epoch}\n${frame.kind}\n${frame.seq}\n".toByteArray(Charsets.UTF_8)
    private fun encode(bytes: ByteArray) = SessionCrypto.encodeSecretB64Url(bytes)
    private fun decode(value: String) = SessionCrypto.decodeSecretB64Url(value)
}
