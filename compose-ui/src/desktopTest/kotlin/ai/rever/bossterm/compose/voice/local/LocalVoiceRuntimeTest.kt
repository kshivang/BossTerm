package ai.rever.bossterm.compose.voice.local

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * The managed runtime's decisions, without installing Python or binding a port.
 *
 * The command construction is asserted directly because it is the part with no feedback loop: a
 * wrong flag or a wrong venv layout produces "installed successfully, cannot start", and the only
 * place that surfaces is a user's machine.
 */
class LocalVoiceRuntimeTest {

    // ---- command and layout ----

    /**
     * `--host 127.0.0.1` is passed explicitly even though it is the server's own default, because
     * this server performs no authentication: the bind address is a security boundary, not a
     * preference. If upstream ever changes that default, this assertion is what stops an
     * unauthenticated microphone-and-tools endpoint appearing on the user's network.
     */
    @Test
    fun `serve command binds loopback explicitly`() {
        val command = LocalVoiceInstall.serveCommand(File("/tmp/home"), 8765, windows = false)
        assertTrue(command.contains("serve"), "got: $command")
        val hostIndex = command.indexOf("--host")
        assertTrue(hostIndex >= 0, "the bind address must be explicit: $command")
        assertEquals("127.0.0.1", command[hostIndex + 1])
        assertEquals("8765", command[command.indexOf("--port") + 1])
    }

    @Test
    fun `venv layout follows the platform`() {
        val venv = File("/tmp/home/venv")
        assertTrue(LocalVoiceInstall.venvBin(venv, "python", windows = false).path.endsWith("venv/bin/python"))
        // Windows puts scripts in Scripts\ with an .exe suffix; getting this wrong installs fine
        // and then cannot start.
        val win = LocalVoiceInstall.venvBin(venv, "python", windows = true).path
        assertTrue(win.endsWith("python.exe"), "got: $win")
        assertTrue(win.contains("Scripts"), "got: $win")
    }

    /**
     * The install is pinned. An unpinned spec would let a future upstream release change event
     * names or the audio contract under a user who only ever pressed Call, and that failure
     * surfaces as a silent call rather than an install error.
     */
    @Test
    fun `install pins an exact version`() {
        val commands = LocalVoiceInstall.installCommands(File("/tmp/home"), "python3", uv = null, windows = false)
        val spec = commands.flatten().firstOrNull { it.startsWith(LocalVoiceInstall.PACKAGE) }
        assertEquals("${LocalVoiceInstall.PACKAGE}==${LocalVoiceInstall.VERSION}", spec)
    }

    @Test
    fun `uv is preferred when present and pip is the fallback`() {
        val withUv = LocalVoiceInstall.installCommands(File("/tmp/home"), "python3", uv = "/usr/bin/uv", windows = false)
        assertTrue(withUv.all { it.first() == "/usr/bin/uv" }, "got: $withUv")

        val withoutUv = LocalVoiceInstall.installCommands(File("/tmp/home"), "python3", uv = null, windows = false)
        assertTrue(withoutUv.none { it.first() == "/usr/bin/uv" })
        assertTrue(withoutUv.first().containsAll(listOf("python3", "-m", "venv")), "got: ${withoutUv.first()}")
    }

    /**
     * The probe must not be the realtime socket: polling `/v1/realtime` would open and abandon a
     * real session every 500 ms while waiting for startup.
     */
    @Test
    fun `readiness probe is a plain http get, not the realtime socket`() {
        val probe = LocalVoiceInstall.probeUrl(8765)
        assertTrue(probe.startsWith("http://"), "got: $probe")
        assertFalse(probe.contains("/v1/realtime"), "got: $probe")
        assertEquals("ws://127.0.0.1:8765/v1/realtime", LocalVoiceInstall.realtimeUrl(8765))
    }

