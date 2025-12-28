package ai.rever.bossterm.compose

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TabController
import ai.rever.bossterm.compose.tabs.TerminalSessionListener
import ai.rever.bossterm.compose.tabs.TerminalTab

/**
 * External state holder for TabbedTerminal that survives recomposition.
 *
 * This class enables TabbedTerminal to persist its state (all terminal tabs and sessions)
 * when used within another tab system or navigation framework. Without this, switching
 * away from a TabbedTerminal and back would lose all terminal sessions.
 *
 * ## Thread Safety
 *
 * This class is designed for use with Compose, which performs recomposition on the main
 * thread. All state access and mutations should occur on the main/UI thread. The class
 * is not thread-safe for concurrent access from multiple threads.
 *
 * ## Lifecycle Notes
 *
 * - **Before initialization**: All property accessors return safe defaults (empty list,
 *   0, null). Tab management methods are silently ignored.
 * - **After disposal**: Same behavior as before initialization. The state can be reused
 *   by composing TabbedTerminal again, which will re-initialize it.
 * - **Initialization**: Happens automatically when TabbedTerminal is first composed
 *   with this state. Only initializes once; subsequent compositions are no-ops.
 *
 * ## Usage Patterns
 *
 * ### Pattern 1: Auto-managed lifecycle (default)
 * ```kotlin
 * val state = rememberTabbedTerminalState()
 * TabbedTerminal(state = state, onExit = { ... })
 * // State is automatically disposed when the composable leaves composition
 * ```
 *
 * ### Pattern 2: Manual lifecycle (for persistence across navigation)
 * ```kotlin
 * // Create at a higher level that survives navigation
 * val state = rememberTabbedTerminalState(autoDispose = false)
 *
 * // Use in child composable that may unmount
 * when (selectedTab) {
 *     "terminal" -> TabbedTerminal(state = state, onExit = { ... })
 *     "editor" -> EditorPane()
 * }
 *
 * // Manually dispose when truly done
 * DisposableEffect(Unit) { onDispose { state.dispose() } }
 * ```
 *
 * ### Pattern 3: Programmatic control
 * ```kotlin
 * val state = rememberTabbedTerminalState(autoDispose = false)
 *
 * // Control tabs programmatically
 * Button(onClick = { state.createTab() }) { Text("New Tab") }
 * Button(onClick = { state.closeActiveTab() }) { Text("Close") }
 *
 * // Listen for session events
 * state.addSessionListener(object : TerminalSessionListener {
 *     override fun onSessionCreated(session: TerminalSession) { ... }
 *     override fun onSessionClosed(session: TerminalSession) { ... }
 * })
 * ```
 */
class TabbedTerminalState {
    internal var tabController: TabController? by mutableStateOf(null)
    private var initialized = false

    /**
     * Split view states per tab (tab.id -> SplitViewState).
     * Stored here to persist across recomposition when TabbedTerminal unmounts.
     */
    internal val splitStates: SnapshotStateMap<String, SplitViewState> = mutableStateMapOf()

    /**
     * List of all terminal tabs (observable, triggers recomposition).
     */
    val tabs: List<TerminalTab>
        get() = tabController?.tabs?.toList() ?: emptyList()

    /**
     * Number of open tabs.
     */
    val tabCount: Int
        get() = tabController?.tabs?.size ?: 0

    /**
     * Index of the currently active tab (0-based).
     */
    val activeTabIndex: Int
        get() = tabController?.activeTabIndex ?: 0

    /**
     * The currently active tab, or null if no tabs exist.
     */
    val activeTab: TerminalTab?
        get() = tabController?.activeTab

    /**
     * Whether the state has been initialized (has a TabController).
     */
    val isInitialized: Boolean
        get() = initialized && tabController != null

    /**
     * Whether the state has been disposed.
     */
    val isDisposed: Boolean
        get() = tabController == null && initialized

