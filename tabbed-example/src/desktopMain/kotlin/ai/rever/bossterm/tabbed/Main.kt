package ai.rever.bossterm.tabbed

import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.TerminalTabInfo
import ai.rever.bossterm.compose.rememberTabbedTerminalState
import ai.rever.bossterm.compose.menu.MenuActions
import ai.rever.bossterm.compose.onboarding.OnboardingWizard
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.splits.NavigationDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Example application demonstrating BossTerm's TabbedTerminal component.
 *
 * This shows how to embed a full-featured tabbed terminal in your application with:
 * - Multiple tabs with tab bar
 * - Split panes (Cmd/Ctrl+D for vertical, Cmd/Ctrl+Shift+D for horizontal)
 * - Window title updates from active tab
 * - Multiple windows support
 * - Window focus tracking for command notifications
 * - Menu bar integration
 * - **Custom context menu items** (right-click to see)
 * - **State persistence across view switches** (TabbedTerminalState demo)
 * - **Plugin API demo** for Split Pane (T6) and Reactive State (T7) APIs
 * - **Welcome Wizard** for first-time setup (Help > Welcome Wizard)
 *
 * Run with: ./gradlew :tabbed-example:run
 */
fun main() = application {
    // Track all open windows
    val windows = remember { mutableStateListOf(WindowState()) }

    // Create new window
    fun createWindow() {
        windows.add(WindowState())
    }

    // Close window
    fun closeWindow(index: Int) {
        if (windows.size > 1) {
            windows.removeAt(index)
        } else {
            exitApplication()
        }
    }

    // Render all windows
    windows.forEachIndexed { index, windowState ->
        TabbedTerminalWindow(
            windowState = windowState,
            windowIndex = index,
            totalWindows = windows.size,
            onCloseRequest = { closeWindow(index) },
            onNewWindow = { createWindow() }
        )
    }
}

/**
 * Available views in the application.
 * Demonstrates switching between views while preserving terminal state.
 */
private enum class AppView(val label: String) {
    TERMINAL("Terminal"),
    API_DEMO("API Demo"),
    SETTINGS("Settings")
}

