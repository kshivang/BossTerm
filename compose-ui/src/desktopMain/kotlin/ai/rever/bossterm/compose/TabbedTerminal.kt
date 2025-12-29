package ai.rever.bossterm.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import ai.rever.bossterm.compose.ContextMenuElement
import ai.rever.bossterm.compose.menu.MenuActions
import ai.rever.bossterm.compose.util.loadTerminalFont
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.settings.withOverrides
import ai.rever.bossterm.compose.splits.NavigationDirection
import ai.rever.bossterm.compose.splits.SplitContainer
import ai.rever.bossterm.compose.splits.SplitOrientation
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.window.WindowManager
import ai.rever.bossterm.compose.tabs.TabBar
import ai.rever.bossterm.compose.tabs.TabController
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.compose.ui.ProperTerminal

/**
 * Terminal composable with multi-tab support.
 *
 * This provides a complete tabbed terminal experience with:
 * - Multiple tabs per window
 * - Tab bar with close buttons
 * - Keyboard shortcuts for tab management
 * - Working directory inheritance for new tabs
 * - Command completion notifications (when window unfocused)
 *
 * Basic usage:
 * ```kotlin
 * TabbedTerminal(
 *     onExit = { exitApplication() }
 * )
 * ```
 *
 * With external state (survives recomposition):
 * ```kotlin
 * val state = rememberTabbedTerminalState(autoDispose = false)
 * TabbedTerminal(
 *     state = state,
 *     onExit = { exitApplication() }
 * )
 * ```
 *
 * With callbacks:
 * ```kotlin
 * TabbedTerminal(
 *     onExit = { exitApplication() },
 *     onWindowTitleChange = { title -> window.title = title },
 *     onNewWindow = { WindowManager.createWindow() }
 * )
 * ```
 *
 * @param state Optional external state holder. When provided, the terminal state survives
 *              recomposition (e.g., when embedded in a tab system). When null, state is
 *              managed internally and lost when composable unmounts.
 * @param onExit Called when the last tab is closed
 * @param onTabClose Called when a tab is about to close (before removal). Receives the stable tab ID.
 *                   Use this to clean up resources associated with specific tabs.
 * @param onWindowTitleChange Called when active tab's title changes (for window title bar)
 * @param onNewWindow Called when user requests a new window (Cmd/Ctrl+N)
 * @param menuActions Optional menu action callbacks for wiring up menu bar
 * @param isWindowFocused Lambda returning whether this window is currently focused (for notifications)
 * @param initialCommand Optional command to run in the first terminal tab after startup
 * @param workingDirectory Initial working directory for the first tab (defaults to user home)
 * @param onLinkClick Optional callback for custom link handling. When provided, intercepts Ctrl/Cmd+Click
 *                    on links and context menu "Open Link" action. When null, links open in system browser.
 * @param contextMenuItems Custom context menu items to add below the built-in items (Copy, Paste, Clear, Select All).
 *                         Applies to all tabs and split panes within the terminal.
 * @param settingsOverride Per-instance settings overrides. Non-null fields override global settings.
 *                         Example: `TerminalSettingsOverride(alwaysShowTabBar = true)` to always show tab bar.
 * @param modifier Compose modifier for the terminal container
 */
