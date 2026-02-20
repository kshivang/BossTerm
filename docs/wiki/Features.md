# Features

BossTerm is packed with features for modern terminal workflows.

---

## Terminal Emulation

| Feature | Description |
|---------|-------------|
| **Xterm Emulation** | Full VT100/Xterm compatibility |
| **True Color** | 256 color and 24-bit true color support |
| **Unicode** | Full emoji, CJK, surrogate pairs, combining characters |
| **Nerd Fonts** | Built-in powerline symbols and devicons |
| **Mouse Reporting** | Click, scroll, drag support (vim, tmux, htop, less, fzf) |

---

## Window Management

| Feature | Description |
|---------|-------------|
| **Multiple Windows** | Cmd/Ctrl+N opens new independent windows |
| **Multiple Tabs** | Create, switch, and close tabs within a window |
| **Split Panes** | Horizontal and vertical splits |
| **Tab Bar** | Auto-hiding tab bar (configurable to always show) |
| **Working Directory** | New tabs/splits inherit current directory |

---

## Visual Customization

| Feature | Description |
|---------|-------------|
| **Themes** | Built-in presets (Dracula, Solarized, Nord, etc.) |
| **Custom Themes** | Create and save your own color schemes |
| **Transparency** | Adjustable window opacity with blur effects |
| **Background Images** | Custom images with opacity and blur controls |
| **Custom Fonts** | Use any system monospace font |

---

## Productivity

| Feature | Description |
|---------|-------------|
| **Search** | Ctrl/Cmd+F with regex support |
| **Hyperlinks** | Auto-detect URLs, file paths, emails |
| **Copy/Paste** | Standard clipboard + copy-on-select + middle-click paste |
| **Context Menu** | Right-click for quick actions |
| **Drag & Drop** | Drop files to paste shell-escaped paths |
| **IME Support** | Chinese/Japanese/Korean input methods |

---

## Notifications & Integration

| Feature | Description |
|---------|-------------|
| **Command Notifications** | System alerts when long commands complete (OSC 133) |
| **Window Title** | Dynamic title from shell (OSC 0/1/2) |
| **Progress Bar** | Visual indicator for long operations (OSC 1337) |
| **Inline Images** | Display images via iTerm2's imgcat |
| **OSC 52 Clipboard** | Remote clipboard access for tmux, SSH |

---

## Performance

| Feature | Description |
|---------|-------------|
| **Native Performance** | 60fps smooth rendering |
| **Adaptive Debouncing** | Optimized for both interactive and bulk output |
| **Performance Modes** | Latency, Balanced, or Throughput optimization |
| **Large Scrollback** | Configurable buffer size (default 10,000 lines) |

---

## Developer Tools

| Feature | Description |
|---------|-------------|
| **Debug Panel** | Ctrl/Cmd+Shift+D for terminal debugging |
| **Settings UI** | Full GUI settings panel |
| **JSON Config** | `~/.bossterm/settings.json` for advanced users |
| **Embeddable** | Libraries for integrating into your Kotlin apps |
| **Split Pane API** | Programmatic split creation, navigation, pane management |
| **Reactive State API** | Observable flows for tab/pane metadata and settings |

---

## See Also

- [[Keyboard-Shortcuts]] - Full shortcut reference
- [[Configuration]] - Customize all these features
- [[Shell-Integration]] - Enable advanced features
