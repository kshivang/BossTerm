package ai.rever.bossterm.compose.tabs

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.compose.ConnectionState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.hyperlinks.Hyperlink
import ai.rever.bossterm.compose.hyperlinks.HyperlinkHoverConsumer
import ai.rever.bossterm.compose.ime.IMEState
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.compose.debug.DebugDataCollector
import ai.rever.bossterm.compose.selection.SelectionTracker
import ai.rever.bossterm.compose.typeahead.ComposeTypeAheadModel
import ai.rever.bossterm.compose.ai.AICommandInterceptor
import ai.rever.bossterm.core.typeahead.TerminalTypeAheadManager
import java.util.UUID

/**
 * Represents a single terminal tab with its own terminal session, process, and UI state.
 *
 * This encapsulates all per-tab state that was previously managed in ProperTerminal's
 * remember {} blocks, enabling multiple independent terminal sessions in the same window.
 *
 * Architecture:
 * - Each tab has its own complete terminal stack: PTY → BlockingTerminalDataStream →
 *   BossEmulator → BossTerminal → TerminalTextBuffer → ComposeTerminalDisplay
 * - Independent coroutine scope for managing background jobs (emulator processing,
 *   PTY output reading, process monitoring)
 * - Separate UI state (selection, search, scrolling, IME, context menu)
 * - Working directory tracking via OSC 7 for directory inheritance
 */
