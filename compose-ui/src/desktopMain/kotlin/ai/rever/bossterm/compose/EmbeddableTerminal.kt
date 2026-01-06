package ai.rever.bossterm.compose

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.PerformanceMode
import ai.rever.bossterm.compose.ui.ProperTerminal
import ai.rever.bossterm.compose.util.loadTerminalFont
import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.ime.IMEState
import ai.rever.bossterm.compose.settings.SettingsLoader
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.settings.withOverrides
import ai.rever.bossterm.compose.hyperlinks.HyperlinkDetector
import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkRegistry
import ai.rever.bossterm.compose.tabs.ShellIntegrationInjector
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.CommandStateListener
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer

/**
 * Base interface for context menu elements.
 */
sealed interface ContextMenuElement

/**
 * A clickable context menu item.
 *
 * @param id Unique identifier for the menu item
 * @param label Display text for the menu item
 * @param enabled Whether the item is clickable (default: true)
 * @param action Callback invoked when the item is clicked
 */
data class ContextMenuItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val action: () -> Unit
) : ContextMenuElement

/**
 * A section separator in the context menu.
 * Use this to visually group related menu items.
 *
 * @param id Unique identifier for the section
 * @param label Optional label displayed above the separator (section header)
 */
data class ContextMenuSection(
    val id: String,
    val label: String? = null
) : ContextMenuElement

/**
 * A submenu containing nested menu items.
 *
 * @param id Unique identifier for the submenu
 * @param label Display text for the submenu
 * @param items Nested menu elements (can include items, sections, or more submenus)
 */
data class ContextMenuSubmenu(
    val id: String,
    val label: String,
    val items: List<ContextMenuElement>
) : ContextMenuElement

/**
 * Simplified Terminal composable for external integration.
 *
 * This provides a clean API for embedding a terminal in any Compose Desktop application,
 * abstracting away the complexity of session management, settings, and process lifecycle.
 *
 * Basic usage:
 * ```kotlin
 * EmbeddableTerminal()  // Uses default settings from ~/.bossterm/settings.json
 * ```
 *
 * Custom settings path:
 * ```kotlin
 * EmbeddableTerminal(settingsPath = "/path/to/my-settings.json")
 * ```
 *
 * Custom font (via settings):
 * ```kotlin
 * EmbeddableTerminal(settings = TerminalSettings(fontName = "JetBrains Mono"))
 * ```
 *
 * With callbacks:
 * ```kotlin
 * EmbeddableTerminal(
 *     onOutput = { output -> println("Output: $output") },
 *     onTitleChange = { title -> window.title = title },
 *     onExit = { code -> println("Shell exited: $code") },
 *     onReady = { println("Terminal ready!") }
 * )
 * ```
 *
 * Programmatic control with session preservation:
 * ```kotlin
 * // Session survives when EmbeddableTerminal leaves composition
 * val state = rememberEmbeddableTerminalState(autoDispose = false)
 *
 * if (showTerminal) {
 *     EmbeddableTerminal(state = state)
 * }
 * // Terminal process keeps running even when hidden!
 *
 * // Don't forget to dispose when truly done:
 * DisposableEffect(Unit) {
 *     onDispose { state.dispose() }
 * }
 * ```
 *
 * @param state Optional EmbeddableTerminalState for programmatic control and session preservation
 * @param settingsPath Path to custom settings JSON file. If null, uses ~/.bossterm/settings.json
 * @param settings Direct TerminalSettings object. Overrides settingsPath if provided.
 * @param command Shell command to run. Defaults to $SHELL or /bin/zsh
 * @param workingDirectory Initial working directory. Defaults to user home
 * @param environment Additional environment variables to set
 * @param initialCommand Optional command to execute after terminal is ready. Uses OSC 133 shell
 *                       integration for proper timing if available, with fallback delay.
 * @param onInitialCommandComplete Callback invoked when initialCommand finishes executing.
 *                                  Requires OSC 133 shell integration to detect command completion.
 *                                  Parameters: success (true if exit code is 0), exitCode (command exit code).
 * @param onOutput Callback invoked when terminal produces output
 * @param onTitleChange Callback invoked when terminal title changes (OSC 0/1/2)
 * @param onExit Callback invoked when shell process exits with exit code
 * @param onReady Callback invoked when terminal is ready (process started)
 * @param contextMenuItems Custom context menu elements (items, sections, submenus) to add after the default items
 * @param onContextMenuOpen Callback invoked right before the context menu is displayed (sync).
 *                          Use case: refresh dynamic menu item state (e.g., check AI assistant installation status).
 * @param onContextMenuOpenAsync Async callback invoked right before the context menu is displayed.
 *                               Menu display is delayed until this callback completes.
 *                               Use case: async refresh of dynamic menu item state before menu shows.
 * @param onLinkClick Optional callback for custom link handling. When provided, intercepts Ctrl/Cmd+Click
 *                    on links and context menu "Open Link" action. Receives [HyperlinkInfo] with rich metadata:
 *                    type (HTTP, FILE, FOLDER, EMAIL, FTP, CUSTOM), isFile/isFolder validation, scheme, patternId.
 *                    Return true if handled, false to proceed with default behavior (open in browser/finder).
 * @param settingsOverride Per-instance settings overrides. Non-null fields override resolved settings.
 *                         Applied after resolving settings from settings/settingsPath/default.
 *                         Example: `TerminalSettingsOverride(fontSize = 16f)` to use larger font.
 * @param hyperlinkRegistry Custom hyperlink pattern registry for per-instance hyperlink customization.
 *                          Use this to add custom patterns (e.g., JIRA ticket IDs, custom URLs).
 *                          Default: global HyperlinkDetector.registry
 * @param modifier Compose modifier for the terminal container
 */
