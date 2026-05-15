package ai.rever.bossterm.embedded

import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpManager
import ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.rememberEmbeddableTerminalState
import ai.rever.bossterm.compose.onboarding.OnboardingWizard
import ai.rever.bossterm.compose.settings.SettingsManager
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
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Custom PlatformServices wrapper that logs process spawn events.
 * Demonstrates the delegation pattern for overriding platform services (PR #245).
 */
private class LoggingPlatformServices(
    private val defaults: PlatformServices = getPlatformServices(),
    private val onLog: (String) -> Unit
) : PlatformServices by defaults {
    override fun getProcessService() = object : PlatformServices.ProcessService {
        private val delegate = defaults.getProcessService()
        override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig): PlatformServices.ProcessService.ProcessHandle? {
            onLog("[PlatformServices] spawn: ${config.command} ${config.arguments.joinToString(" ")}")
            return delegate.spawnProcess(config)
        }
    }
}

/**
 * Example application demonstrating BossTerm embedded in a parent application.
 *
 * This is useful for testing focus-related issues like:
 * - Issue #126: Terminal input stops working after context menu dismissal
 * - Focus transitions between parent UI and terminal
 * - Multiple embedded terminals
 */
fun main() {
    // === BossTerm MCP setup ============================================
    // Demonstrates two embedder hooks on BossTermMcpConfig:
    //   1. customToolDescriptions — replace the default description of a built-in
    //      tool so MCP clients see app-specific wording. Keys are unprefixed
    //      built-in names (e.g. "list_tabs"). Unmentioned tools keep their default.
    //   2. additionalTools — register app-specific MCP tools alongside the built-ins.
    //      The lambda receives the live `Server` so the embedder can call
    //      `server.addTool(...)` directly. Tool names here are NOT prefixed —
    //      the embedder owns the namespace.
    // The user still has to toggle "Enable BossTerm MCP Server" in settings for
    // the Ktor endpoint to bind (defaultEnabled = true below skips that on a
    // fresh install).
    val mcpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mcpConfig = BossTermMcpConfig(
        serverName = "bossterm-embedded-example",
        // `defaultEnabled` is honored only on the very first BossTerm launch on this
        // machine (gated by the global `mcpConfigured` flag in ~/.bossterm/settings.json).
        // If you've already run another BossTerm-based app on this machine, the setting
        // here is ignored and the user's persisted mcpEnabled value wins. Toggle MCP
        // on/off in Settings → BossTerm MCP if needed.
        defaultEnabled = true,
        customToolDescriptions = mapOf(
            "list_tabs" to "List terminal tabs hosted by the BossTerm Embedded Example app. " +
                    "This host embeds a single primary tab plus an optional compact bottom-panel " +
                    "tab when the user opens it from the sidebar."
        ),
        additionalTools = { server ->
            // Custom tool: returns metadata about the host app. Demonstrates the
            // minimum boilerplate for an embedder-defined MCP tool — zero-arg
            // schema, JSON text content, no error path.
            server.addTool(
                name = "embedded_example_app_info",
                description = "Return metadata about the host application " +
                        "(BossTerm Embedded Example): app name, build flavor, and a " +
                        "short usage hint. Demonstrates embedder-registered tools.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList()
                )
            ) { _ ->
                val body = buildJsonObject {
                    put("appName", "BossTerm Embedded Example")
                    put("flavor", "single-window, two-terminal demo")
                    put(
                        "hint",
                        "The sidebar's buttons drive the primary terminal directly; " +
                                "the compact bottom-panel terminal is opened on demand."
                    )
                }
                CallToolResult(
                    content = listOf(TextContent(text = body.toString())),
                    isError = false,
                    structuredContent = null,
                    meta = null
                )
            }
        }
    )
    val mcpManager = BossTermMcpManager(
        registry = McpTerminalRegistry,
        settingsManager = SettingsManager.instance,
        parentScope = mcpScope,
        config = mcpConfig
    )
    mcpManager.start()

    try {
        application {
            val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)

            // Expose the config to the settings UI so it can render the embedder's
            // server name in the endpoint note and respect showInSettingsUi.
            CompositionLocalProvider(LocalBossTermMcpConfig provides mcpConfig) {
                Window(
                    onCloseRequest = ::exitApplication,
                    title = "BossTerm Embedded Example",
                    state = windowState
                ) {
                    EmbeddedExampleApp()
                }
            }
        }
    } finally {
        mcpManager.stop()
        mcpScope.cancel()
    }
}

