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
    onOutput: ((String) -> Unit)? = null,
    onTitleChange: ((String) -> Unit)? = null,
    onExit: ((Int) -> Unit)? = null,
    onReady: (() -> Unit)? = null,
    contextMenuItems: List<ContextMenuElement> = emptyList(),
    modifier: Modifier = Modifier
)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `state` | `EmbeddableTerminalState` | Terminal state for programmatic control |
| `settings` | `TerminalSettings?` | Custom terminal settings (overrides settingsPath) |
| `settingsPath` | `String?` | Path to settings JSON file |
| `onOutput` | `(String) -> Unit` | Callback for terminal output |
| `onTitleChange` | `(String) -> Unit` | Callback when terminal title changes |
| `onExit` | `(Int) -> Unit` | Callback when shell process exits |
| `onReady` | `() -> Unit` | Callback when terminal is ready |
| `contextMenuItems` | `List<ContextMenuElement>` | Custom context menu items |
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