@Composable
fun EmbeddableTerminal(
    state: EmbeddableTerminalState? = null,
    settingsPath: String? = null,
    settings: TerminalSettings? = null,
    command: String? = null,
    workingDirectory: String? = null,
    environment: Map<String, String>? = null,
    initialCommand: String? = null,
    onInitialCommandComplete: ((success: Boolean, exitCode: Int) -> Unit)? = null,
    onOutput: ((String) -> Unit)? = null,
    onTitleChange: ((String) -> Unit)? = null,
    onExit: ((Int) -> Unit)? = null,
    onReady: (() -> Unit)? = null,
    onNewWindow: (() -> Unit)? = null,
    contextMenuItems: List<ContextMenuElement> = emptyList(),
    onContextMenuOpen: (() -> Unit)? = null,
    onContextMenuOpenAsync: (suspend () -> Unit)? = null,
    onLinkClick: ((HyperlinkInfo) -> Boolean)? = null,
    settingsOverride: TerminalSettingsOverride? = null,
    hyperlinkRegistry: HyperlinkRegistry = HyperlinkDetector.registry,
    modifier: Modifier = Modifier
) {
    // Use provided state or create auto-disposing one
    val effectiveState = state ?: rememberEmbeddableTerminalState(autoDispose = true)

    // Resolve settings: direct > path > default, then apply overrides
    val resolvedSettings = remember(settings, settingsPath, settingsOverride) {
        SettingsLoader.resolveSettings(settings, settingsPath).withOverrides(settingsOverride)
    }

    // Effective shell command
    val effectiveCommand = command ?: System.getenv("SHELL") ?: "/bin/zsh"

    // Load font from settings.fontName or use default bundled font
    val terminalFont = remember(resolvedSettings.fontName) {
        loadTerminalFont(resolvedSettings.fontName)
    }

    // Initialize session if not already done (session lives in state, not composable)
    LaunchedEffect(effectiveState, resolvedSettings, effectiveCommand) {
        if (effectiveState.session == null) {
            effectiveState.initializeSession(
                settings = resolvedSettings,
                command = effectiveCommand,
                workingDirectory = workingDirectory,
                environment = environment,
                initialCommand = initialCommand,
                onInitialCommandComplete = onInitialCommandComplete,
                onOutput = onOutput,
                onExit = onExit
            )
        }
    }

    // Get current session (may be null during initialization)
    val session = effectiveState.session

    // Wire up title change callback
    LaunchedEffect(session, onTitleChange) {
        if (session != null && onTitleChange != null) {
            session.display.windowTitleFlow.collectLatest { title ->
                if (title.isNotEmpty()) {
                    onTitleChange(title)
                }
            }
        }
    }

    // Fire onReady when connected
    val connectionState = session?.connectionState?.value
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected && onReady != null) {
            onReady()
        }
    }

    // Render terminal if session exists
    if (session != null) {
        ProperTerminal(
            tab = session,
            isActiveTab = true,
            sharedFont = terminalFont,
            onTabTitleChange = { onTitleChange?.invoke(it) },
            onNewWindow = onNewWindow,
            enableDebugPanel = false,  // Hide debug panel in embedded mode
            customContextMenuItems = contextMenuItems,
            onContextMenuOpen = onContextMenuOpen,
            onContextMenuOpenAsync = onContextMenuOpenAsync,
            onLinkClick = onLinkClick,
            hyperlinkRegistry = hyperlinkRegistry,
            modifier = modifier
        )
    }
}

