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
- **Split Pane API (T6)** - Programmatic split creation, navigation, and management
- **Reactive State API (T7)** - Observable flows for tab/pane metadata
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
| `tabsFlow` | `StateFlow<List<TerminalTabInfo>>` | Observable tab list (T7) |
| `activeTabIndexFlow` | `StateFlow<Int>` | Observable active index (T7) |
| `settingsFlow` | `StateFlow<TerminalSettings>` | Observable settings (T7) |

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
| `splitVertical(tabId?)` | Split focused pane vertically (T6) |
| `splitHorizontal(tabId?)` | Split focused pane horizontally (T6) |
| `closeFocusedPane(tabId?)` | Close focused pane / close tab if last (T6) |
| `navigatePaneFocus(direction, tabId?)` | Spatial pane navigation (T6) |
| `navigateToNextPane(tabId?)` | Next pane sequentially (T6) |
| `navigateToPreviousPane(tabId?)` | Previous pane sequentially (T6) |
| `getPaneCount(tabId?)` | Number of panes in tab (T6) |
| `hasSplitPanes(tabId?)` | Whether tab has multiple panes (T6) |
| `getSplitSessionIds(tabId?)` | Session IDs of all panes (T6) |
| `getFocusedSplitSession(tabId?)` | Get focused pane's session (T6) |
| `writeToFocusedPane(text, tabId?)` | Write to focused pane (T6) |
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

## Split Pane API (T6)

Programmatic control over split panes. All methods accept an optional `tabId` — defaults to the active tab.

```kotlin
val state = rememberTabbedTerminalState()

// Create splits
state.splitVertical()     // Left/Right split
state.splitHorizontal()   // Top/Bottom split

// Navigate panes
state.navigatePaneFocus(NavigationDirection.RIGHT)
state.navigateToNextPane()

// Query state
state.getPaneCount()           // e.g., 3
state.hasSplitPanes()          // true
state.getSplitSessionIds()     // ["id1", "id2", "id3"]

// Write to focused pane
state.writeToFocusedPane("echo 'Hello!'\n")

// Close focused pane (closes tab if last pane)
state.closeFocusedPane()
```

---

## Reactive State API (T7)

Observable flows for building reactive UIs that update when tab/pane state changes.

```kotlin
@Composable
fun StatusBar(state: TabbedTerminalState) {
    val tabs by state.tabsFlow.collectAsState()
    val activeIndex by state.activeTabIndexFlow.collectAsState()
    val activeTab = tabs.getOrNull(activeIndex)

    Row {
        Text("${tabs.size} tabs")
        activeTab?.let {
            Text(" | ${it.title}")
            if (it.paneCount > 1) Text(" | ${it.paneCount} panes")
        }
    }
}
```

`TerminalTabInfo` provides tab metadata:

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` | Stable tab ID |
| `title` | `String` | Current tab title |
| `isConnected` | `Boolean` | PTY process connected |
| `workingDirectory` | `String?` | CWD from OSC 7 |
| `paneCount` | `Int` | Number of panes (>= 1) |

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

The example includes an **API Demo** view with interactive controls for all Split Pane (T6) and Reactive State (T7) APIs, plus a reactive status bar.

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
TabbedTerminal(
    onLinkClick = { info -> openCustomHandler(info.url) },
    onExit = { exitApplication() }
)

// After - return true if handled, false for default behavior
TabbedTerminal(
    onLinkClick = { info ->
        openCustomHandler(info.url)
        true  // Handled - skip default behavior
    },
    onExit = { exitApplication() }
)
```

Return `false` to fall back to default behavior (open in browser/finder):

```kotlin
TabbedTerminal(
    onLinkClick = { info ->
        when {
            info.patternId == "jira" -> { openJiraTicket(info.matchedText); true }
            info.type == HyperlinkType.FILE -> { openInEditor(info.url); true }
            else -> false  // Use default behavior
        }
    },
    onExit = { exitApplication() }
)
```

---

## See Also

- [[Embedding-Guide]] - Simple single terminal
- [[API-Reference]] - Complete API docs
- [[Keyboard-Shortcuts]] - All shortcuts
