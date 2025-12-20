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
    onExit: () -> Unit,
    onWindowTitleChange: (String) -> Unit = {},
    onNewWindow: () -> Unit = {},
    onShowSettings: () -> Unit = {},
    menuActions: MenuActions? = null,
    isWindowFocused: () -> Boolean = { true },
    initialCommand: String? = null,
    modifier: Modifier = Modifier
)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `onExit` | `() -> Unit` | **Required.** Called when last tab closes |
| `onWindowTitleChange` | `(String) -> Unit` | Called when active tab's title changes |
| `onNewWindow` | `() -> Unit` | Called when user requests new window (Cmd/Ctrl+N) |
| `onShowSettings` | `() -> Unit` | Called when user opens settings |
| `menuActions` | `MenuActions?` | Callbacks for menu bar integration |
| `isWindowFocused` | `() -> Boolean` | Returns window focus state (for notifications) |
| `initialCommand` | `String?` | Command to run in first tab after startup |
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

## Complete Example

See the [tabbed-example](../tabbed-example) module for a full working example with:
- Multiple windows support
- Menu bar integration
- Window focus tracking
- Settings panel overlay

Run the example:

```bash
./gradlew :tabbed-example:run
```

## Comparison: EmbeddableTerminal vs TabbedTerminal

| Feature | EmbeddableTerminal | TabbedTerminal |
|---------|-------------------|----------------|
| Single terminal | Yes | Yes |
| Multiple tabs | No | Yes |
| Tab bar | No | Yes (auto-hide) |
| Split panes | No | Yes |
| Custom context menu | Yes | Built-in |
| Menu bar integration | No | Yes |
| Window management | No | Yes |
| Command notifications | No | Yes |
| Use case | Simple embedding | Full terminal app |

Choose `EmbeddableTerminal` for simple use cases where you need a single terminal instance with custom context menus. Choose `TabbedTerminal` when building a full-featured terminal application with tabs, splits, and window management.
