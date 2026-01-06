package ai.rever.bossterm.embedded

import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.rememberEmbeddableTerminalState
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Example application demonstrating BossTerm embedded in a parent application.
 *
 * This is useful for testing focus-related issues like:
 * - Issue #126: Terminal input stops working after context menu dismissal
 * - Focus transitions between parent UI and terminal
 * - Multiple embedded terminals
 */
fun main() = application {
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "BossTerm Embedded Example",
        state = windowState
    ) {
        EmbeddedExampleApp()
    }
}

@Composable
fun EmbeddedExampleApp() {
    val terminalState = rememberEmbeddableTerminalState()
    val compactTerminalState = rememberEmbeddableTerminalState()
    var sidebarExpanded by remember { mutableStateOf(true) }
    var bottomPanelExpanded by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }
    var contextMenuOpenCount by remember { mutableStateOf(0) }

    // Settings override for compact terminal (smaller font, no scrollbar)
    val compactSettingsOverride = remember {
        TerminalSettingsOverride(
            fontSize = 11f,
            showScrollbar = false,
            lineSpacing = 1.0f
        )
    }

    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Sidebar - parent UI that can steal focus
                if (sidebarExpanded) {
                    Sidebar(
                        onAction = { action -> statusMessage = "Action: $action" },
                        onSendToTerminal = { text -> terminalState.write(text) },
                        // Control signal callbacks using sendInput API
                        onSendCtrlC = { terminalState.sendCtrlC() },
                        onSendCtrlD = { terminalState.sendCtrlD() },
                        onSendCtrlZ = { terminalState.sendCtrlZ() },
                        onToggleBottomPanel = { bottomPanelExpanded = !bottomPanelExpanded },
                        bottomPanelExpanded = bottomPanelExpanded,
                        modifier = Modifier.width(250.dp)
                    )
                }

                // Main content area
                Column(modifier = Modifier.weight(1f)) {
                    // Toolbar
                    Toolbar(
                        sidebarExpanded = sidebarExpanded,
                        onToggleSidebar = { sidebarExpanded = !sidebarExpanded },
                        statusMessage = statusMessage,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Main terminal area
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        EmbeddableTerminal(
                            state = terminalState,
                            // Test workingDirectory fix for Starship prompt
                            workingDirectory = "/tmp",
                            // Run a command automatically when terminal is ready
                            // Uses OSC 133 shell integration for proper timing if available
                            initialCommand = "echo 'Welcome to BossTerm Embedded Example!' && pwd",
                            onExit = { exitCode ->
                                statusMessage = "Terminal exited with code: $exitCode"
                            },
                            onTitleChange = { title ->
                                statusMessage = "Title: $title"
                            },
                            // onInitialCommandComplete callback demo - called when initial command finishes
                            // Requires OSC 133 shell integration to detect command completion
                            onInitialCommandComplete = { success, exitCode ->
                                statusMessage = if (success) {
                                    "Initial command completed successfully (exit code: $exitCode)"
                                } else {
                                    "Initial command failed (exit code: $exitCode)"
                                }
                            },
                            // onContextMenuOpen callback demo - called before menu shows
                            onContextMenuOpen = {
                                contextMenuOpenCount++
                                statusMessage = "Context menu opened ($contextMenuOpenCount times)"
                            },
                            // Custom context menu items with sections and submenus
                            contextMenuItems = listOf(
                                // Section with label
                                ContextMenuSection(id = "commands_section", label = "Quick Commands"),
                                ContextMenuItem(
                                    id = "run_pwd",
                                    label = "Run 'pwd'",
                                    action = { terminalState.write("pwd\n") }
                                ),
                                ContextMenuItem(
                                    id = "run_ls",
                                    label = "Run 'ls'",
                                    action = { terminalState.write("ls\n") }
                                ),
                                // Submenu with more options
                                ContextMenuSubmenu(
                                    id = "more_commands",
                                    label = "More Commands",
                                    items = listOf(
                                        ContextMenuItem(
                                            id = "run_date",
                                            label = "Show Date",
                                            action = { terminalState.write("date\n") }
                                        ),
                                        ContextMenuItem(
                                            id = "run_whoami",
                                            label = "Who Am I",
                                            action = { terminalState.write("whoami\n") }
                                        ),
                                        ContextMenuSection(id = "git_section"),
                                        ContextMenuItem(
                                            id = "git_status",
                                            label = "Git Status",
                                            action = { terminalState.write("git status\n") }
                                        )
                                    )
                                )
                            ),
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Bottom panel with compact terminal (demonstrates settingsOverride)
                    if (bottomPanelExpanded) {
                        HorizontalDivider(color = Color(0xFF3C3C3C))

                        Column(
                            modifier = Modifier
                                .height(200.dp)
                                .fillMaxWidth()
                        ) {
                            // Bottom panel header
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF2D2D2D)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Compact Terminal (settingsOverride demo: fontSize=11, no scrollbar)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                    TextButton(
                                        onClick = { bottomPanelExpanded = false },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                                    ) {
                                        Text("Close")
                                    }
                                }
                            }

                            // Compact terminal with settings override
                            EmbeddableTerminal(
                                state = compactTerminalState,
                                initialCommand = "echo 'Compact terminal with settingsOverride'",
                                // Using settingsOverride to customize this terminal instance
                                settingsOverride = compactSettingsOverride,
                                onExit = { exitCode ->
                                    statusMessage = "Compact terminal exited: $exitCode"
                                },
                                // onContextMenuOpen works in compact terminal too
                                onContextMenuOpen = {
                                    contextMenuOpenCount++
                                    statusMessage = "Compact terminal context menu ($contextMenuOpenCount times)"
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp)
                            )
                        }
                    }

                    // Status bar
                    StatusBar(
                        message = statusMessage,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun Sidebar(
    onAction: (String) -> Unit,
    onSendToTerminal: (String) -> Unit,
    onSendCtrlC: () -> Unit,
    onSendCtrlD: () -> Unit,
    onSendCtrlZ: () -> Unit,
    onToggleBottomPanel: () -> Unit,
    bottomPanelExpanded: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(0xFF1E1E1E))
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Sidebar",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )

        HorizontalDivider(color = Color(0xFF3C3C3C))

        Text(
            text = "Test Focus Scenarios:",
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray
        )

        // Buttons that can steal focus from terminal
        SidebarButton("Button 1") { onAction("Button 1 clicked") }
        SidebarButton("Button 2") { onAction("Button 2 clicked") }
        SidebarButton("Button 3") { onAction("Button 3 clicked") }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Send to Terminal:",
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray
        )

        // Buttons that send commands to terminal
        SidebarButton("Send 'ls'") { onSendToTerminal("ls\n") }
        SidebarButton("Send 'pwd'") { onSendToTerminal("pwd\n") }
        SidebarButton("Send 'clear'") { onSendToTerminal("clear\n") }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Control Signals:",
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray
        )

        // Buttons that send control signals (sendInput API demo)
        SidebarButton("Send Ctrl+C") { onSendCtrlC() }
        SidebarButton("Send Ctrl+D") { onSendCtrlD() }
        SidebarButton("Send Ctrl+Z") { onSendCtrlZ() }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Settings Override Demo:",
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray
        )

        // Button to toggle bottom panel with compact terminal
        SidebarButton(
            if (bottomPanelExpanded) "Hide Compact Terminal" else "Show Compact Terminal"
        ) { onToggleBottomPanel() }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Click buttons above, then\nclick in terminal and type\nto test focus restoration.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
fun SidebarButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF3C3C3C),
            contentColor = Color.White
        )
    ) {
        Text(text)
    }
}

@Composable
fun Toolbar(
    sidebarExpanded: Boolean,
    onToggleSidebar: () -> Unit,
    statusMessage: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF2D2D2D),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onToggleSidebar,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text(if (sidebarExpanded) "Hide Sidebar" else "Show Sidebar")
                }

                Text(
                    text = "BossTerm Embedded Example",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
            }

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun StatusBar(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF007ACC)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
            Text(
                text = "Test: Right-click menu, then type",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}
