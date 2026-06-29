package ai.rever.bossterm.compose.auth

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Single-owner / forwarding state machine for [DeepLinkSocket] — the most intricate new auth code
 * and the source of two prior bugs (last-launch-wins routing, premature teardown). Drives the real
 * loopback sockets through an injectable port file: claim, forward round-trip, stale cleanup,
 * no-usurp, and the secret + scheme guards on the listener.
 */
class DeepLinkSocketTest {

    private lateinit var tmpDir: File
    private lateinit var originalPortFile: File

    @BeforeTest
    fun setUp() {
        originalPortFile = DeepLinkSocket.portFile
        tmpDir = java.nio.file.Files.createTempDirectory("deeplink-test").toFile()
        DeepLinkSocket.portFile = File(tmpDir, "deeplink.port")
    }

    @AfterTest
    fun tearDown() {
        DeepLinkSocket.stop()            // closes the loopback socket; the accept loop then exits
        DeepLinkSocket.portFile = originalPortFile
        tmpDir.deleteRecursively()
    }

    private fun collector(latch: CountDownLatch, sink: MutableList<String>): (String) -> Unit =
        { uri -> sink.add(uri); latch.countDown() }

    @Test
    fun `claims ownership when no listener exists and forwards a deep link`() {
        val sink = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        DeepLinkSocket.startPrimaryListener(collector(latch, sink))
        assertTrue(DeepLinkSocket.portFile.isFile, "primary should advertise a port file")

        val uri = "bossterm://auth/verify?token_hash=abc&type=magiclink"
        assertTrue(DeepLinkSocket.tryForward(uri), "forward to a live listener should succeed")
        assertTrue(latch.await(3, TimeUnit.SECONDS), "listener should receive the forwarded uri")
        assertEquals(listOf(uri), sink.toList())
    }

    @Test
    fun `tryForward returns false when there is no port file`() {
        assertFalse(DeepLinkSocket.portFile.exists())
        assertFalse(DeepLinkSocket.tryForward("bossterm://auth/verify?token_hash=x"))
    }

    @Test
    fun `tryForward deletes a stale port file and returns false`() {
        // Advertise a port nothing listens on: bind then close to obtain a free (refused) port.
        val freePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        DeepLinkSocket.portFile.writeText("$freePort\ndeadbeefdeadbeefdeadbeefdeadbeef\n")
        assertFalse(DeepLinkSocket.tryForward("bossterm://auth/verify?token_hash=x"))
        assertFalse(DeepLinkSocket.portFile.exists(), "a stale port file must be cleared")
    }

    @Test
    fun `does not usurp a live listener`() {
        DeepLinkSocket.startPrimaryListener { }
        val claimed = DeepLinkSocket.portFile.readText()
        // A second launch must detect the live owner and leave the advertised port untouched.
        DeepLinkSocket.startPrimaryListener { }
        assertEquals(claimed, DeepLinkSocket.portFile.readText(), "second launch must not overwrite routing")
    }

    @Test
    fun `rejects a forward presenting the wrong secret`() {
        val sink = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        DeepLinkSocket.startPrimaryListener(collector(latch, sink))
        val port = DeepLinkSocket.portFile.readLines()[0].trim().toInt()

        // Hand-craft a connection with a bogus secret — must be ignored by the constant-time check.
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().write("wrongsecret bossterm://auth/verify?token_hash=evil\n".toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
        }
        // A legitimate forward afterward should be the ONLY thing routed.
        val good = "bossterm://auth/verify?token_hash=good"
        assertTrue(DeepLinkSocket.tryForward(good))
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(good), sink.toList(), "the wrong-secret line must not be routed")
    }

    @Test
    fun `listener ignores a non-bossterm uri`() {
        val sink = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        DeepLinkSocket.startPrimaryListener(collector(latch, sink))
        val lines = DeepLinkSocket.portFile.readLines()
        val port = lines[0].trim().toInt()
        val secret = lines[1].trim()

        // Correct secret but a non-bossterm scheme — must be dropped by the scheme filter.
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().write("$secret http://evil.example/exfil\n".toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
        }
        val good = "bossterm://auth/verify?token_hash=good"
        assertTrue(DeepLinkSocket.tryForward(good))
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(good), sink.toList(), "a non-bossterm uri must not be routed")
    }
}
