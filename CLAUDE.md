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

## Git Workflow

```bash
git checkout dev
git add . && git commit -m "Message

Generated with [Claude Code](https://claude.com/claude-code)"
git push origin dev
gh pr create --base master --head dev --title "Title" --body "Description"
```

Do NOT include `Co-Authored-By: Claude` in commits.

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
- Task tool for searches, specialized tools over bash
- No backwards-compatibility hacks

## Shell Integration

See `.claude/rules/shell-integration.md` for OSC 7/133 setup.

---
*Last Updated: January 12, 2026*
