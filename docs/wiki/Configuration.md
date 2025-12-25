# Configuration

BossTerm settings are stored in `~/.bossterm/settings.json`. You can edit this file directly or use the Settings UI (Ctrl/Cmd+,).

---

## Settings UI

Open the Settings panel:
- **Keyboard**: Ctrl/Cmd+, (comma)
- **Menu**: View > Settings

The Settings UI provides a visual interface for all options with live preview.

---

## Settings File

Location: `~/.bossterm/settings.json`

### Example Configuration

```json
{
  "fontSize": 14,
  "fontName": "JetBrains Mono",
  "lineSpacing": 1.0,
  "copyOnSelect": true,
  "pasteOnMiddleClick": true,
  "bufferMaxLines": 10000,
  "caretBlinkMs": 500,
  "enableMouseReporting": true,
  "performanceMode": "balanced",
  "notifyOnCommandComplete": true,
  "notifyMinDurationSeconds": 5,
  "alwaysShowTabBar": false
}
```

---

## Settings Reference

### Visual Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `fontSize` | Float | `14` | Font size in SP |
| `fontName` | String? | `null` | System font name (null = bundled MesloLGS) |
| `lineSpacing` | Float | `1.0` | Line height multiplier |
| `defaultForeground` | String | `"0xFFFFFFFF"` | Foreground color (ARGB hex) |
| `defaultBackground` | String | `"0xFF000000"` | Background color (ARGB hex) |
| `backgroundOpacity` | Float | `1.0` | Window opacity (0.0-1.0) |
| `windowBlur` | Boolean | `false` | Enable blur behind transparent window |
| `blurRadius` | Float | `30` | Blur intensity (1-100) |
| `backgroundImagePath` | String | `""` | Path to background image |
| `backgroundImageOpacity` | Float | `0.3` | Background image opacity |
| `showUnfocusedOverlay` | Boolean | `true` | Dim window when unfocused |

### Behavior Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `useLoginSession` | Boolean | `true` | Use login shell on macOS |
| `initialCommand` | String | `""` | Command to run on new tabs |
| `copyOnSelect` | Boolean | `false` | Auto-copy selected text |
| `pasteOnMiddleClick` | Boolean | `true` | Middle-click paste |
| `emulateX11CopyPaste` | Boolean | `false` | Separate selection clipboard |
| `scrollToBottomOnTyping` | Boolean | `true` | Auto-scroll when typing |
| `altSendsEscape` | Boolean | `true` | Alt key sends ESC prefix |
| `enableMouseReporting` | Boolean | `true` | Forward mouse events to apps |
| `audibleBell` | Boolean | `true` | Play sound on bell |
| `visualBell` | Boolean | `true` | Flash screen on bell |

### Tab Bar Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `alwaysShowTabBar` | Boolean | `false` | Show tab bar even with single tab |

### Performance Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `performanceMode` | String | `"balanced"` | `"latency"`, `"balanced"`, or `"throughput"` |
| `maxRefreshRate` | Int | `60` | Max FPS (0 = unlimited) |
| `bufferMaxLines` | Int | `10000` | Scrollback buffer size |
| `caretBlinkMs` | Int | `500` | Cursor blink rate (0 = no blink) |

### Notification Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `notifyOnCommandComplete` | Boolean | `true` | Enable command notifications |
| `notifyMinDurationSeconds` | Int | `5` | Minimum command duration to notify |
| `notifyShowExitCode` | Boolean | `true` | Include exit code in notification |
| `notifyWithSound` | Boolean | `true` | Play notification sound |

### Split Pane Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `splitDefaultRatio` | Float | `0.5` | Default split ratio (0.0-1.0) |
| `splitMinimumSize` | Float | `0.1` | Minimum pane size (0.0-0.5) |
| `splitFocusBorderEnabled` | Boolean | `true` | Show border on focused pane |
| `splitFocusBorderColor` | String | `"0xFF4A90E2"` | Focus border color |
| `splitInheritWorkingDirectory` | Boolean | `true` | New splits inherit CWD |

### Clipboard Settings (OSC 52)

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `clipboardOsc52Enabled` | Boolean | `true` | Enable OSC 52 clipboard |
| `clipboardOsc52AllowRead` | Boolean | `false` | Allow clipboard read (security) |
| `clipboardOsc52AllowWrite` | Boolean | `true` | Allow clipboard write |

---

## Performance Modes

| Mode | Best For |
|------|----------|
| **Balanced** | General use - good balance of responsiveness and throughput |
| **Latency** | SSH, vim, interactive commands - fastest response time |
| **Throughput** | Build logs, large files - maximum data processing speed |

---

## Themes

BossTerm includes built-in themes:

- Default (Dark)
- Dracula
- Solarized Dark/Light
- Nord
- Monokai
- One Dark
- Gruvbox

Access themes via Settings > Themes.

---

## Custom Fonts

To use a custom font:

1. Install the font on your system
2. Set `fontName` to the exact font name (e.g., `"JetBrains Mono"`)
3. Restart BossTerm

**Tip**: Use a monospace font with good Unicode coverage. Nerd Fonts are recommended.

---

## See Also

- [[Shell-Integration]] - Enable OSC 7/133 features
- [[Troubleshooting]] - Fix common issues
