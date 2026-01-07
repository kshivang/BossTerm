# Embedding BossTerm

This guide covers how to embed BossTerm's terminal component in your Compose Desktop application.

## Quick Start

```kotlin
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.rememberEmbeddableTerminalState

@Composable
fun MyApp() {
    val terminalState = rememberEmbeddableTerminalState()

    EmbeddableTerminal(
        state = terminalState,
        modifier = Modifier.fillMaxSize()
    )
}
```

## Installation

Add BossTerm dependencies to your `build.gradle.kts`:

```kotlin
dependencies {
    // Core terminal emulation engine
    implementation("com.risaboss:bossterm-core:<version>")

    // Compose Desktop UI component
    implementation("com.risaboss:bossterm-compose:<version>")
}
```

See the main [README](../README.md#embedding-in-your-app) for alternative repository options (JitPack, GitHub Packages).

## API Reference

### EmbeddableTerminal

The main composable for embedding a terminal.

```kotlin
@Composable
fun EmbeddableTerminal(
    state: EmbeddableTerminalState = rememberEmbeddableTerminalState(),
    settings: TerminalSettings? = null,
    settingsPath: String? = null,
    command: String? = null,
    workingDirectory: String? = null,
    environment: Map<String, String>? = null,
    initialCommand: String? = null,
    onOutput: ((String) -> Unit)? = null,
    onTitleChange: ((String) -> Unit)? = null,
    onExit: ((Int) -> Unit)? = null,
    onReady: (() -> Unit)? = null,
    contextMenuItems: List<ContextMenuElement> = emptyList(),
    contextMenuItemsProvider: (() -> List<ContextMenuElement>)? = null,
    onContextMenuOpen: (() -> Unit)? = null,
    onContextMenuOpenAsync: (suspend () -> Unit)? = null,
    onLinkClick: ((HyperlinkInfo) -> Boolean)? = null,
    settingsOverride: TerminalSettingsOverride? = null,
    hyperlinkRegistry: HyperlinkRegistry = HyperlinkDetector.registry,
    modifier: Modifier = Modifier
)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `state` | `EmbeddableTerminalState` | Terminal state for programmatic control |
| `settings` | `TerminalSettings?` | Custom terminal settings (overrides settingsPath) |
| `settingsPath` | `String?` | Path to settings JSON file |
| `command` | `String?` | Shell command to run (defaults to `$SHELL` or `/bin/zsh`) |
| `workingDirectory` | `String?` | Initial working directory (defaults to user home) |
| `environment` | `Map<String, String>?` | Additional environment variables |
| `initialCommand` | `String?` | Command to run after terminal is ready (see [Initial Command](#initial-command)) |
| `onInitialCommandComplete` | `(Boolean, Int) -> Unit` | Callback when initial command finishes (see [Initial Command Completion](#initial-command-completion)) |
| `onOutput` | `(String) -> Unit` | Callback for terminal output |
| `onTitleChange` | `(String) -> Unit` | Callback when terminal title changes |
| `onExit` | `(Int) -> Unit` | Callback when shell process exits |
| `onReady` | `() -> Unit` | Callback when terminal is ready |
| `contextMenuItems` | `List<ContextMenuElement>` | Custom context menu items |
| `contextMenuItemsProvider` | `(() -> List<ContextMenuElement>)?` | Lambda to get fresh menu items after async callback (see [Dynamic Context Menu Items](#dynamic-context-menu-items)) |
| `onContextMenuOpen` | `() -> Unit` | Callback before context menu displays (sync) |
| `onContextMenuOpenAsync` | `suspend () -> Unit` | Async callback before context menu displays - menu waits for completion |
| `onLinkClick` | `(HyperlinkInfo) -> Boolean` | Custom link click handler with rich metadata; return `true` if handled, `false` for default behavior (see [Custom Link Handling](#custom-link-handling)) |
| `settingsOverride` | `TerminalSettingsOverride?` | Per-instance settings overrides (see [Settings Override](#settings-override)) |
| `hyperlinkRegistry` | `HyperlinkRegistry` | Custom hyperlink patterns for this instance (see [Custom Hyperlink Patterns](#custom-hyperlink-patterns)) |
| `modifier` | `Modifier` | Compose modifier |

### EmbeddableTerminalState

State holder for programmatic terminal control.

```kotlin
val state = rememberEmbeddableTerminalState(
    autoDispose: Boolean = true  // Auto-dispose when leaving composition
)

// Write to terminal
state.write("ls -la\n")

// Send control signals (useful for interrupting processes)
state.sendCtrlC()  // Send Ctrl+C (interrupt)
state.sendCtrlD()  // Send Ctrl+D (EOF)
state.sendCtrlZ()  // Send Ctrl+Z (suspend)

// Send raw bytes
state.sendInput(byteArrayOf(0x03))  // Same as sendCtrlC()

// Manual dispose (when autoDispose = false)
state.dispose()
```

#### Session Persistence

By default, the terminal process is disposed when the composable leaves composition. For persistent sessions:

```kotlin
// Terminal survives navigation/visibility changes
val persistentState = rememberEmbeddableTerminalState(autoDispose = false)

if (showTerminal) {
    EmbeddableTerminal(state = persistentState)
}
// Process keeps running when hidden!

// Clean up when truly done
DisposableEffect(Unit) {
    onDispose { persistentState.dispose() }
}
```

## Initial Command

The `initialCommand` parameter lets you run a command automatically when the terminal is ready. This is useful for:
- Setting up the environment
- Running a welcome message or status check
- Starting an application or script

```kotlin
EmbeddableTerminal(
    initialCommand = "echo 'Welcome!' && ls -la"
)
```

### Timing and Shell Integration

BossTerm uses OSC 133 shell integration for proper command timing:

1. **With OSC 133**: If your shell has OSC 133 configured, BossTerm waits for the prompt-ready signal (OSC 133;A) before sending the command. This ensures the shell is fully ready.

2. **Without OSC 133**: Falls back to a configurable delay (default 500ms, adjustable via `settings.initialCommandDelayMs`).

For best results, configure OSC 133 in your shell. See the main [README](../README.md#shell-integration) for setup instructions.

### Alternative: Using onReady Callback

For more control over timing, you can use the `onReady` callback with `state.write()`:

```kotlin
val state = rememberEmbeddableTerminalState()

EmbeddableTerminal(
    state = state,
    onReady = {
        // Runs when terminal is connected
        state.write("echo 'Terminal ready!'\n")
    }
)
```

The `initialCommand` parameter is preferred because it handles shell readiness timing automatically.

## Initial Command Completion

The `onInitialCommandComplete` callback fires when the initial command finishes executing. This is useful for:
- Triggering the next step in a workflow after setup completes
- Updating UI status based on command success/failure
- Error handling when initial setup fails

```kotlin
EmbeddableTerminal(
    initialCommand = "npm install && npm run build",
    onInitialCommandComplete = { success, exitCode ->
        if (success) {
            println("Setup complete!")
            // Proceed with next step
        } else {
            println("Setup failed with exit code: $exitCode")
            // Show error UI
        }
    }
)
```

### Callback Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `success` | `Boolean` | `true` if exit code is 0, `false` otherwise |
| `exitCode` | `Int` | The command's exit code (0 = success, non-zero = failure) |

### Requirements

This callback requires **OSC 133 shell integration** to detect command completion. Without shell integration, the callback will not fire.

To enable OSC 133 in your shell, see [Shell Integration](../README.md#shell-integration).

### Example: Build Status Indicator

```kotlin
@Composable
fun TerminalWithBuildStatus() {
    var buildStatus by remember { mutableStateOf<String?>(null) }

    Column {
        // Status indicator
        buildStatus?.let { status ->
            Text(
                text = status,
                color = if (status.contains("success")) Color.Green else Color.Red
            )
        }

        EmbeddableTerminal(
            initialCommand = "./gradlew build",
            onInitialCommandComplete = { success, exitCode ->
                buildStatus = if (success) {
                    "Build succeeded!"
                } else {
                    "Build failed (exit code: $exitCode)"
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}
```

## Context Menu Open Callback

The `onContextMenuOpen` callback fires immediately before the context menu is displayed. This is useful for:
- Refreshing dynamic menu item state (e.g., checking if a tool is installed)
- Analytics tracking
- Updating UI state based on menu visibility

```kotlin
EmbeddableTerminal(
    onContextMenuOpen = {
        // Refresh menu item state before display
        refreshAIAssistantStatus()
    },
    contextMenuItems = listOf(
        ContextMenuItem(
            id = "ai_assist",
            label = if (isAIInstalled) "Ask AI" else "Install AI Assistant",
            action = { /* ... */ }
        )
    )
)
```

### Use Cases

- **Dynamic menu items**: Check installation status, connection state, or permissions before showing menu
- **Analytics**: Track how often users open the context menu
- **State refresh**: Update menu item labels or enabled states based on current context

## Dynamic Context Menu Items

The `contextMenuItemsProvider` lambda solves a timing problem with dynamic context menus. When you use `contextMenuItems` with `onContextMenuOpenAsync`, the menu shows stale data because Compose captures the items at composition time, before the async callback runs.

### The Problem

```kotlin
// This doesn't work as expected!
var installStatus by remember { mutableStateOf("checking...") }

EmbeddableTerminal(
    onContextMenuOpenAsync = {
        installStatus = checkInstallation()  // Updates state
    },
    contextMenuItems = listOf(
        ContextMenuItem(
            id = "status",
            label = installStatus,  // Captured at composition time!
            action = { }
        )
    )
)
// Menu shows old value - requires TWO right-clicks to see fresh data
```

### The Solution: contextMenuItemsProvider

Use `contextMenuItemsProvider` to get fresh items **after** the async callback completes:

```kotlin
var installStatus by remember { mutableStateOf("checking...") }

EmbeddableTerminal(
    onContextMenuOpenAsync = {
        installStatus = checkInstallation()  // Updates state
    },
    contextMenuItemsProvider = {
        // Called AFTER onContextMenuOpenAsync completes, BEFORE menu shows
        listOf(
            ContextMenuItem(
                id = "status",
                label = installStatus,  // Always fresh!
                action = { }
            )
        )
    }
)
// Menu always shows current value on first right-click
```

### Execution Order

1. User right-clicks
2. `onContextMenuOpenAsync` runs (if provided), menu waits
3. `contextMenuItemsProvider` called to get fresh items
4. Menu displays with up-to-date items

### Fallback Behavior

| `contextMenuItemsProvider` | `contextMenuItems` | Result |
|---------------------------|-------------------|--------|
| `null` | `[...]` | Uses static `contextMenuItems` |
| `{ [...] }` | `[...]` | Uses `contextMenuItemsProvider` result |
| `{ [...] }` | `emptyList()` | Uses `contextMenuItemsProvider` result |

### Real-World Example: AI Assistant Status

```kotlin
@Composable
fun TerminalWithAIMenu() {
    val aiDetector = remember { AIAssistantDetector() }
    val installStatuses = aiDetector.installationStatuses.collectAsState()

    EmbeddableTerminal(
        // Refresh installation status before menu shows
        onContextMenuOpenAsync = {
            aiDetector.refreshIfStale()
        },
        // Build menu items with fresh data
        contextMenuItemsProvider = {
            buildAIContextMenuItems(installStatuses.value)
        }
    )
}

fun buildAIContextMenuItems(statuses: Map<String, Boolean>): List<ContextMenuElement> {
    return listOf(
        ContextMenuSection(id = "ai_section", label = "AI Assistants"),
        ContextMenuItem(
            id = "claude",
            label = if (statuses["claude"] == true) "Ask Claude" else "Install Claude",
            action = { /* ... */ }
        ),
        ContextMenuItem(
            id = "copilot",
            label = if (statuses["copilot"] == true) "Ask Copilot" else "Install Copilot",
            action = { /* ... */ }
        )
    )
}
```

## Custom Context Menu

Add custom items to the right-click context menu using the `contextMenuItems` parameter.

### Context Menu Elements

BossTerm provides three types of context menu elements:

```kotlin
import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu
```

#### ContextMenuItem

A clickable menu item with an action.

```kotlin
ContextMenuItem(
    id: String,           // Unique identifier
    label: String,        // Display text
    enabled: Boolean = true,  // Whether item is clickable
    action: () -> Unit    // Click handler
)
```

#### ContextMenuSection

A visual separator, optionally with a label.

```kotlin
ContextMenuSection(
    id: String,           // Unique identifier
    label: String? = null // Optional section header
)
```

#### ContextMenuSubmenu

A nested menu containing other elements.

```kotlin
ContextMenuSubmenu(
    id: String,           // Unique identifier
    label: String,        // Submenu display text
    items: List<ContextMenuElement>  // Nested items
)
```

### Example: Custom Context Menu

```kotlin
@Composable
fun TerminalWithCustomMenu() {
    val terminalState = rememberEmbeddableTerminalState()

    EmbeddableTerminal(
        state = terminalState,
        contextMenuItems = listOf(
            // Section with label
            ContextMenuSection(
                id = "commands_section",
                label = "Quick Commands"
            ),

            // Simple menu items
            ContextMenuItem(
                id = "run_pwd",
                label = "Print Working Directory",
                action = { terminalState.write("pwd\n") }
            ),
            ContextMenuItem(
                id = "run_ls",
                label = "List Files",
                action = { terminalState.write("ls -la\n") }
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

            // Separator without label
            ContextMenuSection(id = "tools_section"),

            // Disabled item example
            ContextMenuItem(
                id = "coming_soon",
                label = "Coming Soon...",
                enabled = false,
                action = { }
            )
        ),
        modifier = Modifier.fillMaxSize()
    )
}
```

### Menu Structure

Custom items appear below the built-in items (Copy, Paste, Clear, Select All). The menu structure:

```
┌─────────────────────────┐
│ Copy              ⌘C    │
│ Paste             ⌘V    │
│ Clear                   │
│ Select All        ⌘A    │
├─────────────────────────┤
│ ── Quick Commands ──    │  ← ContextMenuSection with label
│ Print Working Directory │  ← ContextMenuItem
│ List Files              │
│ Git Commands        ▸   │  ← ContextMenuSubmenu
├─────────────────────────┤  ← ContextMenuSection without label
│ Coming Soon...          │  ← Disabled ContextMenuItem
└─────────────────────────┘
```

## Complete Example

See the [embedded-example](../embedded-example) module for a full working example with:
- Parent application with sidebar and toolbar
- Embedded terminal with custom context menu
- Focus management between parent UI and terminal

Run the example:

```bash
./gradlew :embedded-example:run
```

## Focus Management

When embedding BossTerm in a larger application, focus is automatically managed:

- **Context menu**: Focus returns to terminal after menu dismissal
- **Parent UI clicks**: Click in terminal area to restore focus
- **Keyboard shortcuts**: Terminal captures keyboard when focused

> **Important**: Parent containers must NOT compete for focus with the terminal. Avoid using `.focusable()` or `.clickable { requestFocus() }` on containers that wrap `EmbeddableTerminal`. See the [Troubleshooting Guide](troubleshooting.md#focus-management-issues) for detailed examples and solutions.

## Settings

You can customize terminal appearance and behavior:

```kotlin
EmbeddableTerminal(
    settings = TerminalSettings(
        fontSize = 14,
        fontName = "JetBrains Mono",
        copyOnSelect = true,
        scrollbackLines = 10000
    )
)
```

Or load from a JSON file:

```kotlin
EmbeddableTerminal(
    settingsPath = "/path/to/custom-settings.json"
)
```

See the main [README](../README.md#configuration) for available settings.

## Settings Override

For per-instance customization without replacing all settings, use `settingsOverride`. This allows you to override specific settings while inheriting others from the resolved settings (via `settings`, `settingsPath`, or defaults).

```kotlin
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride

EmbeddableTerminal(
    settingsOverride = TerminalSettingsOverride(
        fontSize = 12f,           // Override font size
        showScrollbar = false     // Hide scrollbar
    )
    // All other settings come from defaults or settingsPath
)
```

### When to Use

| Approach | Use Case |
|----------|----------|
| `settings` | Full control over all settings |
| `settingsPath` | Load settings from a config file |
| `settingsOverride` | Override specific settings per-instance |

### Combining with Other Settings

`settingsOverride` is applied last, after resolving from `settings`/`settingsPath`/defaults:

```kotlin
EmbeddableTerminal(
    // Load base settings from file
    settingsPath = "~/.myapp/terminal-settings.json",
    // Override specific settings for this instance
    settingsOverride = TerminalSettingsOverride(
        fontSize = 10f  // Smaller font for sidebar terminal
    )
)
```

### Common Override Examples

```kotlin
// Compact sidebar terminal
TerminalSettingsOverride(
    fontSize = 11f,
    showScrollbar = false,
    lineSpacing = 1.0f
)

// High-contrast terminal
TerminalSettingsOverride(
    defaultForeground = "0xFFFFFFFF",
    defaultBackground = "0xFF000000"
)

// Performance-optimized terminal
TerminalSettingsOverride(
    maxRefreshRate = 30,
    bufferMaxLines = 5000
)
```

## Custom Link Handling

By default, clicking links in the terminal (with Ctrl/Cmd+Click or via the context menu "Open Link") opens them in the system's default browser. You can intercept these clicks with the `onLinkClick` callback, which receives rich metadata about the link.

### HyperlinkInfo

The callback receives a `HyperlinkInfo` object with detailed link metadata:

```kotlin
data class HyperlinkInfo(
    val url: String,           // The resolved URL or file path
    val type: HyperlinkType,   // HTTP, FILE, FOLDER, EMAIL, FTP, or CUSTOM
    val patternId: String,     // Pattern that matched (e.g., "builtin:http", "jira")
    val matchedText: String,   // Original text that was matched
    val isFile: Boolean,       // True if path points to existing file
    val isFolder: Boolean,     // True if path points to existing directory
    val scheme: String?,       // URL scheme (http, https, file, mailto, etc.)
    val isBuiltin: Boolean     // True if matched by built-in pattern
)

enum class HyperlinkType {
    HTTP,    // http:// or https:// URLs
    FILE,    // File paths (validated as existing file)
    FOLDER,  // Directory paths (validated as existing directory)
    EMAIL,   // mailto: links
    FTP,     // ftp:// or ftps:// URLs
    CUSTOM   // User-defined patterns
}
```

### Basic Usage

Return `true` if you handled the link, `false` to use default behavior:

```kotlin
EmbeddableTerminal(
    onLinkClick = { info ->
        // Custom handling - e.g., open in an in-app browser
        myInAppBrowser.openUrl(info.url)
        true  // We handled it
    }
)
```

### Selective Handling with Default Fallback

Handle only specific link types and let others use the default behavior:

```kotlin
EmbeddableTerminal(
    onLinkClick = { info ->
        when (info.type) {
            HyperlinkType.FILE -> {
                openInEditor(info.url)
                true  // Handled
            }
            HyperlinkType.FOLDER -> {
                openInFileBrowser(info.url)
                true  // Handled
            }
            else -> false  // Use default behavior (open in browser)
        }
    }
)
```

### Use Cases

- **File handling**: Open files in your app's editor instead of system default
- **Folder handling**: Open directories in your app's file browser
- **In-app browser**: Open URLs in a browser tab within your application
- **Custom patterns**: Handle JIRA tickets, PR links, or other custom patterns
- **Analytics**: Track link clicks while still using default behavior

### Behavior

| `onLinkClick` | Return Value | Result |
|---------------|--------------|--------|
| `null` (default) | N/A | Opens in system browser/finder |
| Provided | `true` | Your callback handles it |
| Provided | `false` | Falls back to system browser/finder |

### Example: Smart Link Handling

```kotlin
@Composable
fun TerminalWithSmartLinks() {
    var browserUrl by remember { mutableStateOf<String?>(null) }

    Column {
        EmbeddableTerminal(
            onLinkClick = { info ->
                when {
                    // Handle JIRA tickets specially
                    info.patternId == "jira" -> {
                        openJiraTicket(info.matchedText)
                        true
                    }
                    // Open files in our editor
                    info.type == HyperlinkType.FILE -> {
                        openInEditor(info.url)
                        true
                    }
                    // Show folders in our file browser
                    info.type == HyperlinkType.FOLDER -> {
                        openInFileBrowser(info.url)
                        true
                    }
                    // HTTP links go to our in-app browser
                    info.type == HyperlinkType.HTTP -> {
                        browserUrl = info.url
                        true
                    }
                    // Everything else uses default behavior
                    else -> false
                }
            },
            modifier = Modifier.weight(1f)
        )

        // In-app browser (shown when URL is set)
        browserUrl?.let { url ->
            InAppBrowser(
                url = url,
                onClose = { browserUrl = null },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
```

## Custom Hyperlink Patterns

Add custom hyperlink patterns (e.g., JIRA tickets, GitHub issues) using `HyperlinkRegistry`:

```kotlin
import ai.rever.bossterm.compose.hyperlinks.HyperlinkRegistry
import ai.rever.bossterm.compose.hyperlinks.HyperlinkPattern

// Create a custom registry with additional patterns
val customRegistry = HyperlinkRegistry().apply {
    // Add JIRA ticket pattern (e.g., PROJ-123)
    register(HyperlinkPattern(
        id = "jira",
        regex = Regex("""\b([A-Z]+-\d+)\b"""),
        priority = 10,
        urlTransformer = { match -> "https://jira.company.com/browse/$match" }
    ))

    // Add GitHub issue pattern (e.g., #123)
    register(HyperlinkPattern(
        id = "github-issue",
        regex = Regex("""#(\d+)\b"""),
        priority = 5,
        urlTransformer = { match -> "https://github.com/org/repo/issues/${match.removePrefix("#")}" }
    ))
}

EmbeddableTerminal(
    hyperlinkRegistry = customRegistry,
    onLinkClick = { info ->
        when (info.patternId) {
            "jira" -> {
                openJiraInApp(info.matchedText)
                true
            }
            else -> false  // Default behavior
        }
    }
)
```

### HyperlinkPattern Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | `String` | Unique pattern identifier (returned in `HyperlinkInfo.patternId`) |
| `regex` | `Regex` | Pattern to match in terminal output |
| `priority` | `Int` | Higher priority patterns are checked first (default: 0) |
| `urlTransformer` | `(String) -> String` | Transform matched text to URL |
| `quickCheck` | `((String) -> Boolean)?` | Optional fast pre-check before regex (for performance) |

## Programmatic Input

Send text and control signals to the terminal programmatically using `EmbeddableTerminalState`.

### Sending Text

```kotlin
val state = rememberEmbeddableTerminalState()

// Send a command (include \n for enter)
state.write("ls -la\n")

// Send multiple commands
state.write("cd /tmp && ls\n")
```

### Sending Control Signals

Control signals allow you to interact with running processes:

```kotlin
val state = rememberEmbeddableTerminalState()

// Interrupt running process (like pressing Ctrl+C)
state.sendCtrlC()

// Send EOF to close stdin (like pressing Ctrl+D)
state.sendCtrlD()

// Suspend foreground process (like pressing Ctrl+Z)
state.sendCtrlZ()
```

### Sending Raw Bytes

For advanced use cases, send arbitrary bytes:

```kotlin
val state = rememberEmbeddableTerminalState()

// Send raw bytes
state.sendInput(byteArrayOf(0x03))  // Same as sendCtrlC()
state.sendInput(byteArrayOf(0x04))  // Same as sendCtrlD()
state.sendInput(byteArrayOf(0x1A))  // Same as sendCtrlZ()

// Send escape sequence
state.sendInput("\u001b[A".toByteArray())  // Up arrow
```

### FIFO Ordering

All input methods (`write()`, `sendInput()`, `sendCtrlC()`, etc.) share the same internal queue, guaranteeing FIFO (first-in-first-out) order:

```kotlin
state.write("sleep 10\n")  // Queued first
state.sendCtrlC()          // Queued second, arrives after sleep starts
```

### Async Behavior

All input methods are **asynchronous** - they queue the input and return immediately. This ensures the UI remains responsive even during high-volume input.

```kotlin
// Returns immediately after queuing
state.sendCtrlC()

// Input is processed by a background coroutine
// No way to await completion (fire-and-forget)
```

### Example: Run and Stop Button

```kotlin
@Composable
fun TerminalWithControls() {
    val state = rememberEmbeddableTerminalState()

    Column {
        Row {
            Button(onClick = { state.write("sleep 30\n") }) {
                Text("Run Sleep")
            }
            Button(onClick = { state.sendCtrlC() }) {
                Text("Stop (Ctrl+C)")
            }
        }

        EmbeddableTerminal(
            state = state,
            modifier = Modifier.weight(1f)
        )
    }
}
```

## AI Assistant & VCS Tool Installation API

BossTerm includes built-in support for detecting and installing AI coding assistants (Claude Code, Codex, Gemini CLI, OpenCode) and VCS tools (Git, GitHub CLI). This API provides programmatic access to the installation features.

### Available Tools

**AI Assistants:**

| ID | Name | Description |
|----|------|-------------|
| `claude-code` | Claude Code | Anthropic's AI coding assistant |
| `codex` | Codex CLI | OpenAI's coding assistant |
| `gemini-cli` | Gemini CLI | Google's AI assistant |
| `opencode` | OpenCode | Open-source AI coding assistant |

**VCS Tools:**

| ID | Name | Description |
|----|------|-------------|
| `git` | Git | Distributed version control system |
| `gh` | GitHub CLI | GitHub's official CLI |

### API Methods

```kotlin
val state = rememberEmbeddableTerminalState()

// === AI Assistants ===

// List all available AI assistant IDs
val assistants = state.getAvailableAIAssistants()
// Returns: ["claude-code", "codex", "gemini-cli", "opencode"]

// Get assistant definition by ID
val claude = state.getAIAssistant("claude-code")
// Returns: AIAssistantDefinition with displayName, command, website, etc.

// Check if an assistant is installed (suspend function)
val isInstalled = state.isAIAssistantInstalled("claude-code")
// Returns: true if installed, false otherwise

// Trigger installation dialog
state.installAIAssistant("claude-code")
// Opens installation dialog with terminal output

// Use npm installation method instead of script
state.installAIAssistant("claude-code", useNpm = true)

// Cancel pending installation
state.cancelAIInstallation()

// === VCS Tools ===

// Check if Git is installed (uses same API as AI assistants)
val gitInstalled = state.isAIAssistantInstalled("git")
val ghInstalled = state.isAIAssistantInstalled("gh")

// Install Git
state.installGit()
// Opens installation dialog for Git

// Install GitHub CLI
state.installGitHubCLI()
// Opens installation dialog for GitHub CLI
```

### Installation Methods

Most AI assistants support two installation methods:

1. **Script Installation** (default): Uses the assistant's official install script
2. **npm Installation**: Uses npm global install (fallback option)

When script installation fails, the dialog automatically offers the npm option as a fallback.

### Example: Custom AI Menu

```kotlin
@Composable
fun TerminalWithAIMenu() {
    val state = rememberEmbeddableTerminalState()
    var claudeInstalled by remember { mutableStateOf(false) }

    // Check installation status
    LaunchedEffect(Unit) {
        claudeInstalled = state.isAIAssistantInstalled("claude-code")
    }

    Column {
        Row {
            if (claudeInstalled) {
                Button(onClick = { state.write("claude\n") }) {
                    Text("Launch Claude")
                }
            } else {
                Button(onClick = { state.installAIAssistant("claude-code") }) {
                    Text("Install Claude Code")
                }
            }
        }

        EmbeddableTerminal(
            state = state,
            modifier = Modifier.weight(1f)
        )
    }
}
```

### Built-in Context Menu Integration

When `aiAssistantsEnabled` is `true` in settings (default), the context menu automatically includes:
- **AI Assistants** submenu: Installed assistants show direct launch option; uninstalled show Install options
- **Version Control** submenu: Git commands, branch switching, and VCS tool installation

**Note**: The `aiAssistantsEnabled` setting controls both AI assistants and VCS tools. There is no separate VCS-specific setting; they share the same enable/disable flag.

The detection runs asynchronously when the context menu opens, ensuring up-to-date installation status.

### Command Interception (OSC 133 Required)

When OSC 133 shell integration is configured, BossTerm can detect when you type an AI assistant or VCS tool command (like `claude`, `codex`, `gemini`, `opencode`, `git`, `gh`) and show an install prompt **before** the shell tries to execute it.

**VCS Tools Note**: Command interception works identically for VCS tools (git, gh) as it does for AI assistants. They use the same detection and dialog mechanism.

**Requirements**:
- OSC 133 shell integration must be configured in your shell (`.bashrc` / `.zshrc`)
- `aiAssistantsEnabled` must be `true` in settings (default)

**How it works**:
1. Shell emits `OSC 133;A` when prompt is displayed (tells terminal you're at shell prompt)
2. Terminal tracks keystrokes as you type
3. When Enter is pressed, checks if command matches an AI assistant
4. If assistant is not installed, intercepts Enter and shows install dialog
5. If you dismiss the dialog, the command is NOT sent to shell

**Shell Setup** (add to `~/.bashrc` or `~/.zshrc`):
```bash
# For Bash
__prompt_command() {
    local exit_code=$?
    echo -ne "\033]133;D;${exit_code}\007"
    echo -ne "\033]133;A\007"
}
PROMPT_COMMAND='__prompt_command'
trap 'echo -ne "\033]133;B\007"' DEBUG

# For Zsh
precmd() {
    local exit_code=$?
    print -Pn "\e]133;D;${exit_code}\a"
    print -Pn "\e]133;A\a"
}
preexec() { print -Pn "\e]133;B\a" }
```

**Note**: Without OSC 133 shell integration, the command interception feature is automatically disabled (graceful fallback).

## Migration Guide

### v1.0.65+ Breaking Changes

#### `onLinkClick` Signature Change

The `onLinkClick` callback now returns `Boolean` to support fallback behavior:

```kotlin
// Before (v1.0.64 and earlier)
onLinkClick: ((HyperlinkInfo) -> Unit)? = null

// After (v1.0.65+)
onLinkClick: ((HyperlinkInfo) -> Boolean)? = null
```

**Migration:**

```kotlin
// Before
EmbeddableTerminal(
    onLinkClick = { info ->
        openCustomHandler(info.url)
    }
)

// After - return true if handled, false for default behavior
EmbeddableTerminal(
    onLinkClick = { info ->
        openCustomHandler(info.url)
        true  // Handled - skip default behavior
    }
)
```

**Why this change?** Previously, providing `onLinkClick` completely replaced default behavior. If your callback didn't handle all link types, unhandled links did nothing. Now you can return `false` to fall back to default behavior (open in browser/finder):

```kotlin
EmbeddableTerminal(
    onLinkClick = { info ->
        when (info.type) {
            HyperlinkType.FILE -> {
                openInEditor(info.url)
                true  // Handled
            }
            else -> false  // Not handled - use default behavior
        }
    }
)
```
