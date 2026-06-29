package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.tabs.ShellIntegrationInjector
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.PerformanceMode
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalCustomCommandListener
import ai.rever.bossterm.terminal.TerminalOutputStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalApplicationTitleListener
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.util.GraphemeBoundaryUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.net.URI
import java.util.UUID

/**
 * The Compose-free terminal session the **daemon** owns: PTY + emulator + terminal + text buffer +
 * data stream + I/O coroutines + cwd/title OSC tracking. It is the headless equivalent of the
 * GUI's [ai.rever.bossterm.compose.tabs.TerminalTab] — same terminal stack, none of the UI state
 * (focus, selection, search, IME, context menu, …) and a [HeadlessTerminalDisplay] in place of the
 * Compose one. Authoritative state is exposed as `StateFlow` so attached GUIs / MCP can observe it.
 *
 * Output is broadcast to attached clients via the data stream's raw-output tap (see
 * [addRawOutputListener]) — the exact mechanism [ai.rever.bossterm.compose.share.MirrorShare] uses
 * today — so a daemon session and a GUI tab are wire-compatible. Input arrives via [writeInput] /
 * [writeBytes], which share one FIFO queue (keystrokes, pastes, and emulator replies stay ordered).
 *
 * The wiring mirrors `TabController.createTab` + `initializeTerminalSession` deliberately so daemon
 * sessions behave identically to in-process tabs (env, login-shell args, shell-integration inject,
 * grapheme-safe chunking, EOF handling).
 */
