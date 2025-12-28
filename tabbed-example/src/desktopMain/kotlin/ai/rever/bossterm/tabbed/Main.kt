package ai.rever.bossterm.tabbed

import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.rememberTabbedTerminalState
import ai.rever.bossterm.compose.menu.MenuActions
import ai.rever.bossterm.compose.settings.SettingsManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    EDITOR("Editor"),
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
                Item("Editor (Demo)", onClick = { currentView = AppView.EDITOR })
                Separator()
                Item("Split Vertically", onClick = { menuActions.onSplitVertical?.invoke() })
                Item("Split Horizontally", onClick = { menuActions.onSplitHorizontal?.invoke() })
                Separator()
                Item("Settings", onClick = { showSettings = true })
            }
            Menu("Window") {
                Item("Next Tab", onClick = { menuActions.onNextTab?.invoke() })
                Item("Previous Tab", onClick = { menuActions.onPreviousTab?.invoke() })
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
                        terminalTabCount = terminalState.tabCount
                    )

                    // Main content area
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (currentView) {
                            AppView.TERMINAL -> {
                                // Custom context menu items demonstrating the contextMenuItems API
                                val customContextMenuItems = remember {
                                    listOf(
                                        // Section with label
                                        ContextMenuSection(
                                            id = "quick_commands_section",
                                            label = "Quick Commands"
                                        ),
                                        // Simple menu items
                                        ContextMenuItem(
                                            id = "list_files",
                                            label = "List Files (ls -la)",
                                            action = { terminalState.activeTab?.writeUserInput("ls -la\n") }
                                        ),
                                        ContextMenuItem(
                                            id = "show_pwd",
                                            label = "Show Directory (pwd)",
                                            action = { terminalState.activeTab?.writeUserInput("pwd\n") }
                                        ),
                                        // Submenu with nested items
                                        ContextMenuSubmenu(
                                            id = "git_commands",
                                            label = "Git Commands",
                                            items = listOf(
                                                ContextMenuItem(
                                                    id = "git_status",
                                                    label = "Status",
                                                    action = { terminalState.activeTab?.writeUserInput("git status\n") }
                                                ),
                                                ContextMenuItem(
                                                    id = "git_log",
                                                    label = "Log (last 10)",
                                                    action = { terminalState.activeTab?.writeUserInput("git log --oneline -10\n") }
                                                ),
                                                ContextMenuSection(id = "git_branch_section"),
                                                ContextMenuItem(
                                                    id = "git_branch",
                                                    label = "List Branches",
                                                    action = { terminalState.activeTab?.writeUserInput("git branch -a\n") }
                                                )
                                            )
                                        ),
                                        // Another section
                                        ContextMenuSection(id = "system_section"),
                                        ContextMenuItem(
                                            id = "system_info",
                                            label = "System Info (uname -a)",
                                            action = { terminalState.activeTab?.writeUserInput("uname -a\n") }
                                        ),
                                        // Control signals section (sendInput API demo)
                                        ContextMenuSection(
                                            id = "control_signals_section",
                                            label = "Control Signals"
                                        ),
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
                                }

                                // Terminal with external state - sessions persist across view switches!
                                TabbedTerminal(
                                    state = terminalState,
                                    onExit = onCloseRequest,
                                    onWindowTitleChange = { title -> windowTitle = title },
                                    onNewWindow = onNewWindow,
                                    onShowSettings = { showSettings = true },
                                    menuActions = menuActions,
                                    isWindowFocused = { isWindowFocused },
                                    contextMenuItems = customContextMenuItems,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            AppView.EDITOR -> {
                                // Placeholder editor view
                                EditorPlaceholder(
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
                }
            }
        }
    }
}

/**
 * View switcher bar showing current view and tab count.
 * Demonstrates that terminal state persists when switching views.
 */
@Composable
private fun ViewSwitcherBar(
    currentView: AppView,
    onViewChange: (AppView) -> Unit,
    terminalTabCount: Int
) {
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
                        if (view == AppView.TERMINAL && terminalTabCount > 0) {
                            Surface(
                                color = Color(0xFF606060),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "$terminalTabCount",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
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
 * Placeholder editor view to demonstrate view switching.
 */
@Composable
private fun EditorPlaceholder(onSwitchToTerminal: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Editor View (Placeholder)",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Text(
                text = "This demonstrates TabbedTerminalState:",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )
            Text(
                text = "1. Open some terminal tabs",
                color = Color.Gray
            )
            Text(
                text = "2. Switch to this Editor view",
                color = Color.Gray
            )
            Text(
                text = "3. Switch back to Terminal",
                color = Color.Gray
            )
            Text(
                text = "4. All your terminal sessions are still there!",
                color = Color.Green
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onSwitchToTerminal) {
                Text("Switch to Terminal")
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
