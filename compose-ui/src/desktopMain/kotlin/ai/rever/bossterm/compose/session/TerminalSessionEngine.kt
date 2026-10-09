package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.TerminalSessionDispatcher
import ai.rever.bossterm.compose.TerminalSessionSlots
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.putBossTermGraphicsEnvironment
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellIntegrationInjector
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.drainTerminalEmulator
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalCustomCommandListener
import ai.rever.bossterm.terminal.TerminalOutputStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.CommandStateListener
import ai.rever.bossterm.terminal.model.TerminalApplicationTitleListener
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.util.GraphemeBoundaryUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ai.rever.bossterm.compose.util.submitLine
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.UUID

/** The shared PTY/emulator engine. UI, daemon ownership and transports are adapters around it. */
class TerminalSessionEngine(
    val id: String = UUID.randomUUID().toString(),
    private val settings: TerminalSettings,
    private val workingDir: String?,
    command: String? = null,
    arguments: List<String> = emptyList(),
    val stack: TerminalSessionStack,
    private val platformServices: PlatformServices = getPlatformServices(),
    private val initialCommand: String? = null,
    environmentOverrides: Map<String, String> = emptyMap(),
    private val reserveThreads: Boolean = true,
    private val parentScope: CoroutineScope? = null,
    private val onStateChanged: (State) -> Unit = {},
    private val onConnected: (PlatformServices.ProcessService.ProcessHandle, String, List<String>) -> Unit = { _, _, _ -> },
    private val onInitialCommandComplete: ((Boolean, Int) -> Unit)? = null,
    private val onProcessExit: (Int?) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(TerminalSessionEngine::class.java)
    private val environmentOverrides = environmentOverrides.toMap()
    val textBuffer: TerminalTextBuffer get() = stack.textBuffer
    val display: ai.rever.bossterm.terminal.TerminalDisplay get() = stack.display
    val terminal: BossTerminal get() = stack.terminal
    val dataStream: BlockingTerminalDataStream get() = stack.dataStream
    val emulator: BossEmulator get() = stack.emulator
    private val outputPublisher get() = stack.publisher
    private val initCols = textBuffer.width
    private val initRows = textBuffer.height

    // ---- resolved launch command (shared login defaults) ----
    private val effectiveCommand: String
    private val effectiveArguments: List<String>

    // ---- observable state ----
    private val _workingDirectory = MutableStateFlow(workingDir)
    val workingDirectory: StateFlow<String?> = _workingDirectory.asStateFlow()

    private val _connectionState = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _connectionState.asStateFlow()

    /** Window/icon title as set by OSC 0/1/2; useful for tab labels on attached clients. */
    private val _windowTitle = MutableStateFlow("")
    val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()
    private val _iconTitle = MutableStateFlow("")
    val iconTitle: StateFlow<String> = _iconTitle.asStateFlow()

    @Volatile private var handle: PlatformServices.ProcessService.ProcessHandle? = null
    val processHandle: PlatformServices.ProcessService.ProcessHandle? get() = handle

    /** Invoked once when the shell process exits (so the host registry can reap the session). */
    var onExit: (() -> Unit)? = null
    private val exitNotified = java.util.concurrent.atomic.AtomicBoolean(false)

    // Admission is synchronous; lifecycle completion releases slots after all owned loops stop.
    private var slotsReserved = false
    private val slotsReleased = java.util.concurrent.atomic.AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val terminated = CompletableDeferred<Unit>()
    private var ownerWatcher: Job? = null
    @Volatile private var killer: Thread? = null
    private val processKilled = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile var exitCode: Int? = null; private set
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupComplete = java.util.concurrent.CountDownLatch(1)
    @Volatile private var closed = false

    // Completes true once the PTY is spawned (Connected), false on failure/close. The write
    // consumer waits on it so input sent immediately after openSession() is buffered, not dropped.
    private val connected = CompletableDeferred<Boolean>()
    private val initialPrompt = CompletableDeferred<Unit>()

    sealed class State {
        object Initializing : State()
        object Connected : State()
        data class Error(val message: String, val cause: Throwable? = null) : State()
        object Exited : State()
    }

    private companion object {
        /** Upper clamp for a requested grid dimension — generous; just guards against absurd values. */
        const val MAX_GRID_DIM = 2000

        /** Safety ceiling on un-drained write units (see [pendingWriteUnits]) — far above any real
         *  paste, only reachable when the PTY has stopped draining. */
        const val MAX_PENDING_WRITE_UNITS = 32L * 1024 * 1024
    }

    init {
        val (cmd, args) = resolveSessionCommand(settings, workingDir, command, arguments)
        effectiveCommand = cmd
        effectiveArguments = args

        terminal.setCharacterEncoding(settings.characterEncoding)

        // OSC 7 → working directory (headless; no Compose state).
        terminal.addCustomCommandListener(object : TerminalCustomCommandListener {
            override fun process(args: MutableList<String?>) {
                if (args.size >= 2 && args[0] == "7") {
                    val uriString = args[1] ?: return
                    runCatching {
                        val uri = URI(uriString)
                        if (uri.scheme == "file" && uri.path != null) _workingDirectory.value = uri.path
                    }
                }
            }
        })

        // OSC 0/1/2 → window/icon title.
        terminal.addApplicationTitleListener(object : TerminalApplicationTitleListener {
            override fun onApplicationTitleChanged(newApplicationTitle: String) {
                display.windowTitle = newApplicationTitle
                _windowTitle.value = newApplicationTitle
            }
            override fun onApplicationIconTitleChanged(newIconTitle: String) {
                display.iconTitle = newIconTitle
                _iconTitle.value = newIconTitle
            }
        })
        terminal.addCommandStateListener(object : CommandStateListener {
            override fun onPromptStarted() {
                display.windowTitle = ""
                _windowTitle.value = ""
                initialPrompt.complete(Unit)
            }
        })
    }

    /** Subscribe to raw PTY output (the byte stream as decoded text) — used to broadcast to clients. */
    fun addRawOutputListener(listener: (String) -> Unit) {
        outputPublisher?.add(listener) ?: dataStream.addRawOutputListener(listener)
    }
    fun removeRawOutputListener(listener: (String) -> Unit) {
        outputPublisher?.remove(listener) ?: dataStream.removeRawOutputListener(listener)
    }

    /** Atomically enqueue a model baseline and subscribe to bytes applied after that baseline. */
    fun attachOutputListener(listener: (String) -> Unit, snapshot: () -> Unit) {
        checkNotNull(outputPublisher) { "Atomic snapshots require TerminalSessionStack.create" }.attach(listener, snapshot)
    }

    private val resizeListeners = java.util.concurrent.CopyOnWriteArrayList<(Int, Int) -> Unit>()

    /** External grid changes share the parser/publication ordering, including the initial size. */
    fun addResizeListener(listener: (Int, Int) -> Unit) {
        val register = {
            resizeListeners.addIfAbsent(listener)
            listener(textBuffer.width, textBuffer.height)
        }
        outputPublisher?.mutate(register) ?: synchronized(resizeLock) { register() }
    }

    fun removeResizeListener(listener: (Int, Int) -> Unit) { resizeListeners.remove(listener) }

    /**
     * Spawn the PTY and start the read/emulate/exit-monitor loops. Idempotent.
     *
     * A TerminalSessionSlots refusal is permanent for this engine: `started` is
     * consumed before the reservation attempt, so a refused core stays a no-op
     * even after capacity frees up. Intentional — start() is one-shot; a caller
     * that wants to retry after the user closes sessions constructs a new core.
     */
    @Synchronized
    fun start() {
        if (closed) return
        if (!started.compareAndSet(false, true)) return // atomic idempotency — never spawn two PTYs
        ownerWatcher = parentScope?.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { close() }
        }
        if (closed) { terminated.complete(Unit); return }
        // Reserve the session's long-lived threads (reader, emulator, waitFor) up front —
        // a refused session must fail visibly instead of queueing on a starved dispatcher
        // and silently never spawning its shell.
        if (reserveThreads && !TerminalSessionSlots.tryReserve()) {
            updateState(State.Error(TerminalSessionSlots.EXHAUSTED_MESSAGE))
            connected.complete(false)
            close()
            return
        }
        slotsReserved = reserveThreads
        launchWriteConsumer()
        startupRequested.set(true)
        scope.launch {
            try {
                val env = buildEnvironment()
                val config = PlatformServices.ProcessService.ProcessConfig(
                    command = effectiveCommand,
                    arguments = ShellIntegrationInjector.argumentsForShell(
                        effectiveCommand, effectiveArguments, env, settings.autoInjectShellIntegration,
                    ),
                    environment = env,
                    workingDirectory = workingDir ?: System.getProperty("user.home"),
                )
                val h = platformServices.getProcessService().spawnProcess(config)
                if (h == null) {
                    updateState(State.Error("Failed to spawn process"))
                    connected.complete(false)
                    // The exit monitor was never armed, so nothing else will run close() —
                    // without it the TerminalSessionSlots reservation leaks permanently.
                    close()
                    return@launch
                }
                val publish = synchronized(this@TerminalSessionEngine) {
                    handle = h
                    !closed
                }
                if (!publish) {
                    // close() raced ahead of the spawn and saw a null handle, so it couldn't kill this
                    // PTY — do it ourselves so the just-spawned shell isn't orphaned.
                    killProcess(h)
                    connected.complete(false)
                    return@launch
                }
                updateState(State.Connected)
                terminal.setTerminalOutput(PtyTerminalOutput())
                onConnected(h, effectiveCommand, effectiveArguments)
                // Sync the PTY winsize to the current grid: a resize that arrived before the handle
                // existed was a no-op on the PTY (only the model grew), so a full-screen app would
                // otherwise see the wrong winsize until the next resize. Default to the initial grid.
                (lastResize ?: (initCols to initRows)).let { (c, r) -> runCatching { h.resize(c, r) } }
                connected.complete(true)
                initialCommand?.takeIf { it.isNotBlank() }?.let { command ->
                    launch {
                        val ready = withTimeoutOrNull(settings.initialCommandDelayMs.toLong().coerceAtLeast(0)) {
                            initialPrompt.await()
                        }
                        if (ready != null) delay(50)
                        onInitialCommandComplete?.let { callback ->
                            val listener = object : CommandStateListener {
                                private var commandStarted = false
                                override fun onCommandStarted() { commandStarted = true }
                                override fun onCommandFinished(exitCode: Int) {
                                    if (!commandStarted) return
                                    try { runCatching { callback(exitCode == 0, exitCode) } }
                                    finally { terminal.removeCommandStateListener(this) }
                                }
                            }
                            terminal.addCommandStateListener(listener)
                        }
                        writeInput(submitLine(command))
                    }
                }

                // Emulator processing loop — drains the data stream into the terminal model.
                // Blocks in dataStream.char between chunks, so it must not hold one of
                // Dispatchers.Default's nCPU permits.
                val emulatorJob = launch(TerminalSessionDispatcher) {
                    drainTerminalEmulator(
                        emulator = emulator,
                        dataStream = dataStream,
                        terminal = terminal,
                        shouldContinue = { !closed },
                        processCharacter = { ch ->
                            outputPublisher?.process(ch) { emulator.processChar(it, terminal) }
                                ?: emulator.processChar(ch, terminal)
                        },
                        onProcessingError = { e ->
                            log.warn("emulator processing error: {}", e.message)
                            if (!closed) {
                                updateState(State.Error("Terminal output processing failed: ${e.message}", e))
                                close()
                            }
                        },
                    )
                }

                // PTY reader loop — grapheme-safe chunking without dropping oversized reads.
                // Blocks in h.read() for the session's whole life, kept off the shared IO permits.
                val readerJob = launch(TerminalSessionDispatcher) {
                    val maxChunkSize = 64 * 1024
                    try {
                        while (!closed) {
                            try {
                                // read() returns null on EOF / shutdown (a healthy zero-byte read is
                                // ""); `continue` here would busy-spin at 100% CPU in the window before
                                // isAlive() flips false. Break out instead.
                                val output = h.read() ?: break
                                var offset = 0
                                while (offset < output.length) {
                                    val remaining = output.substring(offset)
                                    val size = if (remaining.length > maxChunkSize) {
                                        GraphemeBoundaryUtils.findLastCompleteGraphemeBoundary(remaining, maxChunkSize)
                                            .takeIf { it > 0 } ?: maxChunkSize
                                    } else remaining.length
                                    dataStream.append(remaining.substring(0, size))
                                    offset += size
                                }
                            } catch (e: java.io.IOException) {
                                if (!closed && h.isAlive()) {
                                    updateState(State.Error("Terminal read failed: ${e.message}", e))
                                    close()
                                }
                                break
                            }
                        }
                    } finally {
                        runCatching { dataStream.close() }
                    }
                }

                // All three blocking lanes are children of the same lifecycle job. Its completion
                // releases the reservation only after reader, emulator, and process wait unwind.
                launch(TerminalSessionDispatcher) {
                    exitCode = h.waitFor()
                    // Process exit does not imply its final output has been read or emulated.
                    // Drain EOF completely; explicit close cancels these waits and kills the PTY.
                    readerJob.join()
                    emulatorJob.join()
                    if (closed) return@launch
                    updateState(State.Exited)
                    runCatching { onProcessExit(exitCode) }
                    // Fire onExit at most once even if reaping races a concurrent closeSession.
                    if (exitNotified.compareAndSet(false, true)) runCatching { onExit?.invoke() }
                    close()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) { close(); return@launch }
                if (!closed) updateState(State.Error("Terminal initialization failed: ${e.message}", e))
                connected.complete(false)
                if (!closed) log.error("session {} init failed: {}", id, e.message)
                // The exit monitor was never armed, so nothing else will run close() —
                // without it the TerminalSessionSlots reservation leaks permanently.
                // close() also kills a half-spawned PTY via `handle`, and is a no-op
                // if a concurrent closeSession already ran.
                close()
            }
        }.invokeOnCompletion { failure ->
            startupComplete.countDown()
            if (!closed) {
                if (failure != null && failure !is kotlinx.coroutines.CancellationException) {
                    updateState(State.Error("Terminal session failed: ${failure.message}", failure))
                }
                close()
            }
            if (slotsReserved && slotsReleased.compareAndSet(false, true)) TerminalSessionSlots.release()
            terminated.complete(Unit)
        }
    }

    // One non-suspending trySend per write: on the unbounded channel it cannot fail while the
    // session is open, so the keystroke path has no coroutine launch and ordering is structural
    // (see [writeChannel]). After close() the channel is closed and the write is dropped — the
    // PTY is gone anyway.
    fun writeInput(text: String) {
        enqueueWrite(WriteOp.Text(text), text.length)
    }

    fun writeBytes(bytes: ByteArray) {
        enqueueWrite(WriteOp.Raw(bytes.copyOf()), bytes.size)
    }

    // Depth gauge for the write pipe: units (chars for Text, bytes for Raw — close enough for a
    // ceiling) enqueued but not yet written to the PTY. The channel itself is unbounded so
    // ordering stays structural; this counter is the safety ceiling a wedged PTY needs — its
    // write() blocks forever, and a runaway programmatic producer (MCP send_input loop, giant
    // paste) would otherwise grow a weeks-lived daemon's heap without bound. At the ceiling we
    // log-and-drop: those bytes were never going to reach a wedged PTY anyway.
    private val pendingWriteUnits = java.util.concurrent.atomic.AtomicLong(0)

    private fun enqueueWrite(op: WriteOp, units: Int) {
        if (closed || (units == 0 && op !is WriteOp.Resize)) return
        // Add first, then check-and-rollback: a check-then-add gate is racy with several producers
        // (WS reader, MCP, emulator replies) — each could pass the gate before any increments and
        // overshoot the ceiling together. Reserving up front bounds steady-state at the ceiling;
        // the transient overshoot is at most one in-flight op per concurrent producer.
        val pending = pendingWriteUnits.addAndGet(units.toLong())
        if (pending > MAX_PENDING_WRITE_UNITS) {
            pendingWriteUnits.addAndGet(-units.toLong())
            log.warn("session {}: write queue saturated ({} units pending, PTY not draining); dropping {} units",
                id, pending - units, units)
            return
        }
        if (writeChannel.trySend(op).isFailure) pendingWriteUnits.addAndGet(-units.toLong()) // closed
    }

    // Last requested grid size, so a resize that arrives before the PTY is spawned (handle still null)
    // is replayed onto the PTY in start() instead of being silently dropped.
    @Volatile private var lastResize: Pair<Int, Int>? = null
    private val resizeRequestLock = Any()
    // A partial CSI/OSC can hold the publication lock indefinitely. Keep only the latest
    // requested grid while it does, without parking the UI or the PTY input consumer.
    private var pendingResize: Pair<Int, Int>? = null
    private var resizeWorkerRunning = false
    @Volatile private var resizeWorker: Job? = null

    /** Serialize model resize with parsing/snapshot capture, then enqueue the matching PTY size. */
    fun resize(cols: Int, rows: Int) {
        if (closed) return // don't launch a SIGWINCH on a closing session (model/PTY would diverge)
        val c = cols.coerceIn(1, MAX_GRID_DIM)
        val r = rows.coerceIn(1, MAX_GRID_DIM)
        synchronized(resizeRequestLock) {
            if (closed) return
            pendingResize = c to r
            if (applyPendingResize()) return
            if (!resizeWorkerRunning) {
                resizeWorkerRunning = true
                resizeWorker = scope.launch {
                    while (isActive) {
                        val done = synchronized(resizeRequestLock) {
                            if (closed || applyPendingResize()) {
                                resizeWorkerRunning = false
                                true
                            } else false
                        }
                        if (done) return@launch
                        delay(10)
                    }
                }
            }
        }
    }

    /** Caller owns resizeRequestLock, so a deferred older grid cannot overtake a new one. */
    private fun applyPendingResize(): Boolean {
        val (c, r) = pendingResize ?: return true
        val apply = {
            pendingResize = null
            if (!closed) {
                lastResize = c to r
                terminal.resize(TermSize(c, r), RequestOrigin.User)
                resizeListeners.forEach { listener -> runCatching { listener(c, r) } }
                enqueueWrite(WriteOp.Resize(c, r), 0)
            }
        }
        return runCatching {
            outputPublisher?.tryMutate(apply) ?: synchronized(resizeLock) { apply(); true }
        }.getOrElse { true }
    }

    private val resizeLock = Any()

    /**
     * Whether this session is live. Reports true while the PTY is still spawning (started, handle not
     * yet set) so `list_sessions` right after `open_session` doesn't show a healthy session as dead;
     * false once it has exited, errored, or been closed (and before [start] is ever called).
     */
    fun isAlive(): Boolean {
        if (closed) return false
        return when (state.value) {
            State.Connected -> handle?.isAlive() ?: true // handle is set before state flips to Connected
            State.Initializing -> started.get()          // started but PTY not up yet — coming alive
            else -> false                                // Error / Exited
        }
    }

    /**
     * Kill the PTY and cancel all loops. Idempotent. Returns the kill thread (or null) so a caller
     * tearing the daemon down can [Thread.join] it — otherwise the JVM may exit before the blocking
     * destroy finishes, orphaning the child shell (Unix doesn't reap children on parent exit).
     */
    @Synchronized
    fun close(): Thread? {
        if (closed) return killer
        closed = true
        connected.complete(false)
        writeChannel.cancel()
        val h = handle
        runCatching { dataStream.close() }
        // Publish the one kill task before cancellation/exit callbacks can re-enter close().
        if (h != null || startupRequested.get()) {
            killer = Thread({
                if (h == null) runCatching { startupComplete.await(2, java.util.concurrent.TimeUnit.SECONDS) }
                (h ?: handle)?.let(::killProcess)
            }, "bossterm-session-kill-$id").apply { isDaemon = true }
            killer?.start()
        }
        ownerWatcher?.cancel()
        scope.cancel()
        if (!started.get() || (started.get() && !slotsReserved && reserveThreads)) terminated.complete(Unit)
        if (exitNotified.compareAndSet(false, true)) runCatching { onExit?.invoke() }
        return killer
    }

    private fun killProcess(process: PlatformServices.ProcessService.ProcessHandle) {
        if (processKilled.compareAndSet(false, true)) runCatching { runBlocking { process.kill() } }
    }

    /** Wait for all I/O consumers and process teardown, including the independent kill task. */
    suspend fun awaitTermination() {
        terminated.await()
        writeConsumer?.join()
        resizeWorker?.join()
        kotlinx.coroutines.withContext(Dispatchers.IO) { killer?.join() }
    }

    /** A UI may expose a handle while keeping every input/reply/resize on the engine's FIFO. */
    fun processHandleAdapter(actual: PlatformServices.ProcessService.ProcessHandle): PlatformServices.ProcessService.ProcessHandle =
        object : PlatformServices.ProcessService.ProcessHandle by actual {
            override suspend fun write(data: String) { writeInput(data) }
            override suspend fun writeBytes(data: ByteArray) { this@TerminalSessionEngine.writeBytes(data) }
            override suspend fun resize(columns: Int, rows: Int) { this@TerminalSessionEngine.resize(columns, rows) }
            override suspend fun kill() {
                val killer = close()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { killer?.join(3000) }
            }
        }

    private fun updateState(value: State) {
        _connectionState.value = value
        runCatching { onStateChanged(value) }
    }

    // ---- internals ----

    private sealed class WriteOp {
        data class Text(val data: String) : WriteOp()
        class Raw(val data: ByteArray) : WriteOp()
        data class Resize(val cols: Int, val rows: Int) : WriteOp()
    }

    private fun opUnits(op: WriteOp): Long = when (op) {
        is WriteOp.Text -> op.data.length.toLong()
        is WriteOp.Raw -> op.data.size.toLong()
        is WriteOp.Resize -> 0L
    }

    // A single UNBOUNDED FIFO pipe for everything written to the PTY — user input, pastes, and
    // emulator replies. Unbounded so every producer is one non-suspending trySend: a bounded
    // channel needs a suspending fallback under backpressure, and any two-path enqueue can reorder
    // a write past one that arrived earlier once a slot frees (silent corruption of a byte
    // stream). Not a new memory risk: the bounded version parked each overflowing write in its own
    // suspended coroutine, holding the same bytes with more overhead — a wedged PTY grew the heap
    // either way, and input volume is human/MCP-scale.
    private val writeChannel = Channel<WriteOp>(capacity = Channel.UNLIMITED)
    @Volatile private var writeConsumer: Job? = null

    /**
     * Launch the single coroutine that drains [writeChannel] → PTY. Started from [start] (NOT eagerly at
     * construction) so an engine that is built but never started doesn't park a coroutine forever on
     * `connected.await()`, leaking it until `scope` is cancelled.
     */
    private fun launchWriteConsumer() {
        writeConsumer = scope.launch(Dispatchers.IO) {
            // Don't drain until the PTY is up, so input sent right after open isn't written to a null
            // handle and dropped. If we never connect (spawn failed / closed early), just exit.
            if (!connected.await()) return@launch
            for (op in writeChannel) {
                try {
                    when (op) {
                        is WriteOp.Text -> handle?.write(op.data)
                        is WriteOp.Raw -> handle?.writeBytes(op.data)
                        is WriteOp.Resize -> handle?.resize(op.cols, op.rows)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!closed) {
                        updateState(State.Error("Terminal input failed: ${e.message}", e))
                        close()
                    }
                } finally {
                    // A failed write leaves the queue too, so it must release its depth units.
                    pendingWriteUnits.addAndGet(-opUnits(op))
                }
            }
        }
    }

    /**
     * Routes emulator-generated replies (DA, cursor reports, …) back to the PTY. Enqueued onto the
     * SAME [writeChannel] as user input, so a reply can't interleave its bytes mid-sequence with a
     * keystroke — a single [writeConsumer] owns the PTY's outputStream. trySend (non-suspending)
     * keeps the emulator thread unblocked and, on the unbounded channel, never drops while the
     * session is open. Ordered with input, as the queue guarantees.
     */
    private inner class PtyTerminalOutput : TerminalOutputStream {
        override fun sendBytes(response: ByteArray, userInput: Boolean) {
            writeBytes(response)
        }
        override fun sendString(string: String, userInput: Boolean) {
            enqueueWrite(WriteOp.Text(string), string.length)
        }
    }

    /** Build terminal environment; display/transport adapters supply their own additions. */
    private fun buildEnvironment(): MutableMap<String, String> {
        val env = buildMap {
            putAll(System.getenv().filterKeys { key ->
                !key.startsWith("ITERM_") && !key.startsWith("KITTY_") &&
                    key != "TERM_SESSION_ID" && key != "PWD" && key != "OLDPWD"
            })
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("TERM_PROGRAM", "BossTerm")
            putBossTermGraphicsEnvironment(id)
            put("TERM_FEATURES", "T2:M:H:Ts0:Ts1:Ts2:Sc0:Sc1:Sc2:B:U:Aw")
            put("PWD", workingDir ?: System.getProperty("user.home"))
            putAll(environmentOverrides)
        }.toMutableMap()
        ShellIntegrationInjector.injectForShell(effectiveCommand, env, settings.autoInjectShellIntegration)
        return env
    }

}
