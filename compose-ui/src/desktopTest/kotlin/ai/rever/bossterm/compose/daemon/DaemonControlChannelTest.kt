package ai.rever.bossterm.compose.daemon

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DaemonControlChannel] request handling: the HELLO/PING handshake, and — the key safety property —
 * a request line that exceeds the cap with no newline is dropped WITHOUT first buffering the whole
 * thing into memory (a local pre-auth peer must not be able to OOM the daemon), while the channel
 * stays responsive to legitimate requests afterward.
 */
class DaemonControlChannelTest {

    @Test
    fun `answers PING with the secret and stays responsive after an over-cap line`() = withSettingsDir {
        val channel = DaemonControlChannel("1.0.0", DaemonControlChannel.PROTOCOL_VERSION) { _, _ -> "OK" }
        val port = channel.start()
        val secret = channel.secretValue
        try {
            assertEquals("PONG", request(port, "$secret PING\n"), "valid PING should PONG")

            // Over-cap line with NO newline: the server must shed it (no response) without OOM.
            val flood = "x".repeat(200_000) // >> MAX_REQUEST_CHARS, no '\n'
            assertNull(request(port, flood, expectResponse = false), "an over-cap newline-less line must get no response")

            // The daemon is still alive and serving (it didn't OOM or wedge the accept loop).
            assertEquals("PONG", request(port, "$secret PING\n"), "channel must stay responsive after the flood")
        } finally {
            channel.stop()
        }
    }

    @Test
    fun `rejects a bad secret without invoking the handler`() = withSettingsDir {
        val handlerCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val channel = DaemonControlChannel("1.0.0", DaemonControlChannel.PROTOCOL_VERSION) { _, _ ->
            handlerCalls.incrementAndGet(); "OK"
        }
        val port = channel.start()
        try {
            assertNull(request(port, "wrong-secret STATUS\n", expectResponse = false), "bad secret → no response")
            Thread.sleep(200)
            assertEquals(0, handlerCalls.get(), "the handler must not run for an unauthenticated request")
        } finally {
            channel.stop()
        }
    }

    // ---- helpers ----

    /** Send [payload] to the channel; return the single response line, or null if none / on error. */
    private fun request(port: Int, payload: String, expectResponse: Boolean = true): String? =
        runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = if (expectResponse) 3000 else 1500
                s.getOutputStream().apply { write(payload.toByteArray(StandardCharsets.UTF_8)); flush() }
                BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine()?.trim()
            }
        }.getOrNull()

    private fun newTempDir(): String =
        java.nio.file.Files.createTempDirectory("bossterm-control").toFile().absolutePath

    private fun <T> withSettingsDir(block: () -> T): T {
        val prev = System.getProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, newTempDir())
        try {
            return block()
        } finally {
            if (prev != null) System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, prev)
            else System.clearProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        }
    }
}