@Composable
fun TabbedTerminal(
    state: TabbedTerminalState? = null,
    onExit: () -> Unit,
    onTabClose: ((tabId: String) -> Unit)? = null,
    onWindowTitleChange: (String) -> Unit = {},
    onNewWindow: () -> Unit = {},
    onShowSettings: () -> Unit = {},
    menuActions: MenuActions? = null,
    isWindowFocused: () -> Boolean = { true },
    initialCommand: String? = null,
    workingDirectory: String? = null,
    onLinkClick: ((String) -> Unit)? = null,
    contextMenuItems: List<ContextMenuElement> = emptyList(),
    settingsOverride: TerminalSettingsOverride? = null,
    modifier: Modifier = Modifier
) {
    // Settings integration
    val settingsManager = remember { SettingsManager.instance }
    val globalSettings by settingsManager.settings.collectAsState()

    // Merge global settings with per-instance overrides
    val settings = remember(globalSettings, settingsOverride) {
        globalSettings.withOverrides(settingsOverride)
    }

    // Load font once and share across all tabs (supports custom font via settings)
    val sharedFont = remember(settings.fontName) {
        loadTerminalFont(settings.fontName)
    }

    // Initialize external state if provided (only once)
    if (state != null && !state.isInitialized) {
        state.initialize(
            settings = settings,
            onLastTabClosed = onExit,
            isWindowFocused = isWindowFocused,
            onTabClose = onTabClose
        )
    }

    // Use external state's tabController if provided, otherwise create internal one
    val tabController = state?.tabController ?: remember {
        TabController(
            settings = settings,
            onLastTabClosed = onExit,
            isWindowFocused = isWindowFocused,
            onTabClose = onTabClose
        )
    }

    // Track window focus state reactively for overlay
    val isWindowFocusedState by remember { derivedStateOf { isWindowFocused() } }

    // Track SplitViewState per tab (tab.id -> SplitViewState)
    // Use external state's splitStates if provided, otherwise create internal ones
    val splitStates = state?.splitStates ?: remember { mutableStateMapOf<String, SplitViewState>() }

    // Helper function to get or create SplitViewState for a tab
    fun getOrCreateSplitState(tab: TerminalTab): SplitViewState {
        return splitStates.getOrPut(tab.id) {
            val state = SplitViewState(initialSession = tab)

            // Set up split-aware process exit for the original tab
            // This ensures exiting the original pane closes just that pane,
            // not the entire tab (unless it's the last pane)
            tab.onProcessExit = {
                if (state.isSinglePane) {
                    // Last pane - close the tab
                    val tabIndex = tabController.tabs.indexOfFirst { it.id == tab.id }
                    if (tabIndex != -1) {
                        tabController.closeTab(tabIndex)
                    }
                } else {
                    // Close just this pane
                    state.getAllPanes()
                        .find { it.session === tab }
                        ?.let { pane -> state.closePane(pane.id) }
                }
            }

            state
        }
    }

    // Helper function to create a new session for splitting
    fun createSessionForSplit(splitState: SplitViewState, paneId: String): TerminalSession {
        val workingDir = splitState.getFocusedSession()?.workingDirectory?.value
        return tabController.createSessionForSplit(
            workingDir = workingDir,
            onProcessExit = {
                // Auto-close the pane when shell exits
                splitState.closePane(paneId)
            }
        )
    }

    // Wire up menu actions for tab management
    LaunchedEffect(menuActions, tabController) {
        menuActions?.apply {
            onNewTab = {
                // New tabs always start in home directory (no working dir inheritance)
                // Use initial command from settings if configured
                tabController.createTab(initialCommand = settings.initialCommand.ifEmpty { null })
            }
            onCloseTab = {
                tabController.closeTab(tabController.activeTabIndex)
            }
            onNextTab = {
                tabController.nextTab()
            }
            onPreviousTab = {
                tabController.previousTab()
            }
        }
    }

    // Wire up split menu actions (updates when active tab changes or tabs are added)
    LaunchedEffect(menuActions, tabController.activeTabIndex, tabController.tabs.size) {
        if (tabController.tabs.isEmpty()) return@LaunchedEffect
        val activeTab = tabController.tabs.getOrNull(tabController.activeTabIndex) ?: return@LaunchedEffect
        val splitState = getOrCreateSplitState(activeTab)

        menuActions?.apply {
            onSplitVertical = {
                val workingDir = splitState.getFocusedSession()?.workingDirectory?.value
                var newSessionRef: TerminalSession? = null
                val newSession = tabController.createSessionForSplit(
                    workingDir = workingDir,
                    onProcessExit = {
                        // Auto-close the pane when process exits
                        if (splitState.isSinglePane) {
                            // Last pane - close the tab
                            val tabIndex = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                            if (tabIndex != -1) {
                                tabController.closeTab(tabIndex)
                            }
                        } else {
                            // Close just this pane
                            newSessionRef?.let { session ->
                                splitState.getAllPanes()
                                    .find { it.session === session }
                                    ?.let { pane -> splitState.closePane(pane.id) }
                            }
                        }
                    }
                )
                newSessionRef = newSession
                splitState.splitFocusedPane(SplitOrientation.VERTICAL, newSession)
            }
            onSplitHorizontal = {
                val workingDir = splitState.getFocusedSession()?.workingDirectory?.value
                var newSessionRef: TerminalSession? = null
                val newSession = tabController.createSessionForSplit(
                    workingDir = workingDir,
                    onProcessExit = {
                        // Auto-close the pane when process exits
                        if (splitState.isSinglePane) {
                            // Last pane - close the tab
                            val tabIndex = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                            if (tabIndex != -1) {
                                tabController.closeTab(tabIndex)
                            }
                        } else {
                            // Close just this pane
                            newSessionRef?.let { session ->
                                splitState.getAllPanes()
                                    .find { it.session === session }
                                    ?.let { pane -> splitState.closePane(pane.id) }
                            }
                        }
                    }
                )
                newSessionRef = newSession
                splitState.splitFocusedPane(SplitOrientation.HORIZONTAL, newSession)
            }
            onClosePane = {
                if (splitState.isSinglePane) {
                    tabController.closeTab(tabController.activeTabIndex)
                } else {
                    splitState.closeFocusedPane()
                }
            }
        }
    }

    // Initialize with one tab on first composition
    // Check for pending tab transfer from another window first
    LaunchedEffect(Unit) {
        if (tabController.tabs.isEmpty()) {
            val pendingTab = WindowManager.pendingTabForNewWindow
            val pendingSplitState = WindowManager.pendingSplitStateForNewWindow
            if (pendingTab != null) {
                // Clear pending state
                WindowManager.pendingTabForNewWindow = null
                WindowManager.pendingSplitStateForNewWindow = null
                // Add the transferred tab
                tabController.createTabFromExistingSession(pendingTab)
                // Restore split state if present
                if (pendingSplitState != null) {
                    splitStates[pendingTab.id] = pendingSplitState
                }
            } else {
                // No pending tab, create fresh terminal with optional initial command
                // Priority: parameter > settings > none
                val effectiveInitialCommand = initialCommand ?: settings.initialCommand.ifEmpty { null }
                tabController.createTab(
                    workingDir = workingDirectory,
                    initialCommand = effectiveInitialCommand
                )
            }
        }
    }

    // Cleanup split states when tabs are closed
    LaunchedEffect(tabController.tabs.size) {
        val currentTabIds = tabController.tabs.map { it.id }.toSet()
        // Find orphaned split states (tabs that were closed)
        val orphanedIds = splitStates.keys.filter { it !in currentTabIds }
        for (tabId in orphanedIds) {
            val splitState = splitStates.remove(tabId) ?: continue
            // Get all processes from split panes before disposing
            val processes = splitState.getAllSessions().mapNotNull { it.processHandle?.value }
            // Kill all processes first, then dispose
            for (process in processes) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        process.kill()
                    }
                } catch (e: Exception) {
                    println("WARN: Error killing split process: ${e.message}")
                }
            }
            // Now safe to dispose the split state (cancels coroutines, closes channels)
            splitState.dispose()
        }
    }

    // Cleanup when composable is disposed
    // Only dispose internal state - external TabbedTerminalState manages its own lifecycle
    DisposableEffect(tabController) {
        onDispose {
            if (state == null) {
                // Internal state: dispose everything
                splitStates.values.forEach { it.dispose() }
                splitStates.clear()
                tabController.disposeAll()
            }
            // External state: don't dispose - TabbedTerminalState.dispose() handles cleanup
        }
    }

    // Tab UI layout with focus overlay support
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab bar at top (show when multiple tabs or alwaysShowTabBar setting is enabled)
            if (tabController.tabs.size > 1 || settings.alwaysShowTabBar) {
            TabBar(
                tabs = tabController.tabs,
                activeTabIndex = tabController.activeTabIndex,
                onTabSelected = { index -> tabController.switchToTab(index) },
                onTabClosed = { index -> tabController.closeTab(index) },
                onNewTab = {
                    // New tabs always start in home directory (no working dir inheritance)
                    // Use initial command from settings if configured
                    tabController.createTab(initialCommand = settings.initialCommand.ifEmpty { null })
                },
                onTabMoveToNewWindow = { index ->
                    // Get tab first to access its ID for split state lookup
                    val tab = tabController.tabs.getOrNull(index) ?: return@TabBar
                    // Extract split state before extracting tab (preserves entire split layout)
                    val splitState = splitStates.remove(tab.id)
                    // Extract tab without disposing (preserves PTY session)
                    val extractedTab = tabController.extractTab(index) ?: return@TabBar
                    // Create new window and transfer both tab and split state
                    WindowManager.createWindowWithTab(extractedTab, splitState)
                }
            )
        }

        // Render active terminal tab with split support
        if (tabController.tabs.isNotEmpty()) {
            val activeTab = tabController.tabs[tabController.activeTabIndex]
            val splitState = getOrCreateSplitState(activeTab)

            // Update window title when active tab's title changes
            LaunchedEffect(activeTab) {
                activeTab.display.windowTitleFlow.collect { newTitle ->
                    if (newTitle.isNotEmpty()) {
                        onWindowTitleChange(newTitle)
                    }
                }
            }

            // Split operation handlers
            val onSplitHorizontal: () -> Unit = {
                // Only inherit working directory if setting is enabled
                val workingDir = if (settings.splitInheritWorkingDirectory) {
                    val session = splitState.getFocusedSession()
                    // First try OSC 7 tracked directory, then fall back to querying process
                    session?.workingDirectory?.value
                        ?: session?.processHandle?.value?.getWorkingDirectory()
                } else null
                var newSessionRef: TerminalSession? = null
                val newSession = tabController.createSessionForSplit(
                    workingDir = workingDir,
                    onProcessExit = {
                        // Auto-close the pane when process exits
                        if (splitState.isSinglePane) {
                            // Last pane - close the tab
                            val tabIndex = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                            if (tabIndex != -1) {
                                tabController.closeTab(tabIndex)
                            }
                        } else {
                            // Close just this pane
                            newSessionRef?.let { session ->
                                splitState.getAllPanes()
                                    .find { it.session === session }
                                    ?.let { pane -> splitState.closePane(pane.id) }
                            }
                        }
                    }
                )
                newSessionRef = newSession
                splitState.splitFocusedPane(SplitOrientation.HORIZONTAL, newSession, settings.splitDefaultRatio)
            }

            val onSplitVertical: () -> Unit = {
                // Only inherit working directory if setting is enabled
                val workingDir = if (settings.splitInheritWorkingDirectory) {
                    val session = splitState.getFocusedSession()
                    // First try OSC 7 tracked directory, then fall back to querying process
                    session?.workingDirectory?.value
                        ?: session?.processHandle?.value?.getWorkingDirectory()
                } else null
                var newSessionRef: TerminalSession? = null
                val newSession = tabController.createSessionForSplit(
                    workingDir = workingDir,
                    onProcessExit = {
                        // Auto-close the pane when process exits
                        if (splitState.isSinglePane) {
                            // Last pane - close the tab
                            val tabIndex = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                            if (tabIndex != -1) {
                                tabController.closeTab(tabIndex)
                            }
                        } else {
                            // Close just this pane
                            newSessionRef?.let { session ->
                                splitState.getAllPanes()
                                    .find { it.session === session }
                                    ?.let { pane -> splitState.closePane(pane.id) }
                            }
                        }
                    }
                )
                newSessionRef = newSession
                splitState.splitFocusedPane(SplitOrientation.VERTICAL, newSession, settings.splitDefaultRatio)
            }

            val onClosePane: () -> Unit = {
                if (splitState.isSinglePane) {
                    // Last pane - close the tab
                    tabController.closeTab(tabController.activeTabIndex)
                } else {
                    // Close just this pane
                    splitState.closeFocusedPane()
                }
            }

            val onNavigatePane: (NavigationDirection) -> Unit = { direction ->
                splitState.navigateFocus(direction)
            }

            SplitContainer(
                splitState = splitState,
                sharedFont = sharedFont,
                isActiveTab = true,
                onTabTitleChange = { newTitle ->
                    activeTab.title.value = newTitle
                },
                onNewTab = {
                    // New tabs always start in home directory (no working dir inheritance)
                    // Use initial command from settings if configured
                    tabController.createTab(initialCommand = settings.initialCommand.ifEmpty { null })
                },
                onCloseTab = {
                    tabController.closeTab(tabController.activeTabIndex)
                },
                onNextTab = {
                    tabController.nextTab()
                },
                onPreviousTab = {
                    tabController.previousTab()
                },
                onSwitchToTab = { index ->
                    if (index in tabController.tabs.indices) {
                        tabController.switchToTab(index)
                    }
                },
                onNewWindow = onNewWindow,
                onShowSettings = onShowSettings,
                onSplitHorizontal = onSplitHorizontal,
                onSplitVertical = onSplitVertical,
                onClosePane = onClosePane,
                onNavigatePane = onNavigatePane,
                onNavigateNextPane = { splitState.navigateToNextPane() },
                onNavigatePreviousPane = { splitState.navigateToPreviousPane() },
                onMoveToNewTab = if (!splitState.isSinglePane) {
                    {
                        // Extract the session from the split and move it to a new tab
                        val extractedSession = splitState.extractFocusedPaneSession()
                        if (extractedSession != null) {
                            // Check if we extracted the original tab's session
                            // This happens when the user moves the first pane (which is the tab itself)
                            if (extractedSession.id == activeTab.id) {
                                // The remaining session should take the original tab's position
                                // and the extracted original tab goes to a new tab
                                val remainingSession = splitState.getFocusedSession()
                                if (remainingSession != null) {
                                    // Remove the old split state (it's now invalid)
                                    splitStates.remove(activeTab.id)

                                    // Replace the tab at current position with the remaining session
                                    tabController.replaceTabAtIndex(tabController.activeTabIndex, remainingSession)

                                    // Add the extracted original tab as a new tab
                                    tabController.createTabFromExistingSession(extractedSession)
                                }
                            } else {
                                // Normal case: extracted a split session (not the original tab)
                                tabController.createTabFromExistingSession(extractedSession)
                            }
                        }
                    }
                } else null,  // Don't show option if only one pane (nothing to move)
                menuActions = menuActions,
                // Split pane settings
                splitFocusBorderEnabled = settings.splitFocusBorderEnabled,
                splitFocusBorderColor = settings.splitFocusBorderColorValue,
                splitMinimumSize = settings.splitMinimumSize,
                onLinkClick = onLinkClick,
                customContextMenuItems = contextMenuItems,
                modifier = Modifier.fillMaxSize()
            )
        }
        }

        // Semi-transparent overlay when window loses focus
        if (!isWindowFocusedState && settings.showUnfocusedOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.15f))
            )
        }
    }
}
