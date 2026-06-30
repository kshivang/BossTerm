package ai.rever.bossterm.compose.daemon

import java.io.RandomAccessFile
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards for [DaemonClient]'s discovery/handshake: it attaches to a live, protocol-compatible daemon;
 * cleans up a stale `daemon.port` left by a `kill -9`'d daemon; refuses an incompatible-proto daemon
 * (without deleting its file); and takes the "another process is spawning — wait" branch when the
 * single-spawn lock is already held, picking up the daemon that the other process brings up.
 */
class DaemonClientTest {

    @Test
    fun `attaches to a live, compatible daemon (no spawn)`() = withSettingsDir {
        val channel = DaemonControlChannel("4.5.6", DaemonControlChannel.PROTOCOL_VERSION) { _, _ -> "OK" }
        channel.start()
        try {
            val ep = DaemonClient().ensureConnected(spawnIfAbsent = false)
            assertNotNull(ep)
            assertEquals("4.5.6", ep.version)
            assertEquals(DaemonControlChannel.PROTOCOL_VERSION, ep.protocolVersion)
        } finally {
            channel.stop()
        }
    }

    @Test
    fun `stale port file (dead daemon) is deleted, no attach`() = withSettingsDir {
        val deadPort = freeEphemeralPort()
        val file = BossTermPaths.daemonPortFile()
        file.writeText("$deadPort\ndeadbeefsecret\n9.9.9 ${DaemonControlChannel.PROTOCOL_VERSION}\n", StandardCharsets.UTF_8)

        val ep = DaemonClient().ensureConnected(spawnIfAbsent = false)
        assertNull(ep, "nothing is listening, so no attach")
        assertFalse(file.exists(), "stale daemon.port must be cleaned up")
    }

    @Test
    fun `incompatible-proto live daemon is refused but its file is kept`() = withSettingsDir {
        val channel = DaemonControlChannel("9.9.9", DaemonControlChannel.PROTOCOL_VERSION + 1) { _, _ -> "OK" }
        channel.start()
        try {
            val ep = DaemonClient().ensureConnected(spawnIfAbsent = false)
            assertNull(ep, "proto mismatch → do not attach")
            // The daemon is alive (ping succeeds), so its port file is NOT treated as stale.
            assertTrue(BossTermPaths.daemonPortFile().exists(), "a live daemon's port file is kept")
        } finally {
            channel.stop()
        }
    }

    @Test
    fun `when the spawn lock is held, waits for the daemon the other process brings up`() = withSettingsDir {
        // Hold the single-spawn lock as if another GUI were mid-spawn. In the same JVM, DaemonClient's
        // own tryLock on this file throws OverlappingFileLockException → it takes the "wait" branch.
        val raf = RandomAccessFile(BossTermPaths.daemonLockFile(), "rw")
        val lock = raf.channel.tryLock()
        assertNotNull(lock, "test must own the spawn lock")

        // Shortly after, the "other process" brings a compatible daemon up (writes a live port file).
        val channel = DaemonControlChannel("1.2.3", DaemonControlChannel.PROTOCOL_VERSION) { _, _ -> "OK" }
        val spawner = thread(name = "test-daemon-spawner") {
            runCatching { Thread.sleep(300) }
            channel.start()
        }
        try {
            val ep = DaemonClient().ensureConnected(spawnIfAbsent = true)
            assertNotNull(ep, "should pick up the daemon brought up while we held the lock")
            assertEquals("1.2.3", ep.version)
        } finally {
            spawner.join()
            channel.stop()
            runCatching { lock.release() }
            runCatching { raf.close() }
        }
    }

    // ---- helpers ----

    /** A currently-free loopback port number (the socket is closed before returning). */
    private fun freeEphemeralPort(): Int = ServerSocket(0).use { it.localPort }

    private fun newTempDir(): String =
        java.nio.file.Files.createTempDirectory("bossterm-client").toFile().absolutePath

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