/**
 * State holder for controlling an EmbeddableTerminal programmatically.
 *
 * The session lifecycle is owned by this state, not by the EmbeddableTerminal composable.
 * This means the terminal process survives when EmbeddableTerminal leaves the composition tree.
 *
 * Usage patterns:
 *
 * 1. Auto-dispose (default): Session disposed when state is forgotten
 * ```kotlin
 * val state = rememberEmbeddableTerminalState()  // autoDispose = true
 * EmbeddableTerminal(state = state)
 * ```
 *
 * 2. Manual lifecycle: Session preserved across navigation
 * ```kotlin
 * val state = rememberEmbeddableTerminalState(autoDispose = false)
 * // Must call state.dispose() when done!
 * ```
 *
 * 3. App-level state: Session outlives composition entirely
 * ```kotlin
 * // At app level (outside @Composable)
 * val terminalState = EmbeddableTerminalState()
 *
 * @Composable fun App() {
 *     EmbeddableTerminal(state = terminalState)
 *     DisposableEffect(Unit) { onDispose { terminalState.dispose() } }
 * }
 * ```
 */
class EmbeddableTerminalState {
    internal var session: TerminalTab? by mutableStateOf(null)
    private var initialized = false

    /**
     * Whether the terminal is connected to a shell process.
     */
    val isConnected: Boolean
        get() = session?.connectionState?.value is ConnectionState.Connected

    /**
     * Whether the terminal is currently initializing.
     */
    val isInitializing: Boolean
        get() = session?.connectionState?.value is ConnectionState.Initializing

    /**
     * Whether the session has been disposed.
     */
    val isDisposed: Boolean
        get() = session == null && initialized

    /**
     * Current scroll offset in lines from the bottom.
     */
    val scrollOffset: Int
        get() = session?.scrollOffset?.value ?: 0

    /**
     * Initialize the terminal session. Called automatically by EmbeddableTerminal.
     * Only initializes once; subsequent calls are no-ops.
     */
    internal fun initializeSession(
        settings: TerminalSettings,
        command: String,
        workingDirectory: String?,
        environment: Map<String, String>?,
        initialCommand: String?,
        onInitialCommandComplete: ((success: Boolean, exitCode: Int) -> Unit)?,
        onOutput: ((String) -> Unit)?,
        onExit: ((Int) -> Unit)?
    ) {
        if (initialized) return
        initialized = true

        // Create session
        session = createTerminalSession(settings, onOutput)

        // Start process in session's coroutine scope
        session?.coroutineScope?.launch {
            initializeProcess(
                session = session!!,
                settings = settings,
                command = command,
                workingDirectory = workingDirectory,
                environment = environment,
                initialCommand = initialCommand,
                onInitialCommandComplete = onInitialCommandComplete,
                onExit = onExit
            )
        }
    }

    /**
     * Dispose the terminal session and kill the process.
     * After disposal, this state can be reused by calling EmbeddableTerminal again.
     */
    fun dispose() {
        session?.dispose()
        session = null
        initialized = false
    }

    /**
     * Send text input to the terminal.
     * Use "\n" for enter key.
     *
     * @param text Text to send to the shell
     */
    fun write(text: String) {
        session?.writeUserInput(text)
    }

    /**
     * Send raw bytes to the terminal process.
     * Useful for sending control characters like Ctrl+C (0x03) or Ctrl+D (0x04).
     *
     * This method is asynchronous - it queues the bytes and returns immediately.
     * Bytes are sent in FIFO order with respect to [write] calls.
     *
     * Note: If the session is not yet initialized or has been disposed, this call is a no-op.
     *
     * @param bytes Raw bytes to send to the shell
     */
    fun sendInput(bytes: ByteArray) {
        session?.writeRawBytes(bytes)
    }

    /**
     * Send Ctrl+C (SIGINT) to the terminal process.
     * This is equivalent to pressing Ctrl+C in the terminal to interrupt a running process.
     *
     * This method is asynchronous - it queues the signal and returns immediately.
     *
     * Note: If the session is not yet initialized or has been disposed, this call is a no-op.
     */
    fun sendCtrlC() {
        sendInput(byteArrayOf(0x03))
    }

    /**
     * Send Ctrl+D (EOF) to the terminal process.
     * This is equivalent to pressing Ctrl+D in the terminal to signal end-of-input.
     *
     * This method is asynchronous - it queues the signal and returns immediately.
     *
     * Note: If the session is not yet initialized or has been disposed, this call is a no-op.
     */
    fun sendCtrlD() {
        sendInput(byteArrayOf(0x04))
    }