    /**
     * `python --version` has printed to both stdout and stderr across releases.
     */
    @Test
    fun `python version gate matches the distribution's own floor`() {
        assertTrue(LocalVoiceInstall.pythonVersionOk("Python 3.12.1"))
        assertTrue(LocalVoiceInstall.pythonVersionOk("Python 3.10.0"))
        assertTrue(LocalVoiceInstall.pythonVersionOk("Python 4.0.0"))
        // 3.9 is below the distribution's requires-python and must be rejected HERE, with a
        // message, rather than thousands of lines later inside pip's resolver.
        assertFalse(LocalVoiceInstall.pythonVersionOk("Python 3.9.18"))
        assertFalse(LocalVoiceInstall.pythonVersionOk("nonsense"))
    }

    /** The major in the message is the one the gate enforces, not a second copy of "3". */
    @Test
    fun `the version floor is named once`() {
        assertEquals(3, LocalVoiceInstall.MIN_PYTHON_MAJOR)
        assertTrue(LocalVoiceInstall.pythonVersionOk("Python ${LocalVoiceInstall.MIN_PYTHON_MAJOR}.${LocalVoiceInstall.MIN_PYTHON_MINOR}.0"))
    }

    // ---- the external server address ----

    /**
     * `http://host:port/v1/realtime` is the address a person copies out of a browser, and the only
     * sensible way to describe "the server I am already running". The JDK's
     * `newWebSocketBuilder()` speaks `ws:`/`wss:` only, so an untranslated value reached the
     * transport and threw `Invalid scheme` from inside `connect`, where it surfaced as an opaque
     * connection failure rather than as the settings typo it was.
     */
    @Test
    fun `an http server address is translated to the websocket spelling`() {
        assertEquals(
            "ws://192.168.1.9:8765/v1/realtime",
            LocalVoiceInstall.parseExternalUrl("http://192.168.1.9:8765/v1/realtime"),
        )
        assertEquals(
            "wss://voice.example.com/v1/realtime",
            LocalVoiceInstall.parseExternalUrl("https://voice.example.com/v1/realtime"),
        )
        // Already correct: passed through, not rewritten.
        assertEquals(
            "ws://127.0.0.1:9000/v1/realtime",
            LocalVoiceInstall.parseExternalUrl("ws://127.0.0.1:9000/v1/realtime"),
        )
        assertEquals(
            "wss://voice.example.com/v1/realtime",
            LocalVoiceInstall.parseExternalUrl("  wss://voice.example.com/v1/realtime  "),
        )
    }

    /**
     * Rejected as null, so the resolver reports it against the setting by name. The alternative —
     * passing it through — fails one layer down inside the transport, which is where a user cannot
     * see what to fix.
     */
    @Test
    fun `an unusable server address is rejected rather than passed to the transport`() {
        assertNull(LocalVoiceInstall.parseExternalUrl(""))
        assertNull(LocalVoiceInstall.parseExternalUrl("   "))
        assertNull(LocalVoiceInstall.parseExternalUrl(null))
        // Right idea, wrong protocol: this is not a Realtime server address.
        assertNull(LocalVoiceInstall.parseExternalUrl("ftp://host:8765/v1/realtime"))
        // No scheme at all — the shape someone types when they mean a host and a port.
        assertNull(LocalVoiceInstall.parseExternalUrl("192.168.1.9:8765"))
        // A scheme with no host behind it.
        assertNull(LocalVoiceInstall.parseExternalUrl("ws://"))
        assertNull(LocalVoiceInstall.parseExternalUrl("http:///v1/realtime"))
    }