@Composable
private fun ApplicationScope.TabbedTerminalWindow(
    windowState: WindowState,
    windowIndex: Int,
    totalWindows: Int,
    onCloseRequest: () -> Unit,
    onNewWindow: () -> Unit
) {
    // Settings integration
    val settingsManager = remember { SettingsManager.instance }
    val settings by settingsManager.settings.collectAsState()

    // Track window title from terminal
    var windowTitle by remember { mutableStateOf("BossTerm Tabbed Example") }

    // Track window focus state for notifications
    var isWindowFocused by remember { mutableStateOf(true) }

    // Track current view (for demonstrating state persistence)
    var currentView by remember { mutableStateOf(AppView.TERMINAL) }

    // Track settings panel visibility
    var showSettings by remember { mutableStateOf(false) }

    // Track Welcome Wizard visibility
    var showWelcomeWizard by remember { mutableStateOf(false) }

    // Check if onboarding should be shown on first launch
    LaunchedEffect(Unit) {
        if (!settings.onboardingCompleted) {
            showWelcomeWizard = true
        }
    }

    // Track context menu opens (onContextMenuOpen demo)
    var contextMenuOpenCount by remember { mutableStateOf(0) }

    // === Dynamic Context Menu Demo (issue #223) ===
    // Simulates AI assistant installation status that changes over time
    var aiAssistantInstalled by remember { mutableStateOf(false) }
    var lastCheckTime by remember { mutableStateOf("Never") }

    // Menu actions for wiring up menu bar
    val menuActions = remember { MenuActions() }

    // === KEY FEATURE: TabbedTerminalState for state persistence ===
    // This state survives when switching to Editor/Settings views and back!
    // Without this, terminal sessions would be lost when unmounting TabbedTerminal.
    val terminalState = rememberTabbedTerminalState(autoDispose = false)

    // Manual cleanup when window closes
    DisposableEffect(Unit) {
        onDispose {
            terminalState.dispose()
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = if (totalWindows > 1) "$windowTitle (Window ${windowIndex + 1})" else windowTitle,
        state = rememberWindowState(
            size = DpSize(1000.dp, 700.dp)
        )
    ) {
        // Track window focus via AWT listener
        LaunchedEffect(Unit) {
            val awtWindow = window
            val focusListener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    isWindowFocused = true
                }
                override fun windowLostFocus(e: WindowEvent?) {
                    isWindowFocused = false
                }
            }
            awtWindow.addWindowFocusListener(focusListener)
        }

        // Menu bar
        MenuBar {
            Menu("File") {
                Item("New Tab", onClick = { menuActions.onNewTab?.invoke() })
                Item("New Window", onClick = onNewWindow)
                Separator()
                Item("Close Tab", onClick = { menuActions.onCloseTab?.invoke() })
            }
            Menu("Edit") {
                Item("Copy", onClick = { /* Handled by terminal */ })
                Item("Paste", onClick = { /* Handled by terminal */ })
            }
            Menu("View") {
                Item("Terminal", onClick = { currentView = AppView.TERMINAL })
                Item("API Demo", onClick = { currentView = AppView.API_DEMO })
                Separator()
                Item("Split Vertically", onClick = { menuActions.onSplitVertical?.invoke() })
                Item("Split Horizontally", onClick = { menuActions.onSplitHorizontal?.invoke() })
                Separator()
                Item("Split Vertical (API)", onClick = { terminalState.splitVertical() })
                Item("Split Horizontal (API)", onClick = { terminalState.splitHorizontal() })
                Item("Close Pane (API)", onClick = { terminalState.closeFocusedPane() })
                Separator()
                Item("Settings", onClick = { showSettings = true })
            }
            Menu("Window") {
                Item("Next Tab", onClick = { menuActions.onNextTab?.invoke() })
                Item("Previous Tab", onClick = { menuActions.onPreviousTab?.invoke() })
            }
            Menu("Help") {
                Item("Welcome Wizard...", onClick = { showWelcomeWizard = true })
            }
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = settings.defaultBackgroundColor
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // View switcher bar (demonstrates state persistence)
                    ViewSwitcherBar(
                        currentView = currentView,
                        onViewChange = { currentView = it },
                        tabsFlow = terminalState.tabsFlow,
                        activeTabIndexFlow = terminalState.activeTabIndexFlow
                    )

                    // Main content area
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (currentView) {
                            AppView.TERMINAL -> {
                                // Terminal with external state - sessions persist across view switches!
                                TabbedTerminal(
                                    state = terminalState,
                                    onExit = onCloseRequest,
                                    onWindowTitleChange = { title -> windowTitle = title },
                                    onNewWindow = onNewWindow,
                                    onShowSettings = { showSettings = true },
                                    onShowWelcomeWizard = { showWelcomeWizard = true },
                                    menuActions = menuActions,
                                    isWindowFocused = { isWindowFocused },
                                    // onInitialCommandComplete callback - called when initial command finishes
                                    // Use case: trigger next step in workflow after setup completes
                                    // Requires OSC 133 shell integration to detect command completion
                                    onInitialCommandComplete = { success, exitCode ->
                                        windowTitle = if (success) {
                                            "Initial command completed (exit: $exitCode)"
                                        } else {
                                            "Initial command failed (exit: $exitCode)"
                                        }
                                    },
                                    // === contextMenuItemsProvider Demo (issue #223) ===
                                    // onContextMenuOpenAsync runs first, updates state
                                    onContextMenuOpenAsync = {
                                        contextMenuOpenCount++
                                        windowTitle = "Checking AI assistant... ($contextMenuOpenCount)"
                                        // Simulate async check (e.g., checking if Claude/Copilot is installed)
                                        delay(150)
                                        // Toggle installation status to demonstrate dynamic updates
                                        aiAssistantInstalled = !aiAssistantInstalled
                                        lastCheckTime = java.time.LocalTime.now().toString().take(8)
                                        windowTitle = "AI: ${if (aiAssistantInstalled) "Installed" else "Not installed"}"
                                    },
                                    // contextMenuItemsProvider is called AFTER onContextMenuOpenAsync completes
                                    // This ensures the menu items reflect the latest state
                                    contextMenuItemsProvider = {
                                        listOf(
                                            // === Dynamic AI Assistant Section ===
                                            ContextMenuSection(id = "ai_section", label = "AI Assistant (Dynamic)"),
                                            ContextMenuItem(
                                                id = "ai_action",
                                                // Label changes based on installation status
                                                label = if (aiAssistantInstalled) "Ask AI Assistant" else "Install AI Assistant",
                                                action = {
                                                    if (aiAssistantInstalled) {
                                                        terminalState.write("echo 'AI Assistant: How can I help?'\n")
                                                    } else {
                                                        terminalState.write("echo 'Installing AI Assistant...'\n")
                                                    }
                                                }
                                            ),
                                            ContextMenuItem(
                                                id = "ai_status",
                                                label = "Status: ${if (aiAssistantInstalled) "Installed" else "Not installed"} (checked: $lastCheckTime)",
                                                enabled = false,  // Info-only item
                                                action = {}
                                            ),
                                            // === Static Commands Section ===
                                            ContextMenuSection(id = "quick_commands_section", label = "Quick Commands"),
                                            ContextMenuItem(
                                                id = "list_files",
                                                label = "List Files (ls -la)",
                                                action = { terminalState.write("ls -la\n") }
                                            ),
                                            ContextMenuItem(
                                                id = "show_pwd",
                                                label = "Show Directory (pwd)",
                                                action = { terminalState.write("pwd\n") }
                                            ),
                                            // Submenu with nested items
                                            ContextMenuSubmenu(
                                                id = "git_commands",
                                                label = "Git Commands",
                                                items = listOf(
                                                    ContextMenuItem(
                                                        id = "git_status",
                                                        label = "Status",
                                                        action = { terminalState.write("git status\n") }
                                                    ),
                                                    ContextMenuItem(
                                                        id = "git_log",
                                                        label = "Log (last 10)",
                                                        action = { terminalState.write("git log --oneline -10\n") }
                                                    ),
                                                    ContextMenuSection(id = "git_branch_section"),
                                                    ContextMenuItem(
                                                        id = "git_branch",
                                                        label = "List Branches",
                                                        action = { terminalState.write("git branch -a\n") }
                                                    )
                                                )
                                            ),
                                            // Control signals section
                                            ContextMenuSection(id = "control_signals_section", label = "Control Signals"),
                                            ContextMenuItem(
                                                id = "send_ctrl_c",
                                                label = "Send Ctrl+C (Interrupt)",
                                                action = { terminalState.sendCtrlC() }
                                            ),
                                            ContextMenuItem(
                                                id = "send_ctrl_d",
                                                label = "Send Ctrl+D (EOF)",
                                                action = { terminalState.sendCtrlD() }
                                            ),
                                            ContextMenuItem(
                                                id = "send_ctrl_z",
                                                label = "Send Ctrl+Z (Suspend)",
                                                action = { terminalState.sendCtrlZ() }
                                            )
                                        )
                                    },
                                    workingDirectory = "/tmp",
                                    // Initial command to run when terminal starts
                                    initialCommand = "echo 'TabbedTerminal ready!' && date",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            AppView.API_DEMO -> {
                                // Plugin API demo (T6 Split Pane + T7 Reactive State)
                                PluginApiDemoPanel(
                                    terminalState = terminalState,
                                    onSwitchToTerminal = { currentView = AppView.TERMINAL }
                                )
                            }
                            AppView.SETTINGS -> {
                                // Settings view
                                SettingsPanel(
                                    onDismiss = { currentView = AppView.TERMINAL }
                                )
                            }
                        }

                        // Settings panel overlay
                        if (showSettings && currentView != AppView.SETTINGS) {
                            SettingsPanel(
                                onDismiss = { showSettings = false }
                            )
                        }
                    }

                    // Reactive status bar (T7 Reactive State API demo)
                    ReactiveStatusBar(terminalState = terminalState)
                }
            }
        }

        // Welcome Wizard dialog
        if (showWelcomeWizard) {
            OnboardingWizard(
                onDismiss = { showWelcomeWizard = false },
                onComplete = { showWelcomeWizard = false },
                settingsManager = settingsManager
            )
        }
    }
}

