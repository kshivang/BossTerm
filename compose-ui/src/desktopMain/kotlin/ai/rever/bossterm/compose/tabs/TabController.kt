package ai.rever.bossterm.compose.tabs

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalApplicationTitleListener
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlinx.coroutines.*
import ai.rever.bossterm.compose.ComposeQuestioner
import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.compose.ConnectionState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.debug.ChunkSource
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.PerformanceMode
import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.ime.IMEState
import ai.rever.bossterm.compose.osc.WorkingDirectoryOSCListener
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.typeahead.ComposeTypeAheadModel
import ai.rever.bossterm.compose.typeahead.CoroutineDebouncer
import ai.rever.bossterm.compose.notification.CommandNotificationHandler
import ai.rever.bossterm.compose.clipboard.ClipboardHandler
import ai.rever.bossterm.terminal.model.CommandStateListener
import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.core.typeahead.TerminalTypeAheadManager
import ai.rever.bossterm.core.typeahead.TypeAheadTerminalModel
import ai.rever.bossterm.terminal.util.GraphemeBoundaryUtils
import java.io.EOFException

/**
 * Controller for managing multiple terminal tabs.
 *
 * This class is responsible for the lifecycle of terminal tabs, including:
 * - Creating new tabs with full terminal initialization
 * - Closing tabs with proper resource cleanup
 * - Switching between tabs
 * - Tracking working directories for tab inheritance
 *
 * Architecture:
 * - Each tab has independent PTY process, terminal state, and UI state
 * - Tabs run background jobs even when not visible
 * - Active tab receives keyboard focus and immediate updates
 * - Background tabs pause UI updates (performance optimization)
 */
