# Tabbed Terminal Guide

Embed BossTerm's full-featured tabbed terminal with splits, window management, and more.

---

## Installation

```kotlin
dependencies {
    implementation("com.risaboss:bossterm-core:<version>")
    implementation("com.risaboss:bossterm-compose:<version>")
}
```

---

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

---

## Features

- **Multiple Tabs** - Create, switch, and close tabs
- **Tab Bar** - Visual tab bar (auto-hides with single tab)
- **Split Panes** - Horizontal and vertical splits
- **State Persistence** - Preserve sessions across recomposition
- **Working Directory Inheritance** - New tabs/splits inherit CWD
- **Command Notifications** - System notifications for long commands
- **Menu Integration** - Wire up application menu bar
- **Keyboard Shortcuts** - Full navigation support

---

## TabbedTerminal API

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
    onLinkClick: ((HyperlinkInfo) -> Boolean)? = null,
    contextMenuItems: List<ContextMenuElement> = emptyList(),
    modifier: Modifier = Modifier
)
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `state` | `TabbedTerminalState?` | External state for persistence |
| `onExit` | `() -> Unit` | **Required.** Called when last tab closes |
| `onWindowTitleChange` | `(String) -> Unit` | Active tab title change |
| `onNewWindow` | `() -> Unit` | New window request (Cmd/Ctrl+N) |
| `onShowSettings` | `() -> Unit` | Settings request (Cmd/Ctrl+,) |
| `menuActions` | `MenuActions?` | Menu bar integration |
| `isWindowFocused` | `() -> Boolean` | Window focus state |
| `initialCommand` | `String?` | First tab initial command |
| `onLinkClick` | `(HyperlinkInfo) -> Boolean` | Custom link handler; return `true` if handled, `false` for default |
| `hyperlinkRegistry` | `HyperlinkRegistry` | Custom hyperlink patterns (e.g., JIRA tickets) |
| `contextMenuItems` | `List<ContextMenuElement>` | Custom context menu |

---

## State Persistence

Terminal sessions survive navigation with `TabbedTerminalState`:

```kotlin
// State survives when composable unmounts
val terminalState = rememberTabbedTerminalState(autoDispose = false)

when (selectedView) {
    "terminal" -> TabbedTerminal(state = terminalState, onExit = { ... })
    "editor" -> EditorPane()  // Terminal sessions preserved!
}

// Manual cleanup when done
DisposableEffect(Unit) {
    onDispose { terminalState.dispose() }
}
```

### TabbedTerminalState Properties

| Property | Type | Description |
|----------|------|-------------|
| `tabs` | `List<TerminalTab>` | All open tabs |
| `tabCount` | `Int` | Number of tabs |
| `activeTabIndex` | `Int` | Active tab index (0-based) |
| `activeTab` | `TerminalTab?` | Currently active tab |

### TabbedTerminalState Methods

| Method | Description |
|--------|-------------|
| `createTab(workingDir?, initialCommand?)` | Create new tab |
| `closeTab(index)` | Close tab at index |
| `closeActiveTab()` | Close active tab |
| `switchToTab(index)` | Switch to tab |
| `nextTab()` | Next tab (wraps) |
| `previousTab()` | Previous tab (wraps) |
| `getActiveWorkingDirectory()` | Get CWD (OSC 7) |
| `write(text)` / `write(text, tabIndex)` | Send text to terminal |
| `sendInput(bytes)` / `sendInput(bytes, tabIndex)` | Send raw bytes |
| `sendCtrlC()` / `sendCtrlC(tabIndex)` | Send Ctrl+C (interrupt) |
| `sendCtrlD()` / `sendCtrlD(tabIndex)` | Send Ctrl+D (EOF) |
| `sendCtrlZ()` / `sendCtrlZ(tabIndex)` | Send Ctrl+Z (suspend) |
| `dispose()` | Cleanup all sessions |

---

## Menu Bar Integration

```kotlin
val menuActions = remember { MenuActions() }

Window(onCloseRequest = { ... }) {
    MenuBar {
        Menu("File") {
            Item("New Tab", onClick = { menuActions.onNewTab?.invoke() })
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
        onExit = { ... },
        menuActions = menuActions
    )
}
```

---

## Window Focus Tracking

For command notifications, track window focus:

```kotlin
var isWindowFocused by remember { mutableStateOf(true) }

Window(onCloseRequest = { ... }) {
    LaunchedEffect(Unit) {
        val listener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                isWindowFocused = true
            }
            override fun windowLostFocus(e: WindowEvent?) {
                isWindowFocused = false
            }
        }
        window.addWindowFocusListener(listener)
    }

    TabbedTerminal(
        onExit = { ... },
        isWindowFocused = { isWindowFocused }
    )
}
```

---

## Multiple Windows

```kotlin
fun main() = application {
    val windows = remember { mutableStateListOf(WindowId()) }

    fun createWindow() = windows.add(WindowId())
    fun closeWindow(index: Int) {
        if (windows.size > 1) windows.removeAt(index)
        else exitApplication()
    }

    windows.forEachIndexed { index, _ ->
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

---

## Custom Context Menu

```kotlin
TabbedTerminal(
    onExit = { ... },
    contextMenuItems = listOf(
        ContextMenuSection(id = "custom", label = "Quick Commands"),
        ContextMenuItem(id = "build", label = "Run Build", action = { ... }),
        ContextMenuSubmenu(
            id = "git", label = "Git Commands",
            items = listOf(
                ContextMenuItem(id = "status", label = "Status", action = { ... }),
                ContextMenuItem(id = "log", label = "Log", action = { ... })
            )
        )
    )
)
```

---

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl/Cmd+T | New tab |
| Ctrl/Cmd+W | Close tab/pane |
| Ctrl+Tab | Next tab |
| Ctrl+Shift+Tab | Previous tab |
| Ctrl/Cmd+1-9 | Jump to tab |
| Ctrl/Cmd+D | Split vertically |
| Ctrl/Cmd+Shift+D | Split horizontally |
| Ctrl/Cmd+Option+Arrow | Navigate panes |
| Ctrl/Cmd+, | Settings |

---

## Example Project

See the [tabbed-example](https://github.com/kshivang/BossTerm/tree/master/tabbed-example) module:

```bash
./gradlew :tabbed-example:run
```

---

## EmbeddableTerminal vs TabbedTerminal

| Feature | EmbeddableTerminal | TabbedTerminal |
|---------|-------------------|----------------|
| Single terminal | Yes | Yes |
| Multiple tabs | No | Yes |
| Split panes | No | Yes |
| Tab bar | No | Yes |
| Menu integration | No | Yes |
| Window management | No | Yes |
| Notifications | No | Yes |
| Use case | Simple embedding | Full terminal app |

---

## See Also

- [[Embedding-Guide]] - Simple single terminal
- [[API-Reference]] - Complete API docs
- [[Keyboard-Shortcuts]] - All shortcuts