/**
 * View switcher bar showing current view, tab count, and pane count.
 * Demonstrates that terminal state persists when switching views.
 * Uses reactive T7 flows to display live tab/pane info.
 *
 * Accepts minimal flow parameters rather than the full TabbedTerminalState
 * to keep the composable decoupled and easier to test/reuse.
 */
@Composable
private fun ViewSwitcherBar(
    currentView: AppView,
    onViewChange: (AppView) -> Unit,
    tabsFlow: StateFlow<List<TerminalTabInfo>>,
    activeTabIndexFlow: StateFlow<Int>
) {
    val tabs by tabsFlow.collectAsState()
    val activeTabIndex by activeTabIndexFlow.collectAsState()
    val activeTab = tabs.getOrNull(activeTabIndex)
    val paneCount = activeTab?.paneCount ?: 0

    Surface(
        color = Color(0xFF2D2D2D),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // View tabs
            AppView.entries.filter { it != AppView.SETTINGS }.forEach { view ->
                val isSelected = currentView == view
                Surface(
                    modifier = Modifier.clickable { onViewChange(view) },
                    color = if (isSelected) Color(0xFF404040) else Color.Transparent,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = view.label,
                            color = if (isSelected) Color.White else Color.Gray,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                        // Show tab count for terminal
                        if (view == AppView.TERMINAL && tabs.isNotEmpty()) {
                            Surface(
                                color = Color(0xFF606060),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "${tabs.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            // Show pane count when active tab has splits
                            if (paneCount > 1) {
                                Surface(
                                    color = Color(0xFF4A6A4A),
                                    shape = MaterialTheme.shapes.extraSmall
                                ) {
                                    Text(
                                        text = "$paneCount panes",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = Color(0xFF90EE90),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Info text
            Text(
                text = "Switch views - terminal state persists!",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Plugin API Demo panel showcasing T6 (Split Pane) and T7 (Reactive State) APIs.
 * Provides interactive controls for split pane operations and live state display.
 */
@Composable
private fun PluginApiDemoPanel(
    terminalState: TabbedTerminalState,
    onSwitchToTerminal: () -> Unit
) {
    val scrollState = rememberScrollState()
    // Reactive state for live pane info
    val tabs by terminalState.tabsFlow.collectAsState()
    val activeTabIndex by terminalState.activeTabIndexFlow.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Text(
                text = "Plugin API Demo",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Text(
                text = "Interactive demonstration of T6 (Split Pane) and T7 (Reactive State) APIs",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            HorizontalDivider(color = Color(0xFF404040))

            // === Split Pane Controls (T6) ===
            Text(
                text = "Split Pane Controls (T6)",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF80CBC4)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { terminalState.splitVertical() }) {
                    Text("Split Vertical")
                }
                Button(onClick = { terminalState.splitHorizontal() }) {
                    Text("Split Horizontal")
                }
                OutlinedButton(onClick = { terminalState.closeFocusedPane() }) {
                    Text("Close Focused Pane")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { terminalState.navigateToPreviousPane() }) {
                    Text("Previous Pane")
                }
                OutlinedButton(onClick = { terminalState.navigateToNextPane() }) {
                    Text("Next Pane")
                }
            }

            Text(
                text = "Directional Navigation",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Gray
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavigationDirection.entries.forEach { dir ->
                    OutlinedButton(onClick = { terminalState.navigatePaneFocus(dir) }) {
                        Text(dir.name)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF404040))

            // === Live Pane State ===
            Text(
                text = "Live Pane State",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF80CBC4)
            )

            val paneCount = terminalState.getPaneCount()
            val hasSplits = terminalState.hasSplitPanes()
            val sessionIds = terminalState.getSplitSessionIds()

            Surface(
                color = Color(0xFF252535),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoRow("Pane count", "$paneCount")
                    InfoRow("Has splits", "$hasSplits")
                    InfoRow("Session IDs", if (sessionIds.isEmpty()) "(none)" else sessionIds.joinToString(", "))
                    InfoRow("Active tab", if (tabs.isEmpty()) "(no tabs)" else "#$activeTabIndex: ${tabs.getOrNull(activeTabIndex)?.title ?: "?"}")
                }
            }

            Button(onClick = { terminalState.writeToFocusedPane("echo 'Hello from API!'\n") }) {
                Text("Write to Focused Pane")
            }

            HorizontalDivider(color = Color(0xFF404040))

            // === Tab Controls ===
            Text(
                text = "Tab Controls",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF80CBC4)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { terminalState.createTab() }) {
                    Text("Create Tab")
                }
                OutlinedButton(onClick = { terminalState.closeActiveTab() }) {
                    Text("Close Active Tab")
                }
                OutlinedButton(onClick = onSwitchToTerminal) {
                    Text("Switch to Terminal")
                }
            }
        }
    }
}

/**
 * Simple key-value info row for the Live Pane State section.
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Reactive status bar displaying live tab and pane info from T7 Reactive State API.
 * Updates automatically as tabs and panes change.
 */
@Composable
private fun ReactiveStatusBar(terminalState: TabbedTerminalState) {
    val tabs by terminalState.tabsFlow.collectAsState()
    val activeTabIndex by terminalState.activeTabIndexFlow.collectAsState()
    val activeTab = tabs.getOrNull(activeTabIndex)

    Surface(color = Color(0xFF1A1A2E)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tab count
            Text(
                text = "Tabs: ${tabs.size}",
                color = Color(0xFF8888AA),
                style = MaterialTheme.typography.labelSmall
            )

            // Active tab index
            Text(
                text = "Active: #$activeTabIndex",
                color = Color(0xFF8888AA),
                style = MaterialTheme.typography.labelSmall
            )

            // Active tab details
            if (activeTab != null) {
                Text(
                    text = activeTab.title,
                    color = Color(0xFFAAAACC),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (activeTab.isConnected) "Connected" else "Disconnected",
                    color = if (activeTab.isConnected) Color(0xFF66BB6A) else Color(0xFFEF5350),
                    style = MaterialTheme.typography.labelSmall
                )
                activeTab.workingDirectory?.let { cwd ->
                    Text(
                        text = cwd,
                        color = Color(0xFF7788AA),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (activeTab.paneCount > 1) {
                    Text(
                        text = "${activeTab.paneCount} panes",
                        color = Color(0xFF90EE90),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Reactive indicator
            Surface(
                color = Color(0xFF2A2A4A),
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = "Reactive",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color(0xFF6A6ADA),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(400.dp)
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "This is a placeholder settings panel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "In a real application, you would integrate with SettingsManager to display and modify terminal settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/**
 * Simple window state holder for tracking multiple windows.
 */
private class WindowState {
    val id = System.currentTimeMillis()
}
