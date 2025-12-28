# Tabbed Terminal

This guide covers how to embed BossTerm's full-featured tabbed terminal component in your Compose Desktop application.

## Quick Start

```kotlin
import ai.rever.bossterm.compose.TabbedTerminal

@Composable
fun MyApp() {
    TabbedTerminal(
        onExit = { exitApplication() },
        modifier = Modifier.fillMaxSize()
    )
}
```

## Installation

Add BossTerm dependencies to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.risaboss:bossterm-core:<version>")
    implementation("com.risaboss:bossterm-compose:<version>")
}
```

## Features

TabbedTerminal provides a complete terminal experience:

- **Multiple Tabs** - Create, switch, and close tabs
- **Tab Bar** - Visual tab bar (auto-hides with single tab)
- **Split Panes** - Horizontal and vertical splits
- **State Persistence** - Preserve sessions across recomposition with `TabbedTerminalState`
- **Working Directory Inheritance** - New tabs/splits inherit CWD
- **Command Notifications** - System notifications for long commands
- **Window Focus Tracking** - Overlay when window unfocused
- **Menu Integration** - Wire up application menu bar
- **Keyboard Shortcuts** - Full keyboard navigation

## API Reference

### TabbedTerminal

```kotlin
@Composable
fun TabbedTerminal(
    state: TabbedTerminalState? = null,
    onExit: () -> Unit,
    onWindowTitleChange: (String) -> Unit = {},
    onNewWindow: () -> Unit = {},
    onShowSettings: () -> Unit = {},
    menuActions: MenuActions? = null,
    isWindowFocused: () -> Boolean = { true },
    initialCommand: String? = null,
    onLinkClick: ((String) -> Unit)? = null,
    contextMenuItems: List<ContextMenuElement> = emptyList(),
    settingsOverride: TerminalSettingsOverride? = null,
    modifier: Modifier = Modifier
)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `state` | `TabbedTerminalState?` | External state holder for persistence across recomposition |
| `onExit` | `() -> Unit` | **Required.** Called when last tab closes |
| `onWindowTitleChange` | `(String) -> Unit` | Called when active tab's title changes |
| `onNewWindow` | `() -> Unit` | Called when user requests new window (Cmd/Ctrl+N) |
| `onShowSettings` | `() -> Unit` | Called when user opens settings |
| `menuActions` | `MenuActions?` | Callbacks for menu bar integration |
| `isWindowFocused` | `() -> Boolean` | Returns window focus state (for notifications) |
| `initialCommand` | `String?` | Command to run in first tab after startup |
| `onLinkClick` | `(String) -> Unit` | Custom link click handler (see [Custom Link Handling](#custom-link-handling)) |
| `contextMenuItems` | `List<ContextMenuElement>` | Custom context menu items (see [Custom Context Menu](#custom-context-menu)) |
| `settingsOverride` | `TerminalSettingsOverride?` | Per-instance settings overrides (see [Settings Override](#settings-override)) |
| `modifier` | `Modifier` | Compose modifier |

### MenuActions

Wire up your application's menu bar to terminal actions:

```kotlin
import ai.rever.bossterm.compose.menu.MenuActions

val menuActions = remember { MenuActions() }

// MenuActions properties (set by TabbedTerminal):
// - onNewTab: () -> Unit
// - onCloseTab: () -> Unit
// - onNextTab: () -> Unit
// - onPreviousTab: () -> Unit
// - onSplitVertical: () -> Unit
// - onSplitHorizontal: () -> Unit
// - onClosePane: () -> Unit
```

### TabbedTerminalState

External state holder that enables terminal sessions to survive recomposition. Use this when embedding `TabbedTerminal` within another tab system or navigation framework.

```kotlin
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.rememberTabbedTerminalState

// Create state that survives navigation
val terminalState = rememberTabbedTerminalState(autoDispose = false)

// Use in composable that may unmount
when (selectedView) {
    "terminal" -> TabbedTerminal(state = terminalState, onExit = { ... })
    "editor" -> EditorPane()  // Terminal sessions preserved!
}

// Manual cleanup when truly done
DisposableEffect(Unit) { onDispose { terminalState.dispose() } }
```

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `tabs` | `List<TerminalTab>` | All open terminal tabs |
| `tabCount` | `Int` | Number of open tabs |
| `activeTabIndex` | `Int` | Index of active tab (0-based) |
| `activeTab` | `TerminalTab?` | Currently active tab |
| `activeTabId` | `String?` | Stable ID of active tab (survives reordering) |
| `isInitialized` | `Boolean` | Whether state has been initialized |
| `isDisposed` | `Boolean` | Whether state has been disposed |

**Methods:**

| Method | Description |
|--------|-------------|
| `createTab(workingDir?, initialCommand?, tabId?): String?` | Create tab, optionally with stable ID. Returns the tab ID |
| `getTabById(tabId): TerminalTab?` | Find tab by stable ID |
| `closeTab(index)` | Close tab at index |
| `closeTab(tabId): Boolean` | Close tab by stable ID |
| `closeActiveTab()` | Close the active tab |
| `switchToTab(index)` | Switch to tab at index |
| `switchToTab(tabId): Boolean` | Switch to tab by stable ID |
| `nextTab()` | Switch to next tab (wraps) |
| `previousTab()` | Switch to previous tab (wraps) |
| `getActiveWorkingDirectory()` | Get active tab's working directory (OSC 7) |
| `write(text)` | Send text to active tab |
| `write(text, tabIndex)` | Send text to specific tab by index |
| `write(text, tabId): Boolean` | Send text to specific tab by stable ID |
| `sendInput(bytes)` | Send raw bytes to active tab |
| `sendInput(bytes, tabIndex)` | Send raw bytes to specific tab by index |
| `sendInput(bytes, tabId): Boolean` | Send raw bytes to specific tab by stable ID |
| `sendCtrlC()` / `sendCtrlC(tabIndex)` / `sendCtrlC(tabId)` | Send Ctrl+C (interrupt) |
| `sendCtrlD()` / `sendCtrlD(tabIndex)` / `sendCtrlD(tabId)` | Send Ctrl+D (EOF) |
| `sendCtrlZ()` / `sendCtrlZ(tabIndex)` / `sendCtrlZ(tabId)` | Send Ctrl+Z (suspend) |
| `addSessionListener(listener)` | Add session lifecycle listener |
| `removeSessionListener(listener)` | Remove session listener |
| `dispose()` | Dispose all sessions and cleanup |

## Keyboard Shortcuts

TabbedTerminal includes built-in keyboard shortcuts:

| Shortcut | Action |
|----------|--------|
| Ctrl/Cmd+T | New tab |
| Ctrl/Cmd+W | Close tab/pane |
| Ctrl+Tab | Next tab |
| Ctrl+Shift+Tab | Previous tab |
| Ctrl/Cmd+1-9 | Jump to tab |
| Ctrl/Cmd+D | Split vertically |
| Ctrl/Cmd+Shift+D | Split horizontally |
| Ctrl/Cmd+Option+Arrow | Navigate between panes |
| Ctrl/Cmd+F | Search |
| Ctrl/Cmd+, | Settings (triggers onShowSettings) |

## Window Focus Tracking

For command completion notifications to work, you need to track window focus:

```kotlin
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

@Composable
fun MyWindow() {
    var isWindowFocused by remember { mutableStateOf(true) }

    Window(onCloseRequest = { /* ... */ }) {
        // Track focus via AWT
        LaunchedEffect(Unit) {
            val focusListener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    isWindowFocused = true
                }
                override fun windowLostFocus(e: WindowEvent?) {
                    isWindowFocused = false
                }
            }
            window.addWindowFocusListener(focusListener)
        }

        TabbedTerminal(
            onExit = { /* ... */ },
            isWindowFocused = { isWindowFocused }
        )
    }
}
```

When enabled and properly configured (see [Shell Integration](../README.md#shell-integration)), users receive system notifications when:
- A command takes longer than 5 seconds (configurable)
- The window is not focused when the command completes

## Menu Bar Integration

Wire up your menu bar to terminal actions:

```kotlin
@Composable
fun MyWindow() {
    val menuActions = remember { MenuActions() }

    Window(onCloseRequest = { /* ... */ }) {
        MenuBar {
            Menu("File") {
                Item("New Tab", onClick = { menuActions.onNewTab?.invoke() })
                Item("New Window", onClick = { /* your window creation */ })
                Separator()
                Item("Close Tab", onClick = { menuActions.onCloseTab?.invoke() })
            }
            Menu("View") {
                Item("Split Vertically", onClick = { menuActions.onSplitVertical?.invoke() })
                Item("Split Horizontally", onClick = { menuActions.onSplitHorizontal?.invoke() })
            }
            Menu("Window") {
                Item("Next Tab", onClick = { menuActions.onNextTab?.invoke() })
                Item("Previous Tab", onClick = { menuActions.onPreviousTab?.invoke() })
            }
        }

        TabbedTerminal(
            onExit = { /* ... */ },
            menuActions = menuActions
        )
    }
}
```

## Multiple Windows

Support multiple terminal windows in your application:

```kotlin
fun main() = application {
    val windows = remember { mutableStateListOf(WindowId()) }

    fun createWindow() {
        windows.add(WindowId())
    }

    fun closeWindow(index: Int) {
        if (windows.size > 1) {
            windows.removeAt(index)
        } else {
            exitApplication()
        }
    }

    windows.forEachIndexed { index, windowId ->
        Window(
            onCloseRequest = { closeWindow(index) },
            title = "Terminal ${index + 1}"
        ) {
            TabbedTerminal(
                onExit = { closeWindow(index) },
                onNewWindow = { createWindow() }
            )
        }
    }
}

private class WindowId {
    val id = System.currentTimeMillis()
}
```

## Window Title Updates

Update your window title bar from the active terminal tab:

```kotlin
@Composable
fun MyWindow() {
    var windowTitle by remember { mutableStateOf("My App") }

    Window(
        onCloseRequest = { /* ... */ },
        title = windowTitle  // Dynamic title
    ) {
        TabbedTerminal(
            onExit = { /* ... */ },
            onWindowTitleChange = { title ->
                windowTitle = title  // Updates from shell (via OSC sequences)
            }
        )
    }
}
```

## Initial Command

Run a command when the terminal starts:

```kotlin
TabbedTerminal(
    onExit = { /* ... */ },
    initialCommand = "echo 'Welcome!' && ls -la"
)
```

## Settings Override

Override specific global settings for a particular `TabbedTerminal` instance without affecting other instances or the global settings file.

```kotlin
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride

TabbedTerminal(
    onExit = { /* ... */ },
    settingsOverride = TerminalSettingsOverride(
        alwaysShowTabBar = true,  // Always show tab bar (useful for sidebars)
        fontSize = 12f            // Smaller font for compact view
    )
)
```

### Use Cases

- **Sidebar terminals**: Force `alwaysShowTabBar = true` so users always see tabs
- **Compact views**: Reduce `fontSize` for space-constrained layouts
- **Different themes per instance**: Override colors for specific terminals
- **Performance tuning**: Different `bufferMaxLines` for different use cases

### How It Works

`settingsOverride` merges with global settings from `~/.bossterm/settings.json`:

1. Global settings are loaded from `SettingsManager`
2. Non-null fields in `settingsOverride` replace corresponding global values
3. Null fields in `settingsOverride` inherit from global settings

```kotlin
// Example: Only override alwaysShowTabBar, inherit everything else
TabbedTerminal(
    settingsOverride = TerminalSettingsOverride(
        alwaysShowTabBar = true
    ),
    onExit = { /* ... */ }
)
```

### Common Override Examples

```kotlin
// Always show tab bar (for sidebar integration)
TerminalSettingsOverride(alwaysShowTabBar = true)

// Compact terminal
TerminalSettingsOverride(
    fontSize = 11f,
    lineSpacing = 1.0f,
    showScrollbar = false
)

// Different split behavior
TerminalSettingsOverride(
    splitDefaultRatio = 0.3f,  // 30/70 splits
    splitFocusBorderEnabled = false
)
```

## State Persistence

When embedding `TabbedTerminal` within another navigation or tab system, terminal sessions are normally lost when the composable unmounts. Use `TabbedTerminalState` to preserve sessions:

```kotlin
@Composable
fun MyApp() {
    var currentView by remember { mutableStateOf("terminal") }

    // State survives when TabbedTerminal unmounts
    val terminalState = rememberTabbedTerminalState(autoDispose = false)

    // Manual cleanup
    DisposableEffect(Unit) {
        onDispose { terminalState.dispose() }
    }

    Column {
        // View switcher
        Row {
            Button(onClick = { currentView = "terminal" }) { Text("Terminal") }
            Button(onClick = { currentView = "editor" }) { Text("Editor") }
        }

        // Content - terminal state persists across view switches
        when (currentView) {
            "terminal" -> TabbedTerminal(
                state = terminalState,
                onExit = { /* ... */ }
            )
            "editor" -> Text("Editor view - switch back to see your terminals!")
        }
    }
}
```

**Key points:**
- Use `autoDispose = false` when state should survive navigation
- Call `terminalState.dispose()` manually when truly done
- State is automatically initialized on first composition
- All tabs, sessions, and split states are preserved

## Stable Tab IDs

Tab indices are unstable - they change when tabs are reordered, closed, or inserted. Use stable tab IDs for reliable tab targeting.

### The Problem

```kotlin
// Create tabs
state.createTab()  // Index 0
state.createTab()  // Index 1

// Later, user closes tab 0...
// Now the second tab is at index 0!
state.write("command\n", tabIndex = 1)  // Wrong tab or no-op!
```

### The Solution: Stable IDs

Each tab has a unique, stable ID (UUID) that survives reordering:

```kotlin
// Create tabs with stable IDs
val configA = state.createTab(tabId = "config-A")  // Returns "config-A"
val configB = state.createTab(tabId = "config-B")  // Returns "config-B"

// Later, even if tabs are reordered or closed:
state.sendCtrlC("config-A")           // Always targets the right tab
state.write("restart\n", "config-A")  // Reliable!
state.closeTab("config-B")            // Works regardless of index
```

### Auto-Generated IDs

If you don't specify a `tabId`, a UUID is generated automatically:

```kotlin
// Get the auto-generated ID
val tabId = state.createTab()  // Returns something like "a1b2c3d4-..."

// Use it later
state.write("command\n", tabId!!)
```

### API Overview

```kotlin
// Properties
state.activeTabId          // Stable ID of active tab

// Lookup
state.getTabById(tabId)    // Find tab by ID

// Tab management (by ID)
state.createTab(tabId = "my-id")  // Create with custom ID
state.closeTab(tabId)             // Close by ID
state.switchToTab(tabId)          // Switch by ID

// Input (by ID)
state.write(text, tabId)          // Send text by ID
state.sendInput(bytes, tabId)     // Send bytes by ID
state.sendCtrlC(tabId)            // Ctrl+C by ID
state.sendCtrlD(tabId)            // Ctrl+D by ID
state.sendCtrlZ(tabId)            // Ctrl+Z by ID
```

### Example: Runner System

```kotlin
// Track config → tab mappings
val configTabs = mutableMapOf<String, String>()

fun runConfig(configId: String, command: String) {
    val existingTabId = configTabs[configId]

    if (existingTabId != null && state.getTabById(existingTabId) != null) {
        // Re-run in existing tab
        state.sendCtrlC(existingTabId)
        state.write("$command\n", existingTabId)
        state.switchToTab(existingTabId)
    } else {
        // Create new tab for this config
        val newTabId = state.createTab(tabId = "config-$configId")!!
        configTabs[configId] = newTabId
        state.write("$command\n", newTabId)
    }
}
```

## Programmatic Input

Send text and control signals to terminal tabs programmatically.

### Basic Usage

```kotlin
val state = rememberTabbedTerminalState()

// Send to active tab
state.write("ls -la\n")
state.sendCtrlC()

// Send to specific tab by index
state.write("pwd\n", tabIndex = 1)
state.sendCtrlC(tabIndex = 0)
```

### Control Signals

```kotlin
// Interrupt running process
state.sendCtrlC()           // Active tab
state.sendCtrlC(tabIndex)   // Specific tab

// Send EOF
state.sendCtrlD()
state.sendCtrlD(tabIndex)

// Suspend process
state.sendCtrlZ()
state.sendCtrlZ(tabIndex)
```

### Raw Bytes

```kotlin
// Send raw bytes to active tab
state.sendInput(byteArrayOf(0x03))  // Ctrl+C

// Send to specific tab
state.sendInput(byteArrayOf(0x04), tabIndex = 1)  // Ctrl+D to tab 1
```

### Example: Tab Controls

```kotlin
@Composable
fun TerminalWithTabControls() {
    val state = rememberTabbedTerminalState()

    Column {
        Row {
            Button(onClick = { state.write("sleep 30\n") }) {
                Text("Run Sleep")
            }
            Button(onClick = { state.sendCtrlC() }) {
                Text("Stop Active Tab")
            }
            // Stop all tabs
            Button(onClick = {
                for (i in 0 until state.tabCount) {
                    state.sendCtrlC(i)
                }
            }) {
                Text("Stop All Tabs")
            }
        }

        TabbedTerminal(
            state = state,
            onExit = { /* ... */ }
        )
    }
}
```

All input methods are asynchronous and share the same FIFO queue per tab, ensuring ordered delivery. See [Embedding Guide - Programmatic Input](embedding.md#programmatic-input) for more details.

## Complete Example

See the [tabbed-example](../tabbed-example) module for a full working example with:
- Multiple windows support
- Menu bar integration
- Window focus tracking
- Settings panel overlay
- **State persistence demo** (view switching)

Run the example:

```bash
./gradlew :tabbed-example:run
```

## Custom Link Handling

By default, clicking links in the terminal (with Ctrl/Cmd+Click or via the context menu "Open Link") opens them in the system's default browser. You can intercept these clicks with the `onLinkClick` callback:

```kotlin
TabbedTerminal(
    onExit = { exitApplication() },
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

The callback is invoked for all tabs and split panes within the terminal.

## Custom Context Menu

Add custom items to the right-click context menu using the `contextMenuItems` parameter.

```kotlin
import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu

TabbedTerminal(
    onExit = { exitApplication() },
    contextMenuItems = listOf(
        ContextMenuSection(id = "custom_section", label = "Quick Commands"),
        ContextMenuItem(
            id = "run_build",
            label = "Run Build",
            action = { /* your action */ }
        ),
        ContextMenuSubmenu(
            id = "git_menu",
            label = "Git Commands",
            items = listOf(
                ContextMenuItem(id = "git_status", label = "Status", action = { /* ... */ }),
                ContextMenuItem(id = "git_log", label = "Log", action = { /* ... */ })
            )
        )
    )
)
```

Custom items appear below the built-in items (Copy, Paste, Clear, Select All) and apply to all tabs and split panes within the terminal.

For detailed context menu element types and examples, see [Custom Context Menu in EmbeddableTerminal](embedding.md#custom-context-menu).

## Comparison: EmbeddableTerminal vs TabbedTerminal

| Feature | EmbeddableTerminal | TabbedTerminal |
|---------|-------------------|----------------|
| Single terminal | Yes | Yes |
| Multiple tabs | No | Yes |
| Tab bar | No | Yes (auto-hide) |
| Split panes | No | Yes |
| External state holder | `EmbeddableTerminalState` | `TabbedTerminalState` |
| State persistence | Yes | Yes |
| Custom context menu | Yes | Yes |
| Custom link handling | Yes | Yes |
| Menu bar integration | No | Yes |
| Window management | No | Yes |
| Command notifications | No | Yes |
| Use case | Simple embedding | Full terminal app |

Choose `EmbeddableTerminal` for simple use cases where you need a single terminal instance with custom context menus. Choose `TabbedTerminal` when building a full-featured terminal application with tabs, splits, and window management. Both support external state holders for persistence across recomposition.
