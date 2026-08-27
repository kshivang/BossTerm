package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.rendering.FrameLatencyProbe
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import ai.rever.bossterm.terminal.model.TerminalSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Compose implementation of TerminalDisplay.
 *
 * Redraws are coalesced by the CONFLATED [redrawChannel] and, downstream of it, by Compose
 * itself: many writes to [_redrawTrigger] between two frames collapse into one
 * recomposition. Nothing here waits on a clock.
 *
 * It used to. An adaptive debounce slept 8 ms per redraw, and 50 ms once output passed 100
 * redraws/sec, to cut redraw COUNT on large files. Measured against the thing a user
 * actually feels, that trade was bad: an isolated keystroke echo took 16.4 ms, of which only
 * 1.8 ms was downstream of the redraw trigger. Removing the wait (with the data stream's own
 * poll, see TerminalSettings.performanceMode) takes it to ~2.0 ms, and `byteToPaint` then
 * equals `triggerToPaint`, which is the signature of nothing being spent before the trigger.
 *
 * Frame counts stay vsync-capped (~62/sec) without it, so the sleep was never what kept the
 * terminal from over-rendering: the frame clock was, and the CONFLATED channel plus Compose's
 * per-frame coalescing already do the job this was added for.
 *
 * What it does NOT fix is bulk output: a 5 MB `cat` still shows a ~983 ms p95, down only
 * ~25% from 1310 ms, and on that workload `triggerToPaint` is 6.7 ms against a `byteToPaint`
 * of 491 ms. That ~485 ms is queue wait and parse, upstream of anything here.
 *
 * See `benchmark_results/LATENCY_BASELINE_2026-08-27.md`.
 */
class ComposeTerminalDisplay : TerminalDisplay {

    /**
     * Redraw request. Carries no priority any more: with the debounce gone, an "immediate"
     * and a "normal" request do exactly the same thing.
     */
    data class RedrawRequest(
        val timestamp: Long = System.currentTimeMillis(),
    )

    /**
     * The four cursor fields as ONE value.
     *
     * Published through an AtomicReference rather than four @Volatile fields because
     * [setCursor] writes x and then y: a reader landing between the two sees a position the
     * terminal was never in, and the emulator moves the cursor many times per rendered frame.
     * Still non-reactive - only `redrawTrigger` drives recomposition.
     */
    data class CursorSnapshot(
        val x: Int,
        val y: Int,
        val visible: Boolean,
        val shape: CursorShape?,
    )

    // Channel for queuing redraw requests with conflation
    private val redrawChannel = Channel<RedrawRequest>(Channel.CONFLATED)

