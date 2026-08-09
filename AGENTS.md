# BossTerm Development Guide

## Project Overview

- **Repository**: BossTerm (Kotlin/Compose Desktop terminal emulator)
- **Main Branch**: `master` | **Dev Branch**: `dev`
- **Settings**: `~/.bossterm/settings.json`
- **Secrets**: `~/.bossterm/voice.json` (chmod 600) - the Boss Calling OpenAI key, deliberately NOT in settings.json

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
Skiko can't read a typeface out of the jar (classloader issues), so bundled fonts must land on
disk first. Extract via `extractBundledFont()` in `compose-ui/.../util/FontUtils.kt` - do NOT go
back to a per-launch `File.createTempFile`:

- `classLoader.getResourceAsStream("fonts/….ttf")` → `~/.bossterm/fonts/<name>-<sha256-12>.ttf`
  (content-addressed, written once per font per upgrade, fsync + atomic rename, superseded copies
  pruned on the write path so a warm launch stays zero-I/O) → `Font(file = …)`
- A fresh multi-megabyte file per launch is the worst case for endpoint-security scanning, and
  Skia's font path calls `dlsym` (CoreText weight mapping) - see the deadlock note below

### Launch hangs with a window that never appears (macOS)
Symptom: the process is alive, logs look healthy (MCP bound, settings saved), but no window paints,
and the JVM sits near 0% CPU. This is not Gatekeeper, MDM policy, or a failed launch - it is
**dyld loader-lock contention**:

```
AWT-EventQueue-0  Skia font load → SkCTFontGetNSFontWeightMapping → dlsym
                  → withLoadersReadLock → __ulock_wait2        (blocked)
other thread      dlopen → Loader::mapSegments → fcntl          (holds the WRITE lock,
                  ^ code-signature validation on first map       stalled in the kernel)
```

Anything that stalls that first-map signature check - an EDR/endpoint-security extension scanning
a newly written file is the common one - blocks the event thread before the first frame.

Diagnose (do not guess at MDM):
```bash
jcmd <pid> Thread.print | grep -A4 '"AWT-EventQueue-0"'   # _nMakeFromFile / dlsym at the top?
sample <pid> 3 -file /tmp/s.txt                           # native leaf: __ulock_wait2 under dyld?
ps -p <pid> -o %cpu=,time=,etime=                         # blocked (low CPU, high elapsed) vs spinning
```
It clears itself once the scan returns; signature results are cached per file, so warm launches are
fine and a clean build re-arms it. Note that querying windows via `osascript`/System Events makes
macOS `dlopen` the accessibility bundles into the target - that adds a waiter on the very same lock
and reports `0 windows` as a false negative. Use `jcmd`/`sample` instead.

### AWT cannot make a decorated frame transparent (measured)

Do not spend an afternoon rediscovering this. AWT allocates an alpha-capable backing store only for
windows it considers translucent, and refuses that for decorated frames -
`IllegalComponentStateException: The frame is decorated`, on both `setBackground(alpha<255)` and
`setOpacity`, before and after the window is shown. Forcing the `NSWindow` non-opaque underneath is
not enough either: measured through the peer, `CPlatformWindow.setOpaque(false)` runs and the window
reports `isOpaque = NO`, and alpha still composites onto black because the surface has no alpha
channel. The same alpha-0 fill in an **undecorated** window is see-through.

macOS itself allows it, which is how Terminal.app is transparent with traffic lights - it is AWT's
window that cannot be. So transparency belongs to the undecorated path only, which is exactly what
`useNativeTitleBar = false` selects. See `compose-ui/.../window/NativeTitleBarStyle.kt`.

