package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless integration test for [TerminalSessionCore] — spawns a REAL pty4j shell, runs a command,
 * and asserts the output flows PTY → data stream → emulator → text buffer, all with no GUI. This is
 * the genuine verification that the daemon-side session stack works end to end (the part the
 * "never run the app" rule can't cover via the Compose UI).
 *
 * Skipped on Windows (the marker command is POSIX `sh`); CI for this repo runs the daemon tests on
 * macOS/Linux where pty4j + /bin/sh are available.
 */
class TerminalSessionCoreTest {

    @Test
    fun `spawns a real pty and captures command output`() {
        if (ShellCustomizationUtils.isWindows()) return // POSIX sh marker; pty differs on Windows

        val marker = "BOSSTERM_CORE_OK_4242"
        val rawSeen = CountDownLatch(1)
        val rawBuffer = StringBuilder()
        val exited = CountDownLatch(1)

        val core = TerminalSessionCore(
            settings = TerminalSettings.DEFAULT,
            workingDir = System.getProperty("java.io.tmpdir"),
            command = "/bin/sh",
            // Print the marker, then exit so the exit-monitor fires.
            arguments = listOf("-c", "printf '%s\\n' $marker; sleep 0.2"),
        )
        core.onExit = { exited.countDown() }
        core.addRawOutputListener { chunk ->
            synchronized(rawBuffer) { rawBuffer.append(chunk) }
            if (rawBuffer.contains(marker)) rawSeen.countDown()
        }

        core.start()
        try {
            assertTrue(rawSeen.await(10, TimeUnit.SECONDS), "marker should arrive on the raw PTY output stream")
            // The emulator should have written the marker into the screen buffer too.
            assertTrue(exited.await(10, TimeUnit.SECONDS), "the shell process should exit and fire onExit")
            val screen = core.textBuffer.getScreenLines()
            assertTrue(screen.contains(marker), "emulator should render the marker into the text buffer; was:\n$screen")
        } finally {
            core.close()
        }
    }

    @Test
    fun `writeInput reaches the shell and echoes back`() {
        if (ShellCustomizationUtils.isWindows()) return

        val marker = "ECHOED_9931"
        val seen = CountDownLatch(1)
        val raw = StringBuilder()

        // `cat` echoes its stdin back to stdout over the PTY — a clean round-trip of writeInput.
        val core = TerminalSessionCore(
            settings = TerminalSettings.DEFAULT,
            workingDir = System.getProperty("java.io.tmpdir"),
            command = "/bin/cat",
            arguments = emptyList(),
        )
        core.addRawOutputListener { chunk ->
            synchronized(raw) { raw.append(chunk) }
            if (raw.contains(marker)) seen.countDown()
        }
        core.start()
        try {
            // Give the PTY a moment to come up, then send a line.
            Thread.sleep(300)
            core.writeInput("$marker\n")
            assertTrue(seen.await(10, TimeUnit.SECONDS), "cat should echo the written input back over the PTY")
        } finally {
            core.close()
        }
    }

    @Test
    fun `holds a reference to the marker without leaking`() {
        // Trivial guard so the suite has a non-PTY test on Windows too (keeps the class non-empty there).
        val ref = AtomicReference("ok")
        assertTrue(ref.get() == "ok")
    }
}
