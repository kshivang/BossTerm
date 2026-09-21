package ai.rever.bossterm.compose.voice.local

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Installs, starts, supervises and stops the local Realtime-compatible speech server.
 *
 * One instance per process. The server is a child process that owns a microphone-adjacent,
 * unauthenticated loopback endpoint, so its lifetime is managed deliberately rather than left to
 * chance: the JVM shutdown hook catches the paths that are not orderly exits at all (a kill, an
 * IDE stop), on top of the orderly ones ([stop], [dispose]). BossTerm has leaked child processes
 * before — a prior incident orphaned hundreds of JVMs because "the parent always disposes" held
 * right up until it did not — and a speech server holding an audio pipeline open is a worse thing
 * to leak than a plugin host.
 *
 * [exec] and [probe] are injected so the state machine is testable without installing Python or
 * binding a port. They are dependencies rather than test hooks: both take exactly what the real
 * implementations take and offer no way to make the runtime misbehave.
 */
internal class LocalVoiceRuntime(
    private val home: File = LocalVoiceInstall.home(),
    private val windows: Boolean = ShellCustomizationUtils.isWindows(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val exec: ProcessRunner = SystemProcessRunner,
    private val probe: suspend (String) -> Boolean = ::httpProbe,
) {
    private val log = LoggerFactory.getLogger(LocalVoiceRuntime::class.java)

    private val _state = MutableStateFlow<LocalVoiceRuntimeState>(
        if (LocalVoiceInstall.installed(home, windows)) LocalVoiceRuntimeState.Stopped
        else LocalVoiceRuntimeState.NotInstalled
    )
    val state: StateFlow<LocalVoiceRuntimeState> = _state.asStateFlow()

    /** The live server process, or null. Guarded by [lock] together with [job]. */
    private var process: Process? = null
    private var job: Job? = null
    private val lock = Any()

    /**
     * Preserves the mutual exclusion that the old process monitor provided while allowing the start
     * sequence to suspend.
     *
     * Before this Mutex, the liveness check and [ProcessRunner.spawn] both ran inside synchronized
     * ([lock]), so concurrent callers already could not double-spawn or orphan a process. The drawback
     * was that a potentially slow `ProcessBuilder.start()` pinned a [Dispatchers.IO] thread while it
     * held a JVM monitor. Moving the spawn out of that monitor makes the sequence suspend-friendly;
     * this Mutex keeps the original single-start guarantee around the new structure.
     */
    private val startMutex = Mutex()

    /**
     * Registered once, never removed.
     *
     * A hook per start would accumulate one entry per start/stop cycle for the process lifetime;
     * the hook reads the current process under the same lock instead, so one registration covers
     * every future start.
     */
    private val shutdownHook = Thread({ destroyProcess("JVM shutdown") }, "boss-voice-local-shutdown")

    init {
        runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
    }

    /** The URL a call should use, or null when the server is not answering. */
    fun endpointUrl(): String? = _state.value.endpointUrl

    /**
     * Install the runtime. Safe to call when already installed (returns immediately).
     *
     * Deliberately not called implicitly by [ensureRunning]: this downloads a multi-gigabyte
     * dependency set, and something that large happens because a user asked for it, not because
     * they pressed Call.
     */
    fun install() {
        synchronized(lock) {
            if (_state.value.busy) return
            if (LocalVoiceInstall.installed(home, windows)) {
                _state.value = LocalVoiceRuntimeState.Stopped
                return
            }
            _state.value = LocalVoiceRuntimeState.Installing("Looking for Python…")
        }
        scope.launch {
            val python = findPython()
            if (python == null) {
                _state.value = LocalVoiceRuntimeState.Failed(
                    "Python ${LocalVoiceInstall.MIN_PYTHON_MAJOR}.${LocalVoiceInstall.MIN_PYTHON_MINOR} " +
                        "or newer is required for the local voice runtime, and none was found on PATH.",
                    canRetry = false,
                )
                return@launch
            }
            home.mkdirs()
            val uv = which("uv")
            val commands = LocalVoiceInstall.installCommands(home, python, uv, windows)
            _state.value = LocalVoiceRuntimeState.Installing(
                if (uv != null) "Installing with uv…" else "Installing with pip…"
            )
            for (command in commands) {
                val result = runCatching {
                    exec.run(command, home, INSTALL_TIMEOUT_MINUTES, LocalVoiceInstall.processEnvironment(home))
                }.getOrElse { e ->
                    _state.value = LocalVoiceRuntimeState.Failed("Install failed: ${e.javaClass.simpleName}")
                    return@launch
                }
                if (result.exitCode != 0) {
                    // The tail, not the whole log: a pip resolver failure is thousands of lines and
                    // the actionable part is at the end, but a bare exit code is unactionable.
                    _state.value = LocalVoiceRuntimeState.Failed(
                        "Install failed (${command.first().substringAfterLast(File.separatorChar)} " +
                            "exited ${result.exitCode}): ${result.tail}"
                    )
                    return@launch
                }
            }
            _state.value =
                if (LocalVoiceInstall.installed(home, windows)) LocalVoiceRuntimeState.Stopped
                else LocalVoiceRuntimeState.Failed("Install finished but the server executable is missing.")
        }
    }

    /**
     * Start the server if it is not already answering, and suspend until it is (or fails).
     *
     * Returns the URL to call, or null. Callers treat null as "do not place the call" — there is no
     * fallback to a cloud backend from here, by design.
     *
     * The spawn decision and the spawn itself share [startMutex], preserving the old monitor's
     * single-start guarantee after the blocking spawn was moved out of that monitor. Everything after
     * it — the readiness poll — runs unlocked and concurrently, so a second caller waits on the first
     * caller's server rather than being turned away.
     */
    suspend fun ensureRunning(port: Int): String? {
        (_state.value as? LocalVoiceRuntimeState.Running)?.let { return it.url }
        // Adopt a server that is ALREADY answering on this port rather than spawning a second one.
        // Runtime state lives in this process, so after an app restart the state says Stopped (or
        // Failed, if the previous instance's child was reaped) while a perfectly good server still
        // holds the port. Spawning then loses the bind, the child dies, and the call reports "the
        // server exited" with a working server sitting right there. One loopback GET removes that.
        if (probe(LocalVoiceInstall.probeUrl(port))) {
            val url = LocalVoiceInstall.realtimeUrl(port)
            log.info("Local voice server already serving on {}; adopting it", url)
            _state.value = LocalVoiceRuntimeState.Running(url)
            return url
        }
        if (!LocalVoiceInstall.installed(home, windows)) {
            _state.value = LocalVoiceRuntimeState.NotInstalled
            return null
        }
        startMutex.withLock {
            // Re-checked under the Mutex: another caller may have started the server while this
            // caller was suspended waiting to enter the start sequence.
            if (synchronized(lock) { process }?.isAlive != true &&
                _state.value !is LocalVoiceRuntimeState.Starting
            ) {
                if (!spawnProcess(port)) return null
            }
        }
        return awaitReady(port)
    }

    /**
     * Spawn the server. Caller holds [startMutex]; the process field is written under [lock].
     *
     * Returns false when the process could not be started at all, having set [LocalVoiceRuntimeState.Failed]
     * with the reason. The process is published under [lock] BEFORE its monitor is launched, so a
     * monitor can never observe a field that does not hold its own process.
     */
    private fun spawnProcess(port: Int): Boolean {
        val command = LocalVoiceInstall.serveCommand(home, port, windows)
        val started = runCatching {
            exec.spawn(command, home, LocalVoiceInstall.processEnvironment(home))
        }.getOrElse { e ->
            _state.value = LocalVoiceRuntimeState.Failed(
                "Couldn't start the local voice server: ${e.javaClass.simpleName}"
            )
            return false
        }
        synchronized(lock) { process = started }
        log.info("Local voice server starting: {}", command.joinToString(" "))
        _state.value = LocalVoiceRuntimeState.Starting
        job = scope.launch {
            // Surfacing an exit is the whole point of holding this handle: without it a server that
            // dies on startup (port in use, missing model) leaves the UI on "Starting…" forever.
            val code = withContext(Dispatchers.IO) { runCatching { started.waitFor() }.getOrNull() }
            synchronized(lock) {
                if (process !== started) return@launch // superseded by a newer start
                process = null
            }
            if (_state.value !is LocalVoiceRuntimeState.Stopped) {
                log.warn("Local voice server exited (code {}); see {}", code, LocalVoiceInstall.serverLog(home))
                _state.value = LocalVoiceRuntimeState.Failed(
                    "The local voice server exited (code $code). See ${LocalVoiceInstall.serverLog(home)}."
                )
            }
        }
        return true
    }

    /** Poll until the server answers, the process dies, or we give up. */
    private suspend fun awaitReady(port: Int): String? {
        val probeUrl = LocalVoiceInstall.probeUrl(port)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS)
        // The CALLER's liveness, not [scope]'s. `scope` is a process-lifetime SupervisorJob, so
        // `scope.isActive` is always true and could never end this loop early; a call that was
        // cancelled or hung up mid-start would poll here for the full timeout instead.
        while (currentCoroutineContext().isActive && System.nanoTime() < deadline) {
            if (_state.value is LocalVoiceRuntimeState.Failed) return null
            if (synchronized(lock) { process }?.isAlive != true) {
                // The monitor's exit reason (port in use, missing model) is NOT surfaced from here:
                // the caller only sees a null endpoint, and the resolver turns that into one
                // "isn't running, set it up in Settings" message. The reason is still visible in the
                // runtime's own state, which is what the settings panel renders.
                return null
            }
            if (probe(probeUrl)) {
                val url = LocalVoiceInstall.realtimeUrl(port)
                _state.value = LocalVoiceRuntimeState.Running(url)
                return url
            }
            delay(PROBE_INTERVAL_MS)
        }
        // Do not leave a half-started server running: it holds models in memory and the port.
        stop()
        _state.value = LocalVoiceRuntimeState.Failed(
            "The local voice server didn't become ready within ${START_TIMEOUT_SECONDS}s. " +
                "First start loads models and can be slow on a cold cache; try again."
        )
        return null
    }

    /** Stop the server. Idempotent. */
    fun stop() {
        destroyProcess("stop requested")
        if (_state.value !is LocalVoiceRuntimeState.Failed) {
            _state.value =
                if (LocalVoiceInstall.installed(home, windows)) LocalVoiceRuntimeState.Stopped
                else LocalVoiceRuntimeState.NotInstalled
        }
    }

    private fun destroyProcess(reason: String) {
        val (p, j) = synchronized(lock) {
            val pair = process to job
            process = null
            job = null
            pair
        }
        j?.cancel()
        val live = p ?: return
        log.info("Stopping local voice server ({})", reason)
        live.destroy()
        // SIGTERM first so the server can release the audio device and its port cleanly; escalate
        // only if it does not. A hard kill as the first move leaves the port in TIME_WAIT often
        // enough that the next start fails with "address already in use".
        if (!runCatching { live.waitFor(GRACE_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)) {
            live.destroyForcibly()
        }
    }

    /** Release everything. After this the instance is not reusable. */
    fun dispose() {
        destroyProcess("dispose")
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        scope.coroutineContext[Job]?.cancel()
    }

    /** First interpreter on PATH new enough for the distribution. */
    private suspend fun findPython(): String? {
        for (candidate in PYTHON_CANDIDATES) {
            val path = which(candidate) ?: continue
            val version = runCatching {
                exec.run(listOf(path, "--version"), home, 1, LocalVoiceInstall.processEnvironment(home))
            }.getOrNull() ?: continue
            // --version has historically printed to stderr as well as stdout; [tail] merges both.
            if (LocalVoiceInstall.pythonVersionOk(version.tail)) return path
        }
        return null
    }

    private suspend fun which(name: String): String? =
        runCatching {
            val probe = if (windows) listOf("where", name) else listOf("which", name)
            exec.run(probe, home, 1, LocalVoiceInstall.processEnvironment(home))
                .takeIf { it.exitCode == 0 }?.tail?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }?.trim()
        }.getOrNull()

    internal companion object {
        /**
         * The one runtime for the process.
         *
         * A singleton because it owns a child process bound to a fixed loopback port: two
         * instances would race to bind it, and the loser's failure would be reported as the
         * feature being broken. The settings UI and the call path therefore observe the same
         * [state] rather than each managing their own server.
         */
        val shared: LocalVoiceRuntime by lazy { LocalVoiceRuntime() }

        /** Interpreters to try, newest-named first. */
        val PYTHON_CANDIDATES = listOf("python3.13", "python3.12", "python3.11", "python3.10", "python3", "python")

        const val PROBE_INTERVAL_MS = 500L

        /**
         * How long a start may take before it is treated as failed.
         *
         * Generous because first start loads speech models plus Qwen3-4B into memory; the shipped
         * fully local pipeline needs at least 24 GB of available memory, and a cold model cache is slow.
         */
        const val START_TIMEOUT_SECONDS = 180L

        const val GRACE_SECONDS = 5L
        const val INSTALL_TIMEOUT_MINUTES = 30L
    }
}

