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

One consequence rides along, and it is why the app no longer follows the system appearance: with
`apple.awt.transparentTitleBar` the title text sits over OUR background, but AppKit still picks that
text's colour from the window appearance. Following the system therefore guarantees an unreadable
title whenever the two disagree - confirmed by hand, a light system appearance drew a near-black
title on the near-black default background. `nativeTitleBarAppearance` derives
`apple.awt.application.appearance` from the terminal background instead, which fixes both
directions. It has to be applied before AWT boots, and it is app-wide, so other AWT chrome (the
native context menus) follows the terminal background too.

### OSC 1 names the TAB, OSC 2 names the WINDOW

xterm's split, and it is load-bearing across `TabController`, `TabbedTerminal` and `ProperTerminal`.
Do not fold them into one field: apps set them to different strings deliberately (oh-my-zsh emits a
short OSC 1 and a long OSC 2 back to back from `precmd`), so merging makes the tab label depend on
which arrived last. OSC 0 sets both.

The window title resolves `resolveWindowTitle`: a Rename… custom title, then OSC 2, then the tab's
own title. The OSC 2 slot is cleared at prompt start so a program that set one stops naming the
window after it exits - which needs OSC 133, so it does not happen inside tmux/screen or without the
shell integration.

### The submit character is CR, and LF is not a fallback (measured)

Anything writing a command to a PTY programmatically must end it with `\r`, via `submitLine()` in
`compose-ui/.../util/TerminalSubmit.kt`. Never `\n`, never `\r\n`.

`TerminalKeyEncoder` sends CR for the Enter key and `pasteText` normalizes pasted newlines to CR, so
a writer using LF is not simulating a keypress. On a Unix pty you cannot tell — the line discipline's
`ICRNL` maps CR to NL on input, so LF submits too, which is why LF sat in the AI-launch path, the git
menu items, `run_command` and `run_in_panel` for years. ConPTY does no such translation:

| sent | runs? | after |
|---|---|---|
| `\n` | **no** | text stranded at a `>>` continuation prompt (LF is Ctrl+J → PSReadLine inserts a newline) |
| `\r` | yes | clean prompt |
| `\r\n` | yes | **the NEXT prompt is `>>`** — the orphan LF is typed into it |
| `\n\r` | yes | runs, but the command echoes on a continuation line |

Measured against Windows PowerShell 5.1 over ConPTY by writing each form to a real pane and reading
the scrollback back. The `\r\n` row is the trap: it looks correct because the command works, and the
damage lands on the user's next keystrokes.

`send_input` (MCP) is the deliberate exception — it stays byte-verbatim, because a bare LF is a
keystroke a caller may actually want (a newline inside a multi-line prompt to an AI CLI) and
rewriting it would remove the only way to send one. Its tool description carries the warning instead.

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
- `compose-ui/.../window/MacOSWindowGlass.kt` — native behind-window material for custom-title-bar
  windows using the dedicated Liquid Glass Light/Dark themes, with `windowGlassMode` set to
  `bars` or `window`. Other themes ignore glass preferences. Controls live in Themes;
  `windowGlassOpacity` is independent of ordinary `backgroundOpacity`. Prefers NSGlassEffectView on macOS 26+,
  otherwise NSVisualEffectView. This is a native backdrop behind Compose, not native controls;
  never replace NSWindow.contentView (AWT sends its own mouse selectors directly to that view).
  Uses Skiko's exact NSWindow handle and the
  AppKit main dispatch queue; never use Swing's EDT for AppKit calls, title matching for native
  window lookup, or a variadic/struct-return objc_msgSend mapping. The owning window closes the
  controller on disposal. `LocalNativeWindowGlass` is true only after installation succeeds,
  allowing translucent sidebar chrome while embedded/non-macOS hosts keep their fallback.
  `windowGlassTint` controls the Compose theme overlay; `windowGlassStyle` chooses the public
  regular/clear native styles. Material sizing follows logical AWT points with no Auto Layout
  constraints on AWT's content view (constraints can fight fullscreen sizing).
- `compose-ui/.../window/WindowPlacementController.kt` — captures normal size/position before
  fullscreen/maximize. Custom-title-bar macOS uses MacOSFullscreen to enter a real fullscreen
  Space through the JDK macOS API, with java.desktop/com.apple.eawt exported by the launcher.
  Keep Java/AWT undecorated to retain the alpha backing store, then configure the underlying
  NSWindow as titled + resizable + fullSizeContentView with a transparent title bar and native traffic lights. This gives AppKit ownership of the rounded silhouette and fullscreen animation.
  Do not layer-clip the frame/content or defer toggling by Compose frames: those attempts did
  not fix the system animation snapshot. Do not resize to screen bounds after entry either.
  Restore saved bounds only after the native exit notification; never resize during animation.
  Custom maximize uses logical usable-screen bounds. Always use the controller's effective
  placement for glass corners and UI state; refresh the native backdrop after transitions.

**Cross-platform glass**

