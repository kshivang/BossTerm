package ai.rever.bossterm.compose.settings

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Per-assistant configuration stored in settings.
 */
@Serializable
data class AIAssistantConfigData(
    val enabled: Boolean = true,
    val yoloEnabled: Boolean = true,
    val customCommand: String? = null,
    val customYoloFlag: String? = null
)

/**
 * Custom AI assistant defined by the user.
 */
@Serializable
data class CustomAIAssistantData(
    val id: String,
    val displayName: String,
    val command: String,
    val yoloFlag: String = "",
    val yoloLabel: String = "Auto",
    val description: String = "",
    val websiteUrl: String = ""
)

/**
 * Terminal settings data class with all user-configurable options.
 * Based on legacy SettingsProvider interface from ui module.
 */
@Serializable
data class TerminalSettings(
    // ===== Visual Settings =====

    /**
     * Font size in SP (scalable pixels)
     */
    val fontSize: Float = 14f,

    /**
     * Font name for terminal text.
     * When set, uses a system font instead of the bundled MesloLGS Nerd Font.
     * Use a monospace font for best results.
     * Example: "JetBrains Mono", "SF Mono", "Menlo"
     */
    val fontName: String? = null,

    /**
     * Line spacing multiplier (1.0 = normal, 1.5 = 1.5x spacing)
     */
    val lineSpacing: Float = 1.0f,

    /**
     * Disable line spacing when in alternate screen buffer.
     * Full-screen apps (vim, htop, less) may look better without extra spacing.
     */
    val disableLineSpacingInAlternateBuffer: Boolean = false,

    /**
     * Fill character background color through line spacing area.
     * When true, background colors extend into line spacing.
     * When false, line spacing shows terminal background only.
     */
    val fillBackgroundInLineSpacing: Boolean = true,

    /**
     * Use antialiasing for text rendering
     */
    val useAntialiasing: Boolean = true,

    /**
     * Use bundled symbol font (Noto Sans Symbols 2) for symbols like ⏵ ★ ⚡.
     * null (default): Platform-specific - macOS uses Apple Color Emoji, Linux uses bundled font.
     * true: Always use bundled Noto Sans Symbols 2.
     * false: Always use system default font.
     */
    val preferTerminalFontForSymbols: Boolean? = null,

    /**
     * Default foreground color (serialized as ARGB hex)
     */
    val defaultForeground: String = "0xFFD7DEE6",

    /**
     * Default background color (serialized as ARGB hex)
     */
    val defaultBackground: String = "0xFF0E1217",

    /**
     * Selection highlight color (serialized as ARGB hex).
     * Default matches the BOSS Operator theme so a fresh install is self-consistent.
     */
    val selectionColor: String = "0xFF21405A",

    /**
     * Selection highlight opacity (0.0 to 1.0).
     * Lower values make text more readable through selection highlight.
     * Default 0.6 provides good contrast while keeping text visible.
     */
    val selectionAlpha: Float = 0.6f,

    /**
     * Search result highlight color (serialized as ARGB hex)
     */
    val foundPatternColor: String = "0xFFF0B429",

    /**
     * Hyperlink color (serialized as ARGB hex)
     */
    val hyperlinkColor: String = "0xFF56C7E0",

    /**
     * Active theme ID.
     * References a theme from BuiltinThemes or a custom theme.
     * When a theme is applied, the color settings above are updated to match.
     */
    val activeThemeId: String = "boss-operator",

    /**
     * Active color palette ID.
     * When set to "use-theme", uses the theme's built-in ANSI colors.
     * Otherwise, references a palette from BuiltinColorPalettes or a custom palette.
     * This allows mixing themes (for terminal colors) with different ANSI palettes.
     */
    val colorPaletteId: String = "use-theme",

    /**
     * Terminal background opacity (0.0 = fully transparent, 1.0 = fully opaque).
     * When less than 1.0, the window becomes transparent and the desktop shows through.
     * Note: On macOS, this enables native transparency. On other platforms, results may vary.
     */
    val backgroundOpacity: Float = 1.0f,

    /**
     * Enable blur effect behind transparent terminal (macOS only).
     * Creates a frosted glass effect when backgroundOpacity < 1.0.
     * Has no effect when backgroundOpacity is 1.0.
     */
    val windowBlur: Boolean = false,

    /**
     * Blur radius for transparent mode (1-100).
     * Higher values create more blur. Only applies when windowBlur is enabled.
     */
    val blurRadius: Float = 30f,

    /**
     * Path to background image file (PNG, JPG).
     * When set, displays an image behind the terminal content.
     * Empty string means no background image.
     */
    val backgroundImagePath: String = "",

    /**
     * Background image opacity (0.0 = invisible, 1.0 = fully visible).
     * Controls how visible the background image is.
     */
    val backgroundImageOpacity: Float = 0.3f,

    /**
     * Use native window decorations (title bar with traffic lights).
     * When true: Native macOS title bar, proper fullscreen, but no transparency.
     * When false: Custom title bar, transparency works, but no true fullscreen.
     * Changing this requires app restart to take effect.
     */
    val useNativeTitleBar: Boolean = true,

    /**
     * Show semi-transparent overlay when window loses focus.
     * Helps identify which terminal window is currently active.
     */
    val showUnfocusedOverlay: Boolean = true,

    // ===== Behavior Settings =====

    /**
     * Use login session on macOS (shows "Last login" message).
     * When true, uses 'login -fp $USER' to properly register the session in utmp/wtmp.
     * When false, directly spawns shell with -l flag.
     * Only affects macOS; other platforms always use direct shell spawn.
     */
    val useLoginSession: Boolean = true,

    /**
     * Default shell for Windows.
     * Options: "powershell" (default), "cmd"
     * Only affects Windows; other platforms use $SHELL or /bin/bash.
     */
    val windowsShell: String = "powershell",

    /**
     * Initial command to run when a new terminal tab is created.
     * This command is automatically sent to the shell after it starts.
     * Empty string means no initial command.
     * Example: "neofetch", "cd ~/projects", "source ~/.profile && clear"
     */
    val initialCommand: String = "",

    /**
     * Fallback delay (in milliseconds) before sending initial command.
     * Used when OSC 133 shell integration is not configured.
     * With OSC 133, the command is sent immediately when the prompt appears.
     * Without OSC 133, waits this long for the shell to be ready.
     * Range: 100-5000ms. Default: 500ms.
     */
    val initialCommandDelayMs: Int = 500,

    /**
     * Automatically copy selected text to clipboard
     */
    val copyOnSelect: Boolean = false,

    /**
     * Paste clipboard on middle mouse button click
     */
    val pasteOnMiddleClick: Boolean = true,

    /**
     * Emulate X11-style separate selection clipboard
     */
    val emulateX11CopyPaste: Boolean = false,

    /**
     * Scroll to bottom when typing
     */
    val scrollToBottomOnTyping: Boolean = true,

    /**
     * Alt key sends Escape prefix
     */
    val altSendsEscape: Boolean = true,

    /**
     * Enable mouse reporting to terminal application
     */
    val enableMouseReporting: Boolean = true,

    /**
     * Force actions even when mouse reporting is active
     */
    val forceActionOnMouseReporting: Boolean = false,

    /**
     * Mouse scroll sensitivity threshold (filters out tiny scroll events)
     * Higher values = less sensitive, lower values = more sensitive
     * Range: 0.0 (all events) to 2.0 (very insensitive)
     * Default: 0.5 works well for most trackpads and mice
     */
    val mouseScrollThreshold: Float = 0.5f,

    /**
     * Scroll speed multiplier for trackpad/mouse wheel scrolling.
     * Higher values = faster scrolling.
     * Range: 1.0 to 10.0
     * Default: 10.0 for Windows (small fractional deltas), 1.0 for macOS/Linux
     */
    val scrollMultiplier: Float = if (ShellCustomizationUtils.isWindows()) 10.0f else 1.0f,

    /**
     * Play audible bell sound
     */
    val audibleBell: Boolean = true,

    /**
     * Flash screen on bell (visual bell)
     */
    val visualBell: Boolean = true,

    /**
     * Shift+Enter key behavior.
     * Valid values:
     * - "newline": Send LF (0x0A) - inserts literal newline for multi-line input (iTerm2 style)
     * - "same-as-enter": Send CR (0x0D) - same as regular Enter key
     * Default: "newline" to match iTerm2 behavior
     * IMPORTANT: Default must match TerminalKeyEncoder init block default
     */
    val shiftEnterBehavior: String = "newline",

    // ===== Progress Bar Settings =====

    /**
     * Enable progress bar indicator (OSC 1337;SetProgress / OSC 9;4)
     */
    val progressBarEnabled: Boolean = true,

    /**
     * Progress bar position: "top" or "bottom" of terminal
     */
    val progressBarPosition: String = "bottom",

    /**
     * Progress bar height in dp (1-10)
     */
    val progressBarHeight: Float = 6f,

    // ===== Clipboard Settings (OSC 52) =====

    /**
     * Enable OSC 52 clipboard access.
     * Master toggle for terminal apps to access clipboard.
     */
    val clipboardOsc52Enabled: Boolean = true,

    /**
     * Allow terminal apps to read clipboard content (OSC 52 query).
     * Security note: Disabled by default to prevent clipboard theft.
     */
    val clipboardOsc52AllowRead: Boolean = false,

    /**
     * Allow terminal apps to write to clipboard (OSC 52 set).
     * Usually safe to enable - allows apps like tmux to sync clipboard.
     */
    val clipboardOsc52AllowWrite: Boolean = true,

    /**
     * Use inverse selection color (swap fg/bg)
     */
    val useInverseSelectionColor: Boolean = false,

    // ===== Scrollbar Settings =====

    /**
     * Show visual scrollbar on the right side
     */
    val showScrollbar: Boolean = true,

    /**
     * Always show scrollbar (vs. auto-hide on inactivity)
     */
    val scrollbarAlwaysVisible: Boolean = false,

    /**
     * Scrollbar width in pixels
     */
    val scrollbarWidth: Float = 14f,

    /**
     * Scrollbar track color (serialized as ARGB hex)
     */
    val scrollbarColor: String = "0x40FFFFFF",

    /**
     * Scrollbar thumb color (serialized as ARGB hex)
     */
    val scrollbarThumbColor: String = "0xFFAAAAAA",

    /**
     * Show search match markers in scrollbar
     */
    val showSearchMarkersInScrollbar: Boolean = true,

    /**
     * Search marker color for regular matches (serialized as ARGB hex)
     */
    val searchMarkerColor: String = "0xFFFFFF00",

    /**
     * Search marker color for current match (serialized as ARGB hex)
     */
    val currentSearchMarkerColor: String = "0xFFFF6600",

    // ===== Command Blocks =====

    /**
     * Master toggle for per-command blocks (left-edge gutter bar, scrollbar
     * markers, jump/copy/re-run actions). Defaults to false: when off, no block
     * is rendered and the block actions are disabled, so behavior is unchanged.
     */
    val commandBlocksEnabled: Boolean = false,

    /**
     * Width, in dp, of the command-block gutter bar (drawn on the right edge).
     */
    val commandBlockGutterWidth: Float = 4f,

    /**
     * Gutter color for a successful command (exit code 0), serialized as ARGB hex.
     * Defaults to transparent so only failed/running commands draw a bar.
     */
    val commandBlockSuccessColor: String = "0x00000000",

    /**
     * Gutter color for a failed command (non-zero exit), serialized as ARGB hex.
     */
    val commandBlockErrorColor: String = "0xFFF44336",

    /**
     * Gutter color for a still-running command, serialized as ARGB hex.
     */
    val commandBlockRunningColor: String = "0xFF9E9E9E",

    /**
     * Mirror each command-block start position into the scrollbar track.
     */
    val commandBlockShowScrollbarMarkers: Boolean = true,

    /**
     * Tint the whole command block with a faint version of its gutter color
     * (e.g. light red for a failed command). Transparent-gutter states (success)
     * get no tint.
     */
    val commandBlockHighlightBackground: Boolean = true,

    // ===== Command Palette =====

    /**
     * Enable the fuzzy command palette (Cmd/Ctrl+Shift+P). When false the hotkey
     * is not intercepted and the palette never opens.
     */
    val commandPaletteEnabled: Boolean = true,

    // ===== Workflows =====

    /**
     * Enable saved parameterized commands (workflows). When false no workflows
     * are loaded or surfaced in the palette.
     */
    val workflowsEnabled: Boolean = true,

    /**
     * Run a workflow's command immediately on submit (append newline) instead of
     * just inserting it at the prompt for review.
     */
    val workflowsAutoRun: Boolean = false,

    /**
     * Additional directories to scan for workflow `*.yaml` files, beyond
     * `~/.bossterm/workflows` and the project's `.warp/workflows`.
     */
    val workflowExtraDirs: List<String> = emptyList(),

    // ===== History search + AI command bar (Phase 4) =====

    /** Enable Ctrl+R fuzzy history search overlay. */
    val historySearchEnabled: Boolean = false,

    /** Enable the natural-language → command AI bar inside history search. */
    val aiCommandBarEnabled: Boolean = false,

    /** Agent command used for NL→command (e.g. "claude"). */
    val aiCommandBarAgentCommand: String = "claude",

    /** One-shot print flag for the agent (e.g. "-p"); empty disables the AI bar. */
    val aiCommandBarPrintFlag: String = "-p",

    // ===== Tabs & layout (Phase 5) =====

    /** Tab bar position: "left" (vertical, default) or "top". */
    val tabBarPosition: String = "left",

    /** Width (dp) of the vertical (left) tab bar. Ignored when position is "top". */
    val tabBarVerticalWidth: Float = 180f,

    /**
     * Summary mode (Warp's VerticalTabsSummaryMode): show one chip per tab
     * (the active pane, labeled with the tab's title) instead of one chip per
     * split pane. Off = per-pane chips (default).
     */
    val tabBarSummaryMode: Boolean = false,

    /**
     * Auto-color tabs by working directory (Warp's DirectoryTabColors): when on
     * and a tab has no manual color, derive a stable accent from its cwd. A
     * manual color (Color ▸ menu) always wins. Off = no color (default).
     */
    val tabColorByDirectory: Boolean = false,

    // ===== Session restore (Phase 6) =====

    /** Reopen tabs / split layout / cwds on launch. */
    val restoreSessionOnLaunch: Boolean = false,

    // ===== Polish (Phase 7) =====

    /** Show the current git branch near the tab bar. */
    val showGitBranchIndicator: Boolean = false,

    /** Inject shell vi-mode (set -o vi / bindkey -v / fish vi) via shell integration. */
    val shellViMode: Boolean = false,

    /** Enable shell autosuggestions hint via shell integration. */
    val shellAutosuggestions: Boolean = false,

    /** Hold an OS wake-lock while a foreground command runs past the threshold. */
    val preventSleepDuringCommands: Boolean = false,

    /** Seconds a command must run before the wake-lock is acquired. */
    val preventSleepThresholdSeconds: Int = 30,

    // ===== GPU Rendering Settings =====

    /**
     * Enable GPU-accelerated rendering via Skia/Skiko.
     * When true, uses hardware acceleration (Metal on macOS, DirectX on Windows, OpenGL on Linux).
     * When false, forces software rendering (slower but more compatible).
     * Default: true (GPU acceleration enabled)
     */
    val gpuAcceleration: Boolean = true,

    /**
     * GPU render API to use. Options:
     * - "auto": Automatic selection based on platform (recommended)
     * - "metal": Metal backend (macOS only)
     * - "opengl": OpenGL backend (cross-platform)
     * - "direct3d": DirectX 12 backend (Windows only)
     * - "software": Software rendering (fallback, no GPU)
     *
     * Note: Invalid values for the platform will fall back to "auto".
     * Requires app restart to take effect.
     */
    val gpuRenderApi: String = "auto",

    /**
     * GPU priority for systems with multiple GPUs. Options:
     * - "auto": Let the system decide (default, usually integrated for power saving)
     * - "integrated": Prefer integrated GPU (lower power, cooler)
     * - "discrete": Prefer discrete GPU (higher performance)
     *
     * Only affects Metal (macOS) and DirectX (Windows).
     * Requires app restart to take effect.
     */
    val gpuPriority: String = "auto",

    /**
     * Enable vertical sync (VSync) to eliminate screen tearing.
     * When true, synchronizes frame rendering with display refresh rate.
     * When false, allows higher frame rates but may cause tearing.
     * Default: true
     */
    val gpuVsyncEnabled: Boolean = true,

    /**
     * GPU resource cache limit in megabytes.
     * Controls how much GPU memory is used for caching glyphs, textures, etc.
     * Higher values improve performance but use more GPU memory.
     * Range: 64 MB to gpuCacheMaxPercent% of system RAM (max 8192 MB).
     * Default: 256 MB.
     * Requires app restart to take effect.
     */
    val gpuCacheSizeMb: Int = 256,

    /**
     * Maximum GPU cache as percentage of system RAM.
     * Advanced setting to control the upper limit of GPU cache slider.
     * Range: 10-90%. Default: 75%.
     * Higher values allow more GPU memory usage on high-RAM systems.
     * Requires app restart to take effect.
     */
    val gpuCacheMaxPercent: Int = 75,

    // ===== Performance Settings =====

    /**
     * Performance optimization mode.
     * - "latency": Optimized for interactive responsiveness (faster command response, lower throughput)
     * - "throughput": Optimized for bulk output (higher throughput, slightly higher latency)
     * - "balanced": Balance between latency and throughput (default)
     *
     * Use "latency" for: SSH sessions, interactive commands, shell usage
     * Use "throughput" for: Large file operations, build logs, data processing
     */
    val performanceMode: String = "balanced",

    /**
     * Maximum refresh rate in FPS (0 = unlimited)
     */
    val maxRefreshRate: Int = 60,

    /**
     * Maximum lines in scrollback buffer
     */
    val bufferMaxLines: Int = 10000,

    /**
     * Cursor blink rate in milliseconds (0 = no blink)
     */
    val caretBlinkMs: Int = 500,

    /**
     * Master toggle to enable/disable all text blinking (accessibility feature)
     */
    val enableTextBlinking: Boolean = true,

    /**
     * Slow text blink rate in milliseconds
     */
    val slowTextBlinkMs: Int = 1000,

    /**
     * Rapid text blink rate in milliseconds
     */
    val rapidTextBlinkMs: Int = 500,

    // ===== Terminal Emulation Settings =====

    /**
     * DEC compatibility mode
     */
    val decCompatibilityMode: Boolean = true,

    /**
     * Treat ambiguous-width characters as double-width
     */
    val ambiguousCharsAreDoubleWidth: Boolean = false,

    /**
     * Character encoding mode: "UTF-8" or "ISO-8859-1"
     * UTF-8: GR range (160-255) passes through for multi-byte sequences (default, safe)
     * ISO-8859-1: GR range maps through character sets (enables Latin-1 supplemental)
     *
     * Note: Auto-detected from locale (LANG/LC_ALL/LC_CTYPE) on PTY initialization.
     * Can be manually overridden via settings.
     */
    val characterEncoding: String = "UTF-8",

    /**
     * Simulate mouse scroll with arrow keys in alternate screen
     */
    val simulateMouseScrollInAlternateScreen: Boolean = true,

    // ===== Search Settings =====

    /**
     * Search is case-sensitive by default
     */
    val searchCaseSensitive: Boolean = false,

    /**
     * Enable regex search by default
     */
    val searchUseRegex: Boolean = false,

    // ===== Hyperlink Settings =====

    /**
     * Show hyperlink underline on hover
     */
    val hyperlinkUnderlineOnHover: Boolean = true,

    /**
     * Hyperlink click requires Ctrl/Cmd modifier
     */
    val hyperlinkRequireModifier: Boolean = true,

    /**
     * Detect file/folder paths as clickable hyperlinks.
     * When enabled, absolute paths, home-relative paths (~/...), and relative paths
     * (./..., ../...) are highlighted and can be Ctrl+Clicked to open.
     * Paths are validated to exist before being highlighted.
     */
    val detectFilePaths: Boolean = true,

    // ===== Type-Ahead Settings =====

    /**
     * Enable type-ahead prediction for reduced perceived latency on SSH connections.
     * Predictions are invisible on local terminals but latency statistics are collected.
     */
    val typeAheadEnabled: Boolean = true,

    /**
     * Latency threshold in nanoseconds to activate visible predictions.
     * Predictions become visible when median round-trip latency exceeds this threshold.
     * Default: 100ms (100_000_000 nanos)
     */
    val typeAheadLatencyThresholdNanos: Long = 100_000_000L,

    // ===== Debug Settings =====

    /**
     * Enable debug mode (captures I/O for debug panel)
     */
    val debugModeEnabled: Boolean = false,

    /**
     * Maximum number of chunks to store in debug buffer (circular)
     */
    val debugMaxChunks: Int = 1000,

    /**
     * Maximum number of state snapshots to store
     */
    val debugMaxSnapshots: Int = 100,

    /**
     * Auto-capture terminal state snapshots (ms interval)
     */
    val debugCaptureInterval: Long = 100L,

    /**
     * Show chunk IDs in control sequence visualization
     */
    val debugShowChunkIds: Boolean = true,

    /**
     * Show invisible characters in debug view
     */
    val debugShowInvisibleChars: Boolean = true,

    /**
     * Wrap long lines in debug sequence view
     */
    val debugWrapLines: Boolean = true,

    /**
     * Color-code escape sequences in debug view
     */
    val debugColorCodeSequences: Boolean = true,

    // ===== File Logging Settings =====

    /**
     * Enable automatic file logging on terminal start.
     * When enabled, all terminal I/O is written to a log file.
     */
    val fileLoggingEnabled: Boolean = false,

    /**
     * Directory for log files. If empty, uses ~/.bossterm/logs/
     */
    val fileLoggingDirectory: String = "",

    /**
     * Log file name pattern. Supports placeholders:
     * {timestamp} - ISO 8601 timestamp
     * {tab} - Tab ID (first 8 chars)
     * {pid} - Process ID
     */
    val fileLoggingPattern: String = "bossterm_{timestamp}_{tab}.log",

    // ===== Notification Settings =====

    /**
     * Enable command completion notifications when window is not focused.
     * Requires shell integration (OSC 133) to detect command completion.
     */
    val notifyOnCommandComplete: Boolean = true,

    /**
     * Minimum command duration in seconds to trigger notification.
     * Commands finishing faster than this threshold won't trigger notifications.
     * Default: 5 seconds (similar to iTerm2)
     */
    val notifyMinDurationSeconds: Int = 5,

    /**
     * Include command exit code in notification.
     * When true, shows "Command finished (exit 0)" vs just "Command finished"
     */
    val notifyShowExitCode: Boolean = true,

    /**
     * Play notification sound.
     * When true, uses system default notification sound.
     */
    val notifyWithSound: Boolean = true,

    /**
     * Whether notification permission has been requested.
     * On first launch, a welcome notification is sent to trigger macOS permission dialog.
     */
    val notificationPermissionRequested: Boolean = false,

    /**
     * Whether first-run onboarding wizard has been completed.
     * When false, wizard is shown on first launch. Can be re-run from Help menu.
     */
    val onboardingCompleted: Boolean = false,

    // ===== Global Hotkey Settings =====

    /**
     * Enable global hotkey to summon BossTerm from anywhere.
     * Default: Disabled on macOS and Linux (opt-in due to desktop environment conflicts).
     *          Enabled on Windows.
     * Hotkey: Configured via modifiers + 1-9 for window-specific summoning.
     */
    val globalHotkeyEnabled: Boolean = ShellCustomizationUtils.isWindows(),

    /**
     * Ctrl modifier for global hotkey.
     */
    val globalHotkeyCtrl: Boolean = true,

    /**
     * Alt modifier for global hotkey.
     */
    val globalHotkeyAlt: Boolean = false,

    /**
     * Shift modifier for global hotkey.
     * Defaults to true so the out-of-the-box global hotkey is a two-modifier chord
     * (Ctrl+Shift), which is far less likely to shadow ordinary app shortcuts than a
     * single modifier. Shift (not Alt) is the second default modifier on purpose:
     * Ctrl+Alt is AltGr on many European layouts and would interfere with typing.
     */
    val globalHotkeyShift: Boolean = true,

    /**
     * Windows key modifier for global hotkey.
     */
    val globalHotkeyWin: Boolean = false,

    /**
     * Key for global hotkey.
     * Valid values: "GRAVE" (`), "SPACE", "ESCAPE", A-Z, 0-9, F1-F12
     */
    val globalHotkeyKey: String = "GRAVE",

    /**
     * Register a single global show/hide toggle hotkey: the configured modifiers +
     * [globalHotkeyKey] (e.g. Ctrl+Shift+`). Summons or hides the active BossTerm window
     * from anywhere — a lightweight "drop-down terminal" trigger that is independent of
     * the per-window 1–9 hotkeys. Only takes effect while [globalHotkeyEnabled] is on.
     */
    val globalHotkeyToggleEnabled: Boolean = true,

    /**
     * Automatically inject shell integration (OSC 133) into new terminal sessions.
     * When enabled, BossTerm hijacks shell environment variables (ZDOTDIR for zsh,
     * ENV for bash, XDG_DATA_DIRS for fish) to auto-load shell integration scripts.
     * This enables command completion notifications without manual shell configuration.
     * Set to false if this causes issues with your shell setup.
     */
    val autoInjectShellIntegration: Boolean = true,

    // ===== Split Pane Settings =====

    /**
     * Default ratio for new splits (0.0 to 1.0).
     * 0.5 means equal 50/50 split, 0.6 means 60/40, etc.
     */
    val splitDefaultRatio: Float = 0.5f,

    /**
     * Minimum pane size when resizing (0.0 to 0.5).
     * Prevents panes from being resized too small.
     * Default: 0.1 (10% minimum)
     */
    val splitMinimumSize: Float = 0.1f,

    /**
     * Show border on focused pane when splits exist.
     * Helps identify which pane has keyboard focus.
     */
    val splitFocusBorderEnabled: Boolean = true,

    /**
     * Color of the focus border (serialized as ARGB hex).
     * Only visible when splitFocusBorderEnabled is true.
     */
    val splitFocusBorderColor: String = "0xFF4A90E2",

    /**
     * New split panes inherit working directory from parent.
     * When true, new splits start in the same directory as the focused pane.
     * When false, new splits start in the user's home directory.
     */
    val splitInheritWorkingDirectory: Boolean = true,

    // ===== Tab Bar Settings =====

    /**
     * Always show tab bar even with single tab.
     * When true (default), tab bar is always visible for consistency and quick access to "+" button.
     * When false, tab bar auto-hides when only one tab is open.
     */
    val alwaysShowTabBar: Boolean = true,

    /**
     * Show the small dim "Cmd+1"/"Ctrl+1" hotkey hint label in the top-right
     * of each window (native title bar mode) or in the trailing slot of the
     * custom title bar. Hidden by default since global hotkeys are advanced
     * and the hint adds visual clutter for users who don't use them.
     */
    val showGlobalHotkeyHint: Boolean = false,

    // ===== MCP Server Settings =====

    /**
     * Enable the in-process Model Context Protocol (MCP) server.
     * When true, BossTerm exposes a streamable-HTTP MCP endpoint on localhost
     * so external tools (e.g. AI assistants) can introspect and control the
     * terminal. Defaults to false for opt-in safety - the server only starts
     * when this flag is explicitly enabled.
     */
    val mcpEnabled: Boolean = false,

    /**
     * Localhost TCP port for the in-process MCP server's streamable-HTTP endpoint.
     * Only used when mcpEnabled is true. Change this if the default port conflicts
     * with another service on your machine.
     * Default: 7676
     */
    val mcpPort: Int = 7676,

    // ===== Session daemon (tmux-style client/server) =====

    /**
     * Master switch for the BossTerm session daemon — a long-lived background process that owns
     * terminal sessions, the MCP server, and (later) session sharing, so they survive the GUI
     * closing. Defaults to false for opt-in safety: when off, BossTerm behaves exactly as before
     * (in-process MCP/sharing, sessions die with the window). When on, the GUI spawns/connects the
     * daemon and hosts MCP there instead. The daemon is started on-demand by the GUI; see also
     * [startDaemonAtLogin] for an always-on service.
     */
    val daemonEnabled: Boolean = false,

    /**
     * Install a per-OS login service (macOS LaunchAgent / Linux systemd user unit / Windows Run
     * key) so the daemon starts at login — always available, even before the GUI is first opened.
     * Defaults on and is coupled to [daemonEnabled]: enabling the daemon also schedules it at login
     * (the daemon is meant to be always-available); only meaningful while [daemonEnabled] is true.
     * The toggle install/uninstalls the service via LoginServiceManager.
     */
    val startDaemonAtLogin: Boolean = true,

    // ===== Session sharing / remote control (issue #276) =====

    /**
     * Master switch for session sharing — a self-hosted web viewer that mirrors
     * a terminal tab to another device's browser (and, with control granted,
     * accepts input). Defaults to false for opt-in safety: the share server only
     * starts when this is explicitly enabled, and even then nothing is exposed
     * until the user shares a specific tab.
     */
    val sessionSharingEnabled: Boolean = false,

    /** Localhost/LAN TCP port for the session-sharing web server. Default 7677. */
    val sessionSharingPort: Int = 7677,

    /**
     * Bind scope for the share server (OpenClaw-style):
     *  - "lan" (default): 0.0.0.0 — reachable by devices on the local network (e.g.
     *    your phone); the share URL is this machine's LAN IP. Sharing's whole point is
     *    another device, so this is the default. Access is gated by an unguessable token.
     *  - "loopback": 127.0.0.1 — locks the share to this machine only (use a tunnel/VPN
     *    for other devices).
     *  - "custom": bind [sessionSharingBindHost] verbatim.
     */
    val sessionSharingBind: String = "lan",

    /** Host to bind when [sessionSharingBind] == "custom". Ignored otherwise. */
    val sessionSharingBindHost: String = "",

    /**
     * Remote-access mode for sharing (which tunnel provider exposes the share server):
     *  - "off": no tunnel — reach is loopback/LAN only.
     *  - "serve": Tailscale Serve — your tailnet only (private, TLS). Requires `tailscale`.
     *  - "funnel": Tailscale Funnel — public internet via Tailscale's edge (TLS).
     *  - "cloudflare" (default): Cloudflare Quick Tunnel — instant public https link via
     *    `cloudflared`, no account/config (ephemeral URL); auto-installable via Homebrew.
     *    The zero-config option, so it's the default. Falls back to the LAN URL if
     *    `cloudflared` isn't present. See [ai.rever.bossterm.compose.share.CloudflaredExposer].
     * When active, the share URL becomes the published https link (…ts.net or …trycloudflare.com).
     * Note: sharing is still gated by [sessionSharingEnabled] (off by default) and, for public
     * modes, [sessionSharingApprovalScope] — so a public tunnel only opens once the user shares.
     * (Field name is historical — it's the general remote-access mode, not Tailscale-only.)
     */
    val shareTailscaleMode: String = "cloudflare",

    /**
     * Explicit public base URL to advertise instead of the bound host — for when the
     * user fronts the share server with their own reverse proxy / cloudflared / SSH
     * reverse tunnel (e.g. "https://term.example.com"). Blank = derive from the bind.
     * Takes precedence only when Tailscale is off.
     */
    val sessionSharingPublicUrl: String = "",

    /**
     * When a new device connects to a share, whether the host must approve it before
     * it can view/control. On approval the device gets a 24h rolling access key so it
     * isn't re-prompted on every reconnect. Values:
     *  - "off":    no approval — the share link alone grants access (original behavior).
     *  - "funnel": require approval only for public reach (Tailscale Funnel or a custom
     *              public URL); LAN and Tailscale Serve stay link-only. (default)
     *  - "all":    require approval for every connection, including LAN.
     */
    val sessionSharingApprovalScope: String = "funnel",

    /**
     * Show the small status indicator while a tab is being shared. Mirrors
     * [mcpShowStatusIndicator] for the share server.
     */
    val sessionSharingShowIndicator: Boolean = true,

    /**
     * Show the small green status indicator in the tab bar while the MCP
     * server is running. Has no effect when mcpEnabled is false. Turning
     * this off also stops forcing the tab bar to render in single-tab mode
     * for the indicator's sake.
     */
    val mcpShowStatusIndicator: Boolean = true,

    /**
     * Default size of the new pane (as a fraction of the parent's
     * dimension) when MCP `run_in_panel` opens a horizontal or vertical
     * split and the caller hasn't supplied `split_ratio` explicitly.
     * Range: 0.1..0.9. Default 0.3 — large enough for an `htop` / `tail`,
     * small enough to keep the agent's primary workspace visible.
     */
    val mcpDefaultSplitRatio: Float = 0.3f,

    /**
     * Default hard timeout (in milliseconds) for `run_command`, applied when
     * the caller doesn't pass `timeout_ms`. Default and per-call overrides
     * are both clamped to `100..600_000` server-side. Lower this if you'd
     * rather see "timed out" results sooner; raise it for long builds.
     *
     * Default `120_000` (2 minutes) covers the majority of dev workflows
     * (tests, builds, package installs) while keeping a 10-minute ceiling
     * before a runaway script can hog the pane. Long builds (e.g. cold
     * gradle builds) may need a higher value — pass `timeout_ms` per call,
     * or raise the default.
     */
    val mcpRunCommandDefaultTimeoutMs: Int = 120_000,

    /**
     * Cap on the captured `output` field returned by `run_command`, in
     * UTF-16 chars (Kotlin String length). Beyond this, output is truncated
     * and the response carries `truncated: true`.
     *
     * Default `120_000` is sized to fit under `mcpMaxAnswerChars`
     * (`150_000` soft response cap, also UTF-16 chars) with headroom for
     * the JSON wrapper, so a maxed-out `run_command` reply never trips the
     * response-shortening ladder. Raise it (and `mcpMaxAnswerChars`)
     * together for tooling that emits very large dumps; lower it for
     * tight-context clients.
     *
     * Unit choice: UTF-16 chars (not UTF-8 bytes) so the comparison against
     * `mcpMaxAnswerChars` is apples-to-apples. For ASCII-heavy output 1
     * char ≈ 1 byte; for emoji/CJK the byte count can be up to ~4x the
     * char count, so the real network payload for an emoji-heavy command
     * could exceed the value here. Tighten further if your transport
     * cares about bytes.
     *
     * Minimum enforced: `1024` chars — smaller values are silently raised
     * so a single typical output line still fits.
     *
     * Advanced setting — no UI control, edit settings.json directly.
     */
    val mcpRunCommandMaxOutputChars: Int = 120_000,

    /**
     * Fallback delay `run_command` waits for OSC 133;A on a freshly-created
     * pane before sending the script anyway. Only kicks in when the user's
     * shell hasn't been configured for OSC 133 prompt-ready notifications,
     * so most users never see this matter.
     *
     * Default `1_500` ms. Raise it if your shell rc files are very slow to
     * load; lower it if you want faster "shell integration missing" feedback.
     * Set `0` to skip the wait entirely — the script is sent immediately on
     * a freshly-created pane (cached panes never wait regardless).
     *
     * Advanced setting — no UI control, edit settings.json directly.
     */
    val mcpRunCommandShellReadyTimeoutMs: Int = 1_500,

    /**
     * Panel mode `run_command` uses when it has to create a new MCP scratch
     * pane (no cached pane for the tab, no explicit `pane_id`, and the
     * caller passed `panel: "reuse"` or omitted `panel`). One of:
     * `horizontal_split` (default — splits below the focused pane),
     * `vertical_split` (splits beside), or `new_tab` (opens a fresh tab).
     *
     * Subsequent `run_command` calls reuse the pane created here, so this
     * is "what does the first call's UI look like" — it doesn't kick in
     * every call.
     */
    val mcpRunCommandDefaultPanel: String = "horizontal_split",

    /**
     * Whether to advertise `run_command` as the AI client's **default shell**.
     *
     * The `run_command` tool is always available (when not individually
     * disabled) for explicit use — e.g. when you ask the agent to "split and
     * run X". This flag controls something narrower: whether the MCP server's
     * initialize-time `instructions` actively tell the client to *prefer*
     * `run_command` over its own built-in shell tool for everything.
     *
     * Default `false` — the agent uses its normal shell unless you ask it to
     * use `run_command`. Flip to `true` to make a visible BossTerm pane the
     * default way the agent runs commands (pairs with the optional Claude Code
     * `PreToolUse` hook described in `docs/mcp-server.md`).
     *
     * Read per client connection (via the server's instructions provider), so
     * toggling it takes effect for the next client that connects — no restart.
     */
    val mcpRunCommandPreferredShell: Boolean = false,

    /**
     * Names (enum `.name`) of [ai.rever.bossterm.compose.mcp.McpAttachTarget]s
     * that this BossTerm endpoint is registered with via the user's
     * AI CLIs. Persisted across runs so the manager can silently
     * re-run `<cli> mcp add` on next startup — refreshing the URL if
     * the port changed and surfacing the ✓ marks in the UI immediately.
     * Unknown / removed enum names are silently ignored at load.
     */
    val mcpAttachedTo: Set<String> = emptySet(),

    /**
     * Unprefixed built-in names of BossTerm MCP tools that should NOT be exposed
     * to clients (e.g. "send_input", "run_in_panel"). Default: empty (all tools
     * exposed). Edited via the settings UI or the `manage_tools` MCP tool;
     * changes apply live without restarting the server.
     *
     * Note: `manage_tools` is always exposed and cannot be added to this set —
     * disabling it would leave no way to re-enable other tools from MCP.
     */
    val disabledMcpTools: Set<String> = emptySet(),

    /**
     * Soft ceiling (in characters) on a single BossTerm MCP tool response.
     * When a response would exceed this, the tool returns a progressively
     * smaller summary (e.g. matches-only for `search_output`, metadata-only
     * for `read_debug_console`, last-N-lines for `read_scrollback`) instead
     * of the full payload — the agent sees a well-formed JSON it can reason
     * about and refine, never a truncated mid-response blob.
     *
     * Default `150_000` mirrors Serena's `default_max_tool_answer_chars` and
     * is well above typical responses; it really only kicks in for
     * pathological cases (regex matching thousands of rows, a giant debug
     * buffer dump). Lower it to ~50_000 if you want a tighter guardrail.
     * Advanced setting — no UI control, edit settings.json directly.
     */
    val mcpMaxAnswerChars: Int = 150_000,

    /**
     * Set to `true` the first time [ai.rever.bossterm.compose.mcp.BossTermMcpManager]
     * starts so that embedder-supplied first-launch defaults
     * (`BossTermMcpConfig.defaultEnabled` / `defaultPort`) are applied
     * exactly once. After this flag flips to true, the user's `mcpEnabled`
     * / `mcpPort` choices are authoritative and embedder defaults no longer
     * override them.
     */
    val mcpConfigured: Boolean = false,

    // ===== AI Assistant Settings =====

    /**
     * Enable AI coding assistant integration in context menu.
     * When true, shows "AI Assistants" section with detected assistants.
     * When false, the AI Assistants section is hidden from context menu.
     */
    val aiAssistantsEnabled: Boolean = true,

    /**
     * Per-assistant configuration (enabled, yoloEnabled, customCommand, etc.).
     * Key is the assistant ID.
     */
    val aiAssistantConfigs: Map<String, AIAssistantConfigData> = emptyMap(),

    /**
     * Custom AI assistants defined by the user.
     */
    val customAIAssistants: List<CustomAIAssistantData> = emptyList()
) {
    // Non-serialized computed properties

    @Transient
    val defaultForegroundColor: Color = Color(defaultForeground.removePrefix("0x").toULong(16).toLong())

    @Transient
    val defaultBackgroundColor: Color = Color(defaultBackground.removePrefix("0x").toULong(16).toLong())

    /**
     * Background color with opacity applied.
     * Use this for terminal background when transparency is enabled.
     */
    @Transient
    val defaultBackgroundColorWithOpacity: Color = defaultBackgroundColor.copy(alpha = backgroundOpacity)

    /**
     * Whether transparency is enabled (opacity < 1.0).
     */
    @Transient
    val isTransparencyEnabled: Boolean = backgroundOpacity < 1.0f

    @Transient
    val selectionColorValue: Color = Color(selectionColor.removePrefix("0x").toULong(16).toLong())
        .copy(alpha = selectionAlpha.coerceIn(0f, 1f))

    @Transient
    val foundPatternColorValue: Color = Color(foundPatternColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val hyperlinkColorValue: Color = Color(hyperlinkColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val scrollbarColorValue: Color = Color(scrollbarColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val scrollbarThumbColorValue: Color = Color(scrollbarThumbColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val searchMarkerColorValue: Color = Color(searchMarkerColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val currentSearchMarkerColorValue: Color = Color(currentSearchMarkerColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val splitFocusBorderColorValue: Color = Color(splitFocusBorderColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val commandBlockSuccessColorValue: Color = Color(commandBlockSuccessColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val commandBlockErrorColorValue: Color = Color(commandBlockErrorColor.removePrefix("0x").toULong(16).toLong())

    @Transient
    val commandBlockRunningColorValue: Color = Color(commandBlockRunningColor.removePrefix("0x").toULong(16).toLong())

    companion object {
        /**
         * Default settings instance
         */
        val DEFAULT = TerminalSettings()

        /**
         * Convert Color to hex string for serialization (0xAARRGGBB format)
         */
        fun colorToHex(color: Color): String {
            val argb = (color.alpha * 255).toInt().shl(24) or
                       (color.red * 255).toInt().shl(16) or
                       (color.green * 255).toInt().shl(8) or
                       (color.blue * 255).toInt()
            return "0x${argb.toUInt().toString(16).uppercase().padStart(8, '0')}"
        }
    }
}

/**
 * Extension function to convert Color to settings hex string (0xAARRGGBB format).
 * This is a top-level extension so it can be properly imported and used.
 */
fun Color.toSettingsHex(): String = TerminalSettings.colorToHex(this)
