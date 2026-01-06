# API Reference

Complete API documentation for BossTerm's embeddable components.

---

## Package: ai.rever.bossterm.compose

### EmbeddableTerminal

Single terminal composable for embedding.

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
    onContextMenuOpen: (() -> Unit)? = null,
    onContextMenuOpenAsync: (suspend () -> Unit)? = null,
    onLinkClick: ((HyperlinkInfo) -> Boolean)? = null,
    hyperlinkRegistry: HyperlinkRegistry = HyperlinkDetector.registry,
    modifier: Modifier = Modifier
)
```

---

### EmbeddableTerminalState

State holder for programmatic terminal control.

```kotlin
@Composable
fun rememberEmbeddableTerminalState(
    autoDispose: Boolean = true
): EmbeddableTerminalState
```

#### Properties

| Property | Type | Description |
|----------|------|-------------|
| `isConnected` | `Boolean` | Terminal is connected to shell process |
| `isInitializing` | `Boolean` | Terminal is initializing |
| `isDisposed` | `Boolean` | Terminal has been disposed |
| `scrollOffset` | `Int` | Current scroll position |

#### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `write` | `(text: String)` | Write text to terminal |
| `sendInput` | `(bytes: ByteArray)` | Send raw bytes to terminal |
| `sendCtrlC` | `()` | Send Ctrl+C (interrupt) |
| `sendCtrlD` | `()` | Send Ctrl+D (EOF) |
| `sendCtrlZ` | `()` | Send Ctrl+Z (suspend) |
| `paste` | `(text: String)` | Paste with bracketed paste mode |
| `clear` | `()` | Clear terminal (Ctrl+L) |
| `scrollToBottom` | `()` | Scroll to bottom |
| `scrollBy` | `(lines: Int)` | Scroll by lines |
| `clearSelection` | `()` | Clear selection |
| `toggleSearch` | `()` | Toggle search bar |
| `search` | `(query: String)` | Search terminal |
| `dispose` | `()` | Dispose terminal resources |

---

### TabbedTerminal

Full-featured tabbed terminal composable.

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
    onContextMenuOpen: (() -> Unit)? = null,
    onContextMenuOpenAsync: (suspend () -> Unit)? = null,
    hyperlinkRegistry: HyperlinkRegistry = HyperlinkDetector.registry,
    modifier: Modifier = Modifier
)
```

---

### TabbedTerminalState

State holder for tabbed terminal persistence.

```kotlin
@Composable
fun rememberTabbedTerminalState(
    autoDispose: Boolean = true
): TabbedTerminalState
```

#### Properties

| Property | Type | Description |
|----------|------|-------------|
| `tabs` | `List<TerminalTab>` | All open tabs |
| `tabCount` | `Int` | Number of open tabs |
| `activeTabIndex` | `Int` | Active tab index (0-based) |
| `activeTab` | `TerminalTab?` | Currently active tab |
| `isInitialized` | `Boolean` | State has been initialized |
| `isDisposed` | `Boolean` | State has been disposed |

#### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `createTab` | `(workingDir?, initialCommand?)` | Create new tab |
| `closeTab` | `(index: Int)` | Close tab at index |
| `closeActiveTab` | `()` | Close active tab |
| `switchToTab` | `(index: Int)` | Switch to tab |
| `nextTab` | `()` | Switch to next tab |
| `previousTab` | `()` | Switch to previous tab |
| `getActiveWorkingDirectory` | `(): String?` | Get active tab's CWD |
| `write` | `(text: String)` | Send text to active tab |
| `write` | `(text: String, tabIndex: Int)` | Send text to specific tab |
| `sendInput` | `(bytes: ByteArray)` | Send raw bytes to active tab |
| `sendInput` | `(bytes: ByteArray, tabIndex: Int)` | Send raw bytes to specific tab |
| `sendCtrlC` | `()` / `(tabIndex: Int)` | Send Ctrl+C (interrupt) |
| `sendCtrlD` | `()` / `(tabIndex: Int)` | Send Ctrl+D (EOF) |
| `sendCtrlZ` | `()` / `(tabIndex: Int)` | Send Ctrl+Z (suspend) |
| `addSessionListener` | `(listener)` | Add lifecycle listener |
| `removeSessionListener` | `(listener)` | Remove listener |
| `dispose` | `()` | Dispose all sessions |

