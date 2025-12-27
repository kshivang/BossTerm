# BossTerm vs iTerm2 Feature Comparison

This document provides a comprehensive feature comparison between BossTerm and [iTerm2](https://iterm2.com/), helping users understand feature parity, unique strengths, and gaps.

## Overview

| Aspect | BossTerm | iTerm2 |
|--------|----------|--------|
| **Platform** | macOS, Linux, Windows | macOS only |
| **Technology** | Kotlin/Compose Desktop, Skia | Objective-C, Cocoa, Metal |
| **License** | Apache 2.0 | GPLv2 |
| **First Release** | 2024 | 2006 |
| **Total Features** | 100+ | 80+ |

## Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Full support |
| ⚠️ | Partial support |
| ❌ | Not implemented |
| N/A | Not applicable |

---

## Terminal Emulation (Core)

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| VT100/xterm emulation | ✅ | ✅ | Both fully compatible |
| 256-color support | ✅ | ✅ | Full palette |
| True color (24-bit RGB) | ✅ | ✅ | 16 million colors |
| Unicode/UTF-8 | ✅ | ✅ | Full support |
| Emoji rendering | ✅ | ✅ | With variation selectors |
| Grapheme clusters | ✅ | ✅ | ICU4J in BossTerm |
| Surrogate pairs (U+10000+) | ✅ | ✅ | Full Unicode plane support |
| Double-width chars (CJK) | ✅ | ✅ | Proper spacing |
| Mouse reporting modes | ✅ | ✅ | NORMAL, BUTTON, ALL_MOTION |
| Alternate screen buffer | ✅ | ✅ | vim, less, htop support |
| Bracketed paste mode | ✅ | ✅ | Safe pasting |
| GPU rendering | ✅ | ✅ | BossTerm: Skia (Metal/OpenGL/DirectX), iTerm2: Metal |

---

## Tabs & Window Management

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Multiple tabs | ✅ | ✅ | Full support |
| Split panes (vertical) | ✅ | ✅ | Full support |
| Split panes (horizontal) | ✅ | ✅ | Full support |
| Tab keyboard shortcuts | ✅ | ✅ | Ctrl+T/W/Tab/1-9 |
| Auto-close on shell exit | ✅ | ✅ | Configurable |
| CWD inheritance (new tabs) | ✅ | ✅ | Via OSC 7 |
| Hotkey window | ❌ | ✅ | System-wide terminal shortcut |
| Session restoration | ❌ | ✅ | Crash/upgrade recovery |
| Buried sessions | ❌ | ✅ | Background session management |
| Focus follows mouse | ❌ | ✅ | Unix-style focus |

---

## Clipboard & Selection

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Copy/Paste | ✅ | ✅ | Standard support |
| Copy-on-select | ✅ | ✅ | Configurable |
| Middle-click paste | ✅ | ✅ | X11-style |
| X11 clipboard emulation | ✅ | ✅ | Separate selection buffer |
| OSC 52 clipboard | ✅ | ✅ | Remote clipboard access |
| Selection modes | ✅ | ✅ | Character/word/line/block |
| Auto-scroll during drag | ✅ | ✅ | Proportional speed |
| Paste history | ❌ | ✅ | Access previous clipboard items |
| Advanced paste (edit before) | ❌ | ✅ | Transform text before pasting |
| Smart selection (semantic) | ⚠️ | ✅ | BossTerm: word only; iTerm2: URLs, paths, etc. |

---

## Search

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Find in terminal | ✅ | ✅ | Ctrl/Cmd+F |
| Regex search | ✅ | ✅ | Full regex support |
| Case sensitivity toggle | ✅ | ✅ | Configurable |
| Match highlighting | ✅ | ✅ | Visual highlight |
| Scrollbar search markers | ✅ | ✅ | Visual indicators |
| Global search (all tabs) | ❌ | ✅ | Cross-tab search |
| Open Quickly | ❌ | ✅ | Search sessions by title/host/dir |

---

## Shell Integration

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| OSC 7 (directory tracking) | ✅ | ✅ | Current working directory |
| OSC 133 (command state) | ✅ | ✅ | FinalTerm protocol |
| Command completion notifications | ✅ | ✅ | When window unfocused |
| Command history per host | ❌ | ✅ | Host-specific history |
| SCP file downloads | ❌ | ✅ | Download via shell integration |
| Drag-and-drop file uploads | ❌ | ✅ | Upload via shell integration |

---

## Images & Media

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Inline images (OSC 1337) | ✅ | ✅ | imgcat protocol |
| Image caching | ✅ | ✅ | Memory management |
| Animated GIF support | ✅ | ✅ | Animation playback |
| Sixel graphics | ❌ | ❌ | Neither supports |

---

## Hyperlinks

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| URL auto-detection | ✅ | ✅ | HTTP, HTTPS, etc. |
| Ctrl/Cmd+Click to open | ✅ | ✅ | Open in default browser |
| Custom URL patterns | ✅ | ✅ | Regex-based (Jira, GitHub, etc.) |
| Hover highlighting | ✅ | ✅ | Visual feedback |
| Email/file path detection | ✅ | ✅ | Multiple link types |

---

## Triggers & Automation

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Regex triggers | ❌ | ✅ | Auto-actions on pattern match |
| Highlight triggers | ❌ | ✅ | Color matching text |
| Command triggers | ❌ | ✅ | Run commands on match |
| Notification triggers | ❌ | ✅ | Alert on patterns |
| Password manager integration | ❌ | ✅ | macOS Keychain |

---

## Profiles & Themes

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Color themes | ✅ | ✅ | Built-in + custom |
| Custom color palettes | ✅ | ✅ | Full 256-color customization |
| Font configuration | ✅ | ✅ | Size, family, antialiasing |
| Background opacity | ✅ | ✅ | Transparency support |
| Background images | ✅ | ✅ | Custom backgrounds |
| Window blur (vibrancy) | ✅ | ✅ | macOS blur effect |
| Profile system | ❌ | ✅ | Save/load session configurations |
| Auto profile switching | ❌ | ✅ | Switch by host/user/directory |
| Dynamic profiles | ❌ | ✅ | Programmatic profile creation |
| Badges | ❌ | ✅ | Session info overlay |
| Status bar | ❌ | ✅ | Git branch, system graphs, etc. |

---

## Scripting & API

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Python scripting API | ❌ | ✅ | Full automation capability |
| AppleScript support | ❌ | ✅ | macOS automation |
| Coprocesses | ❌ | ✅ | Background task communication |
| Extensible actions | ✅ | ⚠️ | BossTerm: ActionRegistry system |
| Custom keyboard shortcuts | ✅ | ✅ | Key binding configuration |

---

## Advanced Features

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| tmux integration | ❌ | ✅ | Native iTerm2 UI for tmux |
| AI chat | ❌ | ✅ | Built-in LLM assistant |
| Built-in web browser | ❌ | ✅ | Browse within terminal |
| Instant Replay | ❌ | ✅ | Scrub through terminal history |
| Copy mode (vim keys) | ❌ | ✅ | Keyboard-based selection |
| Line timestamps | ❌ | ✅ | Last-modified time per line |
| Autocomplete | ❌ | ✅ | Word suggestions from history |
| Captured output | ❌ | ✅ | Parse errors/warnings from builds |
| Annotations | ❌ | ✅ | Mark up selected text |

---

## Debug & Development Tools

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Debug panel | ✅ | ❌ | **BossTerm unique** |
| Time-travel debugging | ✅ | ⚠️ | State snapshots every 100ms |
| I/O data collection | ✅ | ❌ | **BossTerm unique** |
| ANSI sequence visualizer | ✅ | ❌ | **BossTerm unique** |
| File logging | ✅ | ⚠️ | Configurable I/O logs |

---

## Performance Optimizations

| Feature | BossTerm | iTerm2 | Notes |
|---------|----------|--------|-------|
| Adaptive debouncing | ✅ | ⚠️ | **BossTerm unique** - 99.5% redraw reduction |
| Snapshot-based rendering | ✅ | ❌ | Lock-free UI during streaming |
| Copy-on-write buffers | ✅ | ❌ | 99.5% allocation reduction |
| Type-ahead prediction | ✅ | ❌ | **BossTerm unique** - latency masking |
| GPU acceleration | ✅ | ✅ | BossTerm: Skia (Metal/OpenGL/DirectX), iTerm2: Metal |

---

## Platform Support

| Platform | BossTerm | iTerm2 |
|----------|----------|--------|
| macOS | ✅ | ✅ |
| Linux | ✅ | ❌ |
| Windows | ✅ | ❌ |

---

## Summary

### BossTerm Strengths

1. **Cross-platform** - Single codebase for macOS, Linux, and Windows
2. **Debug tools** - Time-travel debugging, ANSI sequence visualizer, I/O capture
3. **Performance engineering** - Snapshot rendering, copy-on-write buffers, adaptive debouncing
4. **Type-ahead prediction** - Latency masking for high-latency connections (SSH)
5. **Modern stack** - Kotlin/Compose Desktop with Skia rendering
6. **Extensible actions** - ActionRegistry for custom keyboard shortcuts
7. **Grapheme-aware rendering** - ICU4J-based Unicode handling

### iTerm2 Strengths

1. **Mature ecosystem** - 18+ years of development
2. **Profile system** - Complete session configuration management
3. **Triggers** - Powerful regex-based automation
4. **tmux integration** - Native window management for tmux sessions
5. **Python scripting API** - Full automation and customization
6. **AI chat** - Built-in LLM assistant
7. **Instant Replay** - Time-travel through terminal history
8. **Status bar** - Configurable system information display

---

## Feature Gap Priorities

### High Priority (Frequently Used)

| Feature | Description | Complexity |
|---------|-------------|------------|
| Profile system | Save/load session configurations | Medium |
| Triggers | Regex-based automation actions | High |
| Hotkey window | System-wide terminal shortcut | Medium |
| Paste history | Access previous clipboard items | Low |

### Medium Priority (Power User)

| Feature | Description | Complexity |
|---------|-------------|------------|
| Status bar | Git branch, system info display | Medium |
| Copy mode | Vim-style keyboard selection | Medium |
| Global search | Search across all tabs | Low |
| Autocomplete | Word suggestions from history | Medium |

### Lower Priority (Niche)

| Feature | Description | Complexity |
|---------|-------------|------------|
| tmux integration | Native window management | Very High |
| Python scripting API | Full automation | Very High |
| Session restoration | Crash recovery | High |
| AI chat | Built-in LLM | High |

---

## Conclusion

BossTerm and iTerm2 serve different audiences:

- **Choose BossTerm** if you need cross-platform support, advanced debugging tools, or prefer modern Kotlin/Compose technology
- **Choose iTerm2** if you're on macOS and need advanced automation (triggers, profiles, scripting), tmux integration, or AI features

Both terminals offer excellent core terminal emulation with full Unicode support, mouse reporting, and shell integration. BossTerm's unique strengths lie in its cross-platform nature and performance optimizations, while iTerm2 excels in automation and macOS-specific integrations.

---

## See Also

- [[Features]] - BossTerm feature overview
- [[Configuration]] - BossTerm settings reference
- [[Shell Integration|Shell-Integration]] - OSC 7 and OSC 133 setup
- [iTerm2 Documentation](https://iterm2.com/documentation.html)