class TerminalSessionCore(
    val id: String = UUID.randomUUID().toString(),
    private val settings: TerminalSettings,
    private val workingDir: String?,
    command: String? = null,
    arguments: List<String> = emptyList(),
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val platformServices: PlatformServices = getPlatformServices(),
) {
    private val log = LoggerFactory.getLogger(TerminalSessionCore::class.java)

    // ---- terminal stack (all Compose-free core types) ----
    // Clamp the requested grid: a caller (MCP open_session / attach Open) can pass 0 or an absurd
    // value, which would otherwise build a degenerate or huge buffer/display. [resize] guards the same.
    private val initCols = initialCols.coerceIn(1, MAX_GRID_DIM)
    private val initRows = initialRows.coerceIn(1, MAX_GRID_DIM)
    private val styleState = StyleState()
    val textBuffer: TerminalTextBuffer = TerminalTextBuffer(initCols, initRows, styleState, settings.bufferMaxLines)
    val display: HeadlessTerminalDisplay = HeadlessTerminalDisplay(initCols, initRows)
    val terminal: BossTerminal = BossTerminal(display, textBuffer, styleState)
    val dataStream: BlockingTerminalDataStream =
        BlockingTerminalDataStream(performanceMode = PerformanceMode.fromString(settings.performanceMode))
    val emulator: BossEmulator = BossEmulator(dataStream, terminal)

    // ---- resolved launch command (login-shell defaults match TabController.createTab) ----
    private val effectiveCommand: String
    private val effectiveArguments: List<String>

    // ---- observable state ----
    private val _workingDirectory = MutableStateFlow(workingDir)
    val workingDirectory: StateFlow<String?> = _workingDirectory.asStateFlow()

    private val _connectionState = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _connectionState.asStateFlow()

    /** Window/icon title as set by OSC 0/1/2; useful for tab labels on attached clients. */
    val windowTitle: StateFlow<String> get() = display.windowTitleFlow

    @Volatile private var handle: PlatformServices.ProcessService.ProcessHandle? = null
    val processHandle: PlatformServices.ProcessService.ProcessHandle? get() = handle

    /** Invoked once when the shell process exits (so the host registry can reap the session). */
    var onExit: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var closed = false

    // Completes true once the PTY is spawned (Connected), false on failure/close. The write
    // consumer waits on it so input sent immediately after openSession() is buffered, not dropped.
    private val connected = CompletableDeferred<Boolean>()

    sealed class State {
        object Initializing : State()
        object Connected : State()
        data class Error(val message: String) : State()
        object Exited : State()
    }

    private companion object {
        /** Upper clamp for a requested grid dimension — generous; just guards against absurd values. */
        const val MAX_GRID_DIM = 2000
    }

    init {
        val (cmd, args) = resolveCommand(command, arguments)
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
            override fun onApplicationTitleChanged(newApplicationTitle: String) { display.windowTitle = newApplicationTitle }
            override fun onApplicationIconTitleChanged(newIconTitle: String) { display.iconTitle = newIconTitle }
        })
    }

    /** Subscribe to raw PTY output (the byte stream as decoded text) — used to broadcast to clients. */
    fun addRawOutputListener(listener: (String) -> Unit) = dataStream.addRawOutputListener(listener)
    fun removeRawOutputListener(listener: (String) -> Unit) = dataStream.removeRawOutputListener(listener)

    /** Spawn the PTY and start the read/emulate/exit-monitor loops. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                val env = buildEnvironment()
                val config = PlatformServices.ProcessService.ProcessConfig(
                    command = effectiveCommand,
                    arguments = effectiveArguments,
                    environment = env,
                    workingDirectory = workingDir ?: System.getProperty("user.home"),
                )
                val h = platformServices.getProcessService().spawnProcess(config)
                if (h == null) {
                    _connectionState.value = State.Error("Failed to spawn process")
                    connected.complete(false)
                    return@launch
                }
                handle = h
                _connectionState.value = State.Connected
                connected.complete(true)
                terminal.setTerminalOutput(PtyTerminalOutput())

                // Emulator processing loop — drains the data stream into the terminal model.
                launch(Dispatchers.Default) {
                    try {
                        while (h.isAlive()) {
                            try {
                                emulator.processChar(dataStream.char, terminal)
                            } catch (_: EOFException) {
                                break
                            } catch (e: Exception) {
                                if (e !is ai.rever.bossterm.terminal.TerminalDataStream.EOF) {
                                    log.warn("emulator processing error: {}", e.message)
                                }
                                break
                            }
                        }
                    } finally {
                        dataStream.close()
                    }
                }

                // PTY reader loop — grapheme-safe chunking, matches TabController.startPtyReaderCoroutine.
                launch(Dispatchers.IO) {
                    val maxChunkSize = 64 * 1024
                    try {
                        while (h.isAlive()) {
                            try {
                                val output = h.read() ?: continue
                                val processed = if (output.length > maxChunkSize) {
                                    val safe = GraphemeBoundaryUtils.findLastCompleteGraphemeBoundary(output, maxChunkSize)
                                    output.substring(0, safe)
                                } else output
                                dataStream.append(processed)
                            } catch (e: java.io.IOException) {
                                break
                            }
                        }
                    } finally {
                        runCatching { dataStream.close() }
                    }
                }

                // Exit monitor on a DEDICATED thread — not this coroutine — so we don't pin a
                // Dispatchers.IO pool thread (shared with the reader loops) in a blocking waitFor for
                // the whole life of the session. On exit we self-close so the scope + write consumer
                // + write channel are torn down (otherwise a naturally-exited shell leaks them).
                Thread({
                    runCatching { runBlocking { h.waitFor() } }
                    _connectionState.value = State.Exited
                    runCatching { onExit?.invoke() }
                    close()
                }, "bossterm-session-exit-$id").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                _connectionState.value = State.Error("Terminal initialization failed: ${e.message}")
                connected.complete(false)
                log.error("session {} init failed: {}", id, e.message)
            }
        }
    }

    fun writeInput(text: String) {
        scope.launch { runCatching { writeChannel.send(WriteOp.Text(text)) } }
    }

    fun writeBytes(bytes: ByteArray) {
        scope.launch { runCatching { writeChannel.send(WriteOp.Raw(bytes)) } }
    }

    /** Resize both the buffer and the PTY (SIGWINCH). The daemon's authoritative grid size. */
    fun resize(cols: Int, rows: Int) {
        val c = cols.coerceIn(1, MAX_GRID_DIM)
        val r = rows.coerceIn(1, MAX_GRID_DIM)
        runCatching { terminal.resize(TermSize(c, r), RequestOrigin.User) }
        scope.launch { runCatching { handle?.resize(c, r) } }
    }

    fun isAlive(): Boolean = handle?.isAlive() == true

    /**
     * Kill the PTY and cancel all loops. Idempotent. Returns the kill thread (or null) so a caller
     * tearing the daemon down can [Thread.join] it — otherwise the JVM may exit before the blocking
     * destroy finishes, orphaning the child shell (Unix doesn't reap children on parent exit).
     */
    fun close(): Thread? {
        if (closed) return null
        closed = true
        connected.complete(false) // release a write consumer still waiting to start
        writeChannel.close()
        val h = handle
        runCatching { dataStream.close() }
        scope.cancel()
        // Kill on a dedicated thread — NOT a child of `scope` (we just cancelled it), so the
        // blocking destroy actually runs instead of being cancelled before dispatch. kill() closes
        // streams + destroys + waits up to 2s. (No-op fast when the process already exited.)
        return if (h != null) {
            Thread({ runCatching { runBlocking { h.kill() } } }, "bossterm-session-kill-$id")
                .apply { isDaemon = true; start() }
        } else null
    }

    // ---- internals ----

    private sealed class WriteOp {
        data class Text(val data: String) : WriteOp()
        class Raw(val data: ByteArray) : WriteOp()
    }

    private val writeChannel = Channel<WriteOp>(capacity = 256)
    private val writeConsumer = scope.launch(Dispatchers.IO) {
        // Don't drain until the PTY is up, so input sent right after open isn't written to a null
        // handle and dropped. If we never connect (spawn failed / closed early), just exit.
        if (!connected.await()) return@launch
        for (op in writeChannel) {
            try {
                when (op) {
                    is WriteOp.Text -> handle?.write(op.data)
                    is WriteOp.Raw -> handle?.writeBytes(op.data)
                }
            } catch (e: java.io.IOException) {
                log.debug("PTY write failed (likely closed): {}", e.message)
            }
        }
    }

    /**
     * Routes emulator-generated replies (DA, cursor reports, …) back to the PTY. Enqueued onto the
     * SAME [writeChannel] as user input, so a reply can't interleave its bytes mid-sequence with a
     * keystroke — a single [writeConsumer] owns the PTY's outputStream. trySend (non-suspending) keeps
     * the emulator thread unblocked; a reply is dropped only if 256 writes are already queued (never in
     * practice). Ordered with input, as the queue guarantees.
     */
    private inner class PtyTerminalOutput : TerminalOutputStream {
        override fun sendBytes(response: ByteArray, userInput: Boolean) {
            writeChannel.trySend(WriteOp.Raw(response))
        }
        override fun sendString(string: String, userInput: Boolean) {
            writeChannel.trySend(WriteOp.Text(string))
        }
    }

    /** Mirrors TabController's env: filter host vars, set TERM*, inject shell integration. */
    private fun buildEnvironment(): MutableMap<String, String> {
        val env = buildMap {
            putAll(System.getenv().filterKeys { key ->
                !key.startsWith("ITERM_") && !key.startsWith("KITTY_") &&
                    key != "TERM_SESSION_ID" && key != "PWD" && key != "OLDPWD"
            })
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("TERM_PROGRAM", "BossTerm")
            put("TERM_FEATURES", "T2:M:H:Ts0:Ts1:Ts2:Sc0:Sc1:Sc2:B:U:Aw")
            put("PWD", workingDir ?: System.getProperty("user.home"))
        }.toMutableMap()
        ShellIntegrationInjector.injectForShell(effectiveCommand, env, settings.autoInjectShellIntegration)
        return env
    }

    /** Resolve shell + args exactly as TabController.createTab (login session on macOS, -l for zsh/bash). */
    private fun resolveCommand(command: String?, arguments: List<String>): Pair<String, List<String>> {
        val isMacOS = ShellCustomizationUtils.isMacOS()
        val username = System.getProperty("user.name")
        return if (command == null && arguments.isEmpty() && isMacOS && username != null && settings.useLoginSession && workingDir == null) {
            "/usr/bin/login" to listOf("-fp", username)
        } else {
            val shellCommand = command ?: ShellCustomizationUtils.getValidShell(settings.windowsShell)
            val shellArgs = if (arguments.isEmpty() &&
                (shellCommand.endsWith("/zsh") || shellCommand.endsWith("/bash") ||
                    shellCommand == "zsh" || shellCommand == "bash")) {
                listOf("-l")
            } else arguments
            shellCommand to shellArgs
        }
    }
}