---

### MenuActions

Menu bar integration callbacks.

```kotlin
class MenuActions {
    var onNewTab: (() -> Unit)? = null
    var onCloseTab: (() -> Unit)? = null
    var onNextTab: (() -> Unit)? = null
    var onPreviousTab: (() -> Unit)? = null
    var onSplitVertical: (() -> Unit)? = null
    var onSplitHorizontal: (() -> Unit)? = null
    var onClosePane: (() -> Unit)? = null
}
```

---

## Context Menu Elements

### ContextMenuItem

Clickable menu item.

```kotlin
data class ContextMenuItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val action: () -> Unit
) : ContextMenuElement
```

### ContextMenuSection

Visual separator with optional label.

```kotlin
data class ContextMenuSection(
    val id: String,
    val label: String? = null
) : ContextMenuElement
```

### ContextMenuSubmenu

Nested menu.

```kotlin
data class ContextMenuSubmenu(
    val id: String,
    val label: String,
    val items: List<ContextMenuElement>
) : ContextMenuElement
```

---

## Package: ai.rever.bossterm.compose.settings

### TerminalSettings

All configurable terminal settings.

```kotlin
@Serializable
data class TerminalSettings(
    // Visual
    val fontSize: Float = 14f,
    val fontName: String? = null,
    val lineSpacing: Float = 1.0f,
    val defaultForeground: String = "0xFFFFFFFF",
    val defaultBackground: String = "0xFF000000",
    val backgroundOpacity: Float = 1.0f,
    val windowBlur: Boolean = false,
    val blurRadius: Float = 30f,
    val backgroundImagePath: String = "",
    val backgroundImageOpacity: Float = 0.3f,
    val showUnfocusedOverlay: Boolean = true,

    // Behavior
    val useLoginSession: Boolean = true,
    val initialCommand: String = "",
    val initialCommandDelayMs: Int = 500,
    val copyOnSelect: Boolean = false,
    val pasteOnMiddleClick: Boolean = true,
    val emulateX11CopyPaste: Boolean = false,
    val scrollToBottomOnTyping: Boolean = true,
    val altSendsEscape: Boolean = true,
    val enableMouseReporting: Boolean = true,
    val audibleBell: Boolean = true,
    val visualBell: Boolean = true,

    // Performance
    val performanceMode: String = "balanced",
    val maxRefreshRate: Int = 60,
    val bufferMaxLines: Int = 10000,
    val caretBlinkMs: Int = 500,

    // Notifications
    val notifyOnCommandComplete: Boolean = true,
    val notifyMinDurationSeconds: Int = 5,
    val notifyShowExitCode: Boolean = true,
    val notifyWithSound: Boolean = true,

    // Splits
    val splitDefaultRatio: Float = 0.5f,
    val splitMinimumSize: Float = 0.1f,
    val splitFocusBorderEnabled: Boolean = true,
    val splitFocusBorderColor: String = "0xFF4A90E2",
    val splitInheritWorkingDirectory: Boolean = true,

    // Tab Bar
    val alwaysShowTabBar: Boolean = false,

    // ... and more
)
```

---

### SettingsManager

Settings persistence and hot-reload.

```kotlin
object SettingsManager {
    val instance: SettingsManager

    val settings: StateFlow<TerminalSettings>

    fun updateSettings(settings: TerminalSettings)
    fun resetToDefaults()
}
```

---

## Session Listeners

### TerminalSessionListener

Lifecycle callbacks for terminal sessions.

```kotlin
interface TerminalSessionListener {
    fun onSessionCreated(session: TerminalSession)
    fun onSessionClosed(session: TerminalSession)
    fun onAllSessionsClosed()
}
```

---

## See Also

- [[Embedding-Guide]] - Usage examples
- [[Tabbed-Terminal-Guide]] - Tabbed terminal usage
- [[Configuration]] - Settings reference
