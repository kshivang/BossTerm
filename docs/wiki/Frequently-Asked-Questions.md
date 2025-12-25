# Frequently Asked Questions

Common questions about BossTerm.

---

## General

### What is BossTerm?

BossTerm is a modern terminal emulator built with Kotlin and Jetpack Compose Desktop. It provides both a standalone terminal application and embeddable components for integrating terminal functionality into your own applications.

### What platforms does BossTerm support?

- **macOS** (Intel and Apple Silicon)
- **Linux** (x86_64 and ARM64)
- **Windows** (experimental)

### Is BossTerm open source?

Yes, BossTerm is open source and available on [GitHub](https://github.com/kshivang/BossTerm).

---

## Installation

### Where can I download BossTerm?

See the [[Installation]] page for download options:
- Pre-built binaries from GitHub Releases
- Homebrew (macOS)
- Build from source

### What are the system requirements?

- **JDK**: 17 or higher
- **macOS**: 10.15+ (Catalina or later)
- **Linux**: Modern distribution with GTK3
- **Windows**: Windows 10/11

### How do I update BossTerm?

**Homebrew (macOS)**:
```bash
brew upgrade bossterm
```

**Manual**: Download the latest release from GitHub.

---

## Features

### Does BossTerm support tabs?

Yes! BossTerm supports multiple tabs with keyboard shortcuts:
- **Ctrl/Cmd+T**: New tab
- **Ctrl/Cmd+W**: Close tab
- **Ctrl+Tab**: Next tab
- **Ctrl+Shift+Tab**: Previous tab
- **Ctrl/Cmd+1-9**: Jump to specific tab

### Does BossTerm support split panes?

Yes, you can split terminals horizontally or vertically:
- **Ctrl/Cmd+D**: Split vertically
- **Ctrl/Cmd+Shift+D**: Split horizontally (Note: Debug panel uses this shortcut in some configurations)
- **Ctrl/Cmd+Option+Arrows**: Navigate between panes

### Does BossTerm support tmux/screen?

Yes, BossTerm is fully compatible with terminal multiplexers like tmux and screen. Mouse reporting works correctly within these applications.

### Can I use vim/neovim in BossTerm?

Absolutely! BossTerm supports:
- Full mouse reporting for vim
- Alternate screen buffer
- True color (24-bit)
- Special key sequences

---

## Appearance

### How do I change the font?

Edit `~/.bossterm/settings.json`:
```json
{
  "fontSize": 14,
  "fontName": "JetBrains Mono"
}
```

Or use the Settings UI (Ctrl/Cmd+,).

### How do I change colors?

BossTerm supports custom colors via settings:
```json
{
  "defaultForeground": "0xFFFFFFFF",
  "defaultBackground": "0xFF1E1E2E"
}
```

### Does BossTerm support transparency?

Yes:
```json
{
  "backgroundOpacity": 0.9,
  "windowBlur": true,
  "blurRadius": 30
}
```

Note: Window blur requires compositor support on Linux.

### Can I use a background image?

Yes:
```json
{
  "backgroundImagePath": "/path/to/image.png",
  "backgroundImageOpacity": 0.3
}
```

---

## Unicode & Emoji

### Does BossTerm support emoji?

Yes, BossTerm has full emoji support including:
- Basic emoji
- Emoji with variation selectors (☁️)
- Skin tone modifiers (👍🏽)
- ZWJ sequences (👨‍👩‍👧‍👦)

### Why are some characters showing as boxes?

This usually means the font doesn't have the required glyphs. Solutions:
1. BossTerm includes MesloLGS Nerd Font with good coverage
2. Use a [Nerd Font](https://www.nerdfonts.com/) for maximum glyph support
3. Enable symbol fallback in settings

### Does BossTerm support CJK (Chinese/Japanese/Korean)?

Yes, BossTerm supports:
- CJK characters with proper width calculation
- Input Method Editor (IME) support
- Toggle IME with Ctrl+Space

---

## Shell Integration

### What is shell integration?

Shell integration allows BossTerm to:
- Track your current working directory
- Notify you when commands complete
- Inherit directories in new tabs/splits

See [[Shell-Integration]] for setup instructions.

### Why aren't notifications working?

Check these requirements:
1. OSC 133 configured in your shell
2. `notifyOnCommandComplete: true` in settings
3. Window was unfocused when command completed
4. Command ran longer than `notifyMinDurationSeconds`

### How do I set up notifications?

Add shell integration to your shell config. See [[Shell-Integration]] for Bash, Zsh, and Fish examples.

---

## Embedding

### Can I embed BossTerm in my application?

Yes! BossTerm provides two embeddable components:
- **EmbeddableTerminal**: Single terminal component
- **TabbedTerminal**: Full-featured tabbed terminal

See [[Embedding-Guide]] and [[Tabbed-Terminal-Guide]].

### What dependencies do I need?

```kotlin
dependencies {
    implementation("com.risaboss:bossterm-core:<version>")
    implementation("com.risaboss:bossterm-compose:<version>")
}
```

### Is there a simple example?

```kotlin
@Composable
fun MyApp() {
    EmbeddableTerminal(
        modifier = Modifier.fillMaxSize()
    )
}
```

See [[Embedding-Guide]] for more examples.

---

## Performance

### BossTerm feels slow with heavy output

Switch to throughput mode:
```json
{
  "performanceMode": "throughput"
}
```

### There's lag when typing in vim

Switch to latency mode:
```json
{
  "performanceMode": "latency"
}
```

### What are the performance modes?

| Mode | Description | Best For |
|------|-------------|----------|
| `balanced` | Default, auto-adjusts | General use |
| `throughput` | Optimized for bulk output | cat, build logs |
| `latency` | Optimized for responsiveness | vim, ssh |

---

## Troubleshooting

### Terminal shows blank screen

See [[Troubleshooting#terminal-not-starting]].

### Keyboard input isn't working

Check if parent containers are stealing focus. See [[Troubleshooting#terminal-not-receiving-keyboard-input]].

### How do I report a bug?

1. Enable debug mode: `"debugModeEnabled": true`
2. Reproduce the issue
3. Open an issue on [GitHub](https://github.com/kshivang/BossTerm/issues) with:
   - BossTerm version
   - OS and version
   - Steps to reproduce
   - Debug panel output

---

## Comparison

### How does BossTerm compare to iTerm2?

| Feature | BossTerm | iTerm2 |
|---------|----------|--------|
| Platform | Cross-platform | macOS only |
| Embeddable | Yes | No |
| Technology | Kotlin/Compose | Objective-C |
| Open Source | Yes | Yes |
| Splits | Yes | Yes |
| Tabs | Yes | Yes |

### How does BossTerm compare to Alacritty?

| Feature | BossTerm | Alacritty |
|---------|----------|-----------|
| GPU Accelerated | No | Yes |
| Embeddable | Yes | No |
| Tabs | Yes | No (use tmux) |
| Splits | Yes | No (use tmux) |
| Configuration | JSON | YAML |

---

## Contributing

### How can I contribute?

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

### Where do I report issues?

Open an issue on [GitHub Issues](https://github.com/kshivang/BossTerm/issues).

---

## See Also

- [[Troubleshooting]] - Detailed solutions
- [[Configuration]] - All settings
- [[Shell-Integration]] - Shell setup

