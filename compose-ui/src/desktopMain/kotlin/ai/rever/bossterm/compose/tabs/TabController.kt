package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.session.TerminalSessionStack
import ai.rever.bossterm.compose.session.resolveSessionCommand
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import ai.rever.bossterm.compose.vcs.GitUtils
import ai.rever.bossterm.compose.ComposeQuestioner
import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.compose.notificationTitle
import ai.rever.bossterm.compose.localSessionEnvironment
import ai.rever.bossterm.compose.ConnectionState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.TerminalSessionDispatcher
import ai.rever.bossterm.compose.TerminalSessionSlots
import ai.rever.bossterm.compose.debug.ChunkSource
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.PerformanceMode
import ai.rever.bossterm.compose.terminal.drainTerminalEmulator
import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.ime.IMEState
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.typeahead.ComposeTypeAheadModel
import ai.rever.bossterm.compose.typeahead.CoroutineDebouncer
import ai.rever.bossterm.compose.notification.CommandNotificationHandler
import ai.rever.bossterm.compose.clipboard.ClipboardHandler
import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.core.typeahead.TerminalTypeAheadManager
import ai.rever.bossterm.core.typeahead.TypeAheadTerminalModel
import java.util.concurrent.atomic.AtomicReference

/**
 * Return the full index permutation for moving a tab only among [movableIndices].
 * Indices outside that subset retain both their item and their position.
 */