data class TerminalTab(
    /**
     * Unique identifier for this tab (UUID).
     */
    override val id: String = UUID.randomUUID().toString(),

    /**
     * Display title shown in the tab bar (mutable, can be updated based on shell activity).
     * Default format: "Shell 1", "Shell 2", etc.
     */
    override val title: MutableState<String>,

    // === Core Terminal Components ===

    /**
     * The main terminal instance that handles terminal operations and rendering.
     */
    override val terminal: BossTerminal,

    /**
     * The text buffer that stores terminal content and scrollback history.
     */
    override val textBuffer: TerminalTextBuffer,

    /**
     * The display adapter that handles terminal rendering and redraw requests.
     */
    override val display: ComposeTerminalDisplay,

    /**
     * Blocking data stream that feeds character data to the emulator.
     * CRITICAL: Must be long-lived to prevent CSI sequence truncation.
     */
    val dataStream: BlockingTerminalDataStream,

    /**
     * The terminal emulator that processes escape sequences and terminal protocols.
     * CRITICAL: Must be long-lived (stateful) to preserve emulator state across chunks.
     */
    val emulator: BossEmulator,

    // === Process Management ===

    /**
     * Handle to the shell process (PTY). Null during initialization.
     */
    override val processHandle: MutableState<PlatformServices.ProcessService.ProcessHandle?>,

    /**
     * Current working directory of the shell, tracked via OSC 7 sequences.
     * Used for inheriting CWD when creating new tabs.
     */
    override val workingDirectory: MutableState<String?>,

    /**
     * Connection state of the terminal (Initializing, Connected, Disconnected).
     */
    override val connectionState: MutableState<ConnectionState>,

    /**
     * Callback invoked when the shell process exits.
     * Called by TabController before auto-closing the tab.
     * Can be used for cleanup, logging, or custom exit handling.
     *
     * Note: This can be reassigned after creation (e.g., when the tab becomes
     * part of a split pane and needs split-aware exit behavior).
     */
    var onProcessExit: (() -> Unit)? = null,

    // === Coroutine Management ===

    /**
     * Coroutine scope for this tab's background jobs:
     * - Emulator processing (Dispatchers.Default)
     * - PTY output reading (Dispatchers.IO)
     * - Process exit monitoring (Dispatchers.IO)
     *
     * Cancelled when tab is closed to clean up resources.
     */
    val coroutineScope: CoroutineScope,

    // === UI State ===

    /**
     * Whether this tab currently has keyboard focus.
     */
    override val isFocused: MutableState<Boolean>,

    /**
     * Current scroll offset in lines from the bottom of the buffer.
     */
    override val scrollOffset: MutableState<Int>,

    /**
     * Whether the search bar is visible for this tab.
     */
    override val searchVisible: MutableState<Boolean>,

    /**
     * Current search query text.
     */
    override val searchQuery: MutableState<String>,

    /**
     * List of search match positions (row, column) in the terminal buffer.
     */
    override val searchMatches: MutableState<List<Pair<Int, Int>>>,

    /**
     * Current search match index (for next/previous navigation).
     */
    override val currentSearchMatchIndex: MutableState<Int>,

    /**
     * Selection clipboard for X11 emulation mode (copy-on-select).
     */
    override val selectionClipboard: MutableState<String?>,

    /**
     * Current selection mode (NORMAL for line-based, BLOCK for rectangular).
     * Defaults to NORMAL. Set to BLOCK when Alt+Drag is detected.
     */
    override val selectionMode: MutableState<ai.rever.bossterm.compose.SelectionMode> = mutableStateOf(ai.rever.bossterm.compose.SelectionMode.NORMAL),

    /**
     * IME (Input Method Editor) state for CJK input support.
     */
    override val imeState: IMEState,

    /**
     * Context menu controller for right-click menu.
     */
    override val contextMenuController: ContextMenuController,

    /**
     * Detected hyperlinks in the terminal buffer with their positions and URLs.
     */
    override val hyperlinks: MutableState<List<Hyperlink>>,

    /**
     * Currently hovered hyperlink (for cursor styling and click handling).
     */
    override val hoveredHyperlink: MutableState<Hyperlink?>,

    // === Debug Tools ===

    /**
     * Whether debug mode is enabled for this tab.
     * When enabled, I/O data is captured for visualization in the debug panel.
     * This controls data collection (background), not UI visibility.
     */
    override val debugEnabled: MutableState<Boolean> = mutableStateOf(false),

    /**
     * Whether the debug panel UI is currently visible.
     * Defaults to false even when debugEnabled is true (toggled with Cmd/Ctrl+Shift+D).
     */
    override val debugPanelVisible: MutableState<Boolean> = mutableStateOf(false),

    /**
     * Debug data collector for capturing I/O chunks and terminal state snapshots.
     * Null when debug mode is disabled to avoid memory overhead.
     */
    override val debugCollector: DebugDataCollector? = null,

    // === Type-Ahead Prediction ===

    /**
     * Type-ahead terminal model for applying predictions to the buffer.
     * Null when type-ahead is disabled.
     */
    override val typeAheadModel: ComposeTypeAheadModel? = null,

    /**
     * Type-ahead manager that tracks predictions and latency statistics.
     * Null when type-ahead is disabled.
     */
    override val typeAheadManager: TerminalTypeAheadManager? = null,

    // === AI Command Interception ===

    /**
     * AI command interceptor for detecting AI assistant commands before execution.
     * Null when AI assistant integration is disabled.
     * Set after tab creation by TabbedTerminal/EmbeddableTerminal.
     */
    override var aiCommandInterceptor: AICommandInterceptor? = null,

    // === Listener Management ===

    /**
     * Reference to the ModelListener registered with textBuffer.
     * Must be removed in dispose() to prevent memory leaks.
     *
     * CRITICAL: Without proper cleanup, anonymous listeners accumulate over
     * hours of tab create/close cycles, eventually causing memory pressure
     * and exceptions when references to disposed displays are invoked.
     */
    val modelListener: ai.rever.bossterm.terminal.model.TerminalModelListener? = null,

    /**
     * Command-state listeners we registered against [terminal] in the controller
     * (e.g. CommandNotificationHandler, LastCommandTracker). Removed in [dispose]
     * so listeners don't accumulate over hours of tab create/close cycles.
     *
     * Same rationale as [modelListener] — anonymous listeners holding references
     * to a tab's display / state become memory pressure when the terminal itself
     * outlives the tab (e.g. while we're tearing down asynchronously) and a
     * source of late callbacks against disposed UI.
     */
    val commandStateListeners: MutableList<ai.rever.bossterm.terminal.model.CommandStateListener> = mutableListOf()
) : TerminalSession {
    /**
     * Whether this tab is currently rendering to the UI.
     * False for background tabs (still processing output, but UI updates paused).
     * Thread-safe: Uses MutableState for safe access from multiple coroutines.
     */
    override val isVisible: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Most recently completed shell command for this tab, populated by
     * [ai.rever.bossterm.compose.mcp.LastCommandTracker] via OSC 133 events.
     * `null` until at least one command has completed.
     *
     * Consumed by the in-process MCP server (`get_last_command` tool).
     * Thread-safe: [kotlinx.coroutines.flow.MutableStateFlow] supports
     * concurrent writes from listener callbacks (which may run off the UI
     * thread) and concurrent reads from MCP request handlers.
     */
    val lastCommand: kotlinx.coroutines.flow.MutableStateFlow<ai.rever.bossterm.compose.mcp.LastCommand?> =
        kotlinx.coroutines.flow.MutableStateFlow(null)

    /**
     * Per-tab command-block tracker (OSC 133), populated by [TabController] when
     * the tab/session is created. Captures one [ai.rever.bossterm.compose.blocks.CommandBlock]
     * per command; the renderer and block actions consume it only when
     * `commandBlocksEnabled` is on. `null` only before construction completes.
     */
    override var commandBlockTracker: ai.rever.bossterm.compose.blocks.CommandBlockTracker? = null

    // === Remote mirror (native client of another BossTerm's share) ===

    /**
     * True when this tab mirrors a remote BossTerm pane (no local PTY): its bytes come from a
     * WebSocket and its grid size is the host's. The renderer reads this (via [TerminalSession])
     * to render without a local PTY connection and to skip local auto-fit resizing.
     */
    override var isRemote: Boolean = false

    /** The remote pane id this mirror represents (for routing input back to the host). */
    var remotePaneId: String? = null

    /**
     * When set (remote mirror tabs), [writeUserInput] routes here — typically to send the
     * keystrokes back to the host as `ClientMessage.Input` over the WebSocket — instead of a
     * local PTY. Null for normal local tabs.
     */
    var onUserInput: ((String) -> Unit)? = null

    /**
     * Latest grid (cols×rows) that fits this remote mirror's local canvas, recorded on layout.
     * The "Fit to host" / "Fit to client" menu actions use it: ask the host to resize to this
     * grid, or resize our own window so the host's current grid renders 1:1. 0 until first layout.
     */
    var remoteFitCols: Int = 0
    var remoteFitRows: Int = 0

    /**
     * For a daemon-attached mirror tab (Phase 4 thin-client): invoked when the GUI canvas's fit
     * grid changes, so the GUI can drive the daemon session's size (GUI is the real display). Null
     * for ordinary remote mirrors, where the HOST is authoritative and the mirror only records the
     * fit (see [remoteFitCols]/[remoteFitRows]).
     */
    var onRemoteFit: ((cols: Int, rows: Int) -> Unit)? = null

    /**
     * Per-tab font-size override (sp). Used by remote mirrors when the host's grid can't
     * physically fit this screen at the user's font — the mirror renders smaller so the
     * whole grid stays visible (the native analogue of the web viewer's fit-screen).
     * Null = the global settings font. Cleared on the next "Fit my window to host".
     */
    val fontSizeOverride: androidx.compose.runtime.MutableState<Float?> =
        androidx.compose.runtime.mutableStateOf(null)

    // === Warp-style tab customization (left tab bar) ===

    /**
     * User-assigned tab title (via Rename…). When non-null it overrides the
     * auto cwd-derived title and survives `cd`; clearing it (null) reverts the
     * tab to tracking the working directory. See [TabController.wireCwdTitle].
     */
    override val customTitle: MutableState<String?> = mutableStateOf(null)

    /**
     * User-assigned tab accent color as an ARGB hex string ("0xAARRGGBB"), or
     * null for no manual color. Rendered as a leading stripe by the tab bar.
     * Manual color always wins over auto-color-by-directory.
     */
    override val tabColor: MutableState<String?> = mutableStateOf(null)

    /**
     * Current git branch of [workingDirectory], or null when the directory is
     * not inside a repository. Tracked by [TabController.wireCwdTitle] (debounced
     * lookup on each `cd`) and rendered as the third line of the left tab-bar
     * chips (Warp-style title / cwd / branch).
     */
    val gitBranch: MutableState<String?> = mutableStateOf(null)

    // === Content-Anchored Selection ===

    /**
     * Selection tracker for content-anchored selection (iTerm2-style).
     * Tracks selection by line object identity, surviving buffer scrolling.
     */
    override val selectionTracker: SelectionTracker = SelectionTracker(textBuffer)

    // === User Input Write Channel ===
    // Uses Channel for sequential write ordering and backpressure handling
    // This prevents race conditions from concurrent coroutines and ensures
    // keyboard input is processed in order even under high load.

    /**
     * Sealed class for write operations to ensure FIFO ordering between text and raw bytes.
     */
    private sealed class WriteOperation {
        data class Text(val data: String) : WriteOperation()
        class RawBytes(val data: ByteArray) : WriteOperation()  // Not data class - ByteArray uses referential equality
    }

    /**
     * Channel for queuing user input writes to the PTY.
     * Capacity of 256 provides reasonable buffer for burst input (e.g., paste operations).
     * Uses WriteOperation sealed class to handle both text and raw bytes in FIFO order.
     */
    private val writeChannel = Channel<WriteOperation>(capacity = 256)

    /**
     * Background job that consumes from writeChannel and writes to PTY sequentially.
     * Runs on IO dispatcher to avoid blocking other coroutines.
     */
    private val writeConsumerJob: Job = coroutineScope.launch(Dispatchers.IO) {
        for (operation in writeChannel) {
            try {
                when (operation) {
                    is WriteOperation.Text -> processHandle.value?.write(operation.data)
                    is WriteOperation.RawBytes -> processHandle.value?.writeBytes(operation.data)
                }
            } catch (e: java.io.IOException) {
                // PTY might be closed - log but don't crash
                // This can happen during normal tab close or if shell exits
                println("WARNING: PTY write failed: ${e.message}")
            }
        }
    }

    // === Hyperlink Hover Consumers ===

    /**
     * List of registered hover consumers for hyperlink hover events.
     * External clients can register to receive onMouseEntered/onMouseExited callbacks.
     */
    private val _hoverConsumers = mutableListOf<HyperlinkHoverConsumer>()

    /**
     * Read-only view of registered hover consumers.
     */
    override val hoverConsumers: List<HyperlinkHoverConsumer> get() = _hoverConsumers

    /**
     * Register a hover consumer to receive hyperlink hover events.
     * @param consumer The consumer to register
     */
    override fun addHoverConsumer(consumer: HyperlinkHoverConsumer) {
        _hoverConsumers.add(consumer)
    }

    /**
     * Unregister a hover consumer.
     * @param consumer The consumer to remove
     */
    override fun removeHoverConsumer(consumer: HyperlinkHoverConsumer) {
        _hoverConsumers.remove(consumer)
    }

    /**
     * Lifecycle callback invoked when this tab becomes visible (user switches to it).
     * Note: Redraw optimization is already implemented via Phase 2 adaptive debouncing.
     * TabController checks isVisible flag to skip redraws for hidden tabs.
     */
    override fun onVisible() {
        isVisible.value = true
    }

    /**
     * Lifecycle callback invoked when this tab becomes hidden (user switches away).
     * Note: Redraw optimization is already implemented via Phase 2 adaptive debouncing.
     * TabController checks isVisible flag to skip redraws for hidden tabs.
     */
    override fun onHidden() {
        isVisible.value = false
    }

    /**
     * Clean up resources when closing this tab.
     * - Removes model listener to prevent memory leaks
     * - Closes write channel (signals consumer to stop)
     * - Cancels all coroutines
     * - Releases terminal resources
     *
     * Note: Process termination is handled by TabController.closeTab() to prevent
     * potential GC issues where the tab might be collected before kill() completes.
     *
     * CRITICAL: ModelListener cleanup prevents accumulation of listeners over hours
     * of tab create/close cycles. Without this, listeners referencing disposed
     * displays can cause exceptions that crash the rendering pipeline.
     */
    override fun dispose() {
        // Remove model listener to prevent memory leak
        // This is CRITICAL - without cleanup, listeners accumulate and can crash
        // the rendering pipeline when they reference disposed displays
        modelListener?.let {
            try {
                textBuffer.removeModelListener(it)
            } catch (e: Exception) {
                System.err.println("WARN: Failed to remove model listener: ${e.message}")
            }
        }

        // Remove the command-state listeners the controller registered for us.
        // Same memory-pressure rationale as modelListener above: anonymous OSC 133
        // listeners (CommandNotificationHandler, LastCommandTracker) would otherwise
        // remain attached to `terminal` and keep this tab's state reachable until
        // the terminal itself is collected.
        for (listener in commandStateListeners) {
            // Release any OS wake-lock held by the prevent-sleep listener before detaching.
            (listener as? ai.rever.bossterm.compose.power.PreventSleepListener)?.let {
                runCatching { it.dispose() }
            }
            try {
                terminal.removeCommandStateListener(listener)
            } catch (e: Exception) {
                System.err.println("WARN: Failed to remove command-state listener: ${e.message}")
            }
        }

        // Close write channel to signal consumer to stop
        writeChannel.close()

        // Cancel all coroutines in this scope (including writeConsumerJob)
        coroutineScope.cancel()

        // Tear down the display: cancels its redraw scope, closes the redraw
        // channel, and stops the parked redraw coroutine. Without this every
        // closed tab/pane leaks a Main-dispatcher coroutine and pins the display.
        try {
            display.dispose()
        } catch (e: Exception) {
            System.err.println("WARN: Failed to dispose display: ${e.message}")
        }

        // Terminal cleanup (if needed)
        // terminal.close() may not be available in all BossTerm versions
    }

    /**
     * Paste text with proper bracketed paste mode handling.
     * When bracketed paste mode is enabled by the terminal application,
     * wraps the text with ESC[200~ and ESC[201~ escape sequences.
     *
     * Also normalizes newlines: CRLF/LF → CR (terminal standard).
     *
     * @param text The text to paste from clipboard
     */
    override fun pasteText(text: String) {
        if (text.isEmpty()) return

        // Normalize newlines: CRLF/LF → CR (terminal standard)
        var normalized = text.replace("\r\n", "\n").replace('\n', '\r')

        // Wrap with bracketed paste sequences if mode enabled
        if (display.bracketedPasteMode.value) {
            normalized = "\u001b[200~$normalized\u001b[201~"
        }

        writeUserInput(normalized)
    }

    /**
     * Write user input to the process and record in debug collector.
     * Centralizes input handling to ensure all user input is captured for debugging.
     *
     * Uses Channel-based queue to ensure:
     * - Sequential write ordering (no race conditions)
     * - Backpressure handling (suspends if buffer full, never drops input)
     * - Non-blocking UI (launches coroutine for send)
     *
     * @param text The text to send to the shell
     */
    override fun writeUserInput(text: String) {
        // Record in debug collector
        debugCollector?.recordChunk(text, ai.rever.bossterm.compose.debug.ChunkSource.USER_INPUT)

        // Remote mirror: route keystrokes to the host over the WebSocket (no local PTY).
        onUserInput?.let { it(text); return }

        // Queue for sequential processing by writeConsumerJob
        // Uses coroutine with send() to suspend if buffer full (never drops input)
        // This is safe because we're launching on the tab's scope, not blocking the caller
        coroutineScope.launch {
            try {
                writeChannel.send(WriteOperation.Text(text))
            } catch (e: Exception) {
                // Channel closed (tab closing) - expected during shutdown
                println("WARNING: Failed to queue text input to PTY: ${e.message}")
            }
        }
    }

    /**
     * Write raw bytes to the process stdin.
     * Use this for sending control characters or binary data without string encoding issues.
     *
     * This method uses the same write queue as writeUserInput() to guarantee FIFO ordering.
     * Calls are asynchronous - they return immediately after queuing.
     *
     * @param bytes The raw bytes to send to the shell
     */
    fun writeRawBytes(bytes: ByteArray) {
        // Record in debug collector (convert to string for display)
        debugCollector?.recordChunk(
            bytes.joinToString("") { "\\x%02x".format(it) },
            ai.rever.bossterm.compose.debug.ChunkSource.USER_INPUT
        )

        // Queue for sequential processing by writeConsumerJob (same queue as text)
        // This ensures FIFO ordering between write() and sendInput() calls
        coroutineScope.launch {
            try {
                writeChannel.send(WriteOperation.RawBytes(bytes))
            } catch (e: Exception) {
                // Channel closed (tab closing) - expected during shutdown
                println("WARNING: Failed to queue raw bytes to PTY: ${e.message}")
            }
        }
    }
}

// Hyperlink data class is already defined in ai.rever.bossterm.compose.hyperlinks package
