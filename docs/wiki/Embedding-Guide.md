# Embedding Guide

Embed BossTerm's terminal component in your Compose Desktop application.

---

## Installation

Add BossTerm dependencies to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // Core terminal emulation engine
    implementation("com.risaboss:bossterm-core:<version>")

    // Compose Desktop UI component
    implementation("com.risaboss:bossterm-compose:<version>")
}
```

[![Maven Central](https://img.shields.io/maven-central/v/com.risaboss/bossterm-core)](https://central.sonatype.com/namespace/com.risaboss)

---

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

---

## EmbeddableTerminal API

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
    onLinkClick: ((HyperlinkInfo) -> Boolean)? = null,
    hyperlinkRegistry: HyperlinkRegistry = HyperlinkDetector.registry,
    modifier: Modifier = Modifier
)
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `state` | `EmbeddableTerminalState` | State for programmatic control |
| `settings` | `TerminalSettings?` | Custom settings (overrides settingsPath) |
| `settingsPath` | `String?` | Path to settings JSON file |
| `command` | `String?` | Shell command (default: `$SHELL`) |
| `workingDirectory` | `String?` | Initial directory |
| `environment` | `Map<String, String>?` | Additional environment variables |
| `initialCommand` | `String?` | Command to run after ready |
| `onOutput` | `(String) -> Unit` | Terminal output callback |
| `onTitleChange` | `(String) -> Unit` | Title change callback |
| `onExit` | `(Int) -> Unit` | Process exit callback |
| `onReady` | `() -> Unit` | Terminal ready callback |
| `contextMenuItems` | `List<ContextMenuElement>` | Custom context menu items |
| `onLinkClick` | `(HyperlinkInfo) -> Boolean` | Custom link handler; return `true` if handled, `false` for default |
| `hyperlinkRegistry` | `HyperlinkRegistry` | Custom hyperlink patterns (e.g., JIRA tickets) |

---

## Programmatic Control

```kotlin
val state = rememberEmbeddableTerminalState()

// Write text to terminal
state.write("ls -la\n")

// Send control signals
state.sendCtrlC()  // Interrupt (like pressing Ctrl+C)
state.sendCtrlD()  // EOF (like pressing Ctrl+D)
state.sendCtrlZ()  // Suspend (like pressing Ctrl+Z)

// Send raw bytes
state.sendInput(byteArrayOf(0x03))  // Same as sendCtrlC()

// Check connection status
if (state.isConnected) {
    state.write("echo 'Hello!'\n")
}
```

All input methods are asynchronous and share a FIFO queue, ensuring ordered delivery.

---

## Session Persistence

By default, the terminal process is disposed when the composable leaves composition:

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

---

## Custom Context Menu

Add custom items to the right-click menu:

```kotlin
import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu

EmbeddableTerminal(
    state = terminalState,
    contextMenuItems = listOf(
        ContextMenuSection(id = "commands", label = "Quick Commands"),
        ContextMenuItem(
            id = "run_pwd",
            label = "Print Directory",
            action = { terminalState.write("pwd\n") }
        ),
        ContextMenuSubmenu(
            id = "git",
            label = "Git Commands",
            items = listOf(
                ContextMenuItem(id = "status", label = "Status", action = { ... }),
                ContextMenuItem(id = "log", label = "Log", action = { ... })
            )
        )
    )
)
```

### Menu Structure

```
┌─────────────────────────┐
│ Copy              Cmd+C │  ← Built-in
│ Paste             Cmd+V │
│ Clear                   │
│ Select All        Cmd+A │
├─────────────────────────┤
│ ── Quick Commands ──    │  ← Your items
│ Print Directory         │
│ Git Commands        ▸   │
└─────────────────────────┘
```

---

## Custom Link Handling

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
            else -> false  // Use default browser
        }
    }
)
```

The callback receives `HyperlinkInfo` with link metadata (type, url, patternId, isFile, isFolder).
Return `true` if handled, `false` to use default behavior (system browser/finder).

---

## Initial Command

Run a command when the terminal starts:

```kotlin
EmbeddableTerminal(
    initialCommand = "echo 'Welcome!' && ls -la"
)
```

For best results, configure [[Shell-Integration]] (OSC 133) for proper timing.

---

## Custom Settings

```kotlin
EmbeddableTerminal(
    settings = TerminalSettings(
        fontSize = 16,
        fontName = "JetBrains Mono",
        copyOnSelect = true,
        bufferMaxLines = 20000
    )
)
```

Or load from file:

```kotlin
EmbeddableTerminal(
    settingsPath = "/path/to/settings.json"
)
```

---

## Focus Management

**Important**: Parent containers should NOT compete for focus:

```kotlin
// DON'T DO THIS
Column(
    modifier = Modifier
        .focusable()
        .clickable { requestFocus() }  // Steals focus!
) {
    EmbeddableTerminal()  // Never gets focus
}

// DO THIS
Column(
    modifier = Modifier
        .onFocusChanged { state ->
            // Observe focus without competing
        }
) {
    EmbeddableTerminal()  // Gets focus naturally
}
```

See [[Troubleshooting]] for more focus management tips.

---

## Example Project

See the [embedded-example](https://github.com/kshivang/BossTerm/tree/master/embedded-example) module:

```bash
./gradlew :embedded-example:run
```

---

## See Also

- [[Tabbed-Terminal-Guide]] - Full tabbed terminal with splits
- [[API-Reference]] - Complete API documentation
- [[Troubleshooting]] - Common issues