@Composable
fun EmbeddedExampleApp() {
    // Note: this example uses EmbeddableTerminal (single-terminal component),
    // whose state cannot be registered with McpTerminalRegistry — the registry
    // only accepts TabbedTerminalState. As a consequence, `list_tabs` and
    // the other tab-scoped MCP tools return empty here. The custom
    // `embedded_example_app_info` tool wired in main() still works because
    // it doesn't need tab visibility. For a host that wants MCP clients to
    // see tabs, use TabbedTerminal + TabbedTerminalState (see tabbed-example).
    val terminalState = rememberEmbeddableTerminalState()
    val compactTerminalState = rememberEmbeddableTerminalState()
    var sidebarExpanded by remember { mutableStateOf(true) }
    var bottomPanelExpanded by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }
    var contextMenuOpenCount by remember { mutableStateOf(0) }

    // === Custom PlatformServices Demo (PR #245) ===
    val platformLogs = remember { mutableStateListOf<String>() }
    val customPlatformServices = remember {
        LoggingPlatformServices(onLog = { platformLogs.add(it) })
    }

    // Welcome Wizard state
    val settingsManager = remember { SettingsManager.instance }
    var showWelcomeWizard by remember { mutableStateOf(false) }

    // === Dynamic Context Menu Demo ===
    // Simulates AI assistant installation status that changes over time
    // This demonstrates the contextMenuItemsProvider feature (issue #223)
    var aiAssistantInstalled by remember { mutableStateOf(false) }
    var lastCheckTime by remember { mutableStateOf("Never") }

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
                            onShowWelcomeWizard = { showWelcomeWizard = true },
                            // Run a command automatically when terminal is ready
                            // Uses OSC 133 shell integration for proper timing if available
                            initialCommand = "echo 'Welcome to BossTerm Embedded Example!' && pwd",
                            // === Custom PlatformServices Demo (PR #245) ===
                            platformServices = customPlatformServices,
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
                            // === contextMenuItemsProvider Demo (issue #223) ===
                            // onContextMenuOpenAsync runs first, updates state
                            onContextMenuOpenAsync = {
                                contextMenuOpenCount++
                                statusMessage = "Checking AI assistant status... ($contextMenuOpenCount)"
                                // Simulate async check (e.g., checking if Claude/Copilot is installed)
                                delay(150)
                                // Toggle installation status to demonstrate dynamic updates
                                aiAssistantInstalled = !aiAssistantInstalled
                                lastCheckTime = java.time.LocalTime.now().toString().take(8)
                                statusMessage = "AI check complete: ${if (aiAssistantInstalled) "Installed" else "Not installed"}"
                            },
                            // contextMenuItemsProvider is called AFTER onContextMenuOpenAsync completes
                            // This ensures the menu items reflect the latest state
                            contextMenuItemsProvider = {
                                listOf(
                                    // === Dynamic AI Assistant Section ===
                                    // These items update based on aiAssistantInstalled state
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
                                )
                            },
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
                                // onContextMenuOpen (sync) - for simple cases without async operations
                                // Use onContextMenuOpenAsync when you need to await before showing menu
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

                    // Status bar with platform service log
                    StatusBar(
                        message = statusMessage,
                        platformLog = platformLogs.lastOrNull(),
                        modifier = Modifier.fillMaxWidth()
                    )
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
    platformLog: String? = null,
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
                color = Color.White,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (platformLog != null) {
                Text(
                    text = platformLog,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0FFB0)
                )
            } else {
                Text(
                    text = "Test: Right-click menu, then type",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}
