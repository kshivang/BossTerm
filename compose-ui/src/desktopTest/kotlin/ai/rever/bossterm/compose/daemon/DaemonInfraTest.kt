package ai.rever.bossterm.compose.daemon

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase-0 daemon-spine guards: [BossTermPaths] resolution (incl. the `bossterm.settings.dir`
 * override that keeps per-profile daemons isolated) and the [DaemonControlChannel] loopback
 * handshake — bind, port-file round-trip, secret gating, and the built-in HELLO/PING verbs.
 */
class DaemonInfraTest {

    // ---- BossTermPaths ----

    @Test
    fun `paths resolve under the settings-dir override`() = withSettingsDir { dir ->
        assertEquals(dir, BossTermPaths.dir().absolutePath)
        assertEquals("settings.json", BossTermPaths.settingsFile().name)
        assertEquals(dir, BossTermPaths.daemonPortFile().parentFile.absolutePath)
        assertTrue(BossTermPaths.dir().isDirectory, "base dir must be created")
    }

    @Test
    fun `profileTag is stable per dir and differs across dirs`() {
        val dirA = newTempDir()
        val dirB = newTempDir()
        val tagA = withSettingsDir(dirA) { BossTermPaths.profileTag() }
        val tagA2 = withSettingsDir(dirA) { BossTermPaths.profileTag() }
        val tagB = withSettingsDir(dirB) { BossTermPaths.profileTag() }
        assertEquals(tagA, tagA2, "same dir → same tag")
        assertTrue(tagA != tagB, "different dirs → different tags")
    }

    // ---- DaemonLauncher ----

    @Test
    fun `daemon main class is the Kotlin facade (Kt suffix)`() {
        // A Kotlin file-level `fun main` in DaemonMain.kt compiles to the facade class DaemonMainKt.
        // Without the Kt suffix, `java -cp … ai.rever.bossterm.app.DaemonMain` throws
        // ClassNotFoundException and the daemon never spawns (caught by the out-of-process smoke test).
        assertEquals("ai.rever.bossterm.app.DaemonMainKt", DaemonLauncher.DEFAULT_DAEMON_MAIN_CLASS)
    }

    // ---- DaemonControlChannel ----

    @Test
    fun `control channel answers HELLO and PING, writes a readable endpoint`() = withSettingsDir {
        val channel = DaemonControlChannel(version = "9.9.9", protocolVersion = 7) { verb, arg ->
            if (verb == "ECHO") "OK $arg" else "ERR unknown $verb"
        }
        try {
            val port = channel.start()
            assertTrue(port > 0)

            // Port file round-trips through readEndpoint().
            val ep = DaemonControlChannel.readEndpoint()
            assertNotNull(ep)
            assertEquals(port, ep.port)
            assertEquals("9.9.9", ep.version)
            assertEquals(7, ep.protocolVersion)
            assertEquals(64, ep.secret.length, "secret is 32 bytes hex")

            // Built-in verbs (authenticated).
            assertTrue(request(port, "${ep.secret} HELLO")!!.startsWith("OK 9.9.9 7 "))
            assertEquals("PONG", request(port, "${ep.secret} PING"))
            // Delegated verb with arg.
            assertEquals("OK hi there", request(port, "${ep.secret} ECHO hi there"))
        } finally {
            channel.stop()
        }
    }

    @Test
    fun `control channel rejects a bad secret`() = withSettingsDir {
        val channel = DaemonControlChannel(version = "1.0.0", protocolVersion = 1) { _, _ -> "OK" }
        try {
            val port = channel.start()
            // Wrong secret → server drops the connection without replying.
            assertNull(request(port, "deadbeef PING"))
            // Sanity: the real secret still works.
            val ep = DaemonControlChannel.readEndpoint()!!
            assertEquals("PONG", request(port, "${ep.secret} PING"))
        } finally {
            channel.stop()
        }
    }

    @Test
    fun `stop removes the endpoint file`() = withSettingsDir {
        val channel = DaemonControlChannel(version = "1.0.0", protocolVersion = 1) { _, _ -> "OK" }
        channel.start()
        assertNotNull(DaemonControlChannel.readEndpoint())
        channel.stop()
        assertNull(DaemonControlChannel.readEndpoint())
    }

    // ---- helpers ----

    /** One request/response over a fresh loopback socket; null when the server closed without replying. */
    private fun request(port: Int, line: String): String? =
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 2000
            OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8).apply {
                write(line); write("\n"); flush()
            }
            BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine()
        }

    private fun newTempDir(): String =
        java.nio.file.Files.createTempDirectory("bossterm-paths").toFile().absolutePath

    /** Run [block] with `bossterm.settings.dir` pointed at [dir] (a fresh temp dir if null), restoring after. */
    private fun <T> withSettingsDir(dir: String? = null, block: (String) -> T): T {
        val prev = System.getProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        val d = dir ?: newTempDir()
        System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, d)
        try {
            return block(d)
        } finally {
            if (prev != null) System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, prev)
            else System.clearProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        }
    }
}