    /** Loopback detection drives a WARNING only, so it must not call a LAN address local. */
    @Test
    fun `loopback detection covers the spellings of this machine`() {
        assertTrue(LocalVoiceInstall.isLoopbackUrl("ws://127.0.0.1:8765/v1/realtime"))
        assertTrue(LocalVoiceInstall.isLoopbackUrl("wss://localhost:8765/v1/realtime"))
        assertTrue(LocalVoiceInstall.isLoopbackUrl("ws://[::1]:8765/v1/realtime"))
        assertFalse(LocalVoiceInstall.isLoopbackUrl("ws://192.168.1.9:8765/v1/realtime"))
        assertFalse(LocalVoiceInstall.isLoopbackUrl("wss://voice.example.com/v1/realtime"))
    }

    // ---- state machine ----

    private class FakeRunner(
        var exitCode: Int = 0,
        val spawned: MutableList<List<String>> = mutableListOf(),
        val process: Process? = null,
    ) : ProcessRunner {
        override suspend fun run(command: List<String>, workingDir: File, timeoutMinutes: Long) =
            ProcessResult(exitCode, "")

        override fun spawn(command: List<String>, workingDir: File): Process {
            spawned += command
            return process ?: error("no process configured")
        }
    }

    private fun tempHome(): File = Files.createTempDirectory("voice-local-test").toFile()

    /**
     * A home with a fake venv that [LocalVoiceInstall.installed] accepts.
     *
     * `canExecute` is what `installed()` checks, so the file must exist and be executable for any
     * start path to be reachable — otherwise every start test exits through the NotInstalled branch
     * and proves nothing about spawning.
     */
    private fun installedHome(): File {
        val home = tempHome()
        val bin = File(LocalVoiceInstall.venv(home), "bin")
        bin.mkdirs()
        File(bin, LocalVoiceInstall.CONSOLE_SCRIPT).apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        check(LocalVoiceInstall.installed(home, windows = false)) {
            "precondition: installed() must accept the fake venv"
        }
        return home
    }

    /**
     * Counts spawns and hands back a process that lives until it is destroyed.
     *
     * [spawn] parks on a rendezvous before returning, which is what makes the race test actually
     * reproduce the race. Without it the interleaving is left to luck: on a fast machine the first
     * caller finishes spawning and publishes the process field before the second caller even looks,
     * so the second caller takes the reuse path and the test passes even against the BROKEN code
     * (verified — it did). Holding the winner inside `spawn` until the loser has arrived forces both
     * callers past the "is a process already alive?" decision while it is still false, which is the
     * only state in which the bug can occur.
     *
     * [park] is null for tests that do not need the interference.
     */
    private class SpawnCountingRunner(private val park: (() -> Unit)? = null) : ProcessRunner {
        val spawnCount = AtomicInteger(0)
        val spawned = CopyOnWriteArrayList<FakeServerProcess>()

        override suspend fun run(command: List<String>, workingDir: File, timeoutMinutes: Long) =
            ProcessResult(0, "")

        // Not `suspend` in the interface, so the rendezvous blocks the calling thread. That is
        // deliberate: it reproduces a real spawn's latency without needing a scheduler.
        override fun spawn(command: List<String>, workingDir: File): Process {
            park?.invoke()
            return FakeServerProcess(command.joinToString(" ")).also {
                spawned += it
                spawnCount.incrementAndGet()
            }
        }
    }

    /**
     * Widens the window the start race lives in, so the test cannot pass by luck.
     *
     * The race is real but narrow, and it is not "both callers execute the same line at once". In
     * the pre-fix sequence the second caller could reach its spawn decision while the first was
     * still inside `spawn()` — i.e. while the loser's `process?.isAlive` read still saw null. Left
     * to chance the second caller usually turns up after the first has finished and published, takes
     * the reuse path, and the test passes against BROKEN code: measured, an earlier ungated version
     * of this test reported `spawns=1` against the pre-fix sequence, which is worse than no test.
     *
     * So the first caller parks inside `spawn()` until a second caller reaches the decision, or
     * until [timeoutMs] expires — meaning there was no second arrival to wait for.
     */
    private class SpawnGate(private val timeoutMs: Long = 600) {
        private val secondCallerArrived = CountDownLatch(1)
        private val firstCallerParked = CountDownLatch(1)
        @Volatile var attempts = 0
            private set