class TabController(
    private val settings: TerminalSettings,
    private val onLastTabClosed: () -> Unit,
    private val isWindowFocused: () -> Boolean = { true },
    private val onTabClose: ((tabId: String) -> Unit)? = null
) {
    /**
     * List of all terminal tabs (observable, triggers recomposition).
     */
    val tabs: SnapshotStateList<TerminalTab> = mutableStateListOf()

    /**
     * Index of the currently active tab (0-based).
     */
    var activeTabIndex by mutableStateOf(0)
        private set

    /**
     * Counter for generating unique tab titles (Shell 1, Shell 2, etc.).
     */
    private var tabCounter = 0

    /**
     * Registered session lifecycle listeners.
     * Thread-safe: uses CopyOnWriteArrayList for safe iteration during modification.
     */
    private val sessionListeners = java.util.concurrent.CopyOnWriteArrayList<TerminalSessionListener>()

    /**
     * Dedicated scope for cleanup operations (process kills).
     * Using a dedicated scope instead of GlobalScope ensures:
     * 1. Coroutines are cancelled when the controller is disposed
     * 2. Better lifecycle management than orphaned GlobalScope coroutines
     * 3. SupervisorJob prevents individual failures from cancelling siblings
     */
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Add a session lifecycle listener.
     *
     * @param listener The listener to add
     */
    fun addSessionListener(listener: TerminalSessionListener) {
        sessionListeners.add(listener)
    }

    /**
     * Remove a session lifecycle listener.
     *
     * @param listener The listener to remove
     */
    fun removeSessionListener(listener: TerminalSessionListener) {
        sessionListeners.remove(listener)
    }

    /**
     * Notify all listeners that a session was created.
     */
    private fun notifySessionCreated(session: TerminalTab) {
        sessionListeners.forEach { listener ->
            try {
                listener.onSessionCreated(session)
            } catch (e: Exception) {
                println("WARN: Session listener threw exception in onSessionCreated: ${e.message}")
            }
        }
    }

    /**
     * Notify all listeners that a session was closed.
     */
    private fun notifySessionClosed(session: TerminalTab) {
        sessionListeners.forEach { listener ->
            try {
                listener.onSessionClosed(session)
            } catch (e: Exception) {
                println("WARN: Session listener threw exception in onSessionClosed: ${e.message}")
            }
        }
    }

    /**
     * Notify all listeners that all sessions have been closed.
     */
    private fun notifyAllSessionsClosed() {
        sessionListeners.forEach { listener ->
            try {
                listener.onAllSessionsClosed()
            } catch (e: Exception) {
                println("WARN: Session listener threw exception in onAllSessionsClosed: ${e.message}")
            }
        }
    }

    /**
     * Log an error/warning message to both System.err and the tab's debug collector.
     * This ensures errors are visible in both the console and the debug panel.
     *
     * @param tab The tab to log to (or null for global logging)
     * @param message The error message
     * @param exception Optional exception for stack trace
     */
    private fun logTabError(tab: TerminalTab?, message: String, exception: Exception? = null) {
        val timestamp = java.time.Instant.now().toString()
        val fullMessage = if (exception != null) {
            "[$timestamp] $message\n${exception.stackTraceToString()}"
        } else {
            "[$timestamp] $message"
        }
        System.err.println(fullMessage)
        tab?.debugCollector?.recordChunk(fullMessage, ChunkSource.CONSOLE_LOG)
    }

    /**
     * Get the currently active tab, or null if no tabs exist.
     */
    val activeTab: TerminalTab?
        get() = tabs.getOrNull(activeTabIndex)

    /**
     * Get the ID of the currently active tab, or null if no tabs exist.
     */
    val activeTabId: String?
        get() = activeTab?.id

    /**
     * Find a tab by its stable ID.
     *
     * @param tabId The unique tab ID (UUID) to search for
     * @return The tab with the given ID, or null if not found
     */
    fun getTabById(tabId: String): TerminalTab? {
        return tabs.find { it.id == tabId }
    }

    /**
     * Find the index of a tab by its stable ID.
     *
     * @param tabId The unique tab ID (UUID) to search for
     * @return The index of the tab (0-based), or -1 if not found
     */
    fun getTabIndexById(tabId: String): Int {
        return tabs.indexOfFirst { it.id == tabId }
    }

    /**
     * Close a tab by its stable ID.
     *
     * @param tabId The unique tab ID (UUID) to close
     * @return true if the tab was found and closed, false if not found
     */
    fun closeTabById(tabId: String): Boolean {
        val index = getTabIndexById(tabId)
        if (index == -1) return false
        closeTab(index)
        return true
    }

    /**
     * Switch to a tab by its stable ID.
     *
     * @param tabId The unique tab ID (UUID) to switch to
     * @return true if the tab was found and switched to, false if not found
     */
    fun switchToTabById(tabId: String): Boolean {
        val index = getTabIndexById(tabId)
        if (index == -1) return false
        switchToTab(index)
        return true
    }

    /**
     * Create a new terminal tab with optional working directory.
     *
     * @param workingDir Working directory to start the shell in (inherits from active tab if null)
     * @param command Shell command to execute (default: $SHELL or /bin/sh)
     * @param arguments Command-line arguments for the shell (default: empty)
     * @param onProcessExit Callback invoked when shell process exits (before auto-closing tab)
     * @param initialCommand Optional command to execute after terminal is ready (sent as input with newline)
     * @param tabId Optional stable ID for this tab (default: auto-generated UUID). Use this to assign
     *              a predictable ID that survives tab reordering and can be used for reliable lookup.
     * @return The newly created TerminalTab
     * @throws IllegalArgumentException if tabId is provided but already exists
     */
    fun createTab(
        workingDir: String? = null,
        command: String? = null,
        arguments: List<String> = emptyList(),
        onProcessExit: (() -> Unit)? = null,
        initialCommand: String? = null,
        onInitialCommandComplete: ((success: Boolean, exitCode: Int) -> Unit)? = null,
        tabId: String? = null
    ): TerminalTab {
        // Validate tab ID uniqueness if custom ID provided
        if (tabId != null && tabs.any { it.id == tabId }) {
            throw IllegalArgumentException(
                "Tab ID '$tabId' already exists. Each tab must have a unique ID."
            )
        }

        tabCounter++

        // On macOS, optionally use 'login -fp $USER' to properly register the session
        // This shows "Last login" message and registers in utmp/wtmp like iTerm2
        val isMacOS = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
        val username = System.getProperty("user.name")

        val (effectiveCommand, effectiveArguments) = if (command == null && arguments.isEmpty() && isMacOS && username != null && settings.useLoginSession && workingDir == null) {
            // Use login command on macOS for proper session registration
            // NOTE: Only when workingDir is null - login command ignores workingDirectory parameter
            "/usr/bin/login" to listOf("-fp", username)
        } else {
            // Use provided command or fall back to a valid shell
            val shellCommand = command ?: ShellCustomizationUtils.getValidShell()
            // Ensure shell is started as login shell to get proper PATH from /etc/zprofile
            val shellArgs = if (arguments.isEmpty() &&
                (shellCommand.endsWith("/zsh") || shellCommand.endsWith("/bash") ||
                 shellCommand == "zsh" || shellCommand == "bash")) {
                listOf("-l")  // Login shell flag
            } else {
                arguments
            }
            shellCommand to shellArgs
        }

        // Initialize terminal components
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
        val display = ComposeTerminalDisplay()
        val terminal = BossTerminal(display, textBuffer, styleState)

        // CRITICAL: Register ModelListener to trigger redraws when buffer content changes
        // This is how the Swing TerminalPanel gets notified - without this, the display
        // never knows when to redraw after new text is written to the buffer!
        //
        // IMPORTANT: We store a reference to this listener so it can be removed in
        // TerminalTab.dispose(). Without proper cleanup, listeners accumulate over
        // hours of tab create/close cycles, causing memory leaks and potential crashes.
        val modelListener = object : ai.rever.bossterm.terminal.model.TerminalModelListener {
            override fun modelChanged() {
                // Use adaptive debouncing to prevent TUI flickering during streaming
                // Clear+write sequences coalesce into single render within debounce window
                display.requestRedraw()
            }
        }
        textBuffer.addModelListener(modelListener)

        // Configure character encoding mode (ISO-8859-1 enables GR mapping, UTF-8 disables it)
        terminal.setCharacterEncoding(settings.characterEncoding)

        val dataStream = BlockingTerminalDataStream(
            performanceMode = PerformanceMode.fromString(settings.performanceMode)
        )

        // Create working directory state
        val workingDirectoryState = mutableStateOf<String?>(workingDir)

        // Register OSC 7 listener for working directory tracking (Phase 4)
        val oscListener = WorkingDirectoryOSCListener(workingDirectoryState)
        terminal.addCustomCommandListener(oscListener)

        // Register window title listener for reactive updates (OSC 0/1/2 sequences)
        terminal.addApplicationTitleListener(object : TerminalApplicationTitleListener {
            override fun onApplicationTitleChanged(newApplicationTitle: String) {
                display.windowTitle = newApplicationTitle
            }

            override fun onApplicationIconTitleChanged(newIconTitle: String) {
                display.iconTitle = newIconTitle
            }
        })

        // Register command state listener for notifications (OSC 133 shell integration)
        val notificationHandler = CommandNotificationHandler(
            settings = settings,
            isWindowFocused = isWindowFocused,
            tabTitle = { display.windowTitle?.ifEmpty { "BossTerm" } ?: "BossTerm" }
        )
        terminal.addCommandStateListener(notificationHandler)

        // Register clipboard listener (OSC 52)
        val clipboardHandler = ClipboardHandler(settings)
        terminal.addClipboardListener(clipboardHandler)

        // Create emulator with terminal
        val emulator = BossEmulator(dataStream, terminal)

        // Always create debug collector (so it's available when user enables debug mode in settings)
        val debugCollector = ai.rever.bossterm.compose.debug.DebugDataCollector(
            tab = null,  // Will be set after tab creation
            maxChunks = settings.debugMaxChunks,
            maxSnapshots = settings.debugMaxSnapshots
        )

        // Create type-ahead model and manager if enabled
        val typeAheadModel = if (settings.typeAheadEnabled) {
            ComposeTypeAheadModel(
                terminal = terminal,
                textBuffer = textBuffer,
                display = display,
                settings = settings
            ).also { model ->
                // Detect shell type for word boundary calculation (bash vs zsh)
                val shellType = TypeAheadTerminalModel.commandLineToShellType((listOf(effectiveCommand) + effectiveArguments).toMutableList())
                model.setShellType(shellType)
            }
        } else {
            null
        }

        // Create coroutine scope for type-ahead (will be shared with tab scope)
        val tabCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val typeAheadManager = typeAheadModel?.let { model ->
            TerminalTypeAheadManager(model).also { manager ->
                // Set up coroutine-based debouncer for clearing stale predictions
                val debouncer = CoroutineDebouncer(
                    action = manager::debounce,
                    delayNanos = TerminalTypeAheadManager.MAX_TERMINAL_DELAY,
                    scope = tabCoroutineScope
                )
                manager.setClearPredictionsDebouncer(debouncer)
            }
        }

        // Create tab with all state
        val tab = TerminalTab(
            id = tabId ?: java.util.UUID.randomUUID().toString(),
            title = mutableStateOf("Shell $tabCounter"),
            terminal = terminal,
            textBuffer = textBuffer,
            display = display,
            dataStream = dataStream,
            emulator = emulator,
            processHandle = mutableStateOf(null),
            workingDirectory = workingDirectoryState,
            connectionState = mutableStateOf(ConnectionState.Initializing),
            onProcessExit = onProcessExit,
            coroutineScope = tabCoroutineScope,
            isFocused = mutableStateOf(false),
            scrollOffset = mutableStateOf(0),
            searchVisible = mutableStateOf(false),
            searchQuery = mutableStateOf(""),
            searchMatches = mutableStateOf(emptyList()),
            currentSearchMatchIndex = mutableStateOf(-1),
            selectionClipboard = mutableStateOf(null),
            imeState = IMEState(),
            contextMenuController = ContextMenuController(),
            hyperlinks = mutableStateOf(emptyList()),
            hoveredHyperlink = mutableStateOf(null),
            debugEnabled = mutableStateOf(settings.debugModeEnabled),
            debugCollector = debugCollector,
            typeAheadModel = typeAheadModel,
            typeAheadManager = typeAheadManager,
            modelListener = modelListener
        )

        // Complete debug collector initialization
        debugCollector?.let { collector ->
            // Set the tab reference now that tab is created
            collector.setTab(tab)

            // Hook into data stream for PTY output capture
            dataStream.debugCallback = { data ->
                collector.recordChunk(data, ChunkSource.PTY_OUTPUT)
            }

            // Hook into display for console log capture (errors, warnings)
            display.debugLogCallback = { message ->
                collector.recordChunk(message, ChunkSource.CONSOLE_LOG)
            }
        }

        // Connect type-ahead manager to PTY arrival notifications
        typeAheadManager?.let { manager ->
            dataStream.onTerminalStateChanged = {
                manager.onTerminalStateChanged()
            }
        }

        // Wire up chunk batching to prevent intermediate state flickering
        // When a PTY chunk is received (e.g., \r\033[KText), all operations are batched
        // so the clear and write are treated as a single atomic update
        dataStream.onChunkStart = {
            textBuffer.beginBatch()
        }
        dataStream.onChunkEnd = {
            textBuffer.endBatch()
        }

        // Initialize the terminal session (spawn PTY, start coroutines)
        initializeTerminalSession(tab, workingDir, effectiveCommand, effectiveArguments, initialCommand, onInitialCommandComplete)

        // Add to tabs list
        tabs.add(tab)

        // Notify listeners about new session
        notifySessionCreated(tab)

        // Switch to newly created tab
        switchToTab(tabs.size - 1)

        return tab
    }

    /**
     * Create a terminal session for use in split panes.
     *
     * Unlike createTab(), this method:
     * - Does NOT add the session to the tabs list
     * - Does NOT increment the tab counter
     * - Does NOT notify session listeners
     * - Does NOT switch tabs
     *
     * The session is fully initialized with PTY, emulator, and background coroutines.
     * Caller is responsible for managing the session lifecycle via dispose().
     *
     * @param workingDir Working directory to start the shell in
     * @param command Shell command to execute (default: $SHELL or /bin/sh)
     * @param arguments Command-line arguments for the shell (default: empty)
     * @param sessionTitle Title for the session (used for display purposes)
     * @param onProcessExit Callback invoked when the shell process exits (for pane auto-close)
     * @param tabId Optional stable ID for this session (default: auto-generated UUID). The ID is
     *              preserved when the session is promoted to a tab via createTabFromExistingSession.
     * @return A fully initialized TerminalSession for use in split panes
     * @throws IllegalArgumentException if tabId is provided but already exists in the tabs list
     */
    fun createSessionForSplit(
        workingDir: String? = null,
        command: String? = null,
        arguments: List<String> = emptyList(),
        sessionTitle: String = "Split",
        onProcessExit: (() -> Unit)? = null,
        tabId: String? = null
    ): TerminalSession {
        // Validate tab ID uniqueness if custom ID provided
        // Note: We check against tabs list for consistency, even though split sessions
        // aren't added to tabs until promoted via createTabFromExistingSession
        if (tabId != null && tabs.any { it.id == tabId }) {
            throw IllegalArgumentException(
                "Tab ID '$tabId' already exists. Each tab/session must have a unique ID."
            )
        }

        // On macOS, optionally use 'login -fp $USER' for proper session registration
        val isMacOS = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
        val username = System.getProperty("user.name")

        val (effectiveCommand, effectiveArguments) = if (command == null && arguments.isEmpty() && isMacOS && username != null && settings.useLoginSession && workingDir == null) {
            // Use login command on macOS for proper session registration
            // NOTE: Only when workingDir is null - login command ignores workingDirectory parameter
            "/usr/bin/login" to listOf("-fp", username)
        } else {
            // Use provided command or fall back to a valid shell
            val shellCommand = command ?: ShellCustomizationUtils.getValidShell()
            // Ensure shell is started as login shell to get proper PATH from /etc/zprofile
            val shellArgs = if (arguments.isEmpty() &&
                (shellCommand.endsWith("/zsh") || shellCommand.endsWith("/bash") ||
                 shellCommand == "zsh" || shellCommand == "bash")) {
                listOf("-l")  // Login shell flag
            } else {
                arguments
            }
            shellCommand to shellArgs
        }

        // Initialize terminal components (same as createTab)
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
        val display = ComposeTerminalDisplay()
        val terminal = BossTerminal(display, textBuffer, styleState)

        // Register ModelListener to trigger redraws when buffer content changes
        // IMPORTANT: Store reference for cleanup in dispose()
        val modelListener = object : ai.rever.bossterm.terminal.model.TerminalModelListener {
            override fun modelChanged() {
                // Use adaptive debouncing to prevent TUI flickering during streaming
                // Clear+write sequences coalesce into single render within debounce window
                display.requestRedraw()
            }
        }
        textBuffer.addModelListener(modelListener)

        // Configure character encoding mode
        terminal.setCharacterEncoding(settings.characterEncoding)

        val dataStream = BlockingTerminalDataStream(
            performanceMode = PerformanceMode.fromString(settings.performanceMode)
        )

        // Create working directory state
        val workingDirectoryState = mutableStateOf<String?>(workingDir)

        // Register OSC 7 listener for working directory tracking
        val oscListener = WorkingDirectoryOSCListener(workingDirectoryState)
        terminal.addCustomCommandListener(oscListener)

        // Register window title listener for reactive updates (OSC 0/1/2 sequences)
        terminal.addApplicationTitleListener(object : TerminalApplicationTitleListener {
            override fun onApplicationTitleChanged(newApplicationTitle: String) {
                display.windowTitle = newApplicationTitle
            }

            override fun onApplicationIconTitleChanged(newIconTitle: String) {
                display.iconTitle = newIconTitle
            }
        })

        // Register command state listener for notifications (OSC 133 shell integration)
        val notificationHandler = CommandNotificationHandler(
            settings = settings,
            isWindowFocused = isWindowFocused,
            tabTitle = { display.windowTitle?.ifEmpty { sessionTitle } ?: sessionTitle }
        )
        terminal.addCommandStateListener(notificationHandler)

        // Register clipboard listener (OSC 52)
        val clipboardHandler = ClipboardHandler(settings)
        terminal.addClipboardListener(clipboardHandler)

        // Create emulator with terminal
        val emulator = BossEmulator(dataStream, terminal)

        // Always create debug collector (so it's available when user enables debug mode)
        val debugCollector = ai.rever.bossterm.compose.debug.DebugDataCollector(
            tab = null,  // Will be set after tab creation
            maxChunks = settings.debugMaxChunks,
            maxSnapshots = settings.debugMaxSnapshots
        )

        // Create type-ahead model and manager if enabled
        val tabCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val typeAheadModel = if (settings.typeAheadEnabled) {
            ComposeTypeAheadModel(
                terminal = terminal,
                textBuffer = textBuffer,
                display = display,
                settings = settings
            ).also { model ->
                val shellType = TypeAheadTerminalModel.commandLineToShellType(
                    (listOf(effectiveCommand) + effectiveArguments).toMutableList()
                )
                model.setShellType(shellType)
            }
        } else {
            null
        }

        val typeAheadManager = typeAheadModel?.let { model ->
            TerminalTypeAheadManager(model).also { manager ->
                val debouncer = CoroutineDebouncer(
                    action = manager::debounce,
                    delayNanos = TerminalTypeAheadManager.MAX_TERMINAL_DELAY,
                    scope = tabCoroutineScope
                )
                manager.setClearPredictionsDebouncer(debouncer)
            }
        }

        // Create session (TerminalTab) with all state
        val session = TerminalTab(
            id = tabId ?: java.util.UUID.randomUUID().toString(),
            title = mutableStateOf(sessionTitle),
            terminal = terminal,
            textBuffer = textBuffer,
            display = display,
            dataStream = dataStream,
            emulator = emulator,
            processHandle = mutableStateOf(null),
            workingDirectory = workingDirectoryState,
            connectionState = mutableStateOf(ConnectionState.Initializing),
            onProcessExit = onProcessExit,  // Callback for split pane closure
            coroutineScope = tabCoroutineScope,
            isFocused = mutableStateOf(false),
            scrollOffset = mutableStateOf(0),
            searchVisible = mutableStateOf(false),
            searchQuery = mutableStateOf(""),
            searchMatches = mutableStateOf(emptyList()),
            currentSearchMatchIndex = mutableStateOf(-1),
            selectionClipboard = mutableStateOf(null),
            imeState = IMEState(),
            contextMenuController = ContextMenuController(),
            hyperlinks = mutableStateOf(emptyList()),
            hoveredHyperlink = mutableStateOf(null),
            debugEnabled = mutableStateOf(settings.debugModeEnabled),
            debugCollector = debugCollector,
            typeAheadModel = typeAheadModel,
            typeAheadManager = typeAheadManager,
            modelListener = modelListener
        )

        // Complete debug collector initialization
        debugCollector?.let { collector ->
            collector.setTab(session)
            dataStream.debugCallback = { data ->
                collector.recordChunk(data, ChunkSource.PTY_OUTPUT)
            }
            // Hook into display for console log capture (errors, warnings)
            display.debugLogCallback = { message ->
                collector.recordChunk(message, ChunkSource.CONSOLE_LOG)
            }
        }

        // Connect type-ahead manager to PTY arrival notifications
        typeAheadManager?.let { manager ->
            dataStream.onTerminalStateChanged = {
                manager.onTerminalStateChanged()
            }
        }

        // Wire up chunk batching to prevent intermediate state flickering
        dataStream.onChunkStart = {
            textBuffer.beginBatch()
        }
        dataStream.onChunkEnd = {
            textBuffer.endBatch()
        }

        // Initialize the terminal session (spawn PTY, start coroutines)
        // Note: We don't pass onProcessExit since split pane lifecycle is managed separately
        initializeTerminalSession(session, workingDir, effectiveCommand, effectiveArguments)

        return session
    }

    /**
     * Pre-connection configuration collected from user input.
     */
    data class PreConnectConfig(
        val command: String,
        val arguments: List<String> = emptyList(),
        val workingDir: String? = null,
        val environment: Map<String, String> = emptyMap()
    )

    /**
     * Create a new terminal tab with pre-connection user prompts.
     *
     * This method allows interactive setup before the PTY is spawned, useful for:
     * - SSH connections requiring passwords or 2FA
     * - Custom host/port selection
     * - Environment variable configuration
     *
     * The preConnectHandler receives a ComposeQuestioner that can prompt for input.
     * The handler returns either a PreConnectConfig to proceed, or null to cancel.
     *
     * Example:
     * ```kotlin
     * tabController.createTabWithPreConnect { questioner ->
     *     // Use dropdown for connection type selection
     *     val connectionType = questioner.questionSelection(
     *         prompt = "Select connection type:",
     *         options = listOf(
     *             ConnectionState.SelectOption("ssh", "SSH"),
     *             ConnectionState.SelectOption("local", "Local Shell")
     *         )
     *     ) ?: return@createTabWithPreConnect null  // Cancelled
     *
     *     val host = questioner.questionVisible("Enter SSH host:", "localhost")
     *     val password = questioner.questionHidden("Enter password:")
     *     if (password == null) return@createTabWithPreConnect null
     *
     *     questioner.showMessage("Connecting to $host...")
     *     PreConnectConfig(
     *         command = "ssh",
     *         arguments = listOf("-l", "user", host)
     *     )
     * }
     * ```
     *
     * @param onProcessExit Optional callback invoked when shell process exits
     * @param preConnectHandler Suspend function to gather configuration via user prompts
     * @return The newly created TerminalTab, or null if user cancelled
     */
    @Suppress("DEPRECATION")
    fun createTabWithPreConnect(
        onProcessExit: (() -> Unit)? = null,
        preConnectHandler: suspend (ComposeQuestioner) -> PreConnectConfig?
    ): TerminalTab {
        tabCounter++

        // Initialize terminal components (same as createTab)
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
        val display = ComposeTerminalDisplay()
        val terminal = BossTerminal(display, textBuffer, styleState)

        // IMPORTANT: Store reference for cleanup in dispose()
        val modelListener = object : ai.rever.bossterm.terminal.model.TerminalModelListener {
            override fun modelChanged() {
                // Use adaptive debouncing to prevent TUI flickering during streaming
                // Clear+write sequences coalesce into single render within debounce window
                display.requestRedraw()
            }
        }
        textBuffer.addModelListener(modelListener)

        terminal.setCharacterEncoding(settings.characterEncoding)

        val dataStream = BlockingTerminalDataStream(
            performanceMode = PerformanceMode.fromString(settings.performanceMode)
        )
        val workingDirectoryState = mutableStateOf<String?>(null)

        val oscListener = WorkingDirectoryOSCListener(workingDirectoryState)
        terminal.addCustomCommandListener(oscListener)

        terminal.addApplicationTitleListener(object : TerminalApplicationTitleListener {
            override fun onApplicationTitleChanged(newApplicationTitle: String) {
                display.windowTitle = newApplicationTitle
            }

            override fun onApplicationIconTitleChanged(newIconTitle: String) {
                display.iconTitle = newIconTitle
            }
        })

        // Register command state listener for notifications (OSC 133 shell integration)
        val notificationHandler = CommandNotificationHandler(
            settings = settings,
            isWindowFocused = isWindowFocused,
            tabTitle = { display.windowTitle?.ifEmpty { "BossTerm" } ?: "BossTerm" }
        )
        terminal.addCommandStateListener(notificationHandler)

        // Register clipboard listener (OSC 52)
        val clipboardHandler = ClipboardHandler(settings)
        terminal.addClipboardListener(clipboardHandler)

        val emulator = BossEmulator(dataStream, terminal)

        // Always create debug collector (so it's available when user enables debug mode in settings)
        val debugCollector = ai.rever.bossterm.compose.debug.DebugDataCollector(
            tab = null,
            maxChunks = settings.debugMaxChunks,
            maxSnapshots = settings.debugMaxSnapshots
        )

        val tabCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Create tab with Initializing state
        val tab = TerminalTab(
            id = java.util.UUID.randomUUID().toString(),
            title = mutableStateOf("Shell $tabCounter"),
            terminal = terminal,
            textBuffer = textBuffer,
            display = display,
            dataStream = dataStream,
            emulator = emulator,
            processHandle = mutableStateOf(null),
            workingDirectory = workingDirectoryState,
            connectionState = mutableStateOf(ConnectionState.Initializing),
            onProcessExit = onProcessExit,
            coroutineScope = tabCoroutineScope,
            isFocused = mutableStateOf(false),
            scrollOffset = mutableStateOf(0),
            searchVisible = mutableStateOf(false),
            searchQuery = mutableStateOf(""),
            searchMatches = mutableStateOf(emptyList()),
            currentSearchMatchIndex = mutableStateOf(-1),
            selectionClipboard = mutableStateOf(null),
            imeState = IMEState(),
            contextMenuController = ContextMenuController(),
            hyperlinks = mutableStateOf(emptyList()),
            hoveredHyperlink = mutableStateOf(null),
            debugEnabled = mutableStateOf(settings.debugModeEnabled),
            debugCollector = debugCollector,
            typeAheadModel = null,  // Type-ahead configured after preConnect
            typeAheadManager = null,
            modelListener = modelListener
        )

        debugCollector?.let { collector ->
            collector.setTab(tab)
            dataStream.debugCallback = { data ->
                collector.recordChunk(data, ChunkSource.PTY_OUTPUT)
            }
            // Hook into display for console log capture (errors, warnings)
            display.debugLogCallback = { message ->
                collector.recordChunk(message, ChunkSource.CONSOLE_LOG)
            }
        }

        // Add to tabs list
        tabs.add(tab)

        // Notify listeners about new session
        notifySessionCreated(tab)

        switchToTab(tabs.size - 1)

        // Run pre-connection handler in coroutine
        tab.coroutineScope.launch(Dispatchers.IO) {
            try {
                // Create questioner that updates tab's connection state
                val questioner = ComposeQuestioner { newState ->
                    tab.connectionState.value = newState
                }

                // Get configuration from user (may prompt for input)
                val config = preConnectHandler(questioner)

                if (config == null) {
                    // User cancelled - close tab
                    withContext(Dispatchers.Main) {
                        val tabIndex = tabs.indexOf(tab)
                        if (tabIndex != -1) {
                            closeTab(tabIndex)
                        }
                    }
                    return@launch
                }

                // Update working directory from config
                if (config.workingDir != null) {
                    workingDirectoryState.value = config.workingDir
                }

                // Initialize terminal session with collected config
                initializeTerminalSessionWithConfig(tab, config)

            } catch (e: Exception) {
                tab.connectionState.value = ConnectionState.Error(
                    message = "Pre-connection setup failed: ${e.message ?: "Unknown error"}",
                    cause = e
                )
            }
        }

        return tab
    }

    /**
     * Initialize terminal session with pre-collected configuration.
     */
    private suspend fun initializeTerminalSessionWithConfig(
        tab: TerminalTab,
        config: PreConnectConfig
    ) {
        try {
            val services = getPlatformServices()

            // Ensure shell is started as login shell to get proper PATH from /etc/zprofile
            // This is critical for GUI apps that don't inherit terminal environment
            val effectiveArguments = if (config.arguments.isEmpty() &&
                (config.command.endsWith("/zsh") || config.command.endsWith("/bash") ||
                 config.command == "zsh" || config.command == "bash")) {
                listOf("-l")  // Login shell flag
            } else {
                config.arguments
            }

            // Set TERM environment variables for TUI compatibility
            val terminalEnvironment = buildMap {
                putAll(filterEnvironmentVariables(System.getenv()))
                put("TERM", "xterm-256color")
                put("COLORTERM", "truecolor")
                put("TERM_PROGRAM", "BossTerm")
                put("TERM_FEATURES", "T2:M:H:Ts0:Ts1:Ts2:Sc0:Sc1:Sc2:B:U:Aw")
                // Set PWD to match actual working directory (required for Starship and other prompts)
                put("PWD", config.workingDir ?: System.getProperty("user.home"))
                putAll(config.environment)
            }.toMutableMap()

            // Inject shell integration for command completion notifications (OSC 133)
            ShellIntegrationInjector.injectForShell(
                shell = config.command,
                env = terminalEnvironment,
                enabled = settings.autoInjectShellIntegration
            )

            val processConfig = PlatformServices.ProcessService.ProcessConfig(
                command = config.command,
                arguments = effectiveArguments,
                environment = terminalEnvironment,
                workingDirectory = config.workingDir ?: System.getProperty("user.home")
            )

            val handle = services.getProcessService().spawnProcess(processConfig)

            if (handle == null) {
                tab.connectionState.value = ConnectionState.Error(
                    message = "Failed to spawn process",
                    cause = null
                )
                return
            }

            tab.processHandle.value = handle
            tab.connectionState.value = ConnectionState.Connected(handle)

            // Connect terminal output to PTY
            tab.terminal.setTerminalOutput(ProcessTerminalOutput(handle, tab))

            // Configure type-ahead if enabled
            if (settings.typeAheadEnabled) {
                val typeAheadModel = ComposeTypeAheadModel(
                    terminal = tab.terminal,
                    textBuffer = tab.textBuffer,
                    display = tab.display,
                    settings = settings
                ).also { model ->
                    val shellType = TypeAheadTerminalModel.commandLineToShellType(
                        (listOf(config.command) + effectiveArguments).toMutableList()
                    )
                    model.setShellType(shellType)
                }

                val typeAheadManager = TerminalTypeAheadManager(typeAheadModel).also { manager ->
                    val debouncer = CoroutineDebouncer(
                        action = manager::debounce,
                        delayNanos = TerminalTypeAheadManager.MAX_TERMINAL_DELAY,
                        scope = tab.coroutineScope
                    )
                    manager.setClearPredictionsDebouncer(debouncer)
                }

                tab.dataStream.onTerminalStateChanged = {
                    typeAheadManager.onTerminalStateChanged()
                }
            }

            // Wire up chunk batching to prevent intermediate state flickering
            tab.dataStream.onChunkStart = {
                tab.textBuffer.beginBatch()
            }
            tab.dataStream.onChunkEnd = {
                tab.textBuffer.endBatch()
            }

            // Start emulator processing coroutine
            tab.coroutineScope.launch(Dispatchers.Default) {
                try {
                    while (handle.isAlive()) {
                        try {
                            tab.emulator.processChar(tab.dataStream.char, tab.terminal)
                        } catch (_: EOFException) {
                            break
                        } catch (e: Exception) {
                            if (e !is ai.rever.bossterm.terminal.TerminalDataStream.EOF) {
                                println("WARNING: Error processing terminal output: ${e.message}")
                            }
                            break
                        }
                    }
                } finally {
                    tab.dataStream.close()
                }
            }

            // Read PTY output in background (uses shared helper to eliminate duplication)
            startPtyReaderCoroutine(tab.coroutineScope, tab, handle)

            // Start debug state capture coroutine if enabled
            tab.debugCollector?.let { collector ->
                tab.coroutineScope.launch(Dispatchers.IO) {
                    try {
                        while (handle.isAlive() && isActive) {
                            delay(settings.debugCaptureInterval)
                            collector.captureState()
                        }
                    } catch (e: Exception) {
                        println("DEBUG: State capture coroutine stopped: ${e.message}")
                    }
                }
            }

            // Monitor process exit
            handle.waitFor()
            println("INFO: Shell process exited for tab: ${tab.title.value}")

            // Call onProcessExit callback - this handles split pane closure
            // If callback exists, it handles the exit (e.g., closing just the pane in a split)
            val hasCallback = tab.onProcessExit != null
            withContext(Dispatchers.Main) {
                tab.onProcessExit?.invoke()
            }

            // Auto-close tab only if no callback is set
            // (tabs in splits have callbacks that handle pane closure)
            if (!hasCallback) {
                withContext(Dispatchers.Main) {
                    val tabIndex = tabs.indexOf(tab)
                    if (tabIndex != -1) {
                        closeTab(tabIndex)
                    }
                }
            }

        } catch (e: Exception) {
            tab.connectionState.value = ConnectionState.Error(
                message = "Terminal initialization failed: ${e.message ?: "Unknown error"}",
                cause = e
            )
            println("ERROR: Terminal initialization failed for tab ${tab.title.value}: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Initialize a terminal session for a tab.
     * This spawns the PTY process and starts background coroutines for:
     * - Emulator processing (reads from dataStream, processes escape sequences)
     * - PTY output reading (reads from process, feeds dataStream)
     * - Process exit monitoring (auto-closes tab when shell exits)
     *
     * @param tab The tab to initialize
     * @param workingDir Working directory for the shell
     * @param command Shell command to execute
     * @param arguments Command-line arguments
     * @param initialCommand Optional command to execute after terminal is ready
     */
    private fun initializeTerminalSession(
        tab: TerminalTab,
        workingDir: String?,
        command: String,
        arguments: List<String>,
        initialCommand: String? = null,
        onInitialCommandComplete: ((success: Boolean, exitCode: Int) -> Unit)? = null
    ) {
        tab.coroutineScope.launch(Dispatchers.IO) {
            try {
                val services = getPlatformServices()

                // Set TERM environment variables for TUI compatibility
                val terminalEnvironment = buildMap {
                    putAll(filterEnvironmentVariables(System.getenv()))
                    put("TERM", "xterm-256color")
                    put("COLORTERM", "truecolor")
                    put("TERM_PROGRAM", "BossTerm")
                    put("TERM_FEATURES", "T2:M:H:Ts0:Ts1:Ts2:Sc0:Sc1:Sc2:B:U:Aw")
                    // Set PWD to match actual working directory (required for Starship and other prompts)
                    put("PWD", workingDir ?: System.getProperty("user.home"))
                }.toMutableMap()

                // Inject shell integration for command completion notifications (OSC 133)
                ShellIntegrationInjector.injectForShell(
                    shell = command,
                    env = terminalEnvironment,
                    enabled = settings.autoInjectShellIntegration
                )

                val config = PlatformServices.ProcessService.ProcessConfig(
                    command = command,
                    arguments = arguments,
                    environment = terminalEnvironment,
                    workingDirectory = workingDir ?: System.getProperty("user.home")
                )

                val handle = services.getProcessService().spawnProcess(config)

                if (handle == null) {
                    tab.connectionState.value = ConnectionState.Error(
                        message = "Failed to spawn process",
                        cause = null
                    )
                    return@launch
                }

                tab.processHandle.value = handle
                tab.connectionState.value = ConnectionState.Connected(handle)

                // Connect terminal output to PTY for bidirectional communication
                tab.terminal.setTerminalOutput(ProcessTerminalOutput(handle, tab))

                // Start emulator processing coroutine
                // Note: Initial prompt will display via ModelListener → requestImmediateRedraw()
                // when buffer content changes. No need for premature redraw here.
                launch(Dispatchers.Default) {
                    try {
                        while (handle.isAlive()) {
                            try {
                                tab.emulator.processChar(tab.dataStream.char, tab.terminal)
                                // Note: Redraws are triggered by scrollArea() when buffer changes
                                // No need for explicit requestRedraw() here - it causes redundant requests
                                // scrollArea() now uses smart priority (IMMEDIATE for interactive, debounced for bulk)
                            } catch (_: EOFException) {
                                break
                            } catch (e: Exception) {
                                if (e !is ai.rever.bossterm.terminal.TerminalDataStream.EOF) {
                                    println("WARNING: Error processing terminal output: ${e.message}")
                                }
                                break
                            }
                        }
                    } finally {
                        tab.dataStream.close()
                    }
                }

                // Read PTY output in background (uses shared helper to eliminate duplication)
                startPtyReaderCoroutine(this, tab, handle)

                // Start debug state capture coroutine if enabled
                tab.debugCollector?.let { collector ->
                    launch(Dispatchers.IO) {
                        try {
                            while (handle.isAlive() && isActive) {
                                delay(settings.debugCaptureInterval)
                                collector.captureState()
                            }
                        } catch (e: Exception) {
                            println("DEBUG: State capture coroutine stopped: ${e.message}")
                        }
                    }
                }

                // Send initial command if provided (after terminal is ready)
                // Uses OSC 133;A (prompt started) signal for proper synchronization,
                // with configurable fallback delay for shells without OSC 133 support
                if (initialCommand != null) {
                    launch(Dispatchers.IO) {
                        // Create a deferred that will be completed when first prompt appears
                        val promptReady = CompletableDeferred<Unit>()

                        // Add a temporary listener to detect OSC 133;A (prompt started)
                        val promptListener = object : CommandStateListener {
                            override fun onPromptStarted() {
                                promptReady.complete(Unit)
                            }
                        }
                        tab.terminal.addCommandStateListener(promptListener)

                        try {
                            // Wait for either OSC 133;A signal OR fallback timeout
                            val result = withTimeoutOrNull(settings.initialCommandDelayMs.toLong()) {
                                promptReady.await()
                            }

                            if (result != null) {
                                // OSC 133;A received - shell is ready
                                // Small delay to ensure prompt is fully rendered
                                delay(50)
                            }
                            // If result is null, timeout occurred - proceed with fallback delay
                            // (already waited initialCommandDelayMs)

                            // Register one-shot listener BEFORE sending command
                            // (must be registered before command executes to catch fast commands)
                            // Important: Track B->D sequence to avoid false positives from shell startup
                            if (onInitialCommandComplete != null) {
                                val completionListener = object : CommandStateListener {
                                    @Volatile
                                    private var commandStarted = false

                                    override fun onCommandStarted() {
                                        // Only count the first B after we send the command
                                        if (!commandStarted) {
                                            commandStarted = true
                                        }
                                    }

                                    override fun onCommandFinished(exitCode: Int) {
                                        // Only fire callback if we saw a B first (command actually started)
                                        if (!commandStarted) {
                                            return
                                        }
                                        try {
                                            // Fire callback once with success status and exit code
                                            onInitialCommandComplete(exitCode == 0, exitCode)
                                        } finally {
                                            // Always unregister, even if callback throws
                                            tab.terminal.removeCommandStateListener(this)
                                        }
                                    }
                                }
                                tab.terminal.addCommandStateListener(completionListener)
                            }

                            // Send the command followed by newline
                            handle.write(initialCommand + "\n")
                        } finally {
                            // Clean up the temporary listener
                            tab.terminal.removeCommandStateListener(promptListener)
                        }
                    }
                }

                // Monitor process exit
                handle.waitFor()  // Blocks until process exits
                println("INFO: Shell process exited for tab: ${tab.title.value}")

                // Call onProcessExit callback - this handles split pane closure
                // If callback exists, it handles the exit (e.g., closing just the pane in a split)
                val hasCallback = tab.onProcessExit != null
                withContext(Dispatchers.Main) {
                    tab.onProcessExit?.invoke()
                }

                // Auto-close tab only if no callback is set
                // (tabs in splits have callbacks that handle pane closure)
                if (!hasCallback) {
                    withContext(Dispatchers.Main) {
                        val tabIndex = tabs.indexOf(tab)
                        if (tabIndex != -1) {
                            closeTab(tabIndex)
                        }
                    }
                }

            } catch (e: Exception) {
                tab.connectionState.value = ConnectionState.Error(
                    message = "Terminal initialization failed: ${e.message ?: "Unknown error"}",
                    cause = e
                )
                println("ERROR: Terminal initialization failed for tab ${tab.title.value}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Helper function to start PTY reader coroutine.
     * Reads from PTY handle and appends to terminal data stream.
     *
     * CRITICAL: Must handle IOException gracefully to prevent silent death.
     * Extracted to eliminate code duplication between preConnect and initializeTerminalSession.
     *
     * @param scope The coroutine scope to launch the reader in
     * @param tab The terminal tab to read for
     * @param handle The PTY process handle to read from
     */
    private fun startPtyReaderCoroutine(
        scope: kotlinx.coroutines.CoroutineScope,
        tab: TerminalTab,
        handle: PlatformServices.ProcessService.ProcessHandle
    ) {
        scope.launch(Dispatchers.IO) {
            val maxChunkSize = 64 * 1024

            try {
                while (handle.isAlive()) {
                    try {
                        val output: String? = handle.read()
                        if (output != null) {
                            val processedOutput: String = if (output.length > maxChunkSize) {
                                // Find the last complete grapheme boundary before maxChunkSize
                                // to avoid splitting emoji, surrogate pairs, or ZWJ sequences
                                val safeBoundary = GraphemeBoundaryUtils.findLastCompleteGraphemeBoundary(output, maxChunkSize)

                                println("WARNING: Process output chunk (${output.length} chars) exceeds limit, " +
                                        "truncating at grapheme boundary (safe: $safeBoundary chars, " +
                                        "buffering ${output.length - safeBoundary} chars for next chunk)")

                                output.substring(0, safeBoundary)
                            } else {
                                output
                            }

                            tab.dataStream.append(processedOutput)
                        }
                    } catch (e: java.io.IOException) {
                        // PTY disconnected - expected during tab close or process exit
                        logTabError(tab, "INFO: PTY read ended: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                // Unexpected error - log but don't crash
                logTabError(tab, "ERROR: PTY reader crashed", e)
            } finally {
                // Use runCatching to make double-close safe (defensive programming)
                kotlin.runCatching { tab.dataStream.close() }
            }
        }
    }

    /**
     * Close a tab by index.
     * - Cancels all coroutines
     * - Terminates PTY process
     * - Removes from tabs list
     * - Switches to adjacent tab or closes application if last tab
     *
     * @param index Index of the tab to close
     */
    fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return

        val tab = tabs[index]

        // Invoke onTabClose callback BEFORE removal/disposal
        // This allows parent application to clean up associated resources
        try {
            onTabClose?.invoke(tab.id)
        } catch (e: Exception) {
            println("WARN: onTabClose callback threw exception: ${e.message}")
        }

        // Hold reference to process before tab disposal to prevent GC during kill()
        val processToKill = tab.processHandle.value

        // Capture debug collector reference BEFORE disposal for async logging
        // After dispose(), accessing tab.debugCollector is semantically incorrect
        val debugCollectorForLogging = tab.debugCollector

        // Clean up resources (cancels coroutines only, process kill handled below)
        tab.dispose()

        // Remove from list
        tabs.removeAt(index)

        // Notify listeners about session closure (after removal so tab count is accurate)
        notifySessionClosed(tab)

        // Kill process asynchronously with guaranteed reference and timeout
        // This prevents theoretical GC issue where tab might be GC'd before kill() completes
        // Uses cleanupScope to ensure proper lifecycle management
        if (processToKill != null) {
            cleanupScope.launch {
                try {
                    kotlinx.coroutines.withTimeout(5000) {  // 5 second timeout
                        processToKill.kill()
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    val message = "[${java.time.Instant.now()}] WARN: Process kill timed out after 5 seconds"
                    System.err.println(message)
                    debugCollectorForLogging?.recordChunk(message, ChunkSource.CONSOLE_LOG)
                } catch (e: Exception) {
                    val message = "[${java.time.Instant.now()}] WARN: Error killing process: ${e.message}"
                    System.err.println(message)
                    debugCollectorForLogging?.recordChunk(message, ChunkSource.CONSOLE_LOG)
                }
            }
        }

        // Handle tab switching
        if (tabs.isEmpty()) {
            // Notify listeners that all sessions are closed
            notifyAllSessionsClosed()
            // Last tab closed - exit application (legacy callback)
            onLastTabClosed()
        } else {
            // Adjust active tab index
            if (activeTabIndex >= tabs.size) {
                // Active tab was the last one, move to new last tab
                switchToTab(tabs.size - 1)
            } else if (activeTabIndex > index) {
                // Active tab is after the closed tab, decrement index
                activeTabIndex--
            } else if (activeTabIndex == index) {
                // Closed the active tab, switch to the same index (which is now the next tab)
                switchToTab(minOf(index, tabs.size - 1))
            }
        }
    }

    /**
     * Extract a tab from this controller without disposing it.
     * Used for transferring tabs between windows.
     *
     * Unlike closeTab(), this does NOT:
     * - Dispose the tab's resources
     * - Kill the PTY process
     * - Notify session listeners
     *
     * The extracted tab can be added to another TabController via createTabFromExistingSession().
     *
     * @param index Index of the tab to extract
     * @return The extracted tab, or null if index is invalid
     */
    fun extractTab(index: Int): TerminalTab? {
        if (index < 0 || index >= tabs.size) return null

        val tab = tabs[index]

        // Remove from list without disposing
        tabs.removeAt(index)

        // Handle tab switching (same logic as closeTab)
        if (tabs.isEmpty()) {
            // Last tab extracted - notify exit
            notifyAllSessionsClosed()
            onLastTabClosed()
        } else {
            // Adjust active tab index
            if (activeTabIndex >= tabs.size) {
                switchToTab(tabs.size - 1)
            } else if (activeTabIndex > index) {
                activeTabIndex--
            } else if (activeTabIndex == index) {
                switchToTab(minOf(index, tabs.size - 1))
            }
        }

        return tab
    }

    /**
     * Dispose all tabs and cleanup resources.
     * Call this when the window is being closed to prevent memory leaks.
     */
    fun disposeAll() {
        // Collect all processes before disposal to prevent GC issues
        val processesToKill = tabs.mapNotNull { it.processHandle.value }

        // Dispose all tabs (cancels coroutines)
        tabs.forEach { tab ->
            tab.dispose()
            notifySessionClosed(tab)
        }

        // Clear the list
        tabs.clear()

        // Kill all processes asynchronously with timeout
        // Uses cleanupScope to ensure proper lifecycle management
        if (processesToKill.isNotEmpty()) {
            cleanupScope.launch {
                processesToKill.forEach { process ->
                    try {
                        kotlinx.coroutines.withTimeout(5000) {  // 5 second timeout per process
                            process.kill()
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        System.err.println("WARN: Process kill timed out after 5 seconds")
                    } catch (e: Exception) {
                        System.err.println("WARN: Error killing process: ${e.message}")
                    }
                }
            }
        }

        // Cancel cleanup scope to abort any pending process kills on full disposal
        cleanupScope.cancel()

        // Notify listeners
        notifyAllSessionsClosed()
    }

    /**
     * Switch to a specific tab by index.
     *
     * @param index Index of the tab to switch to (0-based)
     */
    fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size || index == activeTabIndex) return

        // Hide previous tab
        activeTab?.onHidden()

        // Switch active index
        activeTabIndex = index

        // Show new tab
        activeTab?.onVisible()
    }

    /**
     * Switch to the next tab (wraps around to first tab).
     */
    fun nextTab() {
        if (tabs.isEmpty()) return
        switchToTab((activeTabIndex + 1) % tabs.size)
    }

    /**
     * Switch to the previous tab (wraps around to last tab).
     */
    fun previousTab() {
        if (tabs.isEmpty()) return
        switchToTab((activeTabIndex - 1 + tabs.size) % tabs.size)
    }

    /**
     * Get the working directory of the currently active tab.
     * Returns null if no working directory is tracked (OSC 7 not received yet).
     */
    fun getActiveWorkingDirectory(): String? {
        val tab = activeTab ?: return null
        // First, try OSC 7 tracked working directory (most accurate, shell-reported)
        tab.workingDirectory.value?.let { return it }
        // Fallback: query the process's current working directory directly
        // This works even without shell OSC 7 integration
        return tab.processHandle.value?.getWorkingDirectory()
    }

    /**
     * Create a new tab from an existing terminal session.
     *
     * This is used when moving a split pane to a new tab. The session's PTY and
     * terminal state are preserved - only the container changes from split pane to tab.
     *
     * Unlike createTab(), this method:
     * - Does NOT spawn a new PTY process
     * - Does NOT create new terminal components
     * - Reuses all existing state from the session
     *
     * @param session The existing session to promote to a tab
     * @return The tab index where the session was added
     */
    fun createTabFromExistingSession(session: TerminalSession): Int {
        tabCounter++

        // Update the session title to reflect it's now a standalone tab
        val existingTitle = session.title.value
        if (existingTitle == "Split" || existingTitle.isEmpty()) {
            session.title.value = "Shell $tabCounter"
        }

        // Cast to TerminalTab (our TerminalSession implementation)
        val tab = session as TerminalTab

        // Add to tabs list
        tabs.add(tab)

        // Notify listeners about session being added as a tab
        notifySessionCreated(tab)

        // Switch to newly created tab
        val newIndex = tabs.size - 1
        switchToTab(newIndex)

        return newIndex
    }

    /**
     * Replace a tab at the given index with a different session.
     *
     * This is used when extracting the original tab from a split - the remaining
     * session needs to take the original tab's position in the list.
     *
     * Note: The returned old tab is NOT disposed. In the typical use case,
     * the old tab is being moved to a new position (via createTabFromExistingSession),
     * not deleted. Caller is responsible for managing the returned tab's lifecycle.
     *
     * @param index The tab index to replace
     * @param newSession The session to put in that position
     * @return The old tab that was replaced (NOT disposed), or null if index is invalid
     */
    fun replaceTabAtIndex(index: Int, newSession: TerminalSession): TerminalTab? {
        if (index !in tabs.indices) return null

        val oldTab = tabs[index]
        val newTab = newSession as TerminalTab

        // Update the session title if needed
        val existingTitle = newTab.title.value
        if (existingTitle == "Split" || existingTitle.isEmpty()) {
            tabCounter++
            newTab.title.value = "Shell $tabCounter"
        }

        // Replace in the list
        tabs[index] = newTab

        // Notify listeners
        notifySessionCreated(newTab)

        return oldTab
    }

    /**
     * Filter environment variables to remove potentially problematic ones.
     * (e.g., parent terminal's TERM variables that shouldn't be inherited,
     * and PWD/OLDPWD which would reflect the parent's directory instead of
     * the requested working directory)
     */
    private fun filterEnvironmentVariables(env: Map<String, String>): Map<String, String> {
        return env.filterKeys { key ->
            !key.startsWith("ITERM_") &&
            !key.startsWith("KITTY_") &&
            key != "TERM_SESSION_ID" &&
            key != "PWD" &&
            key != "OLDPWD"
        }
    }

    /**
     * Routes terminal responses back to the PTY process.
     * Also records emulator-generated output in debug mode.
     */
    private class ProcessTerminalOutput(
        private val processHandle: PlatformServices.ProcessService.ProcessHandle,
        private val tab: TerminalTab
    ) : ai.rever.bossterm.terminal.TerminalOutputStream {
        override fun sendBytes(response: ByteArray, userInput: Boolean) {
            // Record emulator-generated responses in debug mode
            if (!userInput) {
                tab.debugCollector?.recordChunk(
                    String(response, Charsets.UTF_8),
                    ai.rever.bossterm.compose.debug.ChunkSource.EMULATOR_GENERATED
                )
            }

            kotlinx.coroutines.runBlocking {
                processHandle.write(String(response, Charsets.UTF_8))
            }
        }

        override fun sendString(string: String, userInput: Boolean) {
            // Record emulator-generated responses in debug mode
            if (!userInput) {
                tab.debugCollector?.recordChunk(
                    string,
                    ai.rever.bossterm.compose.debug.ChunkSource.EMULATOR_GENERATED
                )
            }

            kotlinx.coroutines.runBlocking {
                processHandle.write(string)
            }
        }
    }
}
