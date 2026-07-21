# BossTerm Development Guide

## Project Overview

- **Repository**: BossTerm (Kotlin/Compose Desktop terminal emulator)
- **Main Branch**: `master` | **Dev Branch**: `dev`
- **Settings**: `~/.bossterm/settings.json`

## Build & Run

```bash
./gradlew :bossterm-app:run --no-daemon          # Main app
./gradlew :embedded-example:run --no-daemon      # Embedded example
./gradlew :tabbed-example:run --no-daemon        # Tabbed example
pkill -9 -f "gradle"                             # Kill stuck gradle
```

## JVM Requirements (Java 16+)

For full functionality on Java 16+, add these JVM arguments:

```
--add-opens java.desktop/java.awt=ALL-UNNAMED        # Windows: HWND access for global hotkeys
--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED     # Linux: WM_CLASS for desktop integration
```

Without these flags:
- **Windows**: Global hotkey window toggle falls back to standard show/hide
- **Linux**: Desktop integration (taskbar grouping) may not work correctly

Add to IDE run configurations or gradle.properties for development.

## Git Workflow

```bash
git checkout dev
git add . && git commit -m "Message"
git push origin dev
gh pr create --base master --head dev --title "Title" --body "Description"
```

Do NOT include AI co-author attribution in commits unless the user explicitly requests it.

## Critical Technical Patterns

### Font Loading
Skiko has classloader issues. Use InputStream + temp file:
- `classLoader.getResourceAsStream("fonts/MesloLGSNF-Regular.ttf")` → temp file → `Font(file = tempFile)`

### Emoji Rendering
Skia ignores variation selectors (U+FE0F). Peek-ahead to detect, switch to `FontFamily.Default`, render as unit.

### Symbol Fallback
macOS: `FontFamily.Default`. Linux: bundled `NotoSansSymbols2-Regular.ttf`.

### Snapshot Rendering
`createIncrementalSnapshot()` for lock-free rendering. 94% lock reduction, 99.5% allocation reduction.

### Blocking Data Stream
Single `BossEmulator` with `BlockingTerminalDataStream` prevents CSI truncation.

### Platform Detection
Use `ShellCustomizationUtils` for platform checks - never raw `System.getProperty("os.name")`:
- `ShellCustomizationUtils.isWindows()`
- `ShellCustomizationUtils.isMacOS()`
- `ShellCustomizationUtils.isLinux()`

Located in: `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/shell/ShellCustomizationUtils.kt`

## Key Files

**Rendering**
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/ui/ProperTerminal.kt`
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/rendering/TerminalCanvasRenderer.kt`

**Buffer**
- `bossterm-core-mpp/src/jvmMain/kotlin/com/bossterm/terminal/model/TerminalTextBuffer.kt`
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/pool/IncrementalSnapshotBuilder.kt`

**Components**
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/TabbedTerminal.kt`
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/EmbeddableTerminal.kt`
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/TabController.kt`

**AI/VCS**
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/ai/AIAssistantDefinition.kt`
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/ai/ToolCommandProvider.kt`

**Settings**
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/settings/TerminalSettings.kt`
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/actions/BuiltinActions.kt`

## Features Summary

- **Tabs**: Ctrl+T/W/Tab, Ctrl+1-9
- **Search**: Ctrl+F (regex, case-sensitive)
- **Clipboard**: Copy-on-select, middle-click paste
- **Mouse**: vim/tmux support, Shift bypasses
- **AI Menu**: Claude Code, Codex, Gemini, OpenCode
- **Debug**: Ctrl+Shift+D

## Programmatic API

```kotlin
state.sendCtrlC()                    // Interrupt
state.write("command\n")             // Send text
state.sendCtrlC(tabIndex = 0)        // Target tab by index
state.write("cmd\n", tabId = "id")   // Target tab by ID
```

## Development Guidelines

- **NEVER run the app** - user handles all testing. Maximum allowed: `./gradlew build` to check for compile errors
- Do NOT capture screenshots
- Use `remember {}` for expensive computations
- Use `rg` for searches and prefer specialized tools when available
- No backwards-compatibility hacks

## Shell Integration

See `.claude/rules/shell-integration.md` for OSC 7/133 setup.

---
*Last Updated: January 12, 2026*
