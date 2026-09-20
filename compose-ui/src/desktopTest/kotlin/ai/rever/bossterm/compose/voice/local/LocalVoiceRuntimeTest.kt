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
    fun `serve command selects an explicit local language model on macOS`() {
        val command = LocalVoiceInstall.serveCommand(File("/tmp/home"), 8765, windows = false, macOS = true)
        assertEquals(LocalVoiceInstall.MAC_LLM_BACKEND, command[command.indexOf("--llm_backend") + 1])
        assertEquals(LocalVoiceInstall.LOCAL_LLM_MODEL, command[command.indexOf("--model_name") + 1])
        assertEquals("parakeet-tdt", command[command.indexOf("--stt") + 1])
        assertEquals("qwen3", command[command.indexOf("--tts") + 1])
        assertEquals("mps", command[command.indexOf("--device") + 1])
        assertFalse(command.contains("responses-api"), "a local call must not inherit the hosted backend: $command")
    }

    @Test
    fun `serve command selects transformers and names the local model off macOS`() {
        val command = LocalVoiceInstall.serveCommand(File("/tmp/home"), 8765, windows = false, macOS = false)
        assertEquals(LocalVoiceInstall.PORTABLE_LLM_BACKEND, command[command.indexOf("--llm_backend") + 1])
        assertEquals(LocalVoiceInstall.LOCAL_LLM_MODEL, command[command.indexOf("--model_name") + 1])
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
     * [spawn] can park before returning so the concurrency test keeps one caller inside the current
     * start critical section while another caller arrives. This does not reproduce a historical
     * double-spawn bug: the pre-change monitor covered both the liveness check and the spawn, so the
     * second caller blocked at monitor entry and the old code also spawned once.
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
     * Keeps the first caller inside [ProcessRunner.spawn] long enough for a concurrent caller to
     * contend for the current start sequence.
     *
     * Under the pre-change synchronized implementation, the first caller held the process monitor
     * here, so the second caller could not reach its spawn decision and this gate timed out. Under
     * the current implementation, the first caller holds [LocalVoiceRuntime]'s start Mutex instead.
     * The test therefore guards the current structure's mutual exclusion; it is not a reproduction
     * of a bug in the old monitor-based implementation.
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
     * Guards the current start structure's single-spawn invariant.
     *
     * This is not a regression test for a historical leak. Before the spawn moved out of the process
     * monitor, that monitor already covered both the decision and `ProcessBuilder.start()`, so two
     * concurrent callers could not both spawn. The Mutex keeps that same mutual exclusion now that
     * the blocking spawn no longer pins an IO thread while holding a JVM monitor.
     *
     * The teardown assertion remains useful for the current structure: every process this test does
     * spawn must still be owned and destroyed when the runtime is disposed.
     */
    @Test
    fun `two concurrent start requests spawn one server, not two`() = runBlocking {
        val gate = SpawnGate()
        val runner = SpawnCountingRunner(park = gate::pass)
        // Release both callers together so one contends for the start sequence while the other is
        // parked inside spawn().
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
            // Use separate threads so the blocking fake spawn cannot prevent the other caller from
            // reaching the Mutex.
            val start = CountDownLatch(1)
            val futures = (1..2).map {
                pool.submit {
                    start.await()
                    callersReady.countDown()
                    runBlocking { runtime.ensureRunning(8765) }
                }
            }
            start.countDown()
            // Wait for the first caller to enter spawn(), then leave enough time for the second to
            // contend. Mutual exclusion means the gate sees no second spawn attempt and times out.
            assertTrue(gate.awaitFirstParked(), "the first caller never reached spawn()")
            Thread.sleep(1_500)
            futures.forEach { it.cancel(true) }
        } finally {
            pool.shutdownNow()
            // The current structure must retain ownership of everything it spawns.
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
