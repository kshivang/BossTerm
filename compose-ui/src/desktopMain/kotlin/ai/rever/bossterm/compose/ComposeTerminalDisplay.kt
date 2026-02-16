package ai.rever.bossterm.compose

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
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.timer

/**
 * Compose implementation of TerminalDisplay interface with adaptive debouncing.
 *
 * Phase 2 Optimization: Automatically switches between three rendering modes
 * based on output rate to reduce redraws by 51-91% for medium/large files
 * while maintaining zero latency for interactive use.
 */
class ComposeTerminalDisplay : TerminalDisplay {
    // ===== ADAPTIVE DEBOUNCING (Phase 2) =====

    /**
     * Rendering modes that adapt to output rate.
     */
    enum class RedrawMode(val debounceMs: Long, val description: String) {
        INTERACTIVE(8L, "120fps equivalent for responsive typing"),
        HIGH_VOLUME(50L, "20fps for bulk output, triggered at >100 redraws/sec"),
        IMMEDIATE(0L, "Instant for keyboard/mouse input")
    }

    /**
     * Redraw request with priority.
     */
    data class RedrawRequest(
        val timestamp: Long = System.currentTimeMillis(),
        val priority: RedrawPriority = RedrawPriority.NORMAL
    )

    enum class RedrawPriority {
        IMMEDIATE,  // User input - bypass debounce
        NORMAL      // PTY output - apply debounce
    }

    // Current rendering mode
    @Volatile
    private var currentMode = RedrawMode.INTERACTIVE

    // Channel for queuing redraw requests with conflation
    private val redrawChannel = Channel<RedrawRequest>(Channel.CONFLATED)

    // Coroutine scope for redraw processing
    private val redrawScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Timestamp tracking for burst detection
    private val recentRedraws = ArrayDeque<Long>(100)
    private val redrawTimestampsLock = Any()

    // Mode transition tracking
    private var lastModeSwitch = System.currentTimeMillis()
    private var returnToInteractiveJob: Job? = null

    // ===== PERFORMANCE METRICS =====
    private val redrawCount = AtomicLong(0)
    private val skippedRedraws = AtomicLong(0) // Count of coalesced redraws
    private val startTime = System.currentTimeMillis()
    private var lastMetricsReport = System.currentTimeMillis()
    private val metricsReportInterval = 5000L // Report every 5 seconds

    init {
        // Start redraw processor coroutine
        startRedrawProcessor()

        // Start metrics reporting timer
        timer("RedrawMetrics", daemon = true, period = metricsReportInterval) {
            reportMetrics()
        }
    }
    // Non-reactive cursor state - only redrawTrigger controls recomposition
    // This prevents flickering caused by Compose State updates racing with debounced redraws
    @Volatile private var _cursorXValue = 0
    @Volatile private var _cursorYValue = 0
    @Volatile private var _cursorVisibleValue = true
    @Volatile private var _cursorShapeValue: CursorShape? = null
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
    private val _windowTitle = MutableStateFlow("")
    private val _iconTitle = MutableStateFlow("")
    private val _mouseMode = mutableStateOf(MouseMode.MOUSE_REPORTING_NONE)
    private val _bellTrigger = mutableStateOf(0)
    private val _progressState = mutableStateOf(TerminalDisplay.ProgressState.HIDDEN)
    private val _progressValue = mutableStateOf(0)