    /**
     * Initialize the TabController. Called automatically by TabbedTerminal.
     * Only initializes once; subsequent calls are no-ops.
     */
    internal fun initialize(
        settings: TerminalSettings,
        onLastTabClosed: () -> Unit,
        isWindowFocused: () -> Boolean
    ) {
        if (initialized) return
        initialized = true

        tabController = TabController(
            settings = settings,
            onLastTabClosed = onLastTabClosed,
            isWindowFocused = isWindowFocused
        )
    }

    /**
     * Dispose all terminal sessions and cleanup resources.
     * After disposal, this state can be reused by calling TabbedTerminal again.
     */
    fun dispose() {
        // Dispose all split states
        splitStates.values.forEach { it.dispose() }
        splitStates.clear()
        // Dispose tab controller
        tabController?.disposeAll()
        tabController = null
        initialized = false
    }

    // ========== Tab Management API ==========

    /**
     * Create a new terminal tab.
     *
     * Note: This method requires the state to be initialized (automatically happens
     * when TabbedTerminal is composed). Calls before initialization are silently ignored.
     *
     * @param workingDir Working directory to start the shell in (null = home directory)
     * @param initialCommand Optional command to run after terminal is ready
     */
    fun createTab(workingDir: String? = null, initialCommand: String? = null) {
        tabController?.createTab(workingDir = workingDir, initialCommand = initialCommand)
    }

    /**
     * Close the tab at the specified index.
     *
     * @param index Index of the tab to close (0-based)
     */
    fun closeTab(index: Int) {
        tabController?.closeTab(index)
    }

    /**
     * Close the currently active tab.
     */
    fun closeActiveTab() {
        tabController?.let { controller ->
            controller.closeTab(controller.activeTabIndex)
        }
    }

    /**
     * Switch to the tab at the specified index.
     *
     * @param index Index of the tab to switch to (0-based)
     */
    fun switchToTab(index: Int) {
        tabController?.switchToTab(index)
    }

    /**
     * Switch to the next tab (wraps around to first if at end).
     */
    fun nextTab() {
        tabController?.nextTab()
    }

    /**
     * Switch to the previous tab (wraps around to last if at beginning).
     */
    fun previousTab() {
        tabController?.previousTab()
    }

    /**
     * Get the working directory of the currently active tab.
     *
     * Returns the directory tracked via OSC 7 shell integration, or null if
     * no working directory has been reported yet.
     *
     * @return The active tab's working directory, or null
     */
    fun getActiveWorkingDirectory(): String? {
        return tabController?.getActiveWorkingDirectory()
    }

    // ========== Input API ==========
    //
    // All input methods are asynchronous - they queue input and return immediately.
    // Input is processed in FIFO order (write() and sendInput() share the same queue).
    // Methods with tabIndex parameter are no-ops if the index is invalid.

    /**
     * Send raw bytes to the active terminal tab's process.
     * Useful for sending control characters like Ctrl+C (0x03) or Ctrl+D (0x04).
     *
     * This method is asynchronous - it queues the bytes and returns immediately.
     * Bytes are sent in FIFO order with respect to [write] calls.
     *
     * Note: If no active tab exists, this call is a no-op.
     *
     * @param bytes Raw bytes to send to the shell
     */
    fun sendInput(bytes: ByteArray) {
        activeTab?.writeRawBytes(bytes)
    }

    /**
     * Send raw bytes to a specific tab's process.
     *
     * This method is asynchronous - it queues the bytes and returns immediately.
     *
     * Note: If tabIndex is out of bounds, this call is a no-op.
     *
     * @param bytes Raw bytes to send to the shell
     * @param tabIndex Index of the tab to send input to (0-based)
     */
    fun sendInput(bytes: ByteArray, tabIndex: Int) {
        tabs.getOrNull(tabIndex)?.writeRawBytes(bytes)
    }