        /** Called from inside `spawn()`, i.e. while the first caller holds its critical section. */
        fun pass() {
            val n = ++attempts
            if (n > 1) {
                secondCallerArrived.countDown()
                return
            }
            firstCallerParked.countDown()
            secondCallerArrived.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        /** True once a caller has parked, so the test can wait for that instead of guessing. */
        fun awaitFirstParked(timeoutMs: Long = 5_000): Boolean =
            firstCallerParked.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Stands in for a live server: [waitFor] BLOCKS, and [isAlive] stays true until [destroy].
     *
     * Both matter. An earlier version of this fake returned from `waitFor()` immediately, which the
     * runtime correctly reads as "the server died" — the monitor then cleared the process field and
     * flagged an exit, and the second caller *correctly* spawned a replacement for a process that
     * was already gone. That looked exactly like the race still being present and was in fact the
     * fake being wrong: a real `speech-to-speech serve` does not exit the moment it starts.
     */
    private class FakeServerProcess(private val script: String) : Process() {
        @Volatile private var alive = true

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int {
            // Blocks like a running server, returning once destroy() lands.
            while (alive) {
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return 0
        }

        override fun exitValue(): Int = 0
        override fun destroy() {
            alive = false
        }

        override fun isAlive(): Boolean = alive
        override fun toString(): String = script
    }

    /**
     * THE regression test for the start race.
     *
     * A call placed while someone presses Start in Settings — or two calls close together — used to
     * spawn TWO servers. The loser's handle was then overwritten by the winner's and nothing could
     * reap it: `stop()` and the shutdown hook only ever look at that one field, and the losing
     * monitor's `process !== started` check returns without destroying anything. The result was an
     * unauthenticated speech server left holding the port and an audio pipeline, while the winner
     * failed with "address already in use" instead of starting.
     *
     * Asserts the observable *cause* rather than only the count: `spawnCount` alone would also be
     * satisfied by two serialized spawns, whereas the bug is specifically that one process is left
     * with no owner — so teardown is required to have reaped every process that was spawned.
     */
    @Test
    fun `two concurrent start requests spawn one server, not two`() = runBlocking {
        val gate = SpawnGate()
        val runner = SpawnCountingRunner(park = gate::pass)
        // Every caller must be past `installed()` and inside the start sequence before either can be
        // allowed to proceed, otherwise the test just races the test harness.
        val callersReady = CountDownLatch(2)
        val runtime = LocalVoiceRuntime(
            home = installedHome(),
            windows = false,
            exec = runner,
            // Never ready: both callers stay inside the readiness poll, where the overlap happened.
            probe = { false },
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            // Released together rather than sequenced: the race is decided within milliseconds, so
            // launching them from one coroutine and hoping was never a reliable reproduction.
            val start = CountDownLatch(1)
            val futures = (1..2).map {
                pool.submit {
                    start.await()
                    callersReady.countDown()
                    runBlocking { runtime.ensureRunning(8765) }
                }
            }
            start.countDown()
            // Wait for the first caller to actually be inside spawn(), then give the second one the
            // gate's full window to reach the decision. A fixed start sequence means it never does.
            assertTrue(gate.awaitFirstParked(), "the first caller never reached spawn()")
            Thread.sleep(1_500)
            futures.forEach { it.cancel(true) }
        } finally {
            pool.shutdownNow()
            // dispose() must reap everything spawned. That is exactly the property the orphan
            // violated: it survived with no handle on it, so nothing could ever destroy it.
            runtime.dispose()
        }
        assertEquals(
            1,
            runner.spawnCount.get(),
            "two concurrent callers must not spawn two servers (spawn attempts: ${gate.attempts})",
        )
        val survivors = runner.spawned.filter { it.isAlive }
        assertTrue(survivors.isEmpty(), "teardown left ${survivors.size} server process(es) alive: $survivors")
    }

    /** A second start request while the server is up reuses it rather than starting another. */
    @Test
    fun `a start request while running reuses the live server`() = runBlocking {
        val runner = SpawnCountingRunner()
        val runtime = LocalVoiceRuntime(
            home = installedHome(),
            windows = false,
            exec = runner,
            probe = { true },
        )
        val first = runtime.ensureRunning(8765)
        val second = runtime.ensureRunning(8765)
        assertEquals(first, second)
        assertEquals(1, runner.spawnCount.get(), "an already-running server must not be spawned again")
        runtime.dispose()
    }

    /** A spawn that throws is a failure with a reason, not a bare null with no state behind it. */
    @Test
    fun `a failed spawn reports why and does not leave Starting behind`() = runBlocking {
        val failing = object : ProcessRunner {
            override suspend fun run(command: List<String>, workingDir: File, timeoutMinutes: Long) =
                ProcessResult(0, "")
            override fun spawn(command: List<String>, workingDir: File): Process =
                throw java.io.IOException("no such executable")
        }
        val runtime = LocalVoiceRuntime(
            home = installedHome(),
            windows = false,
            exec = failing,
            probe = { true },
        )
        assertNull(runtime.ensureRunning(8765))
        val failed = assertIs<LocalVoiceRuntimeState.Failed>(runtime.state.value)
        assertTrue(failed.message.contains("IOException"), "got: ${failed.message}")
        runtime.dispose()
    }

    /**
     * Pressing Call with nothing installed must not silently start a multi-gigabyte download, and
     * must not return a URL either.
     */
    @Test
    fun `ensureRunning refuses when nothing is installed`() = runBlocking {
        val home = tempHome()
        val runtime = LocalVoiceRuntime(home = home, windows = false, exec = FakeRunner(), probe = { false })
        assertNull(runtime.ensureRunning(8765))
        assertIs<LocalVoiceRuntimeState.NotInstalled>(runtime.state.value)
        runtime.dispose()
    }

    @Test
    fun `a fresh runtime reports not installed and offers an install`() {
        val runtime = LocalVoiceRuntime(home = tempHome(), windows = false, exec = FakeRunner(), probe = { false })
        assertTrue(runtime.state.value.needsInstall)
        assertNull(runtime.endpointUrl())
        runtime.dispose()
    }

    /** Only a Running state may hand a URL to a call. */
    @Test
    fun `no state other than Running carries an endpoint url`() {
        assertNull(LocalVoiceRuntimeState.NotInstalled.endpointUrl)
        assertNull(LocalVoiceRuntimeState.Stopped.endpointUrl)
        assertNull(LocalVoiceRuntimeState.Starting.endpointUrl)
        assertNull(LocalVoiceRuntimeState.Installing("x").endpointUrl)
        assertNull(LocalVoiceRuntimeState.Failed("x").endpointUrl)
        assertEquals("ws://host/v1/realtime", LocalVoiceRuntimeState.Running("ws://host/v1/realtime").endpointUrl)
    }

    /**
     * "No Python on this machine" does not become true by pressing Retry, and a UI that offers one
     * anyway teaches users the button does nothing.
     */
    @Test
    fun `an unfixable failure is marked as not retryable`() {
        assertFalse(LocalVoiceRuntimeState.Failed("no python", canRetry = false).let { it.canRetry })
        assertTrue(LocalVoiceRuntimeState.Failed("port busy").canRetry)
    }

    @Test
    fun `busy states are the ones that should disable their button`() {
        assertTrue(LocalVoiceRuntimeState.Installing("x").busy)
        assertTrue(LocalVoiceRuntimeState.Starting.busy)
        assertFalse(LocalVoiceRuntimeState.Stopped.busy)
        assertFalse(LocalVoiceRuntimeState.Running("ws://x").busy)
    }
}
