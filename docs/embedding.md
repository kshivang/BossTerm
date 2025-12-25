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
    onLinkClick: ((String) -> Unit)? = null,
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
| `onOutput` | `(String) -> Unit` | Callback for terminal output |
| `onTitleChange` | `(String) -> Unit` | Callback when terminal title changes |
| `onExit` | `(Int) -> Unit` | Callback when shell process exits |
| `onReady` | `() -> Unit` | Callback when terminal is ready |
| `contextMenuItems` | `List<ContextMenuElement>` | Custom context menu items |
| `onLinkClick` | `(String) -> Unit` | Custom link click handler (see [Custom Link Handling](#custom-link-handling)) |
| `modifier` | `Modifier` | Compose modifier |

### EmbeddableTerminalState

State holder for programmatic terminal control.

```kotlin
val state = rememberEmbeddableTerminalState(
    autoDispose: Boolean = true  // Auto-dispose when leaving composition
)

// Write to terminal
state.write("ls -la\n")

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

## Custom Link Handling

By default, clicking links in the terminal (with Ctrl/Cmd+Click or via the context menu "Open Link") opens them in the system's default browser. You can intercept these clicks with the `onLinkClick` callback:

```kotlin
EmbeddableTerminal(
    onLinkClick = { url ->
        // Custom handling - e.g., open in an in-app browser
        myInAppBrowser.openUrl(url)
    }
)
```

### Use Cases

- **In-app browser**: Open URLs in a browser tab within your application
- **URL filtering**: Validate or sanitize URLs before opening
- **Custom protocols**: Handle custom URL schemes (e.g., `myapp://...`)
- **Logging**: Track which links users click

### Behavior

| `onLinkClick` | Ctrl/Cmd+Click | Context Menu "Open Link" |
|---------------|----------------|--------------------------|
| `null` (default) | Opens in system browser | Opens in system browser |
| Provided | Calls your callback | Calls your callback |

### Example: In-App Browser Integration

```kotlin
@Composable
fun TerminalWithInAppBrowser() {
    var browserUrl by remember { mutableStateOf<String?>(null) }

    Column {
        // Terminal with custom link handling
        EmbeddableTerminal(
            onLinkClick = { url ->
                browserUrl = url  // Open in our browser component
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