/** Result of a finished command: exit code plus the tail of its merged output. */
internal data class ProcessResult(val exitCode: Int, val tail: String)

/** Process execution, injected so [LocalVoiceRuntime] is testable without spawning anything. */
internal interface ProcessRunner {
    /** Run to completion, merging stderr into stdout, and return the tail of its output. */
    suspend fun run(
        command: List<String>,
        workingDir: File,
        timeoutMinutes: Long,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult

    /** Start a long-running process. The caller owns its lifetime. */
    fun spawn(
        command: List<String>,
        workingDir: File,
        environment: Map<String, String> = emptyMap(),
    ): Process
}

/** Where a spawned server's output goes, created if missing so ProcessBuilder can open it. */
private fun logFileFor(workingDir: File): File =
    File(workingDir, "server.log").also { runCatching { it.parentFile?.mkdirs() } }

internal object SystemProcessRunner : ProcessRunner {
    /**
     * Output kept from a finished command.
     *
     * Bounded because a pip resolution failure can emit megabytes and this string reaches a UI
     * label; the actionable part of any of these commands is at the end.
     */
    private const val TAIL_CHARS = 2_000

    override suspend fun run(
        command: List<String>,
        workingDir: File,
        timeoutMinutes: Long,
        environment: Map<String, String>,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(command)
            .directory(workingDir.takeIf { it.isDirectory })
            .redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        // Read concurrently with waiting: a process that fills the pipe buffer blocks forever
        // if nobody drains it, which turns a timeout into a hang that outlives the timeout.
        val output = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        output.append(line).append('\n')
                        if (output.length > TAIL_CHARS * 4) output.delete(0, output.length - TAIL_CHARS * 2)
                    }
                }
            }
        }, "boss-voice-local-reader").apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            reader.interrupt()
            return@withContext ProcessResult(-1, "timed out after ${timeoutMinutes}m")
        }
        runCatching { reader.join(1_000) }
        val text = synchronized(output) { output.toString() }
        ProcessResult(process.exitValue(), text.takeLast(TAIL_CHARS).trim())
    }

    override fun spawn(
        command: List<String>,
        workingDir: File,
        environment: Map<String, String>,
    ): Process {
        val builder = ProcessBuilder(command)
            .directory(workingDir.takeIf { it.isDirectory })
            .redirectErrorStream(true)
            // To a FILE, not a pipe and not DISCARD. Nothing here drains a long-running server's
            // output, so a pipe wedges the server once its buffer fills; DISCARD avoided that but
            // threw away the only explanation a crashed server ever gives. This file is the first
            // thing to read when a local call will not start, and the Failed message cites it.
            .redirectOutput(ProcessBuilder.Redirect.to(logFileFor(workingDir)))
        builder.environment().putAll(environment)
        return builder.start()
    }
}

/** Default readiness probe: a plain GET that does not allocate a realtime session. */
private suspend fun httpProbe(url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