One consequence rides along: with `apple.awt.transparentTitleBar` AppKit draws the title text in the
colour the window's effective appearance dictates, not one picked to contrast with what shows
through - so a light system appearance draws a dark title over the dark terminal background
(issue #368).

### OSC 1 names the TAB, OSC 2 names the WINDOW

xterm's split, and it is load-bearing across `TabController`, `TabbedTerminal` and `ProperTerminal`.
Do not fold them into one field: apps set them to different strings deliberately (oh-my-zsh emits a
short OSC 1 and a long OSC 2 back to back from `precmd`), so merging makes the tab label depend on
which arrived last. OSC 0 sets both.

The window title resolves `resolveWindowTitle`: a Rename… custom title, then OSC 2, then the tab's
own title. The OSC 2 slot is cleared at prompt start so a program that set one stops naming the
window after it exits - which needs OSC 133, so it does not happen inside tmux/screen or without the
shell integration.

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

**Window**
- `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/window/NativeTitleBarStyle.kt`
  (full window content, the title bar inset, and the transparency finding above)

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

**Boss Calling (voice)** - `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/voice/`
- `VoiceToolCatalog.kt` - the curated tool surface both call surfaces advertise; a new tool needs a
  schema here, a handler in each executor, and a description in `HostVoiceCallController.describeTool`
  AND `viewer.js voiceDescribeTool`
- `VoiceCallService.kt` - share-viewer policy: control role, share scope, mint budget, call tokens
- `HostVoiceCallController.kt` - the in-app call (JDK WebSocket + `javax.sound.sampled`, no WebRTC)
- `VoiceAgentStorage.kt` - the chmod-600 key file
- `VoiceKeySource.kt` - **where the key comes from**: an embedder-supplied source first, then
  `voice.json`. Read `resolve()`, never `VoiceAgentStorage.load()` directly, on any path that needs
  a usable key. BossTerm can't depend on `boss-plugin-api`, so the seam is a plain lambda that
  `terminal-tab` fills from BOSS's `Settings → AI Providers` (its **OpenAI** provider specifically -
  Realtime is OpenAI's, so handing over whatever provider is merely *active* would send an
  `sk-ant-…` key to `api.openai.com`). Only the credential is shared: the Realtime model stays
  `TerminalSettings.voiceCallModel`, because an `LlmConfig.modelId` is a *chat* model.
  Process-wide rather than a `TabbedTerminal` parameter because three unrelated paths read the key
  (in-app call, share mint, share advertisement). A throwing embedder falls through to the file
  rather than taking a call down - it runs host code across a plugin classloader.
  The daemon is deliberately excluded: it is headless with no embedder in the process.
  `keyStamp()` still only sees file edits, so an embedder key is noticed on the next read rather
  than pushed - which is why `MirrorShare`'s presence check calls it per read alongside its two
  file-backed caches.
- `VoiceAgentCustomization.kt` - the embedder seam for the agent's instructions (the button's label
  is a `TabbedTerminal` parameter instead, alongside `contextMenuItems`)
- `VoiceToolSource.kt` - the EMBEDDER's tool surface (`TabbedTerminal(voiceToolSource = …)`), merged
  into the in-app agent's tools by `CompositeVoiceToolExecutor`. In-app only; shares keep the
  curated catalog. `VoiceToolPolicy.kt` holds the two safety tiers (never-advertise vs
  confirmation-gated) and `VoiceConfirmationGate.kt` the speech interlock behind the second
- Two surfaces, one tool set: the viewer path is browser↔OpenAI over WebRTC with an ephemeral secret;
  the in-app path is host↔OpenAI over WebSocket with the standard key. They key "agent is speaking"
  off *different* event families (`output_audio_buffer.*` vs `response.output_audio.*`), so a change
  to one is not automatically right for the other.

## Features Summary

- **Tabs**: Ctrl+T/W/Tab, Ctrl+1-9
- **Search**: Ctrl+F (regex, case-sensitive)
- **Clipboard**: Copy-on-select, middle-click paste
- **Mouse**: vim/tmux support, Shift bypasses
- **Context menus**: real OS menus (`java.awt.PopupMenu` -> `NSMenu`) on **macOS only**;
  `TerminalSettings.useNativeContextMenus` turns them off, and Windows/Linux always get the
  BOSS-themed Swing menu. Widening the platform gate is `shouldUseNativeMenus` in
  `features/ContextMenuController.kt`, but measure `show()` blocking and dark-mode behaviour on
  that platform first - the macOS facts (non-blocking `show()`, nothing cancels an open menu, no
  dismissal event, display-only `MenuShortcut`) were measured and do not transfer
- **AI Menu**: open-source-first (`AIAssistants.AI_ASSISTANTS_OSS_FIRST`) - Hermes Agent, Kimi Code
  CLI, OpenClaw, OpenCode, then Codex, Gemini CLI, Grok Build, then Claude Code. Ordering is derived
  from each entry's `openSource`/`localModels` flags, not declaration order. Adding a CLI means ONE entry in
  `AIAssistants.BUILTIN` plus (if it speaks MCP) one `McpAttachTarget` - everything else (menus,
  detection, onboarding, settings, remote/share menus) reads the registry. `AttachTargetCoverageTest`
  fails the build if the two registries drift apart
- **Local Models**: Ollama lives in its own `LOCAL_MODEL_RUNTIME` category - it serves models rather
  than acting as an agent, so it has no auto-mode flag and no MCP attach target
- **Boss Calling**: voice-call the session's AI agent - the status-strip pill (in-app) or the share
  viewer's bottom bar (remote). "Boss Calling" is the FEATURE and is fixed; the in-app button's
  label is `TabbedTerminal(callLabel = …)` - null renders "Call BossTerm", BossConsole passes
  "Call Boss". The viewer's button is a static web asset and stays "Call BossTerm". Needs an OpenAI
  key - from an embedder-registered source if there is one, else `~/.bossterm/voice.json` (see
  `VoiceKeySource.kt`); remote calls additionally require control of the share
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
*Last Updated: July 28, 2026*