internal fun tabOrderAfterMoveWithin(
    tabCount: Int,
    fromIndex: Int,
    toIndex: Int,
    movableIndices: List<Int>
): List<Int>? {
    if (tabCount <= 0 || fromIndex == toIndex) return null

    // TabbedTerminal derives the visible local groups by filtering a mapIndexed list, so
    // visual order is the ascending order of their full-list slots.
    val slots = movableIndices.distinct().sorted()
    if (slots.any { it !in 0 until tabCount }) return null

    val fromSlot = slots.indexOf(fromIndex)
    val toSlot = slots.indexOf(toIndex)
    if (fromSlot == -1 || toSlot == -1) return null

    val reorderedItems = slots.toMutableList()
    val movedItem = reorderedItems.removeAt(fromSlot)
    reorderedItems.add(toSlot, movedItem)

    return (0 until tabCount).toMutableList().also { order ->
        slots.forEachIndexed { slotIndex, fullIndex ->
            order[fullIndex] = reorderedItems[slotIndex]
        }
    }
}

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
    private val onTabClose: ((tabId: String) -> Unit)? = null,
    private val platformServices: PlatformServices = getPlatformServices(),
    private val parentScope: CoroutineScope? = null
) {
    /**
     * List of all terminal tabs (observable, triggers recomposition).
     */
    val tabs: SnapshotStateList<TerminalTab> = mutableStateListOf()

    private var lastAssignedTabColor: String? = null
    internal var randomNewTabColor: Boolean = settings.randomNewTabColor

    private fun assignNewTabColor(tab: TerminalTab) {
        if (!randomNewTabColor || tab.tabColor.value != null) return
        val previousColor = tabs.lastOrNull()?.tabColor?.value
        val color = TAB_COLOR_PRESETS.map { it.second }
            .filter { it != previousColor && it != lastAssignedTabColor }
            .random()
        tab.tabColor.value = color
        lastAssignedTabColor = color
    }

    /**
     * Index of the currently active tab (0-based).
     */
    var activeTabIndex by mutableStateOf(0)
        private set

    /**
     * Handler for CLI-originated open requests (OSC 1341;OpenTarget from the
     * shell-integration open/xdg-open/$BROWSER shim). Wired by TabbedTerminal
     * to the same handler used for Ctrl/Cmd+click links, so embedding hosts
     * route both paths identically. Registered at session creation (not in the
     * composition) so requests from background tabs are still handled. When
     * null or returning false, the target opens with the system default.
     * Invoked on the main thread, like the Ctrl/Cmd+click path.
     */
    var openTargetLinkHandler: ((ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo) -> Boolean)? = null

    /**
     * Derive a tab title from a working directory (Warp-style): home → "~",
     * otherwise the directory's base name. Null/blank → "~".
     */
    private fun cwdLabel(path: String?): String {
        if (path.isNullOrBlank()) return "~"
        val clean = path.trimEnd('/')
        if (clean.isEmpty()) return "/"
        val home = System.getProperty("user.home")?.trimEnd('/')
        if (clean == home) return "~"
        return clean.substringAfterLast('/').ifEmpty { "/" }
    }

    /**
     * Keep a session's title in sync with its working directory, which updates
     * live via OSC 7 from the shell integration. Mirrors Warp, where the tab
     * title is the current directory rather than a static "Shell N".
     */
    private fun wireCwdTitle(session: TerminalTab) {
        // Live updates when the directory changes (cd) or the user clears a rename.
        // A non-null customTitle (set via Rename…) always wins; clearing it (null)
        // reverts the tab to tracking the directory immediately.
        session.coroutineScope.launch {
            snapshotFlow { session.customTitle.value to session.workingDirectory.value }
                .collect { (custom, cwd) -> session.title.value = custom ?: cwdLabel(cwd) }
        }
        // On each fresh prompt (OSC 133;A) re-assert the title, so a full-screen
        // app's OSC title (e.g. "claude") reverts to the directory (or the custom
        // title) on exit even when the working directory itself didn't change.
        val titleResetListener = object : ai.rever.bossterm.terminal.model.CommandStateListener {
            override fun onPromptStarted() {
                session.title.value = session.customTitle.value ?: cwdLabel(session.workingDirectory.value)

                // Clear the OSC 2 window title too, so a program that set one stops naming
                // the window after it exits; resolveWindowTitle then falls back to this same
                // tab title. See AGENTS.md, "OSC 1 names the TAB, OSC 2 names the WINDOW" for
                // why the split is kept and why the reset lives on THIS hook rather than on
                // command start. Short version: our integration is sourced from .zshenv so its
                // hooks run first, and a shell's own precmd OSC 2 lands just after this and
                // survives; clearing at command start instead left the slot empty for the whole
                // duration of every command, which fed "BossTerm" to the completion notification.
                //
                // display, not terminal.setWindowTitle: that publishes to every application-title
                // listener as though the program had set an empty title, including
                // EmbeddableTerminal's public onTitleChange.
                session.display.windowTitle = ""
            }
        }
        session.terminal.addCommandStateListener(titleResetListener)
        session.commandStateListeners.add(titleResetListener)

        // Mirror an app's OSC 0/1 icon title (e.g. "claude") onto the tab name so the
        // LEFT TAB BAR reflects title changes even for BACKGROUND (unfocused) tabs.
        // ProperTerminal also collects this, but only for the active tab's mounted
        // Composable; this runs for the session's whole life regardless of focus.
        // customTitle (Rename…) always wins and is re-asserted by the snapshotFlow above.
        // Deliberately NOT the OSC 2 window title as well: xterm's split, which this
        // codebase follows, is that OSC 1 names the TAB and OSC 2 names the WINDOW, and
        // apps set them to different strings. oh-my-zsh is the case that bites - precmd
        // emits a short OSC 1 ("~/src") and a long OSC 2 ("me@host: ~/src") back to back,
        // so folding both in here would make the tab label whichever arrived last. The
        // window title picks up OSC 2 at its own consumer in TabbedTerminal instead.
        session.coroutineScope.launch {
            session.display.iconTitleFlow.collect { newTitle ->
                if (newTitle.isNotEmpty() && session.customTitle.value == null) {
                    session.title.value = newTitle
                }
            }
        }

        // Track repository membership and the git branch of the working directory for
        // repository-only actions and the left tab-bar's third chip line. Debounced +
        // collectLatest so a rapid `cd` cancels the stale lookup.
        session.coroutineScope.launch {
            snapshotFlow { session.workingDirectory.value }
                .distinctUntilChanged()
                .collectLatest { cwd ->
                    session.isGitRepo.value = null
                    delay(350)
                    val repository = withContext(Dispatchers.IO) {
                        GitUtils.getRepositoryState(cwd)
                    }
                    session.gitBranch.value = repository.branch
                    session.isGitRepo.value = repository.isRepository
                }
        }
    }

    /**
     * Registered session lifecycle listeners.
     * Thread-safe: uses CopyOnWriteArrayList for safe iteration during modification.
     */
    private val sessionListeners = java.util.concurrent.CopyOnWriteArrayList<TerminalSessionListener>()

    /**
     * Raised when a new session is refused because [TerminalSessionSlots] is exhausted
     * (every TerminalSessionDispatcher thread is pinned by a live session). The hosting
     * UI shows a dialog asking the user to close some terminals; the affected pane
     * itself is put into [ConnectionState.Error] with the same message.
     */
    private val _showSessionCapacityDialog = MutableStateFlow(false)
    val showSessionCapacityDialog: StateFlow<Boolean> get() = _showSessionCapacityDialog

    fun dismissSessionCapacityDialog() {
        _showSessionCapacityDialog.value = false
    }

    /**
     * Launch the long-lived coroutine that owns a shell session (spawn → reader/emulator
     * loops → waitFor). Reserves the session's thread budget from [TerminalSessionSlots]
     * first: when the budget is exhausted the session is NOT started — the tab goes to an
     * error state and [showSessionCapacityDialog] is raised — because launching anyway
     * would queue on a starved dispatcher and silently never spawn a shell.
     */
    private fun launchSessionCoroutine(tab: TerminalTab, block: suspend CoroutineScope.() -> Unit) {
        // Reserve synchronously on the caller's thread, BEFORE dispatching: at exact
        // saturation every TerminalSessionDispatcher thread is parked in a live loop,
        // so a coroutine dispatched just to check-and-report would itself queue
        // forever — the silent hang this accounting exists to prevent.
        if (!TerminalSessionSlots.tryReserve()) {
            tab.connectionState.value = ConnectionState.Error(TerminalSessionSlots.EXHAUSTED_MESSAGE)
            _showSessionCapacityDialog.value = true
            return
        }
        // invokeOnCompletion rather than try/finally: it also fires when the scope is
        // cancelled before the coroutine ever starts, so the reservation can't leak.
        // A Job completes only after all its CHILDREN complete — and the session's
        // reader/emulator loops are launched as children inside block() — so this
        // release fires only after every loop has actually unwound, keeping the
        // accounting from running ahead of the physically occupied permits.
        tab.coroutineScope.launch(TerminalSessionDispatcher) {
            block()
        }.invokeOnCompletion {
            TerminalSessionSlots.release()
        }
    }

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
     * Close every tab except the one at [keepIndex] (Warp's "Close Other Tabs").
     * Captures the kept tab's stable ID first, then closes the rest one at a time
     * via [closeTab] so all the usual cleanup (process kill, listener removal,
     * active-index adjustment) runs for each.
     *
     * @param keepIndex Index of the tab to keep open
     */
    fun closeOtherTabs(keepIndex: Int) {
        val keepId = tabs.getOrNull(keepIndex)?.id ?: return
        // Snapshot the ids to close so index shifts during closeTab don't matter.
        val idsToClose = tabs.filter { it.id != keepId }.map { it.id }
        idsToClose.forEach { id -> closeTabById(id) }
    }

    /**
     * Close every tab positioned after [index] (Warp's "Close Tabs Below" on the
     * vertical bar). Closes high→low so each [closeTab] call's index stays valid.
     *
     * @param index Tabs with a higher index than this are closed
     */
    fun closeTabsBelow(index: Int) {
        if (index < 0) return
        for (i in tabs.size - 1 downTo index + 1) {
            closeTab(i)
        }
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
     * @param initialCommand Optional command to execute after terminal is ready (submitted via
     *                       `submitLine`, so a trailing newline is optional)
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
        tabId: String? = null,
        activate: Boolean = true
    ): TerminalTab {
        // Validate tab ID uniqueness if custom ID provided
        if (tabId != null && tabs.any { it.id == tabId }) {
            throw IllegalArgumentException(
                "Tab ID '$tabId' already exists. Each tab must have a unique ID."
            )
        }

        // On macOS, optionally use 'login -fp $USER' to properly register the session
        // This shows "Last login" message and registers in utmp/wtmp like iTerm2
        val (effectiveCommand, effectiveArguments) = resolveSessionCommand(settings, workingDir, command, arguments)

        // Initialize terminal components
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
        val display = ComposeTerminalDisplay(settings)
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


        // Route CLI-originated open requests (OSC 1341;OpenTarget) through the
        // same handler as Ctrl/Cmd+click links; system default when unhandled.
        terminal.addCustomCommandListener(
            ai.rever.bossterm.compose.osc.OpenTargetOSCListener(handlerProvider = { openTargetLinkHandler })
        )

        // Register window title listener for reactive updates (OSC 0/1/2 sequences)

        // Register command state listener for notifications (OSC 133 shell integration).
        // Also captured in `tab.commandStateListeners` after construction so dispose()
        // can remove it (see TerminalTab.commandStateListeners docs).
        val notificationTitleProvider = NotificationTitleProvider(display, "BossTerm")
        val notificationHandler = CommandNotificationHandler(
            settings = settings,
            isWindowFocused = isWindowFocused,
            tabTitle = notificationTitleProvider,
        )
        terminal.addCommandStateListener(notificationHandler)

        // Register clipboard listener (OSC 52)
        val clipboardHandler = ClipboardHandler(settings)
        terminal.addClipboardListener(clipboardHandler)

        // Create emulator with terminal
        val sessionStack = TerminalSessionStack.create(settings, display, textBuffer, terminal, dataStream)
        val emulator = sessionStack.emulator

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
        val tabCoroutineScope = CoroutineScope(SupervisorJob(parentScope?.coroutineContext?.get(Job)) + Dispatchers.Default)

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
            title = mutableStateOf(cwdLabel(workingDir)),
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

        tab.sessionStack = sessionStack

        // Register MCP last-command tracker (OSC 133). Additive — does not
        // affect the notification handler registered earlier. Both listeners
        // are recorded on `tab.commandStateListeners` so dispose() can remove
        // them when the tab closes.
        val lastCommandTracker = ai.rever.bossterm.compose.mcp.LastCommandTracker(tab)
        terminal.addCommandStateListener(lastCommandTracker)
        notificationTitleProvider.attach(tab)
        tab.commandStateListeners.add(notificationHandler)
        tab.commandStateListeners.add(lastCommandTracker)

        // Register command-block tracker (OSC 133) + command-line capture
        // (OSC 1341;BossTermCmd). Additive and capture-only; nothing renders
        // unless `commandBlocksEnabled` is on.
        val commandBlockTracker = ai.rever.bossterm.compose.blocks.CommandBlockTracker(tab)
        terminal.addCommandStateListener(commandBlockTracker)
        tab.commandStateListeners.add(commandBlockTracker)
        tab.commandBlockTracker = commandBlockTracker
        terminal.addCustomCommandListener(
            ai.rever.bossterm.compose.osc.CommandLineOSCListener { cmd ->
                commandBlockTracker.pendingCommandText = cmd
            }
        )

        // Prevent-sleep during long commands (Phase 7). Inert unless enabled.
        val preventSleepListener = ai.rever.bossterm.compose.power.PreventSleepListener(tab.coroutineScope)
        terminal.addCommandStateListener(preventSleepListener)
        tab.commandStateListeners.add(preventSleepListener)

        // Keep the tab title in sync with the working directory (Warp-style).
        wireCwdTitle(tab)

        // Complete debug collector initialization
        debugCollector?.let { collector ->
            // Set the tab reference now that tab is created
            collector.setTab(tab)
            collector.startFileLoggingIfRequested(tab.id)

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

        // Assign an accent before publishing the tab to the UI.
        assignNewTabColor(tab)
        tabs.add(tab)

        // Notify listeners about new session
        notifySessionCreated(tab)

        // Switch to newly created tab (unless the caller wants it created in the background —
        // e.g. a remote viewer creating a tab shouldn't yank the host user's active tab).
        if (activate) switchToTab(tabs.size - 1)

        return tab
    }

    /**
     * Build a **remote mirror** session (no local PTY): a full terminal stack
     * (BossTerminal/textBuffer/display/dataStream/emulator) whose bytes are fed from a remote
     * BossTerm share via [TerminalTab.dataStream] `.append(...)` and whose size is set by the
     * host (`PaneResize` → `terminal.resize`). Used by the native remote-session client to
     * mirror each remote pane. Does NOT spawn a process or add itself to [tabs] — the caller
     * places it (as a tab or a split pane) and feeds it. [onUserInput] routes local keystrokes
     * back to the host (sent as `ClientMessage.Input`); dispose by closing [TerminalTab.dataStream]
     * then [TerminalTab.dispose].
     */
    fun createRemoteSession(
        title: String,
        remotePaneId: String? = null,
        onUserInput: ((String) -> Unit)? = null,
        feedsStream: Boolean = true,
    ): TerminalTab {
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
        val display = ComposeTerminalDisplay(settings)
        val terminal = BossTerminal(display, textBuffer, styleState)
        terminal.setCharacterEncoding(settings.characterEncoding)
        val modelListener = object : ai.rever.bossterm.terminal.model.TerminalModelListener {
            override fun modelChanged() { display.requestRedraw() }
        }
        textBuffer.addModelListener(modelListener)
        val dataStream = BlockingTerminalDataStream(
            performanceMode = PerformanceMode.fromString(settings.performanceMode)
        )
        // Batch each appended chunk so clear+write sequences request one redraw.
        dataStream.onChunkStart = { textBuffer.beginBatch() }
        dataStream.onChunkEnd = { textBuffer.endBatch() }
        val emulator = BossEmulator(dataStream, terminal, settings.allowKittyFileTransfers)
        val scope = CoroutineScope(SupervisorJob(parentScope?.coroutineContext?.get(Job)) + Dispatchers.Default)

        val tab = TerminalTab(
            id = java.util.UUID.randomUUID().toString(),
            title = mutableStateOf(title),
            terminal = terminal,
            textBuffer = textBuffer,
            display = display,
            dataStream = dataStream,
            emulator = emulator,
            processHandle = mutableStateOf(null),
            workingDirectory = mutableStateOf(null),
            connectionState = mutableStateOf(ConnectionState.Initializing),
            coroutineScope = scope,
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
            modelListener = modelListener,
        ).apply {
            isRemote = true
            this.remotePaneId = remotePaneId
            this.onUserInput = onUserInput
        }

        // Mouse reports (wheel scrolling in mouse-tracking TUIs like claude/vim/htop) are
        // emitted by the terminal through its TerminalOutputStream — a PTY-less mirror had
        // none, so they were silently dropped (keyboard goes via writeUserInput instead).
        // Forward USER-initiated output through the same remote-input hook as keystrokes;
        // drop automatic protocol replies (DSR/DA/CPR) — the ORIGIN's terminal already
        // answers those, and a mirror echoing them too would double the app's input.
        if (onUserInput != null) {
            terminal.setTerminalOutput(object : ai.rever.bossterm.terminal.TerminalOutputStream {
                override fun sendBytes(response: ByteArray, userInput: Boolean) {
                    if (userInput) tab.writeUserInput(String(response, Charsets.UTF_8))
                }
                override fun sendString(string: String, userInput: Boolean) {
                    if (userInput) tab.writeUserInput(string)
                }
            })
        }

        // Emulator-processing loop: drain bytes appended from the remote stream. Exits when the
        // stream is closed (dispose) — closing dataStream unblocks the blocking `char` read.
        // Skipped for a container tab ([feedsStream] = false): it owns no remote pane of its own
        // (its panes are separate mirror sessions in the split tree), so it needs no parked thread.
        if (feedsStream) {
            // Mirror drain loop pins one thread — reserve it synchronously (never on
            // the view itself; see launchSessionCoroutine) so exhaustion is reported
            // instead of the loop queueing forever.
            if (!TerminalSessionSlots.tryReserve(1)) {
                tab.connectionState.value = ConnectionState.Error(TerminalSessionSlots.EXHAUSTED_MESSAGE)
                _showSessionCapacityDialog.value = true
                // No drain loop will ever run — close the stream so writers don't feed
                // a queue nobody reads. (The loop's finally does this on the normal path.)
                runCatching { tab.dataStream.close() }
            } else {
                tab.remoteParserJob = scope.launch(TerminalSessionDispatcher) {
                    drainTerminalEmulator(
                        emulator = tab.emulator,
                        dataStream = tab.dataStream,
                        terminal = tab.terminal,
                        shouldContinue = { isActive },
                    )
                }.also { parser ->
                    parser.invokeOnCompletion { TerminalSessionSlots.release(1) }
                }
            }
        }
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
        tabId: String? = null,
        initialCommand: String? = null
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
        val (effectiveCommand, effectiveArguments) = resolveSessionCommand(settings, workingDir, command, arguments)

        // Initialize terminal components (same as createTab)
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
        val display = ComposeTerminalDisplay(settings)
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


        // Route CLI-originated open requests (OSC 1341;OpenTarget) through the
        // same handler as Ctrl/Cmd+click links; system default when unhandled.
        terminal.addCustomCommandListener(
            ai.rever.bossterm.compose.osc.OpenTargetOSCListener(handlerProvider = { openTargetLinkHandler })
        )

        // Register window title listener for reactive updates (OSC 0/1/2 sequences)

        // Register command state listener for notifications (OSC 133 shell integration)
        val notificationTitleProvider = NotificationTitleProvider(display, sessionTitle)
        val notificationHandler = CommandNotificationHandler(
            settings = settings,
            isWindowFocused = isWindowFocused,
            tabTitle = notificationTitleProvider,
        )
        terminal.addCommandStateListener(notificationHandler)

        // Register clipboard listener (OSC 52)
        val clipboardHandler = ClipboardHandler(settings)
        terminal.addClipboardListener(clipboardHandler)

        // Create emulator with terminal
        val sessionStack = TerminalSessionStack.create(settings, display, textBuffer, terminal, dataStream)
        val emulator = sessionStack.emulator

        // Always create debug collector (so it's available when user enables debug mode)
        val debugCollector = ai.rever.bossterm.compose.debug.DebugDataCollector(
            tab = null,  // Will be set after tab creation
            maxChunks = settings.debugMaxChunks,
            maxSnapshots = settings.debugMaxSnapshots
        )

        // Create type-ahead model and manager if enabled
        val tabCoroutineScope = CoroutineScope(SupervisorJob(parentScope?.coroutineContext?.get(Job)) + Dispatchers.Default)

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
            title = mutableStateOf(cwdLabel(workingDir)),
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

        session.sessionStack = sessionStack

        // Register MCP last-command tracker (OSC 133). Additive — does not
        // affect the notification handler registered earlier. Both listeners
        // are recorded on the session so dispose() can remove them when the
        // pane closes.
        val lastCommandTracker = ai.rever.bossterm.compose.mcp.LastCommandTracker(session)
        terminal.addCommandStateListener(lastCommandTracker)
        notificationTitleProvider.attach(session)
        session.commandStateListeners.add(notificationHandler)
        session.commandStateListeners.add(lastCommandTracker)

        // Register command-block tracker (OSC 133) + command-line capture
        // (OSC 1341;BossTermCmd). Additive and capture-only; nothing renders
        // unless `commandBlocksEnabled` is on.
        val commandBlockTracker = ai.rever.bossterm.compose.blocks.CommandBlockTracker(session)
        terminal.addCommandStateListener(commandBlockTracker)
        session.commandStateListeners.add(commandBlockTracker)
        session.commandBlockTracker = commandBlockTracker
        terminal.addCustomCommandListener(
            ai.rever.bossterm.compose.osc.CommandLineOSCListener { cmd ->
                commandBlockTracker.pendingCommandText = cmd
            }
        )

        // Prevent-sleep during long commands (Phase 7). Inert unless enabled.
        val preventSleepListener = ai.rever.bossterm.compose.power.PreventSleepListener(session.coroutineScope)
        terminal.addCommandStateListener(preventSleepListener)
        session.commandStateListeners.add(preventSleepListener)

        // Keep the tab title in sync with the working directory (Warp-style).
        wireCwdTitle(session)

        // Complete debug collector initialization
        debugCollector?.let { collector ->
            collector.setTab(session)
            collector.startFileLoggingIfRequested(session.id)
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

        // Initialize the terminal session (spawn PTY, start coroutines).
        // Note: onProcessExit is wired on the TerminalTab itself (line 630), not passed here.
        // initialCommand is held until OSC 133;A (or the fallback delay) so the shell is
        // ready before bytes go down the PTY — same contract as createTab.
        initializeTerminalSession(session, workingDir, effectiveCommand, effectiveArguments, initialCommand)

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
        // Initialize terminal components (same as createTab)
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
        val display = ComposeTerminalDisplay(settings)
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


        // Route CLI-originated open requests (OSC 1341;OpenTarget) through the
        // same handler as Ctrl/Cmd+click links; system default when unhandled.
        terminal.addCustomCommandListener(
            ai.rever.bossterm.compose.osc.OpenTargetOSCListener(handlerProvider = { openTargetLinkHandler })
        )


        // Register command state listener for notifications (OSC 133 shell integration)
        val notificationTitleProvider = NotificationTitleProvider(display, "BossTerm")
        val notificationHandler = CommandNotificationHandler(
            settings = settings,
            isWindowFocused = isWindowFocused,
            tabTitle = notificationTitleProvider,
        )
        terminal.addCommandStateListener(notificationHandler)

        // Register clipboard listener (OSC 52)
        val clipboardHandler = ClipboardHandler(settings)
        terminal.addClipboardListener(clipboardHandler)

        val sessionStack = TerminalSessionStack.create(settings, display, textBuffer, terminal, dataStream)
        val emulator = sessionStack.emulator

        // Always create debug collector (so it's available when user enables debug mode in settings)
        val debugCollector = ai.rever.bossterm.compose.debug.DebugDataCollector(
            tab = null,
            maxChunks = settings.debugMaxChunks,
            maxSnapshots = settings.debugMaxSnapshots
        )

        val tabCoroutineScope = CoroutineScope(SupervisorJob(parentScope?.coroutineContext?.get(Job)) + Dispatchers.Default)

        // Create tab with Initializing state
        val tab = TerminalTab(
            id = java.util.UUID.randomUUID().toString(),
            title = mutableStateOf(cwdLabel(null)),
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

        tab.sessionStack = sessionStack

        // Register MCP last-command tracker (OSC 133). Additive — does not
        // affect the notification handler registered earlier. Both listeners
        // are recorded on the tab so dispose() can remove them.
        val lastCommandTracker = ai.rever.bossterm.compose.mcp.LastCommandTracker(tab)
        terminal.addCommandStateListener(lastCommandTracker)
        notificationTitleProvider.attach(tab)
        tab.commandStateListeners.add(notificationHandler)
        tab.commandStateListeners.add(lastCommandTracker)

        // Register command-block tracker (OSC 133) + command-line capture
        // (OSC 1341;BossTermCmd). Additive and capture-only; nothing renders
        // unless `commandBlocksEnabled` is on.
        val commandBlockTracker = ai.rever.bossterm.compose.blocks.CommandBlockTracker(tab)
        terminal.addCommandStateListener(commandBlockTracker)
        tab.commandStateListeners.add(commandBlockTracker)
        tab.commandBlockTracker = commandBlockTracker
        terminal.addCustomCommandListener(
            ai.rever.bossterm.compose.osc.CommandLineOSCListener { cmd ->
                commandBlockTracker.pendingCommandText = cmd
            }
        )

        // Prevent-sleep during long commands (Phase 7). Inert unless enabled.
        val preventSleepListener = ai.rever.bossterm.compose.power.PreventSleepListener(tab.coroutineScope)
        terminal.addCommandStateListener(preventSleepListener)
        tab.commandStateListeners.add(preventSleepListener)

        // Keep the tab title in sync with the working directory (Warp-style).
        wireCwdTitle(tab)

        debugCollector?.let { collector ->
            collector.setTab(tab)
            collector.startFileLoggingIfRequested(tab.id)
            dataStream.debugCallback = { data ->
                collector.recordChunk(data, ChunkSource.PTY_OUTPUT)
            }
            // Hook into display for console log capture (errors, warnings)
            display.debugLogCallback = { message ->
                collector.recordChunk(message, ChunkSource.CONSOLE_LOG)
            }
        }

        // Assign an accent before publishing the tab to the UI.
        assignNewTabColor(tab)
        tabs.add(tab)

        // Notify listeners about new session
        notifySessionCreated(tab)

        switchToTab(tabs.size - 1)

        // Run pre-connection handler in coroutine. On TerminalSessionDispatcher:
        // this coroutine ends in handle.waitFor() and so lives as long as the shell.
        launchSessionCoroutine(tab) {
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
                    return@launchSessionCoroutine
                }

                // Update working directory from config
                if (config.workingDir != null) {
                    workingDirectoryState.value = config.workingDir
                }

                // Initialize the shared runtime and retain this pre-connect reservation until
                // its reader/emulator loops have actually unwound.
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
        val engine = startSessionEngine(tab, config.workingDir, config.command, config.arguments,
            environmentOverrides = config.environment, reserveThreads = false)
        // Pre-connect already reserved two threads. Hold that reservation until the shared
        // engine's blocking loops actually unwind, including during cancellation.
        try {
            engine.awaitTermination()
        } finally {
            withContext(NonCancellable) {
                engine.close()
                engine.awaitTermination()
            }
        }
    }

    private fun initializeTerminalSession(
        tab: TerminalTab,
        workingDir: String?,
        command: String,
        arguments: List<String>,
        initialCommand: String? = null,
        onInitialCommandComplete: ((success: Boolean, exitCode: Int) -> Unit)? = null
    ) {
        startSessionEngine(tab, workingDir, command, arguments, initialCommand, onInitialCommandComplete)
    }

    /** UI adaptation only: the shared engine owns spawning, FIFO writes, initial commands,
     * chunking, EOF draining, resize forwarding, and process lifetime. */
    private fun startSessionEngine(
        tab: TerminalTab,
        workingDir: String?,
        command: String,
        arguments: List<String>,
        initialCommand: String? = null,
        onInitialCommandComplete: ((Boolean, Int) -> Unit)? = null,
        environmentOverrides: Map<String, String> = emptyMap(),
        reserveThreads: Boolean = true,
    ): TerminalSessionEngine {
        val stack = checkNotNull(tab.sessionStack) { "Local terminal stack was not initialized" }
        lateinit var engine: TerminalSessionEngine
        engine = TerminalSessionEngine(
            id = tab.id,
            settings = settings,
            workingDir = workingDir,
            command = command,
            arguments = arguments,
            stack = stack,
            platformServices = platformServices,
            initialCommand = initialCommand,
            environmentOverrides = localSessionEnvironment(settings, environmentOverrides),
            reserveThreads = reserveThreads,
            parentScope = tab.coroutineScope,
            onInitialCommandComplete = onInitialCommandComplete,
            onStateChanged = { state ->
                when (state) {
                    is TerminalSessionEngine.State.Error -> {
                        tab.connectionState.value = ConnectionState.Error(state.message, state.cause)
                        if (state.message == TerminalSessionSlots.EXHAUSTED_MESSAGE) _showSessionCapacityDialog.value = true
                    }
                    else -> Unit
                }
            },
            onConnected = { handle, resolvedCommand, resolvedArguments ->
                val proxy = engine.processHandleAdapter(handle)
                tab.processHandle.value = proxy
                tab.connectionState.value = ConnectionState.Connected(proxy)
                tab.terminal.setTerminalOutput(ProcessTerminalOutput(engine, tab))
                if (settings.typeAheadEnabled) {
                    val model = tab.typeAheadModel ?: ComposeTypeAheadModel(tab.terminal, tab.textBuffer, tab.display, settings)
                    model.setShellType(TypeAheadTerminalModel.commandLineToShellType((listOf(resolvedCommand) + resolvedArguments).toMutableList()))
                    val manager = tab.typeAheadManager ?: TerminalTypeAheadManager(model).also {
                        it.setClearPredictionsDebouncer(CoroutineDebouncer(it::debounce,
                            TerminalTypeAheadManager.MAX_TERMINAL_DELAY, tab.coroutineScope))
                    }
                    tab.typeAheadModel = model
                    tab.typeAheadManager = manager
                    tab.dataStream.onTerminalStateChanged = manager::onTerminalStateChanged
                }
                tab.dataStream.onChunkStart = tab.textBuffer::beginBatch
                tab.dataStream.onChunkEnd = tab.textBuffer::endBatch
                tab.debugCollector?.let { collector ->
                    tab.coroutineScope.launch(Dispatchers.IO) {
                        while (isActive && handle.isAlive()) {
                            delay(settings.debugCaptureInterval)
                            collector.captureState()
                        }
                    }
                }
            },
            onProcessExit = {
                tab.coroutineScope.launch(Dispatchers.Main) {
                    val callback = tab.onProcessExit
                    if (callback != null) callback()
                    else tabs.indexOfFirst { it === tab }.takeIf { it >= 0 }?.let { closeTab(it) }
                }
            },
        )
        tab.attachEngine(engine)
        tab.coroutineScope.launch {
            engine.workingDirectory.collect { tab.workingDirectory.value = it }
        }
        engine.start()
        return engine
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

        // Cancellation closes the stream and kills the owned process.
        tab.dispose()

        // Remove from list
        tabs.removeAt(index)

        // Notify listeners about session closure (after removal so tab count is accurate)
        notifySessionClosed(tab)

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
        tabs.forEach { tab ->
            tab.dispose()
            notifySessionClosed(tab)
        }

        // Clear the list
        tabs.clear()

        // Notify listeners
        notifyAllSessionsClosed()
    }

    /**
     * Move a tab among a subset of full-list indices while leaving all other slots untouched.
     * Used by the sidebar so local tabs can reorder without shifting mirrored remote tabs.
     */
    fun moveTabWithinIndices(
        fromIndex: Int,
        toIndex: Int,
        movableIndices: List<Int>
    ) {
        val newOrder = tabOrderAfterMoveWithin(
            tabCount = tabs.size,
            fromIndex = fromIndex,
            toIndex = toIndex,
            movableIndices = movableIndices
        ) ?: return

        val previousTabs = tabs.toList()
        val newActiveTabIndex = newOrder.indexOf(activeTabIndex)
        // Keep the SnapshotStateList instance stable and write only changed local slots;
        // non-movable remote slots retain their entries without emitting snapshot writes.
        newOrder.forEachIndexed { newIndex, previousIndex ->
            if (newIndex != previousIndex) {
                tabs[newIndex] = previousTabs[previousIndex]
            }
        }
        activeTabIndex = newActiveTabIndex
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
        // Title tracks the working directory (the session's cwd observer keeps it updated).
        val existingTitle = session.title.value
        if (existingTitle == "Split" || existingTitle.isEmpty()) {
            session.title.value = cwdLabel(session.workingDirectory.value)
        }

        // Cast to TerminalTab (our TerminalSession implementation)
        val tab = session as TerminalTab

        // Assign an accent before publishing the tab to the UI.
        assignNewTabColor(tab)
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
            newTab.title.value = cwdLabel(newTab.workingDirectory.value)
        }

        // Replace in the list
        tabs[index] = newTab

        // Notify listeners
        notifySessionCreated(newTab)

        return oldTab
    }

    /**
     * Routes terminal responses back to the PTY process.
     * Also records emulator-generated output in debug mode.
     */
    private class ProcessTerminalOutput(
        private val engine: TerminalSessionEngine,
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

            engine.writeBytes(response)
        }

        override fun sendString(string: String, userInput: Boolean) {
            // Record emulator-generated responses in debug mode
            if (!userInput) {
                tab.debugCollector?.recordChunk(
                    string,
                    ai.rever.bossterm.compose.debug.ChunkSource.EMULATOR_GENERATED
                )
            }

            engine.writeInput(string)
        }
    }
}

/**
 * The title a [CommandNotificationHandler] announces, resolved the same way as the window title so
 * the two agree on which session finished.
 *
 * Exists as a class rather than three lambdas because the tab does not exist yet when the handler
 * is constructed, and because that ordering is not the only guarantee needed: the slot is written
 * on the constructing thread and read on the terminal reader thread, where `onCommandFinished`
 * dispatches from. A captured `var` compiles to a non-volatile field with no happens-before edge
 * between the two, so the reader is not guaranteed to see the assignment at all. [AtomicReference]
 * gives that edge for nothing.
 */
internal class NotificationTitleProvider(
    private val display: ComposeTerminalDisplay,
    private val fallback: String,
) : () -> String {
    private val tab = AtomicReference<TerminalTab?>(null)

    /** Called once the tab exists; the lambda is not invoked before a command finishes. */
    fun attach(tab: TerminalTab) = this.tab.set(tab)

    override fun invoke(): String {
        val tab = tab.get()
        return notificationTitle(
            custom = tab?.customTitle?.value,
            osc2 = display.windowTitle.orEmpty(),
            // Through the tab, not display.iconTitle: nothing ever resets that slot, so it would
            // still say "vim" during the next long build.
            tabTitle = tab?.title?.value.orEmpty(),
            fallback = fallback,
        )
    }
}