    /**
     * Send Ctrl+Z (SIGTSTP) to the terminal process.
     * This is equivalent to pressing Ctrl+Z in the terminal to suspend the foreground process.
     *
     * This method is asynchronous - it queues the signal and returns immediately.
     *
     * Note: If the session is not yet initialized or has been disposed, this call is a no-op.
     */
    fun sendCtrlZ() {
        sendInput(byteArrayOf(0x1A))
    }

    /**
     * Paste text to the terminal with proper bracketed paste mode handling.
     *
     * @param text Text to paste
     */
    fun paste(text: String) {
        session?.pasteText(text)
    }

    /**
     * Scroll to the bottom of the terminal (most recent output).
     */
    fun scrollToBottom() {
        session?.scrollOffset?.value = 0
    }

    /**
     * Scroll by a number of lines.
     *
     * @param lines Number of lines to scroll (positive = up into history, negative = down)
     */
    fun scrollBy(lines: Int) {
        session?.let { s ->
            val maxScroll = s.textBuffer.historyLinesCount
            val newOffset = (s.scrollOffset.value + lines).coerceIn(0, maxScroll)
            s.scrollOffset.value = newOffset
        }
    }

    /**
     * Clear the terminal screen.
     * Sends Ctrl+L (form feed) to trigger shell clear.
     */
    fun clear() {
        session?.writeUserInput("\u000C") // Ctrl+L
    }

    /**
     * Clear selection if any.
     */
    fun clearSelection() {
        session?.selectionTracker?.clearSelection()
    }

    /**
     * Toggle search bar visibility.
     */
    fun toggleSearch() {
        session?.let { s ->
            s.searchVisible.value = !s.searchVisible.value
        }
    }

    /**
     * Set search query and perform search.
     *
     * @param query Search query string
     */
    fun search(query: String) {
        session?.let { s ->
            s.searchQuery.value = query
            s.searchVisible.value = true
        }
    }
}

/**
 * Remember an EmbeddableTerminalState for controlling an EmbeddableTerminal composable.
 *
 * @param autoDispose If true (default), the session is disposed when this state is forgotten
 *                    (i.e., when the composable that called this leaves composition).
 *                    If false, you must manually call state.dispose() when done.
 * @return EmbeddableTerminalState instance that persists across recompositions
 */
@Composable
fun rememberEmbeddableTerminalState(autoDispose: Boolean = true): EmbeddableTerminalState {
    val state = remember { EmbeddableTerminalState() }

    if (autoDispose) {
        DisposableEffect(state) {
            onDispose { state.dispose() }
        }
    }

    return state
}

/**
 * Create a terminal session with all required components.
 */