    // Snapshot getters for cursor - non-reactive, read inside remember() blocks
    val cursorXSnapshot: Int get() = _cursorXValue
    val cursorYSnapshot: Int get() = _cursorYValue
    val cursorVisibleSnapshot: Boolean get() = _cursorVisibleValue
    val cursorShapeSnapshot: CursorShape? get() = _cursorShapeValue
    val bracketedPasteMode: State<Boolean> = _bracketedPasteMode
    val termSize: State<TermSize> = _termSize
    val mouseMode: State<MouseMode> = _mouseMode
    val bellTrigger: State<Int> = _bellTrigger
    val progressState: State<TerminalDisplay.ProgressState> = _progressState
    val progressValue: State<Int> = _progressValue
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
        if (debugCursor && (_cursorXValue != x || _cursorYValue != y)) {
            println("🔵 CURSOR MOVE: ($_cursorXValue,$_cursorYValue) → ($x,$y)")
        }
        val changed = _cursorXValue != x || _cursorYValue != y
        _cursorXValue = x
        _cursorYValue = y
        // Trigger redraw when cursor moves - fixes p10k/zsh TUI not updating
        // Cursor-only changes (no buffer modification) still need screen refresh
        if (changed) {
            requestRedraw()
        }
    }

    override fun setCursorShape(cursorShape: CursorShape?) {
        if (debugCursor && _cursorShapeValue != cursorShape) {
            println("🔷 CURSOR SHAPE: $_cursorShapeValue → $cursorShape")
        }
        val changed = _cursorShapeValue != cursorShape
        _cursorShapeValue = cursorShape
        if (changed) {
            requestRedraw()
        }
    }

    override fun setCursorVisible(isCursorVisible: Boolean) {
        if (debugCursor && _cursorVisibleValue != isCursorVisible) {
            println("👁️  CURSOR VISIBLE: $_cursorVisibleValue → $isCursorVisible")
        }
        val changed = _cursorVisibleValue != isCursorVisible
        _cursorVisibleValue = isCursorVisible
        if (changed) {
            requestRedraw()
        }
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
        // Smart priority detection: Use IMMEDIATE for interactive use, NORMAL for bulk output
        val isHighVolume = synchronized(redrawTimestampsLock) {
            currentMode == RedrawMode.HIGH_VOLUME
        }

        if (isHighVolume) {
            // Bulk output detected (cat, streaming) - use debouncing for 98% reduction
            requestRedraw()
        } else {
            // Interactive use (typing, prompts) - instant response for best UX
            requestImmediateRedraw()
        }
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
                        try {
                            when (request.priority) {
                                RedrawPriority.IMMEDIATE -> {
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
                                }

                                RedrawPriority.NORMAL -> {
                                    val mode = detectAndUpdateMode()
                                    delay(mode.debounceMs)

                                    // Re-check sync mode after debounce delay: a new ?2026h may
                                    // have been processed by the emulator while we were waiting.
                                    synchronized(syncUpdateLock) {
                                        if (_synchronizedUpdateEnabled) {
                                            _pendingRedrawDuringSync = true
                                            null
                                        } else Unit
                                    } ?: continue
                                    actualRedraw()
                                }
                            }
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
     * Detect current redraw rate and update mode accordingly.
     * Switches to HIGH_VOLUME when >100 redraws/sec detected.
     */
    private fun detectAndUpdateMode(): RedrawMode {
        val now = System.currentTimeMillis()

        synchronized(redrawTimestampsLock) {
            // Add current timestamp
            recentRedraws.addLast(now)

            // Remove timestamps older than 1 second
            while (recentRedraws.isNotEmpty() &&
                   now - recentRedraws.first() > 1000) {
                recentRedraws.removeFirst()
            }

            // Calculate redraws per second
            val rate = recentRedraws.size

            // Determine appropriate mode
            val newMode = when {
                rate > 100 -> RedrawMode.HIGH_VOLUME  // Bulk output detected
                else -> RedrawMode.INTERACTIVE         // Normal interactive use
            }

            // Handle mode transition
            if (newMode != currentMode && newMode != RedrawMode.IMMEDIATE) {
                onModeTransition(currentMode, newMode)
                currentMode = newMode
                lastModeSwitch = now
            }

            return currentMode
        }
    }

    /**
     * Handle transitions between rendering modes.
     */
    private fun onModeTransition(from: RedrawMode, to: RedrawMode) {
        // Schedule automatic return to INTERACTIVE after bulk output stops
        if (to == RedrawMode.HIGH_VOLUME) {
            returnToInteractiveJob?.cancel()
            returnToInteractiveJob = redrawScope.launch {
                delay(500) // Wait 500ms of low activity
                synchronized(redrawTimestampsLock) {
                    if (recentRedraws.size < 50) { // Less than 50 redraws/sec
                        currentMode = RedrawMode.INTERACTIVE
                    }
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

        val sent = redrawChannel.trySend(RedrawRequest(priority = RedrawPriority.NORMAL))
        if (!sent.isSuccess) {
            // Channel is full (CONFLATED), request was coalesced
            skippedRedraws.incrementAndGet()
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
                _synchronizedUpdateEnabled = true
                _pendingRedrawDuringSync = false
                shouldRedraw = false
            } else {
                shouldRedraw = _pendingRedrawDuringSync
                _synchronizedUpdateEnabled = false
                _pendingRedrawDuringSync = false
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

        // Reset to INTERACTIVE mode after brief delay
        redrawScope.launch {
            delay(100)
            synchronized(redrawTimestampsLock) {
                if (currentMode != RedrawMode.HIGH_VOLUME) {
                    currentMode = RedrawMode.INTERACTIVE
                }
            }
        }
    }

    /**
     * Perform the actual redraw by updating Compose state.
     */
    private fun actualRedraw() {
        redrawCount.incrementAndGet()
        _redrawTrigger.value += 1
    }

    // ===== METRICS REPORTING =====
    private fun reportMetrics() {
        val now = System.currentTimeMillis()
        val totalTime = (now - startTime) / 1000.0
        val intervalTime = (now - lastMetricsReport) / 1000.0
        val totalRedraws = redrawCount.get()
        val totalSkipped = skippedRedraws.get()
        val totalRequests = totalRedraws + totalSkipped
        val efficiencyPercent = if (totalRequests > 0) {
            (totalSkipped.toDouble() / totalRequests * 100)
        } else 0.0

        // Performance metrics reporting removed

        lastMetricsReport = now
    }

    fun printFinalMetrics() {
        // Final metrics reporting removed
    }
}
