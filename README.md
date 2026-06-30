<div align="center">

<img src="BossTerm.png" alt="BossTerm" width="140">

# BossTerm

**A blazing-fast terminal you can embed, share to any device, and hand to your AI.**

[![CI](https://github.com/kshivang/BossTerm/actions/workflows/test.yml/badge.svg)](https://github.com/kshivang/BossTerm/actions/workflows/test.yml)
[![Release](https://github.com/kshivang/BossTerm/actions/workflows/release.yml/badge.svg)](https://github.com/kshivang/BossTerm/releases)
[![Download DMG](https://img.shields.io/github/v/release/kshivang/BossTerm?label=Download%20DMG&logo=apple)](https://github.com/kshivang/BossTerm/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/com.risaboss/bossterm-core)](https://central.sonatype.com/namespace/com.risaboss)

</div>

| ⚡ [**Fast**](#performance) | 📱 [**Share**](#session-sharing) | 🤖 [**MCP for AI**](#bossterm-mcp) | 🧩 [**Embeddable**](#embedding-in-your-app) |
|:--:|:--:|:--:|:--:|
| 1,645 MB/s — edges out Alacritty | Watch & control from any device | Expose tabs to Claude Code & co. | Drop it into your Compose app |

A modern terminal emulator built with **Kotlin** and **Compose Desktop** — high-performance, deeply customizable, and feature-rich on macOS, Linux, and Windows.

## Performance

BossTerm delivers **industry-leading throughput** for developer workflows. Benchmarked against iTerm2, Terminal.app, and Alacritty (December 2025, **Latency Mode**):

### Raw Throughput @ 50MB (MB/s) - Higher is Better
```
BossTerm   ████████████████████████████████████████████████████ 1,645 MB/s ✓
Alacritty  ██████████████████████████████████████████████████   1,633 MB/s
iTerm2     █████████████████████████████████████████████████    1,599 MB/s
Terminal   ████████████████████████████████████████████████     1,491 MB/s
```

### Raw Throughput @ 1MB (MB/s) - Higher is Better
```
BossTerm   ████████████████████████████████████████████████████  364 MB/s ✓
iTerm2     ███████████████████████████████████                   255 MB/s
Terminal   ██████████████████████████████████                    249 MB/s
Alacritty  █████████████████████████████████                     233 MB/s
```

### Variation Selectors (chars/sec) - Higher is Better
```
BossTerm   ████████████████████████████████████████████████████  1.01M ✓
iTerm2     █████████████████████████████████████████████████     904K
Terminal   ████████████████████████████████████████████████      879K
Alacritty  █████████████████████████████████████████████         829K
```

### htop Simulation (ms) - Lower is Better
```
BossTerm   ████████████████████████████████████████████████      3.09 ms ✓
Terminal   █████████████████████████████████████████████████████ 3.21 ms
iTerm2     ██████████████████████████████████████████████████████ 3.55 ms
Alacritty  ███████████████████████████████████████████████████████ 3.72 ms
```

| Benchmark | BossTerm vs iTerm2 |
|-----------|-------------------|
| Raw Throughput (1MB) | **+43% faster** |
| Raw Throughput (5MB) | **+24% faster** |
| Raw Throughput (50MB) | **+3% faster** |
| Variation Selectors | **+12% faster** |
| CJK Characters | **+10% faster** |
| Powerline | **+10% faster** |
| htop Simulation | **+13% faster** |
| Git Diff Simulation | **+5% faster** |
| Flags Emoji | **+3% faster** |

> **Full benchmark details:** [benchmark/README.md](benchmark/README.md) | [Detailed Results](benchmark_results/BENCHMARK_SUMMARY.md)

## Installation

### Universal Installer (Recommended)

[![Install Script](https://github.com/kshivang/BossTerm/actions/workflows/test-install.yml/badge.svg)](https://github.com/kshivang/BossTerm/actions/workflows/test-install.yml)

The universal installer automatically detects your platform and installs BossTerm using the best method available.

| Platform | Command |
|----------|---------|
| **macOS / Linux** | `curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh \| bash` |
| **Windows (PowerShell)** | `iwr -useb https://raw.githubusercontent.com/kshivang/BossTerm/master/install.ps1 \| iex` |
| **Windows (CMD)** | `curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.bat -o install.bat && install.bat` |

**Features:**
- Auto-detects platform (macOS, Linux, Windows) and architecture (x64, ARM64)
- Uses the best installation method (Homebrew → DMG on macOS, Deb → RPM → Snap → JAR on Linux)
- Installs Java 17+ automatically if needed (Windows)
- Creates CLI launcher (`bossterm` command)
- Supports `--version`, `--uninstall`, `--dry-run`, and `--method` flags

**Common operations:**

```bash
# Install specific version
curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash -s -- --version 1.0.80

# Preview without installing
curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash -s -- --dry-run

# Force specific method (homebrew, dmg, deb, rpm, snap, jar)
curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash -s -- --method dmg

# Uninstall
curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash -s -- --uninstall
```

---

### Alternative Installation Methods

<details>
<summary><strong>macOS (Homebrew)</strong></summary>

```bash
brew tap kshivang/bossterm
brew install --cask bossterm
```

</details>

<details>
<summary><strong>macOS (DMG)</strong></summary>

Download the latest DMG from [GitHub Releases](https://github.com/kshivang/BossTerm/releases) and drag BossTerm to Applications.

</details>

<details>
<summary><strong>Linux (Debian/Ubuntu)</strong></summary>

```bash
# Download the .deb package from GitHub Releases
sudo dpkg -i bossterm_*_amd64.deb
sudo apt-get install -f  # Install dependencies if needed
```

</details>

<details>
<summary><strong>Linux (Fedora/RHEL)</strong></summary>

```bash
# Download the .rpm package from GitHub Releases
sudo dnf install bossterm-*.x86_64.rpm
```

</details>

<details>
<summary><strong>Linux (Snap)</strong></summary>

```bash
sudo snap install bossterm --classic
```

Or download the `.snap` file from [GitHub Releases](https://github.com/kshivang/BossTerm/releases) and install manually:

```bash
sudo snap install bossterm_*.snap --classic --dangerous
```

</details>

<details>
<summary><strong>JAR (Cross-platform)</strong></summary>

Requires Java 17+:

```bash
# Download bossterm-*.jar from GitHub Releases
java -jar bossterm-*.jar
```

</details>

<details>
<summary><strong>Build from Source</strong></summary>

```bash
git clone https://github.com/kshivang/BossTerm.git
cd BossTerm
./gradlew :bossterm-app:run
```

</details>

## Features

- **Native Performance** - Built with Kotlin/Compose Desktop for smooth 60fps rendering
- **Multiple Windows** - Cmd/Ctrl+N opens new window, each with independent tabs
- **Multiple Tabs** - Ctrl+T new tab, Ctrl+W close, Ctrl+Tab switch
- **Split Panes** - Horizontal/vertical splits with Cmd+D / Cmd+Shift+D
- **Themes** - Built-in theme presets (Dracula, Solarized, Nord, etc.) with custom theme support
- **Window Transparency** - Adjustable opacity with background blur effects
- **Background Images** - Custom background images with blur and opacity controls
- **Xterm Emulation** - Full VT100/Xterm compatibility
- **True Color** - Full 256 color and 24-bit true color support
- **Mouse Reporting** - Click, scroll, and drag support for terminal apps (vim, tmux, htop, less, fzf)
- **Full Unicode** - Emoji (👨‍👩‍👧‍👦), variation selectors (☁️), surrogate pairs, combining characters
- **Nerd Fonts** - Built-in support for powerline symbols and devicons
- **Inline Images** - Display images in terminal via iTerm2's imgcat (OSC 1337)
- **Progress Bar** - Visual progress indicator for long-running commands (OSC 1337)
- **Search** - Ctrl/Cmd+F to search terminal history with regex support
- **Hyperlink Detection** - Auto-detect URLs, file paths, emails with Ctrl+Click to open
- **Copy/Paste** - Standard clipboard + copy-on-select + middle-click paste + OSC 52
- **Context Menu** - Right-click for Copy, Paste, Clear, Select All
- **Drag & Drop** - Drop files onto terminal to paste shell-escaped paths (iTerm2 style)
- **Auto-Scroll Selection** - Drag selection beyond bounds to scroll through history
- **IME Support** - Full Chinese/Japanese/Korean input method support
- **Visual Bell** - Configurable visual flash for BEL character
- **Command Notifications** - System notifications when long commands complete (OSC 133)
- **OSC 7 Support** - Working directory tracking for new tabs
- **Settings UI** - Full GUI settings panel with live preview
- **Debug Tools** - Built-in terminal debugging with Ctrl+Shift+D
- **Welcome Wizard** - First-time setup wizard for shell, tools, and AI assistants
- **Customizable** - JSON-based settings at `~/.bossterm/settings.json`
- **Session Sharing** - Watch or control a tab / window / all windows from any device — self-hosted, with a QR code and a mobile-friendly web viewer (LAN, Tailscale, or a zero-config Cloudflare tunnel)
- **Remote Control** - End-to-end encrypted; viewers get typing access on approval, or connect from another BossTerm as a native remote client
- **AI / MCP Server** - Built-in [Model Context Protocol](https://modelcontextprotocol.io) server exposes your terminals to Claude Code, Codex, Gemini CLI, and OpenCode
- **Session Daemon** - tmux-style background process (on by default) keeps your sessions, MCP server, and shares alive after the GUI closes — reopen to reattach; starts at login
- **Embeddable** - Drop the terminal into your own Kotlin/Compose Desktop app as a library (`com.risaboss:bossterm-compose`)

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl/Cmd+N | New window |
| Ctrl/Cmd+T | New tab |
| Ctrl/Cmd+W | Close tab/pane |
| Ctrl+Tab | Next tab |
| Ctrl+Shift+Tab | Previous tab |
| Ctrl/Cmd+1-9 | Jump to tab |
| Ctrl/Cmd+D | Split pane vertically |
| Ctrl/Cmd+Shift+D | Split pane horizontally |
| Ctrl/Cmd+Option+Arrow | Navigate between panes |
| Ctrl/Cmd+, | Open settings |
| Ctrl/Cmd+F | Search |
| Ctrl/Cmd+C | Copy |
| Ctrl/Cmd+V | Paste |
| Ctrl+Space | Toggle IME |

## Shell Integration

Enable working directory tracking and command completion notifications:

**Bash** (`~/.bashrc`):
```bash
# OSC 7 (directory tracking) + OSC 133 (command notifications)
__prompt_command() {
    local exit_code=$?
    echo -ne "\033]133;D;${exit_code}\007"  # Command finished
    echo -ne "\033]133;A\007"                # Prompt starting
    echo -ne "\033]7;file://${HOSTNAME}${PWD}\007"  # Working directory
}
PROMPT_COMMAND='__prompt_command'
trap 'echo -ne "\033]133;B\007"' DEBUG  # Command starting
```

**Zsh** (`~/.zshrc`):
```bash
# OSC 7 (directory tracking) + OSC 133 (command notifications)
precmd() {
    local exit_code=$?
    print -Pn "\e]133;D;${exit_code}\a"      # Command finished
    print -Pn "\e]133;A\a"                   # Prompt starting
    print -Pn "\e]7;file://${HOST}${PWD}\a"  # Working directory
}
preexec() { print -Pn "\e]133;B\a" }         # Command starting
```

This enables:
- New tabs inherit working directory from active tab
- System notifications when commands > 5 seconds complete while window is unfocused

## Project Structure

```
BossTerm/
├── bossterm-core-mpp/     # Core terminal emulation library
│   └── src/jvmMain/kotlin/ai/rever/bossterm/
│       ├── core/          # Core utilities and types
│       └── terminal/      # Terminal emulator implementation
├── compose-ui/            # Compose Desktop UI library (embeddable)
│   └── src/desktopMain/kotlin/ai/rever/bossterm/compose/
│       ├── ui/            # Main terminal composable (ProperTerminal)
│       ├── terminal/      # Terminal data stream handling
│       ├── input/         # Mouse/keyboard input handling
│       ├── rendering/     # Canvas rendering engine
│       ├── tabs/          # Tab management
│       ├── window/        # Window management (WindowManager)
│       ├── search/        # Search functionality
│       ├── debug/         # Debug tools
│       └── settings/      # Settings management
├── bossterm-app/          # Main BossTerm application
│   └── src/desktopMain/kotlin/ai/rever/bossterm/app/
│       └── Main.kt        # Application entry point
├── embedded-example/      # Example: single terminal embedding
├── tabbed-example/        # Example: tabbed terminal embedding
└── .github/workflows/     # CI configuration
```

## Configuration

Settings are stored in `~/.bossterm/settings.json`:

```json
{
  "fontSize": 14,
  "fontName": "JetBrains Mono",
  "copyOnSelect": true,
  "pasteOnMiddleClick": true,
  "scrollbackLines": 10000,
  "cursorBlinkRate": 500,
  "enableMouseReporting": true,
  "performanceMode": "balanced",
  "notifyOnCommandComplete": true,
  "notifyMinDurationSeconds": 5
}
```

### Performance Modes

BossTerm offers configurable performance optimization via Settings > Performance:

| Mode | Best For |
|------|----------|
| **Balanced** (default) | General use - good balance of responsiveness and throughput |
| **Latency** | SSH, vim, interactive commands - fastest response time |
| **Throughput** | Build logs, large files - maximum data processing speed |

> **Note:** For `fontName`, use a monospace font name installed on your system (e.g., "SF Mono", "Menlo", "JetBrains Mono"). If not set, BossTerm uses the bundled MesloLGS Nerd Font which includes powerline symbols.

## Embedding in Your App

BossTerm provides embeddable terminal libraries for Kotlin Multiplatform projects.

> **Full Documentation**: See [docs/embedding.md](docs/embedding.md) for the complete embedding guide, including custom context menus, focus management, and session persistence.

### Gradle Setup

**Maven Central** (recommended):

[![Maven Central](https://img.shields.io/maven-central/v/com.risaboss/bossterm-core)](https://central.sonatype.com/namespace/com.risaboss)

```kotlin
// build.gradle.kts
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

**JitPack** (alternative):

[![JitPack](https://jitpack.io/v/kshivang/BossTerm.svg)](https://jitpack.io/#kshivang/BossTerm)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.kshivang.BossTerm:bossterm-core-mpp:<version>")
    implementation("com.github.kshivang.BossTerm:compose-ui:<version>")
}
```

**GitHub Packages** (requires authentication):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/kshivang/BossTerm")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.risaboss:bossterm-core:<version>")
    implementation("com.risaboss:bossterm-compose:<version>")
}
```

### Usage

```kotlin
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.rememberEmbeddableTerminalState

@Composable
fun MyApp() {
    // Basic usage - uses default settings from ~/.bossterm/settings.json
    EmbeddableTerminal()

    // With custom settings path
    EmbeddableTerminal(settingsPath = "/path/to/settings.json")

    // With custom font (via settings)
    EmbeddableTerminal(settings = TerminalSettings(fontName = "JetBrains Mono"))

    // With callbacks
    EmbeddableTerminal(
        onOutput = { output -> println(output) },
        onTitleChange = { title -> window.title = title },
        onExit = { code -> println("Shell exited: $code") },
        onReady = { println("Terminal ready!") }
    )

    // Programmatic control
    val state = rememberEmbeddableTerminalState()

    Button(onClick = { state.write("ls -la\n") }) {
        Text("Run ls")
    }

    // Send control signals (useful for interrupting processes)
    Button(onClick = { state.sendCtrlC() }) {
        Text("Stop (Ctrl+C)")
    }

    EmbeddableTerminal(state = state)

    // Session preservation across navigation/visibility changes
    val persistentState = rememberEmbeddableTerminalState(autoDispose = false)

    if (showTerminal) {
        EmbeddableTerminal(state = persistentState)
    }
    // Terminal process keeps running even when hidden!

    // Don't forget to dispose when truly done:
    DisposableEffect(Unit) {
        onDispose { persistentState.dispose() }
    }

    // Custom PlatformServices - override process spawning, notifications, etc.
    // Uses Kotlin's 'by' delegation to wrap defaults while customizing specific services
    val customServices = object : PlatformServices by getPlatformServices() {
        val defaults = getPlatformServices()
        override fun getProcessService() = object : PlatformServices.ProcessService {
            private val delegate = defaults.getProcessService()
            override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig)
                : PlatformServices.ProcessService.ProcessHandle? {
                println("Spawning: ${config.command}")
                return delegate.spawnProcess(config)
            }
        }
    }
    EmbeddableTerminal(platformServices = customServices)
}
```

## Session Sharing

Watch — or hand over — a live terminal to any device, with **no cloud relay and no account**.
BossTerm runs the share server itself; viewers open a link (or scan a QR code) in any browser, or
connect from another BossTerm as a native client.

- **Scope**: share a single **tab** (with its splits), a whole **window**, or **all windows**
  (viewers see tabs grouped by window).
- **View or Control**: hand out a read-only **view** link or a **control** link (typing access).
  View-only viewers can request control mid-session and you approve from a prompt — required for
  public links by default, skipped on the LAN.
- **Reach**: LAN out of the box, or a public URL via **Tailscale** (Serve/Funnel) or a zero-config
  **Cloudflare** quick tunnel (the default — `cloudflared` is fetched automatically, no account).
  The tunnel is pre-warmed so the QR is ready the moment you hit Share.
- **Mobile web viewer**: xterm.js-based and touch-tuned — soft-keyboard push, an on-screen key bar
  (Esc / Tab / Ctrl / arrows + a ⌨ toggle), pinch-zoom, fit-to-screen, and clickable links.
- **Native remote client**: "Add remote" in BossTerm to mirror another machine's shared tabs into
  your own window — including its **Remote MCP** — with control relayed up the chain.
- **End-to-end encrypted**: the session key rides in the URL **fragment** (`#k=…`), which browsers
  never send to the server — so even a tunnel relay can't read your session. Frames use
  per-connection AES-256-GCM, and a short verification code lets both ends confirm the same key.

Enable it under **Settings → Session Sharing** (off by default), then **Share** from a tab's menu.
Defaults: binds the LAN on port `7677`, Cloudflare remote mode, approval required only for public
links.

See **[docs/session-sharing.md](docs/session-sharing.md)** for the full guide — scopes,
remote-access setup, the viewer, the native client, the encryption design, and every setting.

## BossTerm MCP

BossTerm ships an in-process [Model Context Protocol](https://modelcontextprotocol.io)
server that exposes the running terminal to MCP-aware clients (Claude Code,
Codex, Gemini CLI, OpenCode). Clients can enumerate tabs, read scrollback,
search output, capture the last completed command, and — when write tools
are enabled — drive shells, send signals, open new splits, and **run
commands in a visible pane** while still capturing stdout/stderr and exit
code (`run_command` — recommended default shell for AI clients).

- **Endpoint**: `http://127.0.0.1:7676/` over Server-Sent Events, configurable
  via Settings → BossTerm MCP → Port.
- **Loopback-only**: the server binds `127.0.0.1` and rejects non-loopback
  `Host` headers (DNS-rebinding defense). Any local process running as your
  user can reach it while it is enabled.
- **Opt-in**: disabled by default. Toggle on under Settings → BossTerm MCP.
- **Remote MCP**: when you [share a session](#session-sharing), the host's MCP can be driven from
  the web viewer (an "MCP pill" toggles it and attaches CLIs) or from a native remote client —
  calls on shared tabs are relayed to the host.

### Turning it on (as a user)

1. Open Settings → **BossTerm MCP** and toggle **Enable BossTerm MCP Server**.
   A green "BossTerm MCP on" pill appears in the tab bar.
2. (Optional) Under **Exposed Tools**, untick any built-in tool you don't
   want clients to call — toggles apply live.
3. Under **Attach to AI CLI**, click the button for each AI CLI you want to
   register the endpoint with. Re-attachment is idempotent and happens
   silently on subsequent launches.

### Using as Claude Code's default shell

`run_command` is exposed by default and ready for explicit use (e.g. "split and
run X"). To make it Claude Code's *default* shell — preferred over its built-in
`Bash` for everything — turn on **Settings → BossTerm MCP → "Use `run_command`
as AI clients' default shell"** (off by default). With it on, the server's
initialize-time `instructions` tell Claude Code to prefer `run_command` (a soft
nudge that applies to the next client connection).

For a hard guarantee that also takes effect **instantly**, add the user-global
`PreToolUse` hook described in
[docs/mcp-server.md](docs/mcp-server.md#using-as-claude-codes-default-shell).
BossTerm writes/deletes the `~/.bossterm/mcp.port` marker the moment you flip
the setting, and the hook routes `Bash` calls to `mcp__bossterm__run_command`
whenever the marker is present — so toggling the setting turns enforcement on or
off per command, with no Claude restart.

### Embedding it (as a developer)

```kotlin
import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpManager
import ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager

fun main() {
    val mcpConfig = BossTermMcpConfig(serverName = "myapp", serverVersion = "1.0")
    val mcpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mcpManager = BossTermMcpManager(
        registry = McpTerminalRegistry,
        settingsManager = SettingsManager.instance,
        parentScope = mcpScope,
        config = mcpConfig
    )
    mcpManager.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        mcpManager.stop()
        mcpScope.cancel()
    })

    application {
        CompositionLocalProvider(LocalBossTermMcpConfig provides mcpConfig) {
            // Each window that uses TabbedTerminalState must also register
            // it with McpTerminalRegistry so the server can see its tabs:
            //
            //   DisposableEffect(tabbedState) {
            //       McpTerminalRegistry.register(tabbedState)
            //       onDispose { McpTerminalRegistry.unregister(tabbedState) }
            //   }
            //
            // Apps built on the single-terminal EmbeddableTerminal can still
            // run the MCP server and register custom tools via additionalTools,
            // but the tab-scoped built-ins (list_tabs, send_input, etc.) won't
            // see any tabs. See docs/mcp-server.md for the full contract.
            MyAppWindows()
        }
    }
}
```

Common knobs on `BossTermMcpConfig`: `toolNamePrefix` to namespace built-in
tools, `allowWriteTools = false` for an observe-only build, `additionalTools`
to register app-specific MCP tools, and `customToolDescriptions` to override
descriptions of individual built-ins. The
[`embedded-example`](embedded-example/) and [`tabbed-example`](tabbed-example/)
modules demonstrate both hooks.

See [docs/mcp-server.md](docs/mcp-server.md) for the full reference —
every built-in tool's JSON schema, the `manage_tools` meta-tool, the
`BossTermMcpConfig` field-by-field table, and troubleshooting.

## Session Daemon

A tmux-style background process that **owns your terminal sessions, MCP server, and shares** so they
keep running after you close the GUI — reopen BossTerm and it reattaches to the live sessions. **On by
default**; turn it off under Settings → Session Daemon to fall back to the pre-daemon behavior
(in-process MCP/sharing, sessions die with the window), a path that's preserved byte-for-byte.

- **Survives the GUI**: sessions live in the daemon, not the window. Close the app (or all its
  windows) and your shells keep running — long builds, SSH sessions, and `run_command` agents don't
  die. The next launch mirrors them straight back as tabs.
- **Thin-client GUI**: when enabled, each window attaches to the daemon over a loopback WebSocket and
  renders its sessions; keystrokes and resizes flow back to the daemon, which owns the PTYs. If the
  daemon is unreachable, the GUI falls back to local tabs so you're never stuck.
- **MCP + sharing stay live headless**: the daemon hosts the [MCP server](#bossterm-mcp) and
  [session sharing](#session-sharing), so agents and share links keep working with no window open. A
  menu-bar / tray icon shows it's running and lets you open the GUI or quit the daemon.
- **Starts at login** (on by default): installs a per-OS login service (launchd LaunchAgent / systemd
  user unit / Windows Run key) so the daemon is available even before BossTerm is first opened or after
  a reboot. A separate **Start daemon at login** toggle turns this off without disabling the daemon. On
  a shared/multi-user host, note the loopback MCP endpoint is then reachable by any process running as
  you whenever you're logged in.
- **Secure by construction**: loopback-only, gated by a 256-bit per-launch secret (constant-time
  compare, sent in a header — never the query string), DNS-rebinding `Host` guards on every server,
  owner-only (`0600`) discovery/secret files in a `0700` base dir, and a `FileChannel.tryLock`
  single-spawn guard. SESSION-scoped shares are write-isolated to their one session.

Manage it under **Settings → Session Daemon** (toggles take effect after restarting BossTerm). The
daemon never stops when you close the GUI — only via **Quit daemon**, or OS logout.

## Technology Stack

- **Kotlin** - Modern JVM language
- **Compose Desktop** - Declarative UI framework
- **Pty4J** - PTY support for local terminal sessions
- **ICU4J** - Unicode/grapheme cluster support

## Command-Line Interface

`install.sh` installs a `bossterm` CLI launcher (and a Python helper +
man page) under `/usr/local/bin/` or `~/.local/bin/`. With no arguments it
launches the GUI; with a positional path it opens that directory.

Beyond launching, the CLI has subcommands that talk to a running BossTerm
through the in-process MCP server:

```bash
bossterm                              # Launch the GUI
bossterm ~/Projects/foo               # Launch in a directory
bossterm new                          # New window
bossterm new-tab                      # New tab in the running BossTerm  (MCP)
bossterm run npm test                 # Run a command in a new tab        (MCP)
bossterm run --split=h tail -f log    # Open a horizontal split and tail  (MCP)
bossterm send $'ls\n'                 # Send to the focused pane           (MCP)
bossterm logs --lines 50              # Dump the last 50 scrollback lines  (MCP)
bossterm attach claude                # Re-register with Claude Code
bossterm mcp status                   # Inspect MCP enabled/port/state
bossterm mcp on | off                 # Toggle settings.mcpEnabled
bossterm config                       # Print path to ~/.bossterm/settings.json
bossterm --help                       # Full usage
```

Run `man bossterm` after installation for the complete reference.

> **Note on local dev usage:** the repo-root `./bossterm` is a symlink into
> `cli-resources/bossterm`, so `./bossterm --version` works from a clean
> `git clone`. GitHub's "Download ZIP" link does **not** preserve symlinks
> (the file materializes as plain text containing the link target). If
> you've downloaded a zip rather than cloned, run
> `cli-resources/bossterm` directly, or `git clone` the repo.

## Documentation

- [Embedding Guide](docs/embedding.md) - Embed a single terminal with custom context menus
- [Tabbed Terminal Guide](docs/tabbed-terminal.md) - Full-featured tabbed terminal with splits
- [Session Sharing](docs/session-sharing.md) - Watch & control a terminal from any device (web viewer, QR, tunnels, E2E)
- [BossTerm MCP Server](docs/mcp-server.md) - Expose tabs to MCP clients (Claude Code, Codex, Gemini, OpenCode)
- [BossTerm CLI](docs/bossterm.1) - `man bossterm` reference (troff)
- [Onboarding Wizard](docs/onboarding.md) - First-time setup wizard for users
- [Troubleshooting Guide](docs/troubleshooting.md) - Common issues and solutions
- [Release Notes](docs/release-notes/) - Detailed changelog for each version

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

## License

BossTerm is dual-licensed under:
- [LGPLv3](LICENSE-LGPLv3.txt)
- [Apache 2.0](LICENSE-APACHE-2.0.txt)

You may select either license at your option.

## Authors

**Shivang** — shivang@risalabs.ai

## Open Source Origin and History

BossTerm was originally inspired by [JediTerm](https://github.com/JetBrains/jediterm) by JetBrains (authored by Dmitry Trofimov dmitry.trofimov@jetbrains.com and Clément Poulain). The initial version of JediTerm was itself a reworked terminal emulator Gritty, which was in its own turn a reworked JCTerm terminal implementation.

BossTerm has since been completely rewritten from the ground up in Kotlin with Compose Desktop — no JediTerm, Gritty, or JCTerm code remains. Everything was rewritten from scratch with a new rendering engine, new buffer implementation, and new UI framework. A lot of new features were added including split panes, inline images, AI assistant integration, custom platform services, and high-performance incremental snapshot rendering.

## Acknowledgments

- [JediTerm](https://github.com/JetBrains/jediterm) by JetBrains — original inspiration for terminal emulation
- [iTerm2](https://github.com/gnachman/iTerm2) — the beloved macOS terminal, inspiration for many UX features
- [Pty4J](https://github.com/JetBrains/pty4j) — PTY library for local terminal sessions
- [ICU4J](https://unicode-icu.github.io/icu/userguide/icu4j/) — Unicode and grapheme cluster support

## References

- [Terminal protocol description](http://invisible-island.net/xterm/ctlseqs/ctlseqs.html) — Xterm control sequences
- [Terminal Character Set Terminology and Mechanics](http://www.columbia.edu/kermit/k95manual/iso2022.html) — ISO 2022 character sets
- [VT420 Programmer Reference Manual](http://manx.classiccmp.org/collections/mds-199909/cd3/term/vt420rm2.pdf) — DEC terminal reference
- [UTF-8 Demo](http://www.cl.cam.ac.uk/~mgk25/ucs/examples/UTF-8-demo.txt) — Unicode test file
- [Control sequences visualization](http://www.gnu.org/software/teseq/) — GNU teseq
- [Terminal protocol tests](http://invisible-island.net/vttest/) — vttest suite

---

**Built by [Risa Labs Inc](https://risalabs.ai)**