    /**
     * Send text input to the active terminal tab.
     * Use "\n" for enter key.
     *
     * This method is asynchronous - it queues the text and returns immediately.
     *
     * Note: If no active tab exists, this call is a no-op.
     *
     * @param text Text to send to the shell
     */
    fun write(text: String) {
        activeTab?.writeUserInput(text)
    }

    /**
     * Send text input to a specific tab.
     *
     * This method is asynchronous - it queues the text and returns immediately.
     *
     * Note: If tabIndex is out of bounds, this call is a no-op.
     *
     * @param text Text to send to the shell
     * @param tabIndex Index of the tab to send input to (0-based)
     */
    fun write(text: String, tabIndex: Int) {
        tabs.getOrNull(tabIndex)?.writeUserInput(text)
    }

    /**
     * Send Ctrl+C (SIGINT) to the active terminal tab's process.
     * This is equivalent to pressing Ctrl+C in the terminal to interrupt a running process.
     *
     * This method is asynchronous - it queues the signal and returns immediately.
     */
    fun sendCtrlC() {
        sendInput(byteArrayOf(0x03))
    }

    /**
     * Send Ctrl+C (SIGINT) to a specific tab's process.
     *
     * Note: If tabIndex is out of bounds, this call is a no-op.
     *
     * @param tabIndex Index of the tab to send input to (0-based)
     */
    fun sendCtrlC(tabIndex: Int) {
        sendInput(byteArrayOf(0x03), tabIndex)
    }

    /**
     * Send Ctrl+D (EOF) to the active terminal tab's process.
     * This is equivalent to pressing Ctrl+D in the terminal to signal end-of-input.
     *
     * This method is asynchronous - it queues the signal and returns immediately.
     */
    fun sendCtrlD() {
        sendInput(byteArrayOf(0x04))
    }

    /**
     * Send Ctrl+D (EOF) to a specific tab's process.
     *
     * Note: If tabIndex is out of bounds, this call is a no-op.
     *
     * @param tabIndex Index of the tab to send input to (0-based)
     */
    fun sendCtrlD(tabIndex: Int) {
        sendInput(byteArrayOf(0x04), tabIndex)
    }

    /**
     * Send Ctrl+Z (SIGTSTP) to the active terminal tab's process.
     * This is equivalent to pressing Ctrl+Z in the terminal to suspend the foreground process.
     *
     * This method is asynchronous - it queues the signal and returns immediately.
     */
    fun sendCtrlZ() {
        sendInput(byteArrayOf(0x1A))
    }

    /**
     * Send Ctrl+Z (SIGTSTP) to a specific tab's process.
     *
     * Note: If tabIndex is out of bounds, this call is a no-op.
     *
     * @param tabIndex Index of the tab to send input to (0-based)
     */
    fun sendCtrlZ(tabIndex: Int) {
        sendInput(byteArrayOf(0x1A), tabIndex)
    }

    // ========== Session Listeners ==========

    /**
     * Add a session lifecycle listener.
     *
     * @param listener The listener to add
     */
    fun addSessionListener(listener: TerminalSessionListener) {
        tabController?.addSessionListener(listener)
    }

    /**
     * Remove a session lifecycle listener.
     *
     * @param listener The listener to remove
     */
    fun removeSessionListener(listener: TerminalSessionListener) {
        tabController?.removeSessionListener(listener)
    }
}

/**
 * Remember a TabbedTerminalState for controlling a TabbedTerminal composable.
 *
 * @param autoDispose If true (default), all sessions are disposed when this state is forgotten
 *                    (i.e., when the composable that called this leaves composition).
 *                    If false, you must manually call state.dispose() when done.
 * @return TabbedTerminalState instance that persists across recompositions
 */
@Composable
fun rememberTabbedTerminalState(autoDispose: Boolean = true): TabbedTerminalState {
    val state = remember { TabbedTerminalState() }

    if (autoDispose) {
        DisposableEffect(state) {
            onDispose { state.dispose() }
        }
    }

    return state
}
