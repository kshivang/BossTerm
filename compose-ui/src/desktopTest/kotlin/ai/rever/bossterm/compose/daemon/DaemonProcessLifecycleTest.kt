package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonProcessLifecycleTest {
    @Test
    fun `daemon survives SIGINT sent to the launching process group`() {
        if (ShellCustomizationUtils.isWindows()) return
        val report = Files.createTempFile("daemon-detach", ".txt").toFile().apply { delete() }
        // Gradle's test worker appends its test classpath to a non-URL system classloader.
        val classpath = listOf(
            DaemonProcessLifecycleProbe::class.java, DaemonProcessLifecycle::class.java,
            Native::class.java, org.slf4j.LoggerFactory::class.java, kotlin.Unit::class.java,
        ).map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct().joinToString(File.pathSeparator)
        val controller = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath, DaemonProcessLifecycleProbe::class.java.name, "controller", report.absolutePath,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        var daemon: ProcessHandle? = null
        try {
            assertTrue(controller.waitFor(10, TimeUnit.SECONDS), "isolated test controller must exit on SIGINT")
            assertTrue(report.exists(), "worker must report readiness before SIGINT")
            val ids = report.readText().split(',').map { it.toLong() }
            daemon = ProcessHandle.of(ids[0]).orElse(null)
            assertEquals(controller.pid(), ids[1], "worker initially inherits the controller's process group")
            assertEquals(ids[0], ids[2], "daemon must own a separate process group")
            assertEquals(ids[0], ids[3], "daemon must own a separate session")
            assertFalse(controller.isAlive)
            assertTrue(daemon?.isAlive == true, "Ctrl-C of the GUI group must leave the daemon alive")
        } finally {
            if (daemon == null && report.exists()) {
                daemon = report.readText().substringBefore(',').toLongOrNull()?.let { ProcessHandle.of(it).orElse(null) }
            }
            daemon?.destroy()
            controller.destroyForcibly()
            report.delete()
        }
    }
}

/** No GUI, PTY, server, or real user process is involved in this signal-isolation probe. */
internal object DaemonProcessLifecycleProbe {
    private interface Posix : Library {
        fun getpid(): Int
        fun getpgrp(): Int
        fun getsid(pid: Int): Int
        fun kill(pid: Int, signal: Int): Int
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val posix = Native.load(Platform.C_LIBRARY_NAME, Posix::class.java)
        val report = File(args[1])
        if (args[0] == "worker") {
            val before = posix.getpgrp()
            DaemonProcessLifecycle.detachFromParentTerminal()
            report.writeText("${posix.getpid()},$before,${posix.getpgrp()},${posix.getsid(0)}")
            Thread.sleep(30_000)
        } else {
            DaemonProcessLifecycle.detachFromParentTerminal()
            check(posix.getpgrp() == posix.getpid()) // Never signal the test runner's group.
            val worker = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-cp", System.getProperty("java.class.path"), DaemonProcessLifecycleProbe::class.java.name,
                "worker", report.absolutePath,
            ).inheritIO().start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!report.exists() || report.length() == 0L) && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            check(report.exists())
            check(posix.kill(-posix.getpid(), 2) == 0)
            Thread.sleep(1000)
        }
    }
}