private fun createTerminalSession(
    settings: TerminalSettings,
    onOutput: ((String) -> Unit)?
): TerminalTab {
    val styleState = StyleState()
    val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
    val display = ComposeTerminalDisplay()
    val terminal = BossTerminal(display, textBuffer, styleState)

    // Register buffer listener for redraws
    textBuffer.addModelListener(object : ai.rever.bossterm.terminal.model.TerminalModelListener {
        override fun modelChanged() {
            display.requestImmediateRedraw()
        }
    })

    // Configure encoding
    terminal.setCharacterEncoding(settings.characterEncoding)

    val dataStream = BlockingTerminalDataStream(
        performanceMode = PerformanceMode.fromString(settings.performanceMode)
    )

    // Hook output callback
    if (onOutput != null) {
        dataStream.debugCallback = { data ->
            onOutput(data)
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

    val emulator = BossEmulator(dataStream, terminal)
    val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    return TerminalTab(
        title = mutableStateOf("Terminal"),
        terminal = terminal,
        textBuffer = textBuffer,
        display = display,
        dataStream = dataStream,
        emulator = emulator,
        processHandle = mutableStateOf(null),
        workingDirectory = mutableStateOf(null),
        connectionState = mutableStateOf(ConnectionState.Initializing),
        coroutineScope = coroutineScope,
        isFocused = mutableStateOf(true),
        scrollOffset = mutableStateOf(0),
        searchVisible = mutableStateOf(false),
        searchQuery = mutableStateOf(""),
        searchMatches = mutableStateOf(emptyList()),
        currentSearchMatchIndex = mutableStateOf(-1),
        selectionClipboard = mutableStateOf(null),
        imeState = IMEState(),
        contextMenuController = ContextMenuController(),
        hyperlinks = mutableStateOf(emptyList()),
        hoveredHyperlink = mutableStateOf(null)
    )
}

/**
 * Initialize PTY process for the session.
 */
private suspend fun initializeProcess(
    session: TerminalTab,
    settings: TerminalSettings,
    command: String,
    workingDirectory: String?,
    environment: Map<String, String>?,
    initialCommand: String?,
    onInitialCommandComplete: ((success: Boolean, exitCode: Int) -> Unit)?,
    onExit: ((Int) -> Unit)?
) {
    try {
        val services = getPlatformServices()

        // Determine shell arguments (login shell)
        val args = if (command.endsWith("/zsh") || command.endsWith("/bash") ||
            command == "zsh" || command == "bash") {
            listOf("-l")
        } else {
            emptyList()
        }

        // Build environment (filter out PWD/OLDPWD to avoid inheriting stale values)
        val effectiveWorkingDir = workingDirectory ?: System.getProperty("user.home")
        val terminalEnvironment = mutableMapOf<String, String>().apply {
            putAll(System.getenv().filterKeys { it != "PWD" && it != "OLDPWD" })
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("TERM_PROGRAM", "BossTerm")
            // Set PWD to match actual working directory (required for Starship and other prompts)
            put("PWD", effectiveWorkingDir)
            environment?.let { putAll(it) }
        }

        // Inject shell integration for command completion notifications (OSC 133)
        ShellIntegrationInjector.injectForShell(
            shell = command,
            env = terminalEnvironment,
            enabled = settings.autoInjectShellIntegration
        )

        // Create process config
        val processConfig = PlatformServices.ProcessService.ProcessConfig(
            command = command,
            arguments = args,
            environment = terminalEnvironment,
            workingDirectory = effectiveWorkingDir
        )

        // Spawn PTY process
        val processHandle = services.getProcessService().spawnProcess(processConfig)

        if (processHandle == null) {
            session.connectionState.value = ConnectionState.Error("Failed to spawn process")
            return
        }

        session.processHandle.value = processHandle
        session.connectionState.value = ConnectionState.Connected(processHandle)

        // Start emulator coroutine
        session.coroutineScope.launch(Dispatchers.Default) {
            try {
                while (processHandle.isAlive()) {
                    try {
                        session.emulator.processChar(session.dataStream.char, session.terminal)
                    } catch (e: java.io.EOFException) {
                        break
                    } catch (e: Exception) {
                        if (e !is ai.rever.bossterm.terminal.TerminalDataStream.EOF) {
                            println("WARNING: Error processing terminal output: ${e.message}")
                        }
                        break
                    }
                }
            } finally {
                session.dataStream.close()
            }
        }

        // Start output reader coroutine
        session.coroutineScope.launch(Dispatchers.IO) {
            while (processHandle.isAlive()) {
                val output = processHandle.read()
                if (output != null) {
                    session.dataStream.append(output)
                }
            }
            session.dataStream.close()
        }

        // Send initial command if provided (after terminal is ready)
        // Uses OSC 133;A (prompt started) signal for proper synchronization,
        // with configurable fallback delay for shells without OSC 133 support
        if (initialCommand != null) {
            session.coroutineScope.launch(Dispatchers.IO) {
                // Create a deferred that will be completed when first prompt appears
                val promptReady = CompletableDeferred<Unit>()

                // Add a temporary listener to detect OSC 133;A (prompt started)
                val promptListener = object : CommandStateListener {
                    override fun onPromptStarted() {
                        promptReady.complete(Unit)
                    }
                }
                session.terminal.addCommandStateListener(promptListener)

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
                    if (onInitialCommandComplete != null) {
                        val completionListener = object : CommandStateListener {
                            override fun onCommandFinished(exitCode: Int) {
                                try {
                                    println("DEBUG: Initial command completed with exit code: $exitCode")
                                    // Fire callback once with success status and exit code
                                    onInitialCommandComplete(exitCode == 0, exitCode)
                                } finally {
                                    // Always unregister, even if callback throws
                                    session.terminal.removeCommandStateListener(this)
                                }
                            }
                        }
                        session.terminal.addCommandStateListener(completionListener)
                    }

                    // Send the command followed by newline
                    processHandle.write(initialCommand + "\n")
                } finally {
                    // Clean up the temporary listener
                    session.terminal.removeCommandStateListener(promptListener)
                }
            }
        }

        // Monitor process exit
        session.coroutineScope.launch(Dispatchers.IO) {
            val exitCode = processHandle.waitFor()
            session.connectionState.value = ConnectionState.Error("Process exited with code $exitCode")
            onExit?.invoke(exitCode)
        }

    } catch (e: Exception) {
        session.connectionState.value = ConnectionState.Error(e.message ?: "Failed to start process")
    }
}
