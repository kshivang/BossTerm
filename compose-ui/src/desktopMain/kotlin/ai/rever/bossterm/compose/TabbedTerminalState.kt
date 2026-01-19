package ai.rever.bossterm.compose

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import ai.rever.bossterm.compose.ai.AIAssistantDefinition
import ai.rever.bossterm.compose.ai.AIAssistantDetector
import ai.rever.bossterm.compose.ai.ToolCommandProvider
import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.ai.AIInstallDialogParams
import ai.rever.bossterm.compose.search.RabinKarpSearch
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
     * The stable ID of the currently active tab, or null if no tabs exist.
     * This ID is stable across tab reordering and can be used for reliable tab targeting.
     */
    val activeTabId: String?
        get() = tabController?.activeTabId

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
        isWindowFocused: () -> Boolean,
        onTabClose: ((tabId: String) -> Unit)? = null
    ) {
        if (initialized) return
        initialized = true

        tabController = TabController(
            settings = settings,
            onLastTabClosed = onLastTabClosed,
            isWindowFocused = isWindowFocused,
            onTabClose = onTabClose
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
     * @param tabId Optional stable ID for this tab. If not provided, a UUID will be generated.
     *              Use this to assign a predictable ID that can be used for later operations
     *              (e.g., sendInput, closeTab) even after tabs are reordered.
     * @return The stable ID of the created tab, or null if the state is not initialized
     * @throws IllegalArgumentException if tabId is provided but already exists
     */
    fun createTab(
        workingDir: String? = null,
        initialCommand: String? = null,
        tabId: String? = null
    ): String? {
        return tabController?.createTab(
            workingDir = workingDir,
            initialCommand = initialCommand,
            tabId = tabId
        )?.id
    }

    /**
     * Find a tab by its stable ID.
     *
     * @param tabId The stable tab ID to search for
     * @return The tab with the given ID, or null if not found
     */
    fun getTabById(tabId: String): TerminalTab? {
        return tabController?.getTabById(tabId)
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
     * Close a tab by its stable ID.
     *
     * @param tabId The stable tab ID to close
     * @return true if the tab was found and closed, false otherwise
     */
    fun closeTab(tabId: String): Boolean {
        return tabController?.closeTabById(tabId) ?: false
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
     * Switch to a tab by its stable ID.
     *
     * @param tabId The stable tab ID to switch to
     * @return true if the tab was found and switched to, false otherwise
     */
    fun switchToTab(tabId: String): Boolean {
        return tabController?.switchToTabById(tabId) ?: false
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
     * Send raw bytes to a specific tab's process by index.
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
     * Send raw bytes to a specific tab's process by stable ID.
     *
     * This method is asynchronous - it queues the bytes and returns immediately.
     * Unlike the index-based variant, this method is stable across tab reordering.
     *
     * @param bytes Raw bytes to send to the shell
     * @param tabId Stable ID of the tab to send input to
     * @return true if the tab was found, false otherwise
     */
    fun sendInput(bytes: ByteArray, tabId: String): Boolean {
        val tab = getTabById(tabId) ?: return false
        tab.writeRawBytes(bytes)
        return true
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
     * Send text input to a specific tab by index.
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
     * Send text input to a specific tab by stable ID.
     *
     * This method is asynchronous - it queues the text and returns immediately.
     * Unlike the index-based variant, this method is stable across tab reordering.
     *
     * @param text Text to send to the shell
     * @param tabId Stable ID of the tab to send input to
     * @return true if the tab was found, false otherwise
     */
    fun write(text: String, tabId: String): Boolean {
        val tab = getTabById(tabId) ?: return false
        tab.writeUserInput(text)
        return true
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
     * Send Ctrl+C (SIGINT) to a specific tab's process by index.
     *
     * Note: If tabIndex is out of bounds, this call is a no-op.
     *
     * @param tabIndex Index of the tab to send input to (0-based)
     */
    fun sendCtrlC(tabIndex: Int) {
        sendInput(byteArrayOf(0x03), tabIndex)
    }

    /**
     * Send Ctrl+C (SIGINT) to a specific tab's process by stable ID.
     *
     * @param tabId Stable ID of the tab to send input to
     * @return true if the tab was found, false otherwise
     */
    fun sendCtrlC(tabId: String): Boolean {
        return sendInput(byteArrayOf(0x03), tabId)
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
     * Send Ctrl+D (EOF) to a specific tab's process by index.
     *
     * Note: If tabIndex is out of bounds, this call is a no-op.
     *
     * @param tabIndex Index of the tab to send input to (0-based)
     */
    fun sendCtrlD(tabIndex: Int) {
        sendInput(byteArrayOf(0x04), tabIndex)
    }

    /**
     * Send Ctrl+D (EOF) to a specific tab's process by stable ID.
     *
     * @param tabId Stable ID of the tab to send input to
     * @return true if the tab was found, false otherwise
     */
    fun sendCtrlD(tabId: String): Boolean {
        return sendInput(byteArrayOf(0x04), tabId)
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
     * Send Ctrl+Z (SIGTSTP) to a specific tab's process by index.
     *
     * Note: If tabIndex is out of bounds, this call is a no-op.
     *
     * @param tabIndex Index of the tab to send input to (0-based)
     */
    fun sendCtrlZ(tabIndex: Int) {
        sendInput(byteArrayOf(0x1A), tabIndex)
    }

    /**
     * Send Ctrl+Z (SIGTSTP) to a specific tab's process by stable ID.
     *
     * @param tabId Stable ID of the tab to send input to
     * @return true if the tab was found, false otherwise
     */
    fun sendCtrlZ(tabId: String): Boolean {
        return sendInput(byteArrayOf(0x1A), tabId)
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

    // ========== AI Assistant Installation API ==========

    /**
     * Internal state for AI assistant installation request.
     * Observed by the TabbedTerminal composable to show the install dialog.
     */
    internal var aiInstallRequest by mutableStateOf<AIInstallDialogParams?>(null)

    /**
     * Get list of available AI assistant IDs.
     *
     * @return List of assistant IDs (e.g., "claude-code", "codex", "gemini-cli", "opencode")
     */
    fun getAvailableAIAssistants(): List<String> = AIAssistants.AI_ASSISTANTS.map { it.id }

    /**
     * Get AI assistant definition by ID.
     *
     * @param assistantId The assistant ID (e.g., "claude-code")
     * @return The assistant definition, or null if not found
     */
    fun getAIAssistant(assistantId: String): AIAssistantDefinition? =
        AIAssistants.findById(assistantId)

    /**
     * Check if an AI assistant is installed.
     *
     * @param assistantId The assistant ID to check
     * @return true if installed, false otherwise
     */
    suspend fun isAIAssistantInstalled(assistantId: String): Boolean {
        val assistant = AIAssistants.findById(assistantId) ?: return false
        return AIAssistantDetector().detectSingle(assistant)
    }

    /**
     * Trigger installation of an AI assistant.
     * Opens the installation dialog in the terminal.
     *
     * @param assistantId The assistant ID to install (e.g., "claude-code", "codex", "gemini-cli", "opencode")
     * @param useNpm If true, use npm installation; if false (default), use script installation with npm fallback
     * @return true if installation was triggered, false if assistant not found or no active tab
     */
    fun installAIAssistant(assistantId: String, useNpm: Boolean = false): Boolean {
        val assistant = AIAssistants.findById(assistantId) ?: return false
        val currentTab = activeTab ?: return false
        return triggerInstall(assistant, currentTab, useNpm)
    }

    /**
     * Trigger installation of an AI assistant in a specific tab.
     *
     * @param assistantId The assistant ID to install
     * @param tabIndex Index of the tab to use for the installation
     * @param useNpm If true, use npm installation; if false (default), use script installation with npm fallback
     * @return true if installation was triggered, false if assistant not found or tab index invalid
     */
    fun installAIAssistant(assistantId: String, tabIndex: Int, useNpm: Boolean = false): Boolean {
        val assistant = AIAssistants.findById(assistantId) ?: return false
        val tab = tabs.getOrNull(tabIndex) ?: return false
        return triggerInstall(assistant, tab, useNpm)
    }

    /**
     * Trigger installation of an AI assistant in a specific tab by stable ID.
     *
     * @param assistantId The assistant ID to install
     * @param tabId Stable ID of the tab to use for the installation
     * @param useNpm If true, use npm installation; if false (default), use script installation with npm fallback
     * @return true if installation was triggered, false if assistant or tab not found
     */
    fun installAIAssistant(assistantId: String, tabId: String, useNpm: Boolean = false): Boolean {
        val assistant = AIAssistants.findById(assistantId) ?: return false
        val tab = getTabById(tabId) ?: return false
        return triggerInstall(assistant, tab, useNpm)
    }

    /**
     * Internal helper to trigger installation for a given tab.
     */
    private fun triggerInstall(assistant: AIAssistantDefinition, tab: TerminalTab, useNpm: Boolean): Boolean {
        val resolved = ToolCommandProvider().resolveInstallCommands(assistant, useNpm)

        aiInstallRequest = AIInstallDialogParams(
            assistant = assistant,
            command = resolved.command,
            npmCommand = resolved.npmFallback,
            terminalWriter = { text -> tab.writeUserInput(text) }
        )
        return true
    }

    /**
     * Cancel any pending AI assistant installation request.
     */
    fun cancelAIInstallation() {
        aiInstallRequest = null
    }

    // ==================== VCS Tool Installation ====================

    /**
     * Trigger installation of Git.
     * Opens the installation dialog in the active tab's terminal.
     *
     * @return true if installation was triggered, false if no active tab
     */
    fun installGit(): Boolean = installAIAssistant("git")

    /**
     * Trigger installation of Git in a specific tab.
     *
     * @param tabIndex Index of the tab to use for the installation
     * @return true if installation was triggered, false if tab index invalid
     */
    fun installGit(tabIndex: Int): Boolean = installAIAssistant("git", tabIndex)

    /**
     * Trigger installation of Git in a specific tab by stable ID.
     *
     * @param tabId Stable ID of the tab to use for the installation
     * @return true if installation was triggered, false if tab not found
     */
    fun installGit(tabId: String): Boolean = installAIAssistant("git", tabId)

    /**
     * Trigger installation of GitHub CLI (gh).
     * Opens the installation dialog in the active tab's terminal.
     *
     * @return true if installation was triggered, false if no active tab
     */
    fun installGitHubCLI(): Boolean = installAIAssistant("gh")

    /**
     * Trigger installation of GitHub CLI (gh) in a specific tab.
     *
     * @param tabIndex Index of the tab to use for the installation
     * @return true if installation was triggered, false if tab index invalid
     */
    fun installGitHubCLI(tabIndex: Int): Boolean = installAIAssistant("gh", tabIndex)

    /**
     * Trigger installation of GitHub CLI (gh) in a specific tab by stable ID.
     *
     * @param tabId Stable ID of the tab to use for the installation
     * @return true if installation was triggered, false if tab not found
     */
    fun installGitHubCLI(tabId: String): Boolean = installAIAssistant("gh", tabId)

    // ========== Pattern Search API ==========

    /**
     * Search for a text pattern in the active terminal buffer.
     *
     * This uses the Rabin-Karp algorithm for efficient O(n+m) substring search
     * across the terminal buffer (including scrollback history).
     *
     * @param pattern The text pattern to search for
     * @param ignoreCase If true, performs case-insensitive search (default: false)
     * @return List of matches with text and coordinates, or empty list if not found or no active tab
     */
    fun findPattern(pattern: String, ignoreCase: Boolean = false): List<PatternMatch> {
        if (pattern.isEmpty()) return emptyList()

        // Get the active tab's split state
        val activeId = activeTabId ?: return emptyList()
        val splitState = splitStates[activeId] ?: return emptyList()
        val session = splitState.getFocusedSession() ?: return emptyList()

        // Create snapshot for searching (RabinKarpSearch expects BufferSnapshot)
        val snapshot = session.textBuffer.createSnapshot()

        // Search using Rabin-Karp algorithm
        val matches = RabinKarpSearch.searchBuffer(snapshot, pattern, ignoreCase)

        // Extract matched text from buffer
        return matches.mapNotNull { match ->
            val line = snapshot.getLine(match.row)
            val endCol = minOf(match.column + pattern.length, line.text.length)
            if (match.column < line.text.length) {
                PatternMatch(
                    text = line.text.substring(match.column, endCol),
                    row = match.row,
                    column = match.column
                )
            } else null
        }
    }

    /**
     * Search for a regex pattern in the active terminal buffer.
     *
     * This searches line-by-line using Kotlin's Regex engine.
     * Use this for complex patterns like OTP codes (e.g., "[A-Z0-9]{4}-[A-Z0-9]{4}").
     *
     * @param regexPattern The regex pattern to search for
     * @param ignoreCase If true, performs case-insensitive search (default: false)
     * @return List of matches with text and coordinates, or empty list if not found or no active tab
     */
    fun findPatternRegex(regexPattern: String, ignoreCase: Boolean = false): List<PatternMatch> {
        if (regexPattern.isEmpty()) return emptyList()

        // Get the active tab's split state
        val activeId = activeTabId ?: return emptyList()
        val splitState = splitStates[activeId] ?: return emptyList()
        val session = splitState.getFocusedSession() ?: return emptyList()

        // Create lock-free snapshot for searching
        val snapshot = session.textBuffer.createIncrementalSnapshot()

        // Compile regex with options
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = try {
            Regex(regexPattern, options)
        } catch (e: Exception) {
            return emptyList() // Invalid regex
        }

        val matches = mutableListOf<PatternMatch>()

        // Search from history through screen buffer
        for (row in -snapshot.historyLinesCount until snapshot.height) {
            val line = snapshot.getLine(row)
            val text = line.text

            // Find all matches in this line
            regex.findAll(text).forEach { matchResult ->
                matches.add(
                    PatternMatch(
                        text = matchResult.value,
                        row = row,
                        column = matchResult.range.first
                    )
                )
            }
        }

        return matches
    }
}

/**
 * Represents a pattern match found in the terminal buffer.
 *
 * @property text The actual matched text extracted from the buffer
 * @property row The row number where the match was found (negative for history, 0+ for screen)
 * @property column The column position where the match starts (0-based)
 */
data class PatternMatch(
    val text: String,
    val row: Int,
    val column: Int
)

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