    /**
     * Whether a redraw is already queued and unclaimed.
     *
     * The channel is CONFLATED, so a second `trySend` is harmless to correctness - but it
     * is NOT free. When the processor is parked on the channel, each send resumes its
     * continuation through the Swing dispatcher, and that means `EventQueue.invokeLater`,
     * an `InvocationEvent`, and an `AccessController.getContext` stack walk, per call. The
     * emulator requests a redraw on every buffer mutation, so bulk output turned that into
     * an AWT event storm: stack-sampling the parse thread under load put 20% of its time
     * in `AWTEvent.<init>` beneath `requestRedraw`.
     *
     * Cleared by the processor BEFORE it redraws, so a mutation that lands during a redraw
     * still schedules the next one.
     */
    private val redrawQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    // Coroutine scope for redraw processing
    private val redrawScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // Start redraw processor coroutine
        startRedrawProcessor()
    }
    // Non-reactive cursor state - only redrawTrigger controls recomposition
    // This prevents flickering caused by Compose State updates racing with debounced redraws
    private val _cursor = AtomicReference(
        CursorSnapshot(x = 0, y = 0, visible = true, shape = null)
    )
    private val _bracketedPasteMode = mutableStateOf(false)
    private val _termSize = mutableStateOf(TermSize(80, 24))

    // ===== SYNCHRONIZED UPDATE MODE (DEC Private Mode 2026) =====
    // When enabled, redraws are suppressed until mode is disabled.
    // This reduces flicker for applications that send many escape sequences rapidly.
    //
    // Uses synchronized() instead of Kotlin Mutex because:
    // - requestRedraw() is NOT a suspend function (Mutex.withLock requires suspend)
    // - Critical section is extremely short (nanoseconds) - no suspension benefit
    // - High-frequency calls need low overhead - synchronized is JVM-optimized
    // - Converting to Mutex would require making requestRedraw() suspend (breaking change)
    private val syncUpdateLock = Any()
    @Volatile private var _synchronizedUpdateEnabled = false
    @Volatile private var _pendingRedrawDuringSync = false
    private var synchronizedUpdateGeneration = 0L
    private val _windowTitle = MutableStateFlow("")
    private val _iconTitle = MutableStateFlow("")
    private val _mouseMode = mutableStateOf(MouseMode.MOUSE_REPORTING_NONE)
    private val _bellTrigger = mutableStateOf(0)
    private val _progressState = mutableStateOf(TerminalDisplay.ProgressState.HIDDEN)
    private val _progressValue = mutableStateOf(0)

    /** All four cursor fields at once - see [CursorSnapshot]. Non-reactive. */
    val cursorSnapshot: CursorSnapshot get() = _cursor.get()

    // Single-field getters for callers that genuinely want one value (mirror share, tests).
    // A renderer must use cursorSnapshot instead: reading these one at a time reintroduces
    // exactly the tear the AtomicReference exists to close.
    val cursorXSnapshot: Int get() = _cursor.get().x
    val cursorYSnapshot: Int get() = _cursor.get().y
    val cursorVisibleSnapshot: Boolean get() = _cursor.get().visible
    val cursorShapeSnapshot: CursorShape? get() = _cursor.get().shape
    val bracketedPasteMode: State<Boolean> = _bracketedPasteMode
    val termSize: State<TermSize> = _termSize
    val mouseMode: State<MouseMode> = _mouseMode
    val bellTrigger: State<Int> = _bellTrigger
    val progressState: State<TerminalDisplay.ProgressState> = _progressState
    val progressValue: State<Int> = _progressValue
    /**
     * The app's OSC 2 window title.
     *
     * Empty is a RESET, not merely "nothing yet": TabController clears it at each prompt start
     * so a title set by a program that has since exited stops naming the window. Consumers should
     * fall back to something of their own rather than showing a blank - see
     * `resolveWindowTitle` in TabbedTerminal, which falls back to the tab title.
     *
     * The reset needs OSC 133, so it does not happen inside tmux/screen or in a session with no
     * shell integration; there a title outlives the program that set it, as it always has.
     */
    val windowTitleFlow: StateFlow<String> = _windowTitle.asStateFlow()
    val iconTitleFlow: StateFlow<String> = _iconTitle.asStateFlow()

    // Trigger for redraw - increment this to force redraw
    private val _redrawTrigger = mutableStateOf(0)
    val redrawTrigger: State<Int> = _redrawTrigger

    // Cursor debugging (can be disabled by setting to false)
    private val debugCursor = System.getenv("BOSSTERM_DEBUG_CURSOR")?.toBoolean() ?: false

    /**
     * Callback for logging internal errors and warnings to the debug collector.
     * Set by TabController to route logs to the appropriate tab's debug panel.
     * When null, logs only go to System.err.
     */
    var debugLogCallback: ((String) -> Unit)? = null

    /**
     * Reference to the current redraw processor job.
     * Used to cancel the previous job when auto-restarting to prevent coroutine leaks.
     */
    private var redrawJob: kotlinx.coroutines.Job? = null

    /**
     * Log an error/warning to both System.err and the debug collector.
     * This ensures errors are visible in both the console and the debug panel.
     */
    private fun logError(message: String, exception: Exception? = null) {
        val timestamp = java.time.Instant.now().toString()
        val fullMessage = if (exception != null) {
            "[$timestamp] $message\n${exception.stackTraceToString()}"
        } else {
            "[$timestamp] $message"
        }
        System.err.println(fullMessage)
        debugLogCallback?.invoke(fullMessage)
    }

    /**
     * Cursor state independence: Cursor position, shape, and visibility are managed
     * independently from buffer snapshots and do NOT trigger redraws automatically.
     *
     * This is intentional behavior because:
     * 1. Cursor can blink without buffer content changes
     * 2. Cursor moves independently during editing operations
     * 3. Cursor updates are frequent and don't require buffer re-snapshotting
     *
     * The UI layer observes cursor state via separate Compose State variables
     * (cursorX, cursorY, cursorVisible, cursorShape) which trigger recomposition
     * only of cursor-rendering code, not the entire buffer.
     *
     * Buffer content changes that move the cursor will trigger redraws via
     * scrollArea() or other buffer modification methods.
     */
    override fun setCursor(x: Int, y: Int) {
        // Unchanged is the common case - BossTerminal.finishText() calls this after every
        // writeCharacters - and copy() would allocate a CursorSnapshot for each one. Bail
        // before the allocation, the debug print and the redraw bookkeeping alike.
        //
        // Check-then-act, deliberately: the AtomicReference is here so READERS see x and y
        // together, not to make this method atomic against a second writer. The emulator thread
        // is the only writer, and was the only writer when these were two volatile ints. A
        // second one would need this guard folded into the update.
        val current = _cursor.get()
        if (current.x == x && current.y == y) return
        if (debugCursor) {
            println("🔵 CURSOR MOVE: (${current.x},${current.y}) → ($x,$y)")
        }
        // One atomic update, not two field writes: x and y have to land together or a reader
        // between them sees a position the terminal was never in.
        _cursor.getAndUpdate { it.copy(x = x, y = y) }
        // Trigger redraw when cursor moves - fixes p10k/zsh TUI not updating
        // Cursor-only changes (no buffer modification) still need screen refresh
        requestRedraw()
    }

    override fun setCursorShape(cursorShape: CursorShape?) {
        val current = _cursor.get()
        if (current.shape == cursorShape) return
        if (debugCursor) {
            println("🔷 CURSOR SHAPE: ${current.shape} → $cursorShape")
        }
        _cursor.getAndUpdate { it.copy(shape = cursorShape) }
        requestRedraw()
    }

    override fun setCursorVisible(isCursorVisible: Boolean) {
        val current = _cursor.get()
        if (current.visible == isCursorVisible) return
        if (debugCursor) {
            println("👁️  CURSOR VISIBLE: ${current.visible} → $isCursorVisible")
        }
        _cursor.getAndUpdate { it.copy(visible = isCursorVisible) }
        requestRedraw()
    }

    override fun beep() {
        // Increment bell trigger - UI layer observes this and handles sound/visual bell
        _bellTrigger.value++
    }

    override fun setProgress(state: TerminalDisplay.ProgressState, progress: Int) {
        _progressState.value = state
        _progressValue.value = progress.coerceIn(-1, 100)
    }

    override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) {
        // Note: This method is only called for actual scrolling operations (cursor past bottom, etc.)
        // Regular text output is handled by the ModelListener registered on TerminalTextBuffer
        requestRedraw()
    }

    override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {
        // Buffer switch is handled by TerminalTextBuffer, but we need to trigger redraw
        // to ensure the screen refreshes when switching between main and alternate buffer
        requestImmediateRedraw()
    }

    override var windowTitle: String?
        get() = _windowTitle.value
        set(value) {
            _windowTitle.value = value ?: ""
        }

    override var iconTitle: String?
        get() = _iconTitle.value
        set(value) {
            _iconTitle.value = value ?: ""
        }

    override val selection: TerminalSelection?
        get() {
            // No selection support yet
            return null
        }

    override fun terminalMouseModeSet(mouseMode: MouseMode) {
        _mouseMode.value = mouseMode
    }

    /**
     * Check if terminal is in mouse reporting mode.
     * @return true if mouse events should be forwarded to terminal application
     */
    fun isMouseReporting(): Boolean {
        return _mouseMode.value != MouseMode.MOUSE_REPORTING_NONE
    }

    override fun setMouseFormat(mouseFormat: MouseFormat) {
        // No-op for now - mouse format handling could be added later
    }

    override fun ambiguousCharsAreDoubleWidth(): Boolean {
        // Default to false
        return false
    }

    override fun setBracketedPasteMode(bracketedPasteModeEnabled: Boolean) {
        _bracketedPasteMode.value = bracketedPasteModeEnabled
    }

    override fun onResize(newTermSize: TermSize, origin: RequestOrigin) {
        // Update terminal size state when resize happens (from user window resize or remote app request)
        _termSize.value = newTermSize
        // Trigger redraw to reflect new dimensions
        requestRedraw()
    }

    // ===== ADAPTIVE DEBOUNCING LOGIC =====

    /**
     * Start the redraw processor coroutine that handles debouncing.
     *
     * CRITICAL: This coroutine must never die silently. If it crashes, the UI
     * will freeze while PTY continues working. We use a loop-based restart
     * mechanism to prevent stack overflow from repeated crashes.
     */
    private fun startRedrawProcessor() {
        // Cancel existing job if restarting to prevent coroutine leaks
        redrawJob?.cancel()

        redrawJob = redrawScope.launch {
            var shouldRestart = true

            while (shouldRestart && isActive) {
                shouldRestart = false  // Will be set true only on recoverable crash

                try {
                    for (request in redrawChannel) {
                        // Released before the redraw, not after, so a buffer mutation that
                        // lands mid-redraw still queues the next frame.
                        redrawQueued.set(false)
                        try {
                            // Re-check sync mode: a ?2026h may have arrived after this
                            // redraw was queued (e.g., rapid ?2026l/?2026h toggle by CLIs
                            // like "claude" that use synchronized output for spinner frames).
                            synchronized(syncUpdateLock) {
                                if (_synchronizedUpdateEnabled) {
                                    _pendingRedrawDuringSync = true
                                    null
                                } else Unit
                            } ?: continue
                            actualRedraw()
                        } catch (e: Exception) {
                            // Log but don't crash the loop - individual redraw failures
                            // should not kill the entire rendering pipeline
                            logError("ERROR: Redraw failed (continuing): ${e.message}", e)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Normal cancellation during shutdown - don't restart
                    throw e
                } catch (e: Exception) {
                    // Channel closed or fatal error - restart via loop (not recursion)
                    // This prevents permanent UI freeze from unexpected exceptions
                    logError("ERROR: Redraw processor crashed, will restart: ${e.message}", e)
                    // Small delay before restart to prevent tight loop on persistent errors
                    kotlinx.coroutines.delay(100)
                    shouldRestart = true
                }
            }
        }
    }

    /**
     * Trigger a redraw of the terminal (normal priority, applies debouncing).
     */
    fun requestRedraw() {
        // Synchronized Update Mode (2026): Suppress redraws while enabled
        // Uses lock to prevent race condition with setSynchronizedUpdate()
        synchronized(syncUpdateLock) {
            if (_synchronizedUpdateEnabled) {
                _pendingRedrawDuringSync = true
                return
            }
        }

        // Skip the send entirely when one is already pending: see [redrawQueued].
        if (redrawQueued.compareAndSet(false, true)) {
            // Release the claim if the send did not land, or a closed channel would latch
            // the flag and silently stop every future redraw.
            if (redrawChannel.trySend(RedrawRequest()).isFailure) {
                redrawQueued.set(false)
            }
        }
    }

    /**
     * Capture render data only when it did not overlap a synchronized update.
     *
     * Checking only at redraw-request time is insufficient: Compose may execute
     * the corresponding recomposition after a later ?2026h has started. The
     * generation check also catches a complete h/l pair that occurs while
     * [capture] is running. The display lock is deliberately not held during
     * [capture], because buffer mutations can request redraws while holding the
     * terminal buffer lock and the opposite lock order would deadlock.
     */
    fun <T> captureStableRenderFrame(capture: () -> T): T? {
        val generation = synchronized(syncUpdateLock) {
            if (_synchronizedUpdateEnabled) null else synchronizedUpdateGeneration
        } ?: return null

        val result = capture()
        return synchronized(syncUpdateLock) {
            result.takeIf {
                !_synchronizedUpdateEnabled && synchronizedUpdateGeneration == generation
            }
        }
    }

    /**
     * Set synchronized update mode (DEC Private Mode 2026).
     * When enabled, redraws are suppressed until mode is disabled.
     * When disabled, if any redraws were pending, one redraw is triggered.
     *
     * Note: A single redraw is sufficient because:
     * - TerminalTextBuffer accumulates ALL changes regardless of rendering
     * - A "redraw" renders the entire current buffer state
     * - One final redraw displays all accumulated changes at once
     * - Multiple redraws would just re-render the same final state
     *
     * Thread-safe: Uses lock to prevent race conditions with requestRedraw().
     *
     * @param enabled true to suppress rendering, false to resume
     */
    override fun setSynchronizedUpdate(enabled: Boolean) {
        val shouldRedraw: Boolean
        synchronized(syncUpdateLock) {
            if (enabled) {
                if (!_synchronizedUpdateEnabled) {
                    _synchronizedUpdateEnabled = true
                    _pendingRedrawDuringSync = false
                    synchronizedUpdateGeneration++
                }
                shouldRedraw = false
            } else {
                shouldRedraw = _synchronizedUpdateEnabled && _pendingRedrawDuringSync
                if (_synchronizedUpdateEnabled) {
                    _synchronizedUpdateEnabled = false
                    _pendingRedrawDuringSync = false
                    synchronizedUpdateGeneration++
                }
            }
        }

        // Flush outside the lock to avoid potential deadlock
        if (shouldRedraw) {
            requestRedraw()
        }
    }

    /**
     * Trigger an immediate redraw (bypasses debouncing).
     * Use for user input (keyboard, mouse) to guarantee zero lag.
     *
     * CRITICAL FIX: This bypasses the Channel.CONFLATED to ensure IMMEDIATE requests
     * are never dropped. During initialization, rapid redraw requests (10-20 in <50ms)
     * were being conflated, causing the initial prompt to not display until user clicked.
     * By calling actualRedraw() directly on Main thread, we ensure instant response.
     *
     * Note: Respects Mode 2026 (synchronized update) to maintain flicker-reduction guarantee.
     * During sync mode window, sets pending flag instead of rendering immediately.
     */
    fun requestImmediateRedraw() {
        // Synchronized Update Mode (2026): Suppress redraws while enabled
        // Even immediate redraws must respect sync mode to prevent partial rendering
        synchronized(syncUpdateLock) {
            if (_synchronizedUpdateEnabled) {
                _pendingRedrawDuringSync = true
                return
            }
        }

        // Bypass channel entirely - call actualRedraw() directly on Main thread
        // This ensures IMMEDIATE requests are never dropped during rapid initialization
        // MUST use Main dispatcher because actualRedraw() modifies Compose state
        redrawScope.launch(Dispatchers.Main) {
            // Re-check sync mode: it may have been re-enabled between the check above
            // and this coroutine executing on Main thread
            synchronized(syncUpdateLock) {
                if (_synchronizedUpdateEnabled) {
                    _pendingRedrawDuringSync = true
                    return@launch
                }
            }
            actualRedraw()
        }
    }

    /**
     * Perform the actual redraw by updating Compose state.
     */
    private fun actualRedraw() {
        FrameLatencyProbe.markRedrawTriggered()
        _redrawTrigger.value += 1
    }

    /**
     * Release everything started in [init]. Must be called when the owning
     * tab / split pane is closed.
     *
     * Without this the redraw coroutine stays parked on [redrawChannel] forever
     * and [redrawScope] is never cancelled, so the display (and the render state
     * it captures) can never be collected — one leaked Main-dispatcher coroutine
     * per tab/pane ever opened. Idempotent: cancelling an already-cancelled scope
     * and closing an already-closed channel are both no-ops.
     */
    fun dispose() {
        redrawJob?.cancel()
        redrawScope.cancel()
        redrawChannel.close()
    }
}