- `NativeWindowGlass.kt` selects the backend without loading foreign native libraries. The existing
  MacOSWindowGlass owns AppKit work; Windows/X11 updates run on the EDT. Close controllers with their window.
- Windows uses documented DWM Desktop Acrylic (`DWMWA_SYSTEMBACKDROP_TYPE`, Windows 11 build 22621+).
  Check HRESULTs. Skia retains alpha but the top-level HWND must not stay AWT-layered: DWM owns the backdrop.
  Restore the Swing content pane, alpha state, and frame margins when disabled. No undocumented Windows 10 accent API.
- Linux targets AWT's X11 backend, including XWayland. Require an active compositor and KWin's advertised
  `_KDE_NET_WM_BLUR_BEHIND_REGION` root property before setting a blur region. Do not treat atom existence
  as support, or a Wayland pointer as an XID. Native Wayland requires a separate future backend.
  A timer detects runtime compositor/effect changes; stop it on disposal. Auxiliary X11 windows have
  custom drag/close chrome and a resize grip because AWT forbids decorated alpha windows.
- `surfaceOpacity` keeps glass themes opaque until installation succeeds, without changing saved settings.
  The UI calls these Glass Light/Dark outside macOS; persisted `liquid-glass-*` IDs stay the same.
  Regular/Clear style is macOS-only. Settings/dialogs use the same native capability fallback as the main window.
- `NativeGlassBackendTest` checks native-call failures, rollback, compositor capability loss, and cleanup
  using fake native APIs. These tests do not establish visual correctness on Windows or Linux hardware.

**Components**
- `GlassAuxiliaryWindow.kt` supplies shared native glass for settings and auxiliary Window/
  DialogWindow hosts. AWT stays undecorated on macOS while the native frame supplies the title
  and controls. Keep dialog resizability, owner/modality, close handling, and focus callbacks.
  `GlassDialogs.kt` preserves Material dialog behavior and installs a separate native backdrop
  only when a modal owns its own ComposeDialog. `DialogTheme` changes surface alpha through
  scoped locals; it must not make text/icons translucent or change embedded hosts by default.

- Standalone `TabbedTerminal.headerContent` places its live `StatusStrip` in the custom title
  bar's right action slot. Embedded callers without the slot retain the overlay. Keep status
  state/actions owned by `TabbedTerminal`; never duplicate sharing or voice controllers in a header.
  `LocalWindowChromeOpacity` makes status pills and update banners follow window transparency.

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
- `VoiceBackend.kt` - **which service carries a call**. Both backends speak the SAME wire protocol
  (the OpenAI Realtime event set), which is the only reason this is a provider choice and not a
  second voice stack: `HostVoiceCallController` is unchanged between them. `VoiceEndpointResolver`
  is the pure decision and the place to look first - it deliberately has **no fallback between
  backends**, because silently re-routing a LOCAL call through OpenAI would bill the user and put
  their microphone audio on the network, neither of which someone choosing local is asking for.
- `voice/local/` - the managed local runtime (huggingface/speech-to-speech, Apache-2.0, pinned).
  `LocalVoiceInstall` holds the layout/command construction as pure functions; `LocalVoiceRuntime`
  owns the child process. Verified compatible before adoption: it serves `/v1/realtime`, implements
  every event the controller uses (including `response.output_audio.*`, NOT the legacy
  `response.audio.*` - a server on the old spelling connects and is silent), carries tool calls via
  `session.update` + `function_call_output`, and speaks PCM16 mono 24 kHz, identical to
  `VoiceAudioIo.FORMAT`. `--host 127.0.0.1` is passed explicitly because the server performs **no
  authentication**: the bind address is a security boundary, not a preference.
  **Remote share viewers cannot use the local backend** - the viewer negotiates WebRTC with OpenAI
  directly and has no route to host loopback - so `VoiceCallService` reports `local_backend` and
  refuses, rather than falling back to the paid path.
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

## Default Appearance

Fresh settings use Liquid Glass Dark, custom title bar, whole-window Regular glass,
50% tint and 50% glass opacity. Existing saved choices remain authoritative.
`ThemeDefaultsTest` checks theme/palette ordering and fresh settings together.

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
state.write("command\n")             // Send text (newlines normalized to CR — submits everywhere)
state.sendCtrlC(tabIndex = 0)        // Target tab by index
state.write("cmd\n", tabId = "id")   // Target tab by ID
```

`write()` / `writeToFocusedPane()` are the embedder-facing convenience and normalize newlines, so
`"\n"` presses Enter on every platform. The verbatim path underneath is `TerminalTab.writeUserInput()`
— reachable on the tabbed API via the public `activeTab`, as `EmbeddableTerminalState.writeVerbatim()`
on the single-terminal API, and used by MCP `send_input`. Reach for it only when you mean keystrokes
rather than a command: a bare LF is Ctrl+J, which is how a multi-line prompt gets typed into an AI
CLI. See the CR note above.

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
