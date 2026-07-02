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

    @Test
    fun `applyPropArgs sets properties, strips prop args, keeps the rest`() {
        val key = "bossterm.test.applyPropArgs"
        try {
            val rest = DaemonLauncher.applyPropArgs(arrayOf(
                "${DaemonLauncher.PROP_ARG_PREFIX}$key=hello world",
                "--other",
                "${DaemonLauncher.PROP_ARG_PREFIX}malformed-no-equals",
            ))
            assertEquals("hello world", System.getProperty(key))
            // Malformed prop args are dropped (logged), not passed through to the daemon.
            assertEquals(listOf("--other"), rest.toList())
        } finally {
            System.clearProperty(key)
        }
    }

    @Test
    fun `launcher derivation resolves every jpackage layout (pure, runs on any CI OS)`() {
        val root = java.nio.file.Files.createTempDirectory("bossterm-launcher").toFile()
        try {
            // macOS: Foo.app/Contents/runtime/Contents/Home → Contents/MacOS/Foo.
            val bundle = java.io.File(root, "Foo.app")
            val macHome = java.io.File(bundle, "Contents/runtime/Contents/Home").apply { mkdirs() }
            val macLauncher = java.io.File(bundle, "Contents/MacOS/Foo").apply {
                parentFile.mkdirs(); writeText(""); setExecutable(true)
            }
            assertEquals(macLauncher, DaemonLauncher.macLauncher(macHome.absolutePath))
            assertNull(DaemonLauncher.macLauncher(root.absolutePath), "dev JDK is not a bundle")

            // Windows: <install>\runtime → <install>\<App>.exe; the install-dir-named exe wins
            // over a stray sibling executable (add-launcher/uninstaller).
            val winInstall = java.io.File(root, "Bar").apply { mkdirs() }
            val winHome = java.io.File(winInstall, "runtime").apply { mkdirs() }
            val exe = java.io.File(winInstall, "Bar.exe").apply { writeText("") }
            java.io.File(winInstall, "helper.exe").writeText("")
            assertEquals(exe, DaemonLauncher.windowsLauncher(winHome.absolutePath))
            assertNull(DaemonLauncher.windowsLauncher(root.absolutePath), "java.home must be <install>\\runtime")

            // Linux: <install>/lib/runtime → <install>/bin/<App>; deb/rpm lowercases the install
            // dir but not the launcher, so the name preference is case-insensitive.
            val linuxInstall = java.io.File(root, "baz").apply { mkdirs() }
            val linuxHome = java.io.File(linuxInstall, "lib/runtime").apply { mkdirs() }
            val bin = java.io.File(linuxInstall, "bin/Baz").apply {
                parentFile.mkdirs(); writeText(""); setExecutable(true)
            }
            assertEquals(bin, DaemonLauncher.linuxLauncher(linuxHome.absolutePath))
            assertNull(DaemonLauncher.linuxLauncher(root.absolutePath), "java.home must be <install>/lib/runtime")

            // Several candidates, none matching the expected name → refuse (don't exec a guess).
            val ambiguous = java.io.File(root, "Qux").apply { mkdirs() }
            java.io.File(ambiguous, "runtime").mkdirs()
            java.io.File(ambiguous, "alpha.exe").writeText("")
            java.io.File(ambiguous, "beta.exe").writeText("")
            assertNull(DaemonLauncher.windowsLauncher(java.io.File(ambiguous, "runtime").absolutePath))

            // packagedLauncherBinary itself: null/blank java.home is never a packaged install.
            assertNull(DaemonLauncher.packagedLauncherBinary(null))
            assertNull(DaemonLauncher.packagedLauncherBinary(""))
        } finally {
            root.deleteRecursively()
        }
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
