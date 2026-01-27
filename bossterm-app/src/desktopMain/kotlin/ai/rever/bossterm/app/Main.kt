package ai.rever.bossterm.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.cli.CLIInstallDialog
import ai.rever.bossterm.compose.cli.CLIInstaller
import ai.rever.bossterm.compose.notification.NotificationService
import ai.rever.bossterm.compose.onboarding.OnboardingWizard
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.SettingsWindow
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.update.UpdateBanner
import ai.rever.bossterm.compose.update.UpdateManager
import ai.rever.bossterm.compose.window.CustomTitleBar
import ai.rever.bossterm.compose.window.GlobalHotKeyManager
import ai.rever.bossterm.compose.window.HotKeyConfig
import ai.rever.bossterm.compose.window.WindowManager
import ai.rever.bossterm.compose.window.WindowVisibilityController
import ai.rever.bossterm.compose.window.configureWindowTransparency
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import org.jetbrains.skia.Image
import androidx.compose.ui.graphics.toComposeImageBitmap

/**
 * BossTerm - Modern terminal emulator built with Kotlin and Compose Desktop.
 *
 * This is the main entry point for the BossTerm application.
 */
fun main() {
    // Configure GPU rendering (must be before any Skiko/Compose initialization)
    configureGpuRendering()

    // Set WM_CLASS for Linux desktop integration (must be before any AWT init)
    setLinuxWMClass()

    // Start global hotkey manager (Windows only)
    startGlobalHotKeyManager()

    application {
        // Create initial window if none exist
        if (WindowManager.windows.isEmpty()) {
            WindowManager.createWindow()
        }

        // Detect platform
        val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

        // Render all windows
        for (window in WindowManager.windows) {
            key(window.id) {
                val windowState = rememberWindowState()
                // Settings dialog state (declared before Window for onPreviewKeyEvent access)
                var showSettingsDialog by remember { mutableStateOf(false) }
                // CLI install dialog state
                var showCLIInstallDialog by remember { mutableStateOf(false) }
                var isFirstRun by remember { mutableStateOf(false) }
                var isCLIInstalled by remember { mutableStateOf(CLIInstaller.isInstalled()) }

                // Onboarding wizard state
                var showOnboardingWizard by remember { mutableStateOf(false) }

                // Shell customization tool installation status
                var isStarshipInstalled by remember { mutableStateOf(false) }
                var isOhMyZshInstalled by remember { mutableStateOf(false) }
                var isPreztoInstalled by remember { mutableStateOf(false) }

                // Check for first run (CLI not installed or onboarding not completed)
                LaunchedEffect(Unit) {
                    // Check if this is the first window (avoid showing on every window)
                    if (WindowManager.windows.firstOrNull()?.id == window.id) {
                        // Check onboarding first
                        val settings = SettingsManager.instance.settings.value
                        if (!settings.onboardingCompleted) {
                            showOnboardingWizard = true
                        } else if (!isCLIInstalled) {
                            // Only show CLI install dialog if onboarding is done
                            isFirstRun = true
                            showCLIInstallDialog = true
                        }
                    }
                }

                // Refresh CLI install status when dialog closes
                LaunchedEffect(showCLIInstallDialog) {
                    if (!showCLIInstallDialog) {
                        isCLIInstalled = CLIInstaller.isInstalled()
                    }
                }

                // Check shell customization tool installation status on startup
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        isStarshipInstalled = ShellCustomizationUtils.isStarshipInstalled()
                        isOhMyZshInstalled = ShellCustomizationUtils.isOhMyZshInstalled()
                        isPreztoInstalled = ShellCustomizationUtils.isPreztoInstalled()
                    }
                }

                // Get settings
                val settingsManagerForWindow = remember { SettingsManager.instance }
                val windowSettings by settingsManagerForWindow.settings.collectAsState()

                // Read native title bar setting at startup (not reactive - requires restart)
                val useNativeTitleBar = remember { settingsManagerForWindow.settings.value.useNativeTitleBar }

                Window(
                    onCloseRequest = {
                        WindowManager.closeWindow(window.id)
                        if (!WindowManager.hasWindows()) {
                            exitApplication()
                        }
                    },
                    state = windowState,
                    title = window.title.value,
                    undecorated = !useNativeTitleBar,
                    transparent = !useNativeTitleBar,
                    onPreviewKeyEvent = { keyEvent ->
                        // Handle Cmd+, (macOS) or Ctrl+, (other) for Settings
                        if (keyEvent.type == KeyEventType.KeyDown &&
                            keyEvent.key == Key.Comma &&
                            ((isMacOS && keyEvent.isMetaPressed) || (!isMacOS && keyEvent.isCtrlPressed))
                        ) {
                            showSettingsDialog = true
                            true // Consume event
                        } else {
                            false
                        }
                    }
                ) {
                    // Update manager state
                    val updateManager = remember { UpdateManager.instance }
                    val updateState by updateManager.updateState.collectAsState()
                    val scope = rememberCoroutineScope()

                    // Track window focus for command completion notifications
                    val awtWindow = this.window
                    DisposableEffect(awtWindow) {
                        val focusListener = object : WindowAdapter() {
                            override fun windowGainedFocus(e: WindowEvent?) {
                                window.isWindowFocused.value = true
                            }
                            override fun windowLostFocus(e: WindowEvent?) {
                                window.isWindowFocused.value = false
                            }
                        }
                        awtWindow.addWindowFocusListener(focusListener)
                        // Set initial focus state
                        window.isWindowFocused.value = awtWindow.isFocused

                        // Store AWT window reference for global hotkey toggle
                        window.awtWindow = awtWindow

                        // Configure window transparency and blur (only for custom title bar mode)
                        if (!useNativeTitleBar) {
                            configureWindowTransparency(
                                window = awtWindow,
                                isTransparent = windowSettings.backgroundOpacity < 1.0f,
                                enableBlur = windowSettings.windowBlur
                            )
                        }

                        onDispose {
                            awtWindow.removeWindowFocusListener(focusListener)
                            window.awtWindow = null
                        }
                    }

                    // Handle fullscreen expansion for undecorated windows (only needed for custom title bar)
                    // Store previous bounds to restore when exiting fullscreen
                    var previousBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }

                    if (!useNativeTitleBar) {
                        LaunchedEffect(windowState.placement) {
                            if (windowState.placement == WindowPlacement.Fullscreen) {
                                // Save current bounds before going fullscreen
                                previousBounds = awtWindow.bounds

                                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                val screenDevice = ge.screenDevices.firstOrNull { device ->
                                    awtWindow.bounds.intersects(device.defaultConfiguration.bounds)
                                } ?: ge.defaultScreenDevice

                                val screenBounds = screenDevice.defaultConfiguration.bounds

                                // Set window to fill entire screen (true fullscreen, covers menu bar/dock)
                                awtWindow.setBounds(
                                    screenBounds.x,
                                    screenBounds.y,
                                    screenBounds.width,
                                    screenBounds.height
                                )
                            } else if (windowState.placement == WindowPlacement.Floating && previousBounds != null) {
                                // Restore previous bounds when exiting fullscreen
                                awtWindow.bounds = previousBounds
                                previousBounds = null
                            }
                        }
                    }

                    // Check for updates on first window launch
                    var hasCheckedForUpdates by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        if (!hasCheckedForUpdates) {
                            hasCheckedForUpdates = true
                            if (updateManager.shouldCheckForUpdates()) {
                                updateManager.checkForUpdates()
                            }
                        }
                    }

                    // Request notification permission on first launch
                    val settingsManager = remember { SettingsManager.instance }
                    LaunchedEffect(Unit) {
                        val currentSettings = settingsManager.settings.value
                        if (!currentSettings.notificationPermissionRequested) {
                            // Send welcome notification to trigger macOS permission dialog
                            NotificationService.showNotification(
                                title = "BossTerm",
                                message = "Notifications enabled! You'll be notified when long-running commands complete.",
                                withSound = false
                            )
                            // Mark as requested so we don't show again
                            settingsManager.updateSettings(
                                currentSettings.copy(notificationPermissionRequested = true)
                            )
                        }
                    }

                    // Menu bar
                    MenuBar {
                        Menu("File", mnemonic = 'F') {
                            Item(
                                "New Tab",
                                onClick = { window.menuActions.onNewTab?.invoke() },
                                shortcut = KeyShortcut(Key.T, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Item(
                                "New Window",
                                onClick = { WindowManager.createWindow() },
                                shortcut = KeyShortcut(Key.N, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Separator()
                            Item(
                                "Close Tab",
                                onClick = { window.menuActions.onCloseTab?.invoke() },
                                shortcut = KeyShortcut(Key.W, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Item(
                                "Close Window",
                                onClick = {
                                    WindowManager.closeWindow(window.id)
                                    if (!WindowManager.hasWindows()) {
                                        exitApplication()
                                    }
                                },
                                shortcut = KeyShortcut(Key.W, meta = isMacOS, ctrl = !isMacOS, shift = true)
                            )
                            Separator()
                            Item(
                                "Settings...",
                                onClick = { showSettingsDialog = true },
                                shortcut = KeyShortcut(Key.Comma, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Separator()
                            Item(
                                if (isCLIInstalled) "Uninstall Command Line Tool..." else "Install Command Line Tool...",
                                onClick = { showCLIInstallDialog = true }
                            )
                        }

                        Menu("Edit", mnemonic = 'E') {
                            Item(
                                "Copy",
                                onClick = { window.menuActions.onCopy?.invoke() },
                                shortcut = KeyShortcut(Key.C, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Item(
                                "Paste",
                                onClick = { window.menuActions.onPaste?.invoke() },
                                shortcut = KeyShortcut(Key.V, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Separator()
                            Item(
                                "Select All",
                                onClick = { window.menuActions.onSelectAll?.invoke() },
                                shortcut = KeyShortcut(Key.A, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Item(
                                "Clear",
                                onClick = { window.menuActions.onClear?.invoke() },
                                shortcut = KeyShortcut(Key.K, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Separator()
                            Item(
                                "Find...",
                                onClick = { window.menuActions.onFind?.invoke() },
                                shortcut = KeyShortcut(Key.F, meta = isMacOS, ctrl = !isMacOS)
                            )
                        }

                        Menu("View", mnemonic = 'V') {
                            Item(
                                "Toggle Debug Panel",
                                onClick = { window.menuActions.onToggleDebug?.invoke() },
                                shortcut = KeyShortcut(Key.D, meta = isMacOS, ctrl = !isMacOS, shift = true)
                            )
                        }

                        Menu("Shell", mnemonic = 'S') {
                            Item(
                                "Split Vertically",
                                onClick = { window.menuActions.onSplitVertical?.invoke() },
                                shortcut = KeyShortcut(Key.D, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Item(
                                "Split Horizontally",
                                onClick = { window.menuActions.onSplitHorizontal?.invoke() },
                                shortcut = KeyShortcut(Key.H, meta = isMacOS, ctrl = !isMacOS, shift = true)
                            )
                            Separator()
                            Item(
                                "Close Split",
                                onClick = { window.menuActions.onClosePane?.invoke() }
                                // No shortcut - conflicts with Close Window. Use Cmd+W when pane is focused.
                            )
                        }

                        Menu("Window", mnemonic = 'W') {
                            Item(
                                "Minimize",
                                onClick = { windowState.isMinimized = true },
                                shortcut = KeyShortcut(Key.M, meta = isMacOS, ctrl = !isMacOS)
                            )
                            Separator()
                            Item(
                                "Next Tab",
                                onClick = { window.menuActions.onNextTab?.invoke() },
                                shortcut = KeyShortcut(Key.Tab, ctrl = true)
                            )
                            Item(
                                "Previous Tab",
                                onClick = { window.menuActions.onPreviousTab?.invoke() },
                                shortcut = KeyShortcut(Key.Tab, ctrl = true, shift = true)
                            )
                        }

                        Menu("Tools", mnemonic = 'T') {
                            // AI Assistants submenu
                            Menu("AI Assistants") {
                                Item(
                                    "Claude Code",
                                    onClick = { window.menuActions.onLaunchClaudeCode?.invoke() }
                                )
                                Item(
                                    "Gemini CLI",
                                    onClick = { window.menuActions.onLaunchGemini?.invoke() }
                                )
                                Item(
                                    "Codex",
                                    onClick = { window.menuActions.onLaunchCodex?.invoke() }
                                )
                                Item(
                                    "OpenCode",
                                    onClick = { window.menuActions.onLaunchOpenCode?.invoke() }
                                )
                            }
                            Separator()
                            // Git submenu - conditional based on repo status
                            Menu("Git") {
                                val isGitRepo = window.menuActions.isGitRepo.value
                                if (isGitRepo) {
                                    // In a git repository - show full menu
                                    Item("git status", onClick = { window.menuActions.onGitStatus?.invoke() })
                                    Item("git diff", onClick = { window.menuActions.onGitDiff?.invoke() })
                                    Item("git log", onClick = { window.menuActions.onGitLog?.invoke() })
                                    Separator()
                                    Item("git add .", onClick = { window.menuActions.onGitAddAll?.invoke() })
                                    Item("git add -p", onClick = { window.menuActions.onGitAddPatch?.invoke() })
                                    Item("git reset HEAD", onClick = { window.menuActions.onGitReset?.invoke() })
                                    Separator()
                                    Item("git commit", onClick = { window.menuActions.onGitCommit?.invoke() })
                                    Item("git commit --amend", onClick = { window.menuActions.onGitCommitAmend?.invoke() })
                                    Separator()
                                    Item("git push", onClick = { window.menuActions.onGitPush?.invoke() })
                                    Item("git pull", onClick = { window.menuActions.onGitPull?.invoke() })
                                    Item("git fetch --all", onClick = { window.menuActions.onGitFetch?.invoke() })
                                    Separator()
                                    Item("git branch -a", onClick = { window.menuActions.onGitBranch?.invoke() })
                                    Item("git checkout -", onClick = { window.menuActions.onGitCheckoutPrev?.invoke() })
                                    Item("git checkout -b ...", onClick = { window.menuActions.onGitCheckoutNew?.invoke() })
                                    Separator()
                                    Item("git stash", onClick = { window.menuActions.onGitStash?.invoke() })
                                    Item("git stash pop", onClick = { window.menuActions.onGitStashPop?.invoke() })
                                } else {
                                    // Not in a git repository - show init/clone only
                                    Item("git init", onClick = { window.menuActions.onGitInit?.invoke() })
                                    Item("git clone ...", onClick = { window.menuActions.onGitClone?.invoke() })
                                }
                            }
                            // GitHub CLI submenu - conditional based on repo status
                            Menu("GitHub CLI") {
                                val isGitRepo = window.menuActions.isGitRepo.value
                                val isGhConfigured = window.menuActions.isGhConfigured.value

                                Item("gh auth status", onClick = { window.menuActions.onGhAuthStatus?.invoke() })
                                Item("gh auth login", onClick = { window.menuActions.onGhAuthLogin?.invoke() })
                                Separator()
                                if (!isGitRepo) {
                                    // Not in git repo - show clone only
                                    Item("gh repo clone ...", onClick = { window.menuActions.onGhRepoClone?.invoke() })
                                } else if (!isGhConfigured) {
                                    // In git repo but gh not configured
                                    Item("gh repo set-default", onClick = { window.menuActions.onGhSetDefault?.invoke() })
                                    Separator()
                                    Item("gh repo clone ...", onClick = { window.menuActions.onGhRepoClone?.invoke() })
                                } else {
                                    // Fully configured - show all options
                                    Item("gh pr list", onClick = { window.menuActions.onGhPrList?.invoke() })
                                    Item("gh pr status", onClick = { window.menuActions.onGhPrStatus?.invoke() })
                                    Item("gh pr create", onClick = { window.menuActions.onGhPrCreate?.invoke() })
                                    Item("gh pr view --web", onClick = { window.menuActions.onGhPrView?.invoke() })
                                    Separator()
                                    Item("gh issue list", onClick = { window.menuActions.onGhIssueList?.invoke() })
                                    Item("gh issue create", onClick = { window.menuActions.onGhIssueCreate?.invoke() })
                                    Separator()
                                    Item("gh repo view --web", onClick = { window.menuActions.onGhRepoView?.invoke() })
                                }
                            }
                            Separator()
                            // Shell Configuration submenu (always show - edit config files is always useful)
                            Menu("Shell Config") {
                                Item("Edit .zshrc", onClick = { window.menuActions.onEditZshrc?.invoke() })
                                Item("Edit .bashrc", onClick = { window.menuActions.onEditBashrc?.invoke() })
                                Item("Edit config.fish", onClick = { window.menuActions.onEditFishConfig?.invoke() })
                                Separator()
                                Item("Reload Config", onClick = { window.menuActions.onReloadShellConfig?.invoke() })
                            }
                            // Starship submenu (only if installed)
                            if (isStarshipInstalled) {
                                Menu("Starship") {
                                    Item("Edit Config", onClick = { window.menuActions.onStarshipEditConfig?.invoke() })
                                    Item("Apply Preset...", onClick = { window.menuActions.onStarshipPresets?.invoke() })
                                }
                            }
                            // Oh My Zsh submenu (only if installed)
                            if (isOhMyZshInstalled) {
                                Menu("Oh My Zsh") {
                                    Item("Update", onClick = { window.menuActions.onOhMyZshUpdate?.invoke() })
                                    Item("List Themes", onClick = { window.menuActions.onOhMyZshThemes?.invoke() })
                                    Item("List Plugins", onClick = { window.menuActions.onOhMyZshPlugins?.invoke() })
                                }
                            }
                            // Prezto submenu (only if installed)
                            if (isPreztoInstalled) {
                                Menu("Prezto") {
                                    Item("Update", onClick = { window.menuActions.onPreztoUpdate?.invoke() })
                                    Item("Edit Config", onClick = { window.menuActions.onPreztoEditConfig?.invoke() })
                                    Item("List Themes", onClick = { window.menuActions.onPreztoListThemes?.invoke() })
                                    Item("Show Modules", onClick = { window.menuActions.onPreztoShowModules?.invoke() })
                                }
                            }
                        }

                        Menu("Help", mnemonic = 'H') {
                            Item(
                                "Welcome Wizard...",
                                onClick = { showOnboardingWizard = true }
                            )
                            Separator()
                            Item(
                                "Check for Updates...",
                                onClick = {
                                    scope.launch {
                                        updateManager.checkForUpdates()
                                    }
                                }
                            )
                        }
                    }

                    // Track fullscreen/maximized state for corner radius (only for custom title bar)
                    val isFullscreenOrMaximized = windowState.placement == WindowPlacement.Fullscreen ||
                                                   windowState.placement == WindowPlacement.Maximized
                    val cornerRadius = if (useNativeTitleBar || isFullscreenOrMaximized) 0.dp else 20.dp

                    // Load background image if set
                    val backgroundImage = remember(windowSettings.backgroundImagePath) {
                        if (windowSettings.backgroundImagePath.isNotEmpty()) {
                            try {
                                val file = java.io.File(windowSettings.backgroundImagePath)
                                if (file.exists()) {
                                    Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
                                } else null
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }

                    // Content area with transparent background (transparency only works with custom title bar)
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(cornerRadius)),
                        color = windowSettings.defaultBackgroundColor.copy(
                            alpha = if (useNativeTitleBar) 1f else windowSettings.backgroundOpacity
                        ),
                        shape = RoundedCornerShape(cornerRadius)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Compute global hotkey hint (used for both title bar modes)
                            val globalHotkeyHint = remember(
                                windowSettings.globalHotkeyEnabled,
                                windowSettings.globalHotkeyCtrl,
                                windowSettings.globalHotkeyAlt,
                                windowSettings.globalHotkeyShift,
                                windowSettings.globalHotkeyWin,
                                window.windowNumber
                            ) {
                                if (windowSettings.globalHotkeyEnabled && window.windowNumber in 1..9) {
                                    val config = HotKeyConfig.fromSettings(windowSettings)
                                    config.toWindowDisplayString(window.windowNumber, useMacSymbols = isMacOS)
                                } else {
                                    null
                                }
                            }

                            // Background layer: either image or glass blur effect
                            if (backgroundImage != null) {
                                // Background image with blur
                                androidx.compose.foundation.Image(
                                    bitmap = backgroundImage,
                                    contentDescription = "Background",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    alpha = windowSettings.backgroundImageOpacity,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (windowSettings.windowBlur) {
                                                Modifier.blur(windowSettings.blurRadius.dp)
                                            } else Modifier
                                        )
                                )
                            } else if (!useNativeTitleBar && windowSettings.backgroundOpacity < 1.0f && windowSettings.windowBlur) {
                                // Frosted glass effect - radial gradient
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.3f),
                                                    Color.Gray.copy(alpha = 0.4f),
                                                    Color.DarkGray.copy(alpha = 0.35f)
                                                )
                                            )
                                        )
                                        .blur(windowSettings.blurRadius.dp)
                                )
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                // Custom title bar (only when not using native title bar)
                                if (!useNativeTitleBar) {
                                    CustomTitleBar(
                                        title = window.title.value,
                                        windowState = windowState,
                                        onClose = {
                                            WindowManager.closeWindow(window.id)
                                            if (!WindowManager.hasWindows()) {
                                                exitApplication()
                                            }
                                        },
                                        onMinimize = { windowState.isMinimized = true },
                                        onFullscreen = {
                                            // Toggle fullscreen
                                            windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                                                WindowPlacement.Floating
                                            } else {
                                                WindowPlacement.Fullscreen
                                            }
                                        },
                                        onMaximize = {
                                            // Same as fullscreen for undecorated windows
                                            windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                                WindowPlacement.Floating
                                            } else {
                                                WindowPlacement.Maximized
                                            }
                                        },
                                        backgroundColor = windowSettings.defaultBackgroundColor.copy(
                                            alpha = (windowSettings.backgroundOpacity * 1.1f).coerceAtMost(1f)
                                        ),
                                        globalHotkeyHint = globalHotkeyHint
                                    )
                                }

                                // Update banner (shows when update is available)
                                UpdateBanner(
                                    updateState = updateState,
                                    onCheckForUpdates = {
                                        scope.launch {
                                            updateManager.checkForUpdates()
                                        }
                                    },
                                    onDownloadUpdate = { updateInfo ->
                                        scope.launch {
                                            updateManager.downloadUpdate(updateInfo)
                                        }
                                    },
                                    onInstallUpdate = { downloadPath ->
                                        scope.launch {
                                            updateManager.installUpdate(downloadPath)
                                        }
                                    },
                                    onDismiss = {
                                        updateManager.resetState()
                                    }
                                )

                                // Terminal content
                                TabbedTerminal(
                                    onExit = {
                                        WindowManager.closeWindow(window.id)
                                        if (!WindowManager.hasWindows()) {
                                            exitApplication()
                                        }
                                    },
                                    onWindowTitleChange = { newTitle ->
                                        window.title.value = newTitle
                                    },
                                    onNewWindow = {
                                        WindowManager.createWindow()
                                    },
                                    onShowSettings = { showSettingsDialog = true },
                                    onShowWelcomeWizard = { showOnboardingWizard = true },
                                    menuActions = window.menuActions,
                                    isWindowFocused = { window.isWindowFocused.value },
                                    modifier = Modifier.fillMaxSize().weight(1f)
                                )
                            }

                            // Hotkey hint overlay (top-right corner, like iTerm2)
                            // Shows for native title bar; custom title bar shows it in the title bar itself
                            if (useNativeTitleBar && globalHotkeyHint != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 8.dp, end = 12.dp)
                                ) {
                                    Text(
                                        text = globalHotkeyHint,
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 11.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    // Settings dialog
                    SettingsWindow(
                        visible = showSettingsDialog,
                        onDismiss = { showSettingsDialog = false },
                        onRestartApp = {
                            // Close this window and create a new one with updated settings
                            showSettingsDialog = false
                            WindowManager.closeWindow(window.id)
                            WindowManager.createWindow()
                        }
                    )

                    // CLI install dialog
                    CLIInstallDialog(
                        visible = showCLIInstallDialog,
                        onDismiss = {
                            showCLIInstallDialog = false
                            isFirstRun = false
                        },
                        isFirstRun = isFirstRun
                    )

                    // Onboarding wizard
                    if (showOnboardingWizard) {
                        OnboardingWizard(
                            onDismiss = {
                                showOnboardingWizard = false
                                // After onboarding, check if CLI needs to be installed
                                if (!isCLIInstalled) {
                                    isFirstRun = true
                                    showCLIInstallDialog = true
                                }
                            },
                            onComplete = {
                                showOnboardingWizard = false
                                // After onboarding, check if CLI needs to be installed
                                if (!isCLIInstalled) {
                                    isFirstRun = true
                                    showCLIInstallDialog = true
                                }
                            },
                            settingsManager = settingsManager
                        )
                    }
                }
            }
        }
    }
}

/**
 * Set WM_CLASS for proper Linux desktop integration.
 * Must be called before any windows are created.
 * Requires JVM arg: --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED
 */
private fun setLinuxWMClass() {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return

    try {
        // Get toolkit instance (creates it if needed)
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name == "sun.awt.X11.XToolkit") {
            val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(toolkit, "bossterm")
        }
    } catch (e: Exception) {
        System.err.println("Could not set WM_CLASS: ${e.message}")
    }
}

/**
 * Configure GPU rendering based on user settings.
 * Must be called before any Skiko/Compose initialization.
 *
 * Sets Skiko system properties to control:
 * - Render API (Metal, OpenGL, DirectX, Software)
 * - GPU selection (integrated vs discrete)
 * - VSync
 * - GPU resource cache size
 */
private fun configureGpuRendering() {
    val osName = System.getProperty("os.name").lowercase()
    val isMacOS = osName.contains("mac")
    val isWindows = osName.contains("windows")

    // Load settings using SettingsLoader (handles JSON parsing and defaults)
    val settings = try {
        ai.rever.bossterm.compose.settings.SettingsLoader.loadFromPathOrDefault(null)
    } catch (e: Exception) {
        System.err.println("Could not load settings for GPU config, using defaults: ${e.message}")
        e.printStackTrace()
        ai.rever.bossterm.compose.settings.TerminalSettings()
    }

    // Configure render API
    val renderApi = if (!settings.gpuAcceleration) {
        "SOFTWARE"
    } else {
        when (settings.gpuRenderApi.lowercase()) {
            "metal" -> if (isMacOS) "METAL" else null
            "opengl" -> "OPENGL"
            "direct3d" -> if (isWindows) "DIRECT3D12" else null
            "software" -> "SOFTWARE"
            else -> null // "auto" - let Skiko decide
        }
    }

    renderApi?.let {
        System.setProperty("skiko.renderApi", it)
        println("GPU: Render API set to $it")
    }

    // Configure GPU priority (for systems with multiple GPUs)
    val gpuPriority = when (settings.gpuPriority.lowercase()) {
        "integrated" -> "integrated"
        "discrete" -> "discrete"
        else -> null // "auto" - let system decide
    }

    gpuPriority?.let { priority ->
        if (isMacOS) {
            System.setProperty("skiko.metal.gpu.priority", priority)
            println("GPU: Metal GPU priority set to $priority")
        } else if (isWindows) {
            System.setProperty("skiko.directx.gpu.priority", priority)
            println("GPU: DirectX GPU priority set to $priority")
        }
    }

    // Configure VSync
    System.setProperty("skiko.vsync.enabled", settings.gpuVsyncEnabled.toString())
    if (!settings.gpuVsyncEnabled) {
        println("GPU: VSync disabled")
    }

    // Configure GPU resource cache limit (convert MB to bytes)
    // Note: Skiko uses this for glyph/texture caching
    val cacheSizeMb = settings.gpuCacheSizeMb.coerceIn(
        ai.rever.bossterm.compose.settings.SystemInfoUtils.GPU_CACHE_MIN_MB,
        ai.rever.bossterm.compose.settings.SystemInfoUtils.GPU_CACHE_MAX_MB
    )
    System.setProperty("skiko.gpu.resourceCacheLimit", (cacheSizeMb * 1024L * 1024L).toString())

    // Log GPU configuration summary
    println("GPU: Acceleration=${settings.gpuAcceleration}, API=${settings.gpuRenderApi}, " +
            "Priority=${settings.gpuPriority}, VSync=${settings.gpuVsyncEnabled}, Cache=${settings.gpuCacheSizeMb}MB")
}

/**
 * Start the global hotkey manager.
 * Allows summoning specific BossTerm windows from anywhere with system-wide hotkeys.
 * Each window gets a unique hotkey: Modifiers+1, Modifiers+2, etc.
 */
private fun startGlobalHotKeyManager() {
    // Load settings
    val settings = try {
        ai.rever.bossterm.compose.settings.SettingsLoader.loadFromPathOrDefault(null)
    } catch (e: Exception) {
        System.err.println("Could not load settings for global hotkey: ${e.message}")
        return
    }

    // Check if enabled
    val config = HotKeyConfig.fromSettings(settings)
    if (!config.enabled) {
        println("GlobalHotKey: Disabled in settings")
        return
    }

    // Validate configuration (need at least one modifier)
    if (!(config.ctrl || config.alt || config.shift || config.win)) {
        println("GlobalHotKey: Invalid configuration (no modifiers)")
        return
    }

    // Start the manager with window-specific callback
    GlobalHotKeyManager.start(config) { windowNumber ->
        // Find the window with this number
        val window = WindowManager.getWindowByNumber(windowNumber)
        if (window != null) {
            // Window exists - toggle its visibility
            val awtWindow = window.awtWindow
            if (awtWindow != null) {
                WindowVisibilityController.toggleWindow(listOf(awtWindow))
            }
        } else {
            // No window with this number - create one if it's window 1
            if (windowNumber == 1) {
                javax.swing.SwingUtilities.invokeLater {
                    WindowManager.createWindow()
                }
            }
        }
    }

    // Register shutdown hook to clean up
    Runtime.getRuntime().addShutdownHook(Thread {
        GlobalHotKeyManager.stop()
    })

    println("GlobalHotKey: Started with modifiers for windows 1-9")
}
