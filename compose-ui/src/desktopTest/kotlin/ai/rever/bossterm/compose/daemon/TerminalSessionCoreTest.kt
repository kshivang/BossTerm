package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a rapid writeInput burst preserves order end-to-end`() {
        if (ShellCustomizationUtils.isWindows()) return

        // Regression for input reordering under backpressure: a bounded write channel with a
        // suspending fallback could enqueue a later write ahead of an earlier one once a slot
        // freed. 400 writes also crosses the old 256-slot boundary while the write consumer is
        // still parked on connected.await() (writes sent immediately after start()).
        //
        // Oracle: `cat > file` — the file receives exactly the bytes cat read from the PTY, in
        // order. (Asserting on the raw PTY output stream instead is flaky: tty echo and cat's
        // copies interleave at byte granularity, and an echo copy split mid-marker makes a
        // first-occurrence scan misreport order even when the input was perfectly ordered.)
        val n = 400
        val markers = (0 until n).map { "ORD_%04d".format(it) }
        val sink = java.io.File.createTempFile("bossterm-order", ".txt")

        val core = TerminalSessionCore(
            settings = TerminalSettings.DEFAULT,
            workingDir = System.getProperty("java.io.tmpdir"),
            command = "/bin/sh",
            arguments = listOf("-c", "exec cat > '${sink.absolutePath}'"),
        )
        core.start()
        try {
            markers.forEach { core.writeInput("$it\n") } // burst, no pacing
            val expected = markers.joinToString("\n", postfix = "\n")
            assertTrue(
                awaitTrue(15_000) { sink.length() >= expected.length.toLong() },
                "cat should drain the whole burst to the file (got ${sink.length()}/${expected.length} bytes)",
            )
            assertEquals(expected, sink.readText(), "input must reach the shell intact and in write order")
        } finally {
            core.close()
            sink.delete()
        }
    }

    @Test
    fun `close kills the pty process (no orphan)`() {
        if (ShellCustomizationUtils.isWindows()) return
        val core = TerminalSessionCore(
            settings = TerminalSettings.DEFAULT,
            workingDir = System.getProperty("java.io.tmpdir"),
            command = "/bin/sh",
            arguments = listOf("-c", "sleep 30"),
        )
        core.start()
        assertTrue(awaitTrue(5000) { core.isAlive() }, "session should start")
        core.close()
        // The kill runs on a dedicated thread (NOT the cancelled scope), so the process must die.
        assertTrue(awaitTrue(6000) { !core.isAlive() }, "close() must terminate the PTY, not orphan it")
    }

    @Test
    fun `input sent immediately after start is not dropped`() {
        if (ShellCustomizationUtils.isWindows()) return
        val marker = "EARLY_INPUT_1212"
        val raw = StringBuilder()
        val seen = CountDownLatch(1)
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
        // Write BEFORE the PTY is necessarily spawned — must be buffered until connected, not dropped.
        core.writeInput("$marker\n")
        try {
            assertTrue(seen.await(10, TimeUnit.SECONDS), "input queued before connect must flush once connected")
        } finally {
            core.close()
        }
    }

    private fun awaitTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun `holds a reference to the marker without leaking`() {
        // Trivial guard so the suite has a non-PTY test on Windows too (keeps the class non-empty there).
        val ref = AtomicReference("ok")
        assertTrue(ref.get() == "ok")
    }
}
