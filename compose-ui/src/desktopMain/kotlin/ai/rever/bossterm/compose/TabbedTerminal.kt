package ai.rever.bossterm.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.rever.bossterm.compose.ContextMenuElement
import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSubmenu
import ai.rever.bossterm.compose.mcp.AttachStatus
import ai.rever.bossterm.compose.mcp.AttachToast
import ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig
import ai.rever.bossterm.compose.mcp.McpAttachResult
import ai.rever.bossterm.compose.mcp.McpAttachTarget
import ai.rever.bossterm.compose.mcp.McpCliAttacher
import ai.rever.bossterm.compose.mcp.McpStatusIndicator
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.ai.AIAssistantDefinition
import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.ai.AICommandInterceptor
import ai.rever.bossterm.compose.ai.ToolCommandProvider
import ai.rever.bossterm.compose.ai.AIInstallDialogHost
import ai.rever.bossterm.compose.ai.AIInstallDialogParams
import ai.rever.bossterm.compose.ai.ToolInstallWizardHost
import ai.rever.bossterm.compose.ai.ToolInstallWizardParams
import ai.rever.bossterm.compose.ai.rememberAIAssistantState
import ai.rever.bossterm.compose.vcs.GitUtils
import ai.rever.bossterm.compose.vcs.VersionControlMenuProvider
import ai.rever.bossterm.compose.shell.ShellCustomizationMenuProvider
import ai.rever.bossterm.compose.menu.MenuActions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import ai.rever.bossterm.compose.util.loadTerminalFont
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.settings.withOverrides
import ai.rever.bossterm.compose.hyperlinks.HyperlinkDetector
import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkRegistry
import ai.rever.bossterm.compose.splits.NavigationDirection
import ai.rever.bossterm.compose.splits.SplitContainer
import ai.rever.bossterm.compose.splits.SplitOrientation
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.window.WindowManager
import ai.rever.bossterm.compose.tabs.TabBar
import ai.rever.bossterm.compose.tabs.TabBarHeight
import ai.rever.bossterm.compose.tabs.TabController
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.compose.ui.ProperTerminal

/**
 * Terminal composable with multi-tab support.
 *
 * This provides a complete tabbed terminal experience with:
 * - Multiple tabs per window
 * - Tab bar with close buttons
 * - Keyboard shortcuts for tab management
 * - Working directory inheritance for new tabs
 * - Command completion notifications (when window unfocused)
 *
 * Basic usage:
 * ```kotlin
 * TabbedTerminal(
 *     onExit = { exitApplication() }
 * )
 * ```
 *
 * With external state (survives recomposition):
 * ```kotlin
 * val state = rememberTabbedTerminalState(autoDispose = false)
 * TabbedTerminal(
 *     state = state,
 *     onExit = { exitApplication() }
 * )
 * ```
 *
 * With callbacks:
 * ```kotlin
 * TabbedTerminal(
 *     onExit = { exitApplication() },
 *     onWindowTitleChange = { title -> window.title = title },
 *     onNewWindow = { WindowManager.createWindow() }
 * )
 * ```
 *
 * @param state Optional external state holder. When provided, the terminal state survives
 *              recomposition (e.g., when embedded in a tab system). When null, state is
 *              managed internally and lost when composable unmounts.
 * @param onExit Called when the last tab is closed
 * @param onTabClose Called when a tab is about to close (before removal). Receives the stable tab ID.
 *                   Use this to clean up resources associated with specific tabs.
 * @param onWindowTitleChange Called when active tab's title changes (for window title bar)
 * @param onNewWindow Called when user requests a new window (Cmd/Ctrl+N)
 * @param menuActions Optional menu action callbacks for wiring up menu bar
 * @param isWindowFocused Lambda returning whether this window is currently focused (for notifications)
 * @param initialCommand Optional command to run in the first terminal tab after startup
 * @param onInitialCommandComplete Callback invoked when initialCommand finishes executing.
 *                                  Requires OSC 133 shell integration to detect command completion.
 *                                  Parameters: success (true if exit code is 0), exitCode (command exit code).
 * @param workingDirectory Initial working directory for the first tab (defaults to user home)
 * @param onLinkClick Optional callback for custom link handling. When provided, intercepts Ctrl/Cmd+Click
 *                    on links and context menu "Open Link" action. Receives HyperlinkInfo with type,
 *                    patternId, isFile/isFolder, and other metadata. Return true if handled, false to
 *                    proceed with default behavior (open in browser/finder). When null, uses default behavior.
 * @param contextMenuItems Custom context menu items to add below the built-in items (Copy, Paste, Clear, Select All).
 *                         Applies to all tabs and split panes within the terminal.
 * @param contextMenuItemsProvider Lambda to get fresh context menu items on each menu open.
 *                                 When provided, this is called **after** onContextMenuOpenAsync completes
 *                                 (but before showing the menu) to get the most up-to-date items.
 *                                 If null, contextMenuItems is used instead.
 *                                 Use case: dynamic menu items that change based on async state (e.g., AI assistant status).
 * @param onContextMenuOpen Callback invoked right before the context menu is displayed (sync).
 *                          Use case: refresh dynamic menu item state (e.g., check AI assistant installation status).
 * @param onContextMenuOpenAsync Async callback invoked right before the context menu is displayed.
 *                               Menu display is delayed until this callback completes.
 *                               Use case: async refresh of dynamic menu item state before menu shows.
 * @param settingsOverride Per-instance settings overrides. Non-null fields override global settings.
 *                         Example: `TerminalSettingsOverride(alwaysShowTabBar = true)` to always show tab bar.
 * @param hyperlinkRegistry Custom hyperlink pattern registry for per-instance hyperlink customization.
 *                          Use this to add custom patterns (e.g., JIRA ticket IDs, custom URLs).
 *                          Default: global HyperlinkDetector.registry
 * @param modifier Compose modifier for the terminal container
 * @param platformServices Custom platform services
 */
@Composable
fun TabbedTerminal(
    state: TabbedTerminalState? = null,
    onExit: () -> Unit,
    onTabClose: ((tabId: String) -> Unit)? = null,
    onWindowTitleChange: (String) -> Unit = {},
    onNewWindow: () -> Unit = {},
    onShowSettings: () -> Unit = {},
    onShowMcpSettings: () -> Unit = onShowSettings,
    onShowWelcomeWizard: (() -> Unit)? = null,
    menuActions: MenuActions? = null,
    isWindowFocused: () -> Boolean = { true },
    initialCommand: String? = null,
    onInitialCommandComplete: ((success: Boolean, exitCode: Int) -> Unit)? = null,
    workingDirectory: String? = null,
    onLinkClick: ((HyperlinkInfo) -> Boolean)? = null,
    contextMenuItems: List<ContextMenuElement> = emptyList(),
    contextMenuItemsProvider: (() -> List<ContextMenuElement>)? = null,
    onContextMenuOpen: (() -> Unit)? = null,
    onContextMenuOpenAsync: (suspend () -> Unit)? = null,
    settingsOverride: TerminalSettingsOverride? = null,
    hyperlinkRegistry: HyperlinkRegistry = HyperlinkDetector.registry,
    /**
     * Whether the terminal is currently "active" from the host's
     * perspective — i.e. the surrounding panel/tab is the one the user
     * is interacting with. Defaults to `true` for callers that don't
     * differentiate (single-window, single-panel embedding).
     *
     * Toggling this to `false` when the host loses external focus and
     * back to `true` on regain causes [ai.rever.bossterm.compose.ui.ProperTerminal]'s
     * internal `LaunchedEffect(tab.id, isActiveTab)` to re-issue the
     * focus requester for the focused pane — restoring keyboard input
     * routing to the embedded terminal widget. Without this signal,
     * external focus round-trips (clicking another panel and back) can
     * leave the terminal visually present but unable to receive
     * keystrokes until the user manually clicks a split or switches
     * tabs.
     */
    isActive: Boolean = true,
    modifier: Modifier = Modifier,
    platformServices: PlatformServices = getPlatformServices()
) {
    // Settings integration
    val settingsManager = remember { SettingsManager.instance }
    val globalSettings by settingsManager.settings.collectAsState()

    // Merge global settings with per-instance overrides
    val settings = remember(globalSettings, settingsOverride) {
        globalSettings.withOverrides(settingsOverride)
    }

    // Load font once and share across all tabs (supports custom font via settings)
    val sharedFont = remember(settings.fontName) {
        loadTerminalFont(settings.fontName)
    }

    // AI Assistant integration (issue #225)
    val aiState = rememberAIAssistantState(settings)

    // Thread-safe holder for detection results - avoids Compose state recomposition issues
    // Uses AtomicReference for safe access from suspend functions
    val detectionResultsHolder = remember { AtomicReference<Map<String, Boolean>?>(null) }

    // Version Control menu provider (Git and GitHub CLI)
    val vcsMenuProvider = remember { VersionControlMenuProvider() }
    val vcsStatusHolder = remember { AtomicReference<Pair<Boolean, Boolean>?>(null) }

    // Shell Customization menu provider (Starship, etc.)
    val shellMenuProvider = remember { ShellCustomizationMenuProvider() }
    val shellStatusHolder = remember { AtomicReference<Map<String, Boolean>?>(null) }

    // State for AI assistant installation dialog (uses shared AIInstallDialogParams)
    var installDialogState by remember { mutableStateOf<AIInstallDialogParams?>(null) }

    // State for AI tool installation wizard (shown when command is intercepted or from menu)
    var toolWizardParams by remember { mutableStateOf<ToolInstallWizardParams?>(null) }

    // Track which tabs have interceptors set up
    val interceptorSetupTracker = remember { mutableSetOf<String>() }

    // Initialize external state if provided (only once)
    if (state != null && !state.isInitialized) {
        state.initialize(
            settings = settings,
            onLastTabClosed = onExit,
            isWindowFocused = isWindowFocused,
            onTabClose = onTabClose,
            platformServices = platformServices
        )
    }

    // Use external state's tabController if provided, otherwise create internal one
    val tabController = state?.tabController ?: remember {
        TabController(
            settings = settings,
            onLastTabClosed = onExit,
            isWindowFocused = isWindowFocused,
            onTabClose = onTabClose,
            platformServices = platformServices
        )
    }

    // Track window focus state reactively for overlay
    val isWindowFocusedState by remember { derivedStateOf { isWindowFocused() } }

    // Track SplitViewState per tab (tab.id -> SplitViewState)
    // Use external state's splitStates if provided, otherwise create internal ones
    val splitStates = state?.splitStates ?: remember { mutableStateMapOf<String, SplitViewState>() }

    // Helper function to get or create SplitViewState for a tab
    fun getOrCreateSplitState(tab: TerminalTab): SplitViewState {
        return splitStates.getOrPut(tab.id) {
            val state = SplitViewState(initialSession = tab)

            // Set up split-aware process exit for the original tab
            // This ensures exiting the original pane closes just that pane,
            // not the entire tab (unless it's the last pane)
            tab.onProcessExit = {
                if (state.isSinglePane) {
                    // Last pane - close the tab
                    val tabIndex = tabController.tabs.indexOfFirst { it.id == tab.id }
                    if (tabIndex != -1) {
                        tabController.closeTab(tabIndex)
                    }
                } else {
                    // Close just this pane
                    state.getAllPanes()
                        .find { it.session === tab }
                        ?.let { pane -> state.closePane(pane.id) }
                }
            }

            state
        }
    }

    // Helper function to create a new session for splitting
    fun createSessionForSplit(splitState: SplitViewState, paneId: String): TerminalSession {
        val workingDir = splitState.getFocusedSession()?.workingDirectory?.value
        return tabController.createSessionForSplit(
            workingDir = workingDir,
            onProcessExit = {
                // Auto-close the pane when shell exits
                splitState.closePane(paneId)
            }
        )
    }

    // Wire up menu actions for tab management
    LaunchedEffect(menuActions, tabController) {
        menuActions?.apply {
            onNewTab = {
                // New tabs always start in home directory (no working dir inheritance)
                // Use initial command from settings if configured
                tabController.createTab(initialCommand = settings.initialCommand.ifEmpty { null })
            }
            onCloseTab = {
                tabController.closeTab(tabController.activeTabIndex)
            }
            onNextTab = {
                tabController.nextTab()
            }
            onPreviousTab = {
                tabController.previousTab()
            }
        }
    }

    // Wire up split menu actions (updates when active tab changes or tabs are added)
    LaunchedEffect(menuActions, tabController.activeTabIndex, tabController.tabs.size) {
        if (tabController.tabs.isEmpty()) return@LaunchedEffect
        val activeTab = tabController.tabs.getOrNull(tabController.activeTabIndex) ?: return@LaunchedEffect
        val splitState = getOrCreateSplitState(activeTab)

        menuActions?.apply {
            onSplitVertical = {
                val workingDir = splitState.getFocusedSession()?.workingDirectory?.value
                var newSessionRef: TerminalSession? = null
                val newSession = tabController.createSessionForSplit(
                    workingDir = workingDir,
                    onProcessExit = {
                        // Auto-close the pane when process exits
                        if (splitState.isSinglePane) {
                            // Last pane - close the tab
                            val tabIndex = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                            if (tabIndex != -1) {
                                tabController.closeTab(tabIndex)
                            }
                        } else {
                            // Close just this pane
                            newSessionRef?.let { session ->
                                splitState.getAllPanes()
                                    .find { it.session === session }
                                    ?.let { pane -> splitState.closePane(pane.id) }
                            }
                        }
                    }
                )
                newSessionRef = newSession
                splitState.splitFocusedPane(SplitOrientation.VERTICAL, newSession)
            }
            onSplitHorizontal = {
                val workingDir = splitState.getFocusedSession()?.workingDirectory?.value
                var newSessionRef: TerminalSession? = null
                val newSession = tabController.createSessionForSplit(
                    workingDir = workingDir,
                    onProcessExit = {
                        // Auto-close the pane when process exits
                        if (splitState.isSinglePane) {
                            // Last pane - close the tab
                            val tabIndex = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                            if (tabIndex != -1) {
                                tabController.closeTab(tabIndex)
                            }
                        } else {
                            // Close just this pane
                            newSessionRef?.let { session ->
                                splitState.getAllPanes()
                                    .find { it.session === session }
                                    ?.let { pane -> splitState.closePane(pane.id) }
                            }
                        }
                    }
                )
                newSessionRef = newSession
                splitState.splitFocusedPane(SplitOrientation.HORIZONTAL, newSession)
            }
            onClosePane = {
                if (splitState.isSinglePane) {
                    tabController.closeTab(tabController.activeTabIndex)
                } else {
                    splitState.closeFocusedPane()
                }
            }
        }
    }

    // Wire up Tools menu actions
    LaunchedEffect(menuActions, tabController.activeTabIndex, tabController.tabs.size, aiState) {
        if (tabController.tabs.isEmpty()) return@LaunchedEffect
        val activeTab = tabController.tabs.getOrNull(tabController.activeTabIndex) ?: return@LaunchedEffect
        val splitState = getOrCreateSplitState(activeTab)

        // Helper to write to terminal
        val writeToTerminal: (String) -> Unit = { cmd -> activeTab.writeUserInput(cmd) }

        // Get current working directory from focused session
        val getWorkingDir: () -> String? = {
            splitState.getFocusedSession()?.workingDirectory?.value
        }

        // Check git repo status for current working directory using shared GitUtils
        fun checkGitStatus(cwd: String?) {
            menuActions?.isGitRepo?.value = GitUtils.isGitRepo(cwd)
            menuActions?.isGhConfigured?.value = if (menuActions?.isGitRepo?.value == true) {
                GitUtils.isGhRepoConfigured(cwd)
            } else {
                false
            }
        }

        // Initial check
        checkGitStatus(getWorkingDir())

        // Helper to run git command in working directory using shared GitUtils
        val gitCmd: (String) -> Unit = { cmd ->
            writeToTerminal(GitUtils.gitCommand(cmd, getWorkingDir()))
        }

        // Helper to run gh command in working directory using shared GitUtils
        val ghCmd: (String) -> Unit = { cmd ->
            writeToTerminal(GitUtils.ghCommand(cmd, getWorkingDir()))
        }

        // Get current shell from environment
        val currentShell = System.getenv("SHELL")?.substringAfterLast("/") ?: "bash"
        val shellConfigFile = when (currentShell) {
            "zsh" -> "~/.zshrc"
            "fish" -> "~/.config/fish/config.fish"
            else -> "~/.bashrc"
        }

        menuActions?.apply {
            // AI Assistants - launch or show install dialog
            onLaunchClaudeCode = {
                val assistant = AIAssistants.findById("claude-code")
                if (assistant != null) {
                    val isInstalled = aiState.detector.installationStatus.value["claude-code"] ?: false
                    if (isInstalled) {
                        val config = settings.aiAssistantConfigs["claude-code"]
                        writeToTerminal(aiState.launcher.getLaunchCommand(assistant, config))
                    } else {
                        val resolved = aiState.launcher.resolveInstallCommands(assistant)
                        toolWizardParams = ToolInstallWizardParams(
                            tool = assistant,
                            installCommand = resolved.command,
                            npmCommand = resolved.npmFallback,
                            terminalWriter = writeToTerminal,
                            commandToRunAfter = "claude",
                            clearLine = null  // No line to clear when launched from menu
                        )
                    }
                }
            }
            onLaunchGemini = {
                val assistant = AIAssistants.findById("gemini-cli")
                if (assistant != null) {
                    val isInstalled = aiState.detector.installationStatus.value["gemini-cli"] ?: false
                    if (isInstalled) {
                        val config = settings.aiAssistantConfigs["gemini-cli"]
                        writeToTerminal(aiState.launcher.getLaunchCommand(assistant, config))
                    } else {
                        val resolved = aiState.launcher.resolveInstallCommands(assistant)
                        toolWizardParams = ToolInstallWizardParams(
                            tool = assistant,
                            installCommand = resolved.command,
                            npmCommand = resolved.npmFallback,
                            terminalWriter = writeToTerminal,
                            commandToRunAfter = "gemini",
                            clearLine = null
                        )
                    }
                }
            }
            onLaunchCodex = {
                val assistant = AIAssistants.findById("codex")
                if (assistant != null) {
                    val isInstalled = aiState.detector.installationStatus.value["codex"] ?: false
                    if (isInstalled) {
                        val config = settings.aiAssistantConfigs["codex"]
                        writeToTerminal(aiState.launcher.getLaunchCommand(assistant, config))
                    } else {
                        val resolved = aiState.launcher.resolveInstallCommands(assistant)
                        toolWizardParams = ToolInstallWizardParams(
                            tool = assistant,
                            installCommand = resolved.command,
                            npmCommand = resolved.npmFallback,
                            terminalWriter = writeToTerminal,
                            commandToRunAfter = "codex",
                            clearLine = null
                        )
                    }
                }
            }
            onLaunchOpenCode = {
                val assistant = AIAssistants.findById("opencode")
                if (assistant != null) {
                    val isInstalled = aiState.detector.installationStatus.value["opencode"] ?: false
                    if (isInstalled) {
                        val config = settings.aiAssistantConfigs["opencode"]
                        writeToTerminal(aiState.launcher.getLaunchCommand(assistant, config))
                    } else {
                        val resolved = aiState.launcher.resolveInstallCommands(assistant)
                        toolWizardParams = ToolInstallWizardParams(
                            tool = assistant,
                            installCommand = resolved.command,
                            npmCommand = resolved.npmFallback,
                            terminalWriter = writeToTerminal,
                            commandToRunAfter = "opencode",
                            clearLine = null
                        )
                    }
                }
            }

            // Git commands (path-aware using GitUtils)
            onGitInit = { writeToTerminal("git ${GitUtils.Commands.INIT}\n") }
            onGitClone = { writeToTerminal("git ${GitUtils.Commands.CLONE}") }
            onGitStatus = { gitCmd(GitUtils.Commands.STATUS) }
            onGitDiff = { gitCmd(GitUtils.Commands.DIFF) }
            onGitLog = { gitCmd(GitUtils.Commands.LOG) }
            onGitAddAll = { gitCmd(GitUtils.Commands.ADD_ALL) }
            onGitAddPatch = { gitCmd(GitUtils.Commands.ADD_PATCH) }
            onGitReset = { gitCmd(GitUtils.Commands.RESET) }
            onGitCommit = { gitCmd(GitUtils.Commands.COMMIT) }
            onGitCommitAmend = { gitCmd(GitUtils.Commands.COMMIT_AMEND) }
            onGitPush = { gitCmd(GitUtils.Commands.PUSH) }
            onGitPull = { gitCmd(GitUtils.Commands.PULL) }
            onGitFetch = { gitCmd(GitUtils.Commands.FETCH) }
            onGitBranch = { gitCmd(GitUtils.Commands.BRANCH) }
            onGitCheckoutPrev = { gitCmd(GitUtils.Commands.CHECKOUT_PREV) }
            onGitCheckoutNew = {
                // Uses gitCommand but without trailing newline (user types branch name)
                val cwd = getWorkingDir()
                if (cwd != null) {
                    writeToTerminal("git -C \"$cwd\" ${GitUtils.Commands.CHECKOUT_NEW}")
                } else {
                    writeToTerminal("git ${GitUtils.Commands.CHECKOUT_NEW}")
                }
            }
            onGitStash = { gitCmd(GitUtils.Commands.STASH) }
            onGitStashPop = { gitCmd(GitUtils.Commands.STASH_POP) }

            // GitHub CLI commands (path-aware using GitUtils)
            onGhAuthStatus = { writeToTerminal("gh ${GitUtils.GhCommands.AUTH_STATUS}\n") }
            onGhAuthLogin = { writeToTerminal("gh ${GitUtils.GhCommands.AUTH_LOGIN}\n") }
            onGhSetDefault = { ghCmd(GitUtils.GhCommands.SET_DEFAULT) }
            onGhRepoClone = { writeToTerminal("gh ${GitUtils.GhCommands.REPO_CLONE}") }
            onGhPrList = { ghCmd(GitUtils.GhCommands.PR_LIST) }
            onGhPrStatus = { ghCmd(GitUtils.GhCommands.PR_STATUS) }
            onGhPrCreate = { ghCmd(GitUtils.GhCommands.PR_CREATE) }
            onGhPrView = { ghCmd(GitUtils.GhCommands.PR_VIEW_WEB) }
            onGhIssueList = { ghCmd(GitUtils.GhCommands.ISSUE_LIST) }
            onGhIssueCreate = { ghCmd(GitUtils.GhCommands.ISSUE_CREATE) }
            onGhRepoView = { ghCmd(GitUtils.GhCommands.REPO_VIEW_WEB) }

            // Shell config
            onEditZshrc = { writeToTerminal("\${EDITOR:-nano} ~/.zshrc\n") }
            onEditBashrc = { writeToTerminal("\${EDITOR:-nano} ~/.bashrc\n") }
            onEditFishConfig = { writeToTerminal("\${EDITOR:-nano} ~/.config/fish/config.fish\n") }
            onReloadShellConfig = {
                val sourceCmd = when (currentShell) {
                    "zsh" -> "source ~/.zshrc"
                    "fish" -> "source ~/.config/fish/config.fish"
                    else -> "source ~/.bashrc"
                }
                writeToTerminal("$sourceCmd\n")
            }

            // Starship
            onStarshipEditConfig = { writeToTerminal("\${EDITOR:-nano} ~/.config/starship.toml\n") }
            onStarshipPresets = { writeToTerminal("starship preset --list\n") }

            // Oh My Zsh
            onOhMyZshUpdate = { writeToTerminal("omz update\n") }
            onOhMyZshThemes = { writeToTerminal("ls ~/.oh-my-zsh/themes/\n") }
            onOhMyZshPlugins = { writeToTerminal("ls ~/.oh-my-zsh/plugins/\n") }

            // Prezto
            onPreztoUpdate = { writeToTerminal("cd ~/.zprezto && git pull && git submodule update --init --recursive && cd -\n") }
            onPreztoEditConfig = { writeToTerminal("\${EDITOR:-nano} ~/.zpreztorc\n") }
            onPreztoListThemes = { writeToTerminal("ls ~/.zprezto/modules/prompt/functions/ | grep prompt_ | sed 's/prompt_//'\n") }
            onPreztoShowModules = { writeToTerminal("grep '^\\s*zmodule' ~/.zpreztorc 2>/dev/null || grep \"'\" ~/.zpreztorc | head -20\n") }
        }
    }

    // Initialize with one tab on first composition
    // Check for pending tab transfer from another window first
    LaunchedEffect(Unit) {
        if (tabController.tabs.isEmpty()) {
            val pendingTab = WindowManager.pendingTabForNewWindow
            val pendingSplitState = WindowManager.pendingSplitStateForNewWindow
            if (pendingTab != null) {
                // Clear pending state
                WindowManager.pendingTabForNewWindow = null
                WindowManager.pendingSplitStateForNewWindow = null
                // Add the transferred tab
                tabController.createTabFromExistingSession(pendingTab)
                // Restore split state if present
                if (pendingSplitState != null) {
                    splitStates[pendingTab.id] = pendingSplitState
                }
            } else {
                // Phase 6: restore the saved session (tabs + split layout + cwds) if enabled.
                val restored = if (settings.restoreSessionOnLaunch) {
                    ai.rever.bossterm.compose.session.SessionStore.load()
                } else null
                if (restored != null && restored.tabs.isNotEmpty()) {
                    restored.tabs.forEach { tabSnap ->
                        val firstCwd = ai.rever.bossterm.compose.session.SessionStore.firstLeafCwd(tabSnap.tree)
                        val tab = tabController.createTab(workingDir = firstCwd)
                        val splitState = getOrCreateSplitState(tab)
                        runCatching {
                            ai.rever.bossterm.compose.session.SessionStore.rebuildTree(
                                node = tabSnap.tree,
                                state = splitState,
                                paneId = splitState.focusedPaneId,
                                makeSession = { cwd ->
                                    var sessionRef: TerminalSession? = null
                                    val s = tabController.createSessionForSplit(
                                        workingDir = cwd,
                                        onProcessExit = {
                                            // Close the pane whose shell exited (by identity), not
                                            // whichever pane currently has focus.
                                            if (splitState.isSinglePane) {
                                                val idx = tabController.tabs.indexOfFirst { it.id == tab.id }
                                                if (idx != -1) tabController.closeTab(idx)
                                            } else {
                                                sessionRef?.let { sess ->
                                                    splitState.getAllPanes()
                                                        .find { it.session === sess }
                                                        ?.let { p -> splitState.closePane(p.id) }
                                                }
                                            }
                                        }
                                    )
                                    sessionRef = s
                                    s
                                }
                            )
                        }
                    }
                    tabController.switchToTab(
                        restored.activeTab.coerceIn(0, (tabController.tabs.size - 1).coerceAtLeast(0))
                    )
                } else {
                    // No saved session: create fresh terminal with optional initial command.
                    // Priority: parameter > settings > none
                    val effectiveInitialCommand = initialCommand ?: settings.initialCommand.ifEmpty { null }
                    tabController.createTab(
                        workingDir = workingDirectory,
                        initialCommand = effectiveInitialCommand,
                        onInitialCommandComplete = onInitialCommandComplete
                    )
                }
            }
        }
    }

    // Phase 6: persist session structure on structural change (tab add/close/switch,
    // and split/ratio changes in the active tab via its rootNode identity).
    LaunchedEffect(
        tabController.tabs.size,
        tabController.activeTabIndex,
        settings.restoreSessionOnLaunch,
        tabController.activeTab?.let { splitStates[it.id]?.rootNode }
    ) {
        if (settings.restoreSessionOnLaunch && tabController.tabs.isNotEmpty()) {
            ai.rever.bossterm.compose.session.SessionStore.save(
                ai.rever.bossterm.compose.session.SessionStore.capture(
                    tabs = tabController.tabs,
                    splitStateFor = { splitStates[it.id] },
                    activeTab = tabController.activeTabIndex
                )
            )
        }
    }

    // Run AI assistant detection once on startup (for command interception)
    LaunchedEffect(settings.aiAssistantsEnabled) {
        if (settings.aiAssistantsEnabled) {
            aiState.detector.detectAll()
        }
    }

    // Set up AI command interceptors for all tabs (detects typing "claude", "aider", etc.)
    // When an AI command is typed and the assistant is not installed, shows install prompt
    LaunchedEffect(tabController.tabs.size, settings.aiAssistantsEnabled) {
        if (!settings.aiAssistantsEnabled) return@LaunchedEffect

        for (tab in tabController.tabs) {
            // Skip if already set up
            if (tab.id in interceptorSetupTracker) continue
            if (tab.aiCommandInterceptor != null) {
                interceptorSetupTracker.add(tab.id)
                continue
            }

            // Create interceptor for this tab
            val interceptor = AICommandInterceptor(
                detector = aiState.detector,
                onInstallConfirm = { assistant, originalCommand, clearLineCallback ->
                    // Show installation wizard directly
                    val terminalWriter: (String) -> Unit = { text ->
                        tab.writeUserInput(text)
                    }
                    val resolved = aiState.launcher.resolveInstallCommands(assistant)
                    toolWizardParams = ToolInstallWizardParams(
                        tool = assistant,
                        installCommand = resolved.command,
                        npmCommand = resolved.npmFallback,
                        terminalWriter = terminalWriter,
                        commandToRunAfter = originalCommand,
                        clearLine = clearLineCallback
                    )
                }
            )

            // Set callback to clear the command line (send Ctrl+U)
            interceptor.clearLineCallback = {
                tab.writeUserInput("\u0015") // Ctrl+U clears line
            }

            // Register as CommandStateListener to track shell prompt state (OSC 133)
            tab.terminal.addCommandStateListener(interceptor)
            // Track it so TerminalTab.dispose() detaches it from the terminal; otherwise
            // the interceptor stays registered for the terminal's life and pins the AI
            // detector/launcher state after the tab is gone.
            tab.commandStateListeners.add(interceptor)

            // Store reference in tab for ProperTerminal to access
            tab.aiCommandInterceptor = interceptor

            interceptorSetupTracker.add(tab.id)
        }

        // Clean up tracker for closed tabs
        val currentTabIds = tabController.tabs.map { it.id }.toSet()
        interceptorSetupTracker.removeAll { it !in currentTabIds }
    }

    // Cleanup split states when tabs are closed
    // Keep BACKGROUND tabs' grids in step with the window. Only the composed tab's canvas
    // auto-fits (ProperTerminal.onGloballyPositioned), so without this a background shell
    // gets its SIGWINCH only when focused — apps reflow late, and shared viewers watching
    // an unfocused tab see a stale grid. Whenever the ACTIVE tab settles on a new
    // single-pane grid, propagate it to every other LOCAL single-pane tab of this window.
    // Splits re-measure per-pane on focus (their grids depend on ratios); remote mirrors
    // are host-driven and excluded.
    LaunchedEffect(tabController) {
        snapshotFlow {
            val active = tabController.activeTab ?: return@snapshotFlow null
            val ss = splitStates[active.id]
            if (ss != null && ss.getAllPanes().size > 1) null
            else active.id to active.display.termSize.value
        }.collect { sized ->
            val (activeId, size) = sized ?: return@collect
            if (size.columns < 2 || size.rows < 2) return@collect
            tabController.tabs.forEach { t ->
                if (t.id == activeId || t.isRemote) return@forEach
                val ss = splitStates[t.id]
                val session = when {
                    ss == null -> t
                    ss.getAllPanes().size == 1 ->
                        ss.getAllPanes().first().session as? ai.rever.bossterm.compose.tabs.TerminalTab
                    else -> null
                } ?: return@forEach
                if (session.isRemote) return@forEach
                val cur = session.display.termSize.value
                if (cur.columns == size.columns && cur.rows == size.rows) return@forEach
                runCatching {
                    session.terminal.resize(size, ai.rever.bossterm.terminal.RequestOrigin.User)
                }
                launch { runCatching { session.processHandle.value?.resize(size.columns, size.rows) } }
            }
        }
    }

    LaunchedEffect(tabController.tabs.size) {
        val currentTabIds = tabController.tabs.map { it.id }.toSet()
        // Find orphaned split states (tabs that were closed)
        val orphanedIds = splitStates.keys.filter { it !in currentTabIds }
        for (tabId in orphanedIds) {
            val splitState = splitStates.remove(tabId) ?: continue
            // Get all processes from split panes before disposing
            val processes = splitState.getAllSessions().mapNotNull { it.processHandle?.value }
            // Kill all processes first, then dispose
            for (process in processes) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        process.kill()
                    }
                } catch (e: Exception) {
                    println("WARN: Error killing split process: ${e.message}")
                }
            }
            // Now safe to dispose the split state (cancels coroutines, closes channels)
            splitState.dispose()
        }
    }

    // Cleanup when composable is disposed
    // Only dispose internal state - external TabbedTerminalState manages its own lifecycle
    DisposableEffect(tabController) {
        onDispose {
            if (state == null) {
                // Internal state: dispose everything
                splitStates.values.forEach { it.dispose() }
                splitStates.clear()
                tabController.disposeAll()
            }
            // External state: don't dispose - TabbedTerminalState.dispose() handles cleanup
        }
    }

    // Shared MCP attach plumbing — used by both the indicator's right-click
    // context menu and the terminal canvas's right-click menu. Hoisted to
    // TabbedTerminal scope so the in-flight Pending → Done toast state is
    // a single source of truth across both entry points.
    val mcpRunningPort by McpTerminalRegistry.runningPort.collectAsState()
    val mcpScope = rememberCoroutineScope()
    // Popup menu for picking Tab vs Window when starting a share from the status pill.
    val shareScopeMenu = remember { ai.rever.bossterm.compose.features.ContextMenuController() }
    // Popup menu for the MCP status segment (same menu the old MCP pill showed).
    val mcpMenu = remember { ai.rever.bossterm.compose.features.ContextMenuController() }
    val mcpServerLabel = LocalBossTermMcpConfig.current?.displayName ?: "BossTerm"
    var attachStatus by remember { mutableStateOf<AttachStatus?>(null) }
    var mcpAttaching by remember { mutableStateOf(false) }
    // Session sharing (issue #276): dialog state + live set of shared tab ids.
    var shareDialog by remember { mutableStateOf<ai.rever.bossterm.compose.share.SessionShareManager.ShareInfo?>(null) }
    // Account sign-in (BossConsole Supabase backend) — window opened from the Share menus.
    var showSignInWindow by remember { mutableStateOf(false) }
    var signInFocusTick by remember { mutableStateOf(0) }
    // "Signed in as …" toast for deep-link sign-ins that complete after the window was
    // closed (the manager emits only on interactive verifies, not the startup restore).
    // The collector must NOT block on the dismiss delay — otherwise a second sign-in during
    // the window is dropped (signInEvents is a buffer-of-1 SharedFlow). Auto-dismiss runs in
    // its own effect, keyed on the toast value, so each new toast restarts the timer.
    var signInToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Drain a cold-start sign-in (a deep link that launched the app and verified before any
        // window was listening) so its toast isn't lost; then stream live interactive sign-ins.
        ai.rever.bossterm.compose.auth.BossAccountManager.consumePendingSignInToast()?.let { signInToast = it }
        ai.rever.bossterm.compose.auth.BossAccountManager.signInEvents.collect { email ->
            signInToast = email
        }
    }
    LaunchedEffect(signInToast) {
        if (signInToast != null) {
            kotlinx.coroutines.delay(5000)
            signInToast = null
        }
    }
    // "Add remote": connect to another BossTerm's shared session (native client).
    var showAddRemote by remember { mutableStateOf(false) }
    // Pending "request control?" confirmation: a view-only group's split/new-tab click stores
    // the actual request here; confirming the dialog runs it, dismissing drops it.
    var requestControlPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
    // One-time size-reconcile offer when a remote mirror tab is first viewed and the host's
    // grid doesn't render 1:1 here: (session, container tab, focused pane mirror).
    var remoteFitPrompt by remember {
        mutableStateOf<Triple<ai.rever.bossterm.compose.remote.RemoteSession,
            ai.rever.bossterm.compose.tabs.TerminalTab,
            ai.rever.bossterm.compose.tabs.TerminalTab>?>(null)
    }
    // session.link → "cols×rows" the dialog was last offered for. Re-offering only when the
    // HOST's grid changes to a NEW mismatching size means a dismissal isn't nagged again,
    // but a host-side window resize re-raises the question.
    val remoteFitPromptShown = remember { mutableMapOf<String, String>() }
    // Viewing a remote mirror whose host grid doesn't render 1:1 in this window — on first
    // view AND whenever the host's grid changes — offer to fit OUR window to the host, or
    // (control) the host to us. Mirrors the web viewer's "fit host to this phone?" offer.
    LaunchedEffect(tabController) {
        data class FitCheck(
            val session: ai.rever.bossterm.compose.remote.RemoteSession,
            val container: ai.rever.bossterm.compose.tabs.TerminalTab,
            val pane: ai.rever.bossterm.compose.tabs.TerminalTab,
            val cols: Int,
            val rows: Int,
        )
        snapshotFlow {
            val active = tabController.activeTab ?: return@snapshotFlow null
            val session = state?.remoteSessions?.sessionForTab(active) ?: return@snapshotFlow null
            val pane = splitStates[active.id]?.getFocusedSession() as? ai.rever.bossterm.compose.tabs.TerminalTab
                ?: return@snapshotFlow null
            val grid = pane.display.termSize.value
            FitCheck(session, active, pane, grid.columns, grid.rows)
        }.collectLatest { check ->
            check ?: return@collectLatest
            // Debounce: a host window drag streams sizes — wait for it to settle, and let
            // the local canvas measure once (remoteFitCols/Rows) on a fresh tab.
            kotlinx.coroutines.delay(800)
            val fitC = check.pane.remoteFitCols
            val fitR = check.pane.remoteFitRows
            if (fitC < 2 || fitR < 2 || check.cols < 2 || check.rows < 2) return@collectLatest
            // Roughly 1:1 already → nothing to offer.
            if (kotlin.math.abs(check.cols - fitC) <= 2 && kotlin.math.abs(check.rows - fitR) <= 2) return@collectLatest
            val key = "${check.cols}x${check.rows}"
            if (remoteFitPromptShown[check.session.link] == key) return@collectLatest // already offered for this grid
            if (remoteFitPrompt != null) return@collectLatest // a prompt is already up
            remoteFitPromptShown[check.session.link] = key
            remoteFitPrompt = Triple(check.session, check.container, check.pane)
        }
    }
    // Bumped every time the share window is opened/reopened; ShareWindow brings its OS
    // window to the front when this changes, so clicking the share button while a share
    // window is already open raises that window instead of leaving it behind.
    var shareFocusTick by remember { mutableStateOf(0) }
    val sharedTabIds by ai.rever.bossterm.compose.share.SessionShareManager.sharedTabIds.collectAsState()
    // Devices awaiting host approval to connect (issue #276) — drives the approval toast
    // and the share dialog's pending list.
    val pendingShareRequests by ai.rever.bossterm.compose.share.SessionShareManager.pendingRequests.collectAsState()
    // Remote-access exposure (Tailscale/Cloudflare) resolves asynchronously (off the UI
    // thread), so a share dialog opens with the LAN URL first; when the public link becomes
    // available (or is torn down), rebuild the open dialog so it shows the current best URL.
    val shareRemoteUrl by ai.rever.bossterm.compose.share.SessionShareManager.remoteUrlFlow.collectAsState()
    LaunchedEffect(shareRemoteUrl) {
        val info = shareDialog ?: return@LaunchedEffect
        ai.rever.bossterm.compose.share.SessionShareManager.infoFor(info.tabId)?.let { shareDialog = it }
    }
    // Show (and focus) the share window for [info]. No-op when info is null.
    fun openShareWindow(info: ai.rever.bossterm.compose.share.SessionShareManager.ShareInfo?) {
        if (info == null) return
        shareDialog = info
        shareFocusTick++
    }
    // Start (or reopen) a share for a tab. TAB = this tab + its splits; WINDOW = all tabs.
    // First use auto-enables the feature; the server binds on demand.
    fun startShare(tabId: String, scope: ai.rever.bossterm.compose.share.ShareScope) {
        if (sharedTabIds.contains(tabId)) {
            openShareWindow(ai.rever.bossterm.compose.share.SessionShareManager.infoFor(tabId))
            return
        }
        if (!settings.sessionSharingEnabled) {
            SettingsManager.instance.updateSetting { copy(sessionSharingEnabled = true) }
        }
        mcpScope.launch {
            val info = ai.rever.bossterm.compose.share.SessionShareManager.share(tabId, scope)
            openShareWindow(info)
        }
    }
    // Split the active tab's focused pane (used by the left bar's split buttons). Mirrors
    // the per-tab onSplit handlers so panes auto-close on shell exit.
    fun splitActiveTab(orientation: SplitOrientation) {
        val activeTab = tabController.activeTab ?: return
        val splitState = getOrCreateSplitState(activeTab)
        val workingDir = if (settings.splitInheritWorkingDirectory) {
            val s = splitState.getFocusedSession()
            s?.workingDirectory?.value ?: s?.processHandle?.value?.getWorkingDirectory()
        } else null
        var newSessionRef: TerminalSession? = null
        val newSession = tabController.createSessionForSplit(
            workingDir = workingDir,
            onProcessExit = {
                if (splitState.isSinglePane) {
                    val idx = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                    if (idx != -1) tabController.closeTab(idx)
                } else {
                    newSessionRef?.let { session ->
                        splitState.getAllPanes().find { it.session === session }?.let { p -> splitState.closePane(p.id) }
                    }
                }
            }
        )
        newSessionRef = newSession
        splitState.splitFocusedPane(orientation, newSession, settings.splitDefaultRatio)
    }
    // Auto-dismiss the Done toast a few seconds after it appears, AND clear
    // any stale state if the MCP server unbinds — so the toast doesn't pop
    // back unexpectedly after a brief MCP off-then-on cycle.
    LaunchedEffect(attachStatus, mcpRunningPort) {
        if (mcpRunningPort == null) {
            attachStatus = null
            return@LaunchedEffect
        }
        val s = attachStatus
        if (s is AttachStatus.Done) {
            kotlinx.coroutines.delay(5000)
            if (attachStatus === s) attachStatus = null
        }
    }
    val mcpServerName = LocalBossTermMcpConfig.current?.serverName ?: "bossterm"
    // Sign In appears only in standalone BossTerm: inside an embedder (e.g. BossConsole's
    // terminal-tab plugin, which has its own account + owns the boss:// scheme) it's hidden.
    val signInVisible = mcpServerName == "bossterm"
    // Once signed in, the menu item becomes an account row showing the email instead of
    // "Sign In…" (clicking still opens the window, which then offers "Sign out"). The account
    // glyph is drawn as a Swing Icon in ContextMenuController — NOT an emoji in the label,
    // which corrupts AWT menu text metrics.
    val accountState by ai.rever.bossterm.compose.auth.BossAccountManager.state.collectAsState()
    val signInLabel = (accountState as? ai.rever.bossterm.compose.auth.BossAccountManager.AccountState.SignedIn)
        ?.email ?: "Sign In…"
    val openSignIn: () -> Unit = { showSignInWindow = true; signInFocusTick++ }
    val fireMcpAttach: (McpAttachTarget) -> Unit = { target ->
        // De-dupe: ignore clicks while an attach is in flight from any
        // right-click entry point. The Settings panel maintains its own
        // independent guard.
        if (!mcpAttaching) {
            val port = McpTerminalRegistry.runningPort.value
            if (port != null) {
                mcpAttaching = true
                attachStatus = AttachStatus.Pending(target)
                mcpScope.launch {
                    try {
                        val result = McpCliAttacher.attach(target, mcpServerName, port)
                        if (result is McpAttachResult.Success) {
                            McpTerminalRegistry.markAttached(target)
                        }
                        attachStatus = AttachStatus.Done(result)
                    } finally {
                        mcpAttaching = false
                    }
                }
            }
        }
    }

    // Tab UI layout with focus overlay support.
    // Computed once so the MCP overlay below can offset itself clear of the
    // tab bar (which occupies the same top-right corner as the "+" button).
    val tabBarVisible = tabController.tabs.size > 1 || settings.alwaysShowTabBar
    Box(modifier = modifier.fillMaxSize()) {
        val tabBarOnLeft = settings.tabBarPosition == "left"
        val tabBarComposable: @Composable () -> Unit = {
            // Tab bar (show when multiple tabs or alwaysShowTabBar is set).
            if (tabBarVisible) {
            val summaryMode = settings.tabBarSummaryMode
            val rm = state?.remoteSessions
            val tabBarClipboard = androidx.compose.ui.platform.LocalClipboardManager.current

            // Resolve the per-tab accent color: a manual color (Color ▸ menu) always
            // wins; otherwise, when color-by-directory is on, derive a stable accent
            // from the session's cwd. Reads MutableState so the bar recomposes live.
            fun colorHexFor(session: TerminalSession): String? {
                if (session.isRemote) {
                    // Group accent (set via the remote box header's right-click) wins; the
                    // default cyan marks mirrored remote tabs.
                    return rm?.sessionForMirror(session)?.accent?.value ?: "0xFF4FC3F7"
                }
                session.tabColor.value?.let { return it }
                if (settings.tabColorByDirectory) {
                    val cwd = session.workingDirectory.value
                    if (!cwd.isNullOrBlank()) {
                        val idx = Math.floorMod(cwd.hashCode(), ai.rever.bossterm.compose.tabs.TAB_COLOR_PRESETS.size)
                        return ai.rever.bossterm.compose.tabs.TAB_COLOR_PRESETS[idx].second
                    }
                }
                return null
            }

            // Abbreviate a working directory for the chip's second line (Warp-style):
            // collapse long paths to "~/…/parent/dir". At home the title is already "~", so the
            // second line would collapse to "~" too and get hidden (== title) — instead show the
            // full home path there, matching the web viewer (its second line is never blank/"~").
            fun abbreviateCwd(path: String?): String? {
                if (path.isNullOrBlank()) return null
                val home = System.getProperty("user.home")?.trimEnd('/')
                val clean = path.trimEnd('/').ifEmpty { "/" }
                val withTilde = if (!home.isNullOrEmpty() && (clean == home || clean.startsWith("$home/")))
                    "~" + clean.removePrefix(home) else clean
                val parts = withTilde.split('/').filter { it.isNotEmpty() }
                return when {
                    withTilde == "~" -> clean // home: show the real path (the "~" title carries the tilde)
                    withTilde == "/" -> "/"
                    parts.size <= 2 -> withTilde
                    withTilde.startsWith("~") -> "~/…/" + parts.takeLast(2).joinToString("/")
                    else -> "/…/" + parts.takeLast(2).joinToString("/")
                }
            }

            // Resolve the session a chip refers to: a split pane by id, or the tab
            // itself for the synthetic tab-level chip (summary mode / not-yet-split).
            fun sessionFor(tabIndex: Int, paneId: String): TerminalSession? {
                val tab = tabController.tabs.getOrNull(tabIndex) ?: return null
                return splitStates[tab.id]?.getAllPanes()?.firstOrNull { it.id == paneId }?.session ?: tab
            }

            // Each tab contributes a group of pane-chips (one chip per split pane).
            // In summary mode (or before a tab is split) a single chip represents the
            // whole tab, labeled with the tab's title.
            val tabGroupsWithTab = tabController.tabs.mapIndexed { index, tab ->
                val st = splitStates[tab.id]
                val panes = if (st != null && !summaryMode) {
                    st.getAllPanes().map { p ->
                        ai.rever.bossterm.compose.tabs.TabBarPane(
                            p.id, p.session.title.value, colorHexFor(p.session),
                            subtitle = abbreviateCwd(p.session.workingDirectory.value),
                            branch = (p.session as? ai.rever.bossterm.compose.tabs.TerminalTab)?.gitBranch?.value
                        )
                    }
                } else {
                    listOf(ai.rever.bossterm.compose.tabs.TabBarPane(
                        tab.id, tab.title.value, colorHexFor(tab),
                        subtitle = abbreviateCwd(tab.workingDirectory.value),
                        branch = tab.gitBranch.value
                    ))
                }
                tab to ai.rever.bossterm.compose.tabs.TabBarGroup(index, panes)
            }
            // Partition local tabs from mirrored remote-session tabs: local tabs render as
            // normal chip clusters; each remote session renders as its own boxed group with
            // a link header + footer actions (split / new tab / disconnect) targeting the host.
            val tabGroups = tabGroupsWithTab.filter { rm?.sessionForTab(it.first) == null }.map { it.second }
            val remoteGroups = rm?.sessions.orEmpty().mapNotNull { session ->
                session.upstreamRev.value // subscribe: re-partition when upstream info changes
                val mine = tabGroupsWithTab.filter { rm?.sessionForTab(it.first) === session }
                // The host's own tabs render directly; tabs the host itself mirrors from OTHER
                // sessions nest under a labeled subsection per upstream (read-only flagged).
                val gsWithTab = mine.filter { session.upstreamFor(it.first.id) == null }
                val gs = gsWithTab.map { it.second }
                // A host sharing ALL its windows stamps each tab with its window — section the
                // group's own tabs per window (sub-title rows), like the web viewer's boxes.
                // Only when EVERY own tab is stamped (mixed = an old/partial host → stay flat).
                val windowSections = run {
                    val byWin = gsWithTab.mapNotNull { (t, g) ->
                        session.windowFor(t.id)?.let { w -> Triple(w, t, g) }
                    }
                    if (byWin.size != gsWithTab.size || byWin.isEmpty()) emptyList()
                    else byWin.groupBy { it.first.key }.map { (_, items) ->
                        // Section actions target THIS host window: the active tab when it's in
                        // the section, else its first tab (the host routes by tab id). View-only
                        // routes to the request-control prompt, like the group footer.
                        val secTabs = items.map { it.second }
                        fun secAnchor() = secTabs.firstOrNull { it.id == tabController.activeTab?.id } ?: secTabs.first()
                        fun splitSec(horizontal: Boolean) {
                            if (!session.canControlState.value) {
                                requestControlPrompt = { session.requestControl() }
                                return
                            }
                            val t = secAnchor()
                            val paneId = splitStates[t.id]?.focusedPaneId ?: return
                            session.splitPane(t.id, paneId, horizontal)
                        }
                        ai.rever.bossterm.compose.tabs.RemoteWindowSection(
                            label = items.first().first.name ?: "Window",
                            groups = items.map { it.third },
                            onSplitVertical = { splitSec(horizontal = false) },
                            onSplitHorizontal = { splitSec(horizontal = true) },
                            onNewTab = {
                                if (session.canControlState.value) session.newTabIn(secAnchor().id)
                                else requestControlPrompt = { session.requestControl() }
                            },
                        )
                    }
                }
                val nested = mine
                    .mapNotNull { (t, g) -> session.upstreamFor(t.id)?.let { up -> Triple(up, t, g) } }
                    .groupBy { it.first.key }
                    .map { (_, items) ->
                        val up = items.first().first
                        val nestTabs = items.map { it.second }
                        // Footer actions target the active tab when it's in this nest, else its
                        // first tab; the host relays them to the origin. When the host itself is
                        // view-only on the origin, route to the relayed control request instead
                        // of a silent no-op.
                        fun anchor() = nestTabs.firstOrNull { it.id == tabController.activeTab?.id } ?: nestTabs.first()
                        fun splitNest(horizontal: Boolean) {
                            if (up.readOnly) {
                                requestControlPrompt = { session.requestControlFor(anchor().id) }
                                return
                            }
                            val t = anchor()
                            val paneId = splitStates[t.id]?.focusedPaneId ?: return
                            session.splitPane(t.id, paneId, horizontal)
                        }
                        // The origin itself may share ALL its windows (forwarded by the host as
                        // originWindowId) — section the nest per origin window, like the web
                        // viewer. Only when every tab is stamped; mixed/old hosts stay flat.
                        val nestWindowSections = run {
                            val stamped = items.filter { it.first.window != null }
                            if (stamped.size != items.size || stamped.isEmpty()) emptyList()
                            else stamped.groupBy { it.first.window!!.key }.map { (_, secItems) ->
                                val secTabs = secItems.map { it.second }
                                fun secAnchor() = secTabs.firstOrNull { it.id == tabController.activeTab?.id } ?: secTabs.first()
                                fun splitSec(horizontal: Boolean) {
                                    if (up.readOnly) {
                                        requestControlPrompt = { session.requestControlFor(secAnchor().id) }
                                        return
                                    }
                                    val t = secAnchor()
                                    val paneId = splitStates[t.id]?.focusedPaneId ?: return
                                    session.splitPane(t.id, paneId, horizontal)
                                }
                                ai.rever.bossterm.compose.tabs.RemoteWindowSection(
                                    label = secItems.first().first.window!!.name ?: "Window",
                                    groups = secItems.map { it.third },
                                    onSplitVertical = { splitSec(horizontal = false) },
                                    onSplitHorizontal = { splitSec(horizontal = true) },
                                    onNewTab = {
                                        if (up.readOnly) requestControlPrompt = { session.requestControlFor(secAnchor().id) }
                                        else session.newTabIn(secAnchor().id)
                                    },
                                )
                            }
                        }
                        ai.rever.bossterm.compose.tabs.RemoteNestedGroup(
                            label = up.name ?: "remote",
                            readOnly = up.readOnly,
                            offline = up.offline,
                            groups = items.map { it.third },
                            onSplitVertical = { splitNest(horizontal = false) },
                            onSplitHorizontal = { splitNest(horizontal = true) },
                            onNewTab = {
                                if (up.readOnly) requestControlPrompt = { session.requestControlFor(anchor().id) }
                                else session.newTabIn(anchor().id)
                            },
                            onClose = { session.disconnectUpstream(nestTabs.first().id) },
                            onRequestControl = { session.requestControlFor(anchor().id) },
                            windowSections = nestWindowSections,
                        )
                    }
                if (gs.isEmpty() && nested.isEmpty()) null else ai.rever.bossterm.compose.tabs.RemoteTabGroup(
                    id = session.link,
                    // Group label precedence: local rename → the host's session name (its
                    // username by default) → the link's host.
                    header = session.customName.value
                        ?: session.hostName.value
                        ?: runCatching { java.net.URI(session.link).host ?: session.link }.getOrDefault(session.link),
                    colorHex = session.accent.value,
                    groups = gs,
                    canControl = session.canControlState.value, // Compose state → menus update on grant
                    statusLabel = when (session.statusState.value) {
                        is ai.rever.bossterm.compose.remote.RemoteStatus.Connected -> null
                        ai.rever.bossterm.compose.remote.RemoteStatus.Connecting -> "connecting…"
                        ai.rever.bossterm.compose.remote.RemoteStatus.Pending -> "awaiting approval…"
                        is ai.rever.bossterm.compose.remote.RemoteStatus.Failed -> "disconnected"
                        is ai.rever.bossterm.compose.remote.RemoteStatus.Denied -> "denied"
                        ai.rever.bossterm.compose.remote.RemoteStatus.Closed -> "closed"
                    },
                    statusError = session.statusState.value.let {
                        it is ai.rever.bossterm.compose.remote.RemoteStatus.Failed ||
                            it is ai.rever.bossterm.compose.remote.RemoteStatus.Denied ||
                            it is ai.rever.bossterm.compose.remote.RemoteStatus.Closed
                    },
                    // View-only: split/new-tab first confirm via the request-control dialog
                    // (confirming sends the request; the host shows its approval toast).
                    onSplitVertical = {
                        if (session.canControlState.value) session.splitFocused(horizontal = false)
                        else requestControlPrompt = { session.requestControl() }
                    },
                    onSplitHorizontal = {
                        if (session.canControlState.value) session.splitFocused(horizontal = true)
                        else requestControlPrompt = { session.requestControl() }
                    },
                    onNewTab = {
                        if (session.canControlState.value) session.newRemoteTab()
                        else requestControlPrompt = { session.requestControl() }
                    },
                    onDisconnect = { rm?.disconnect(session) },
                    onChipSplit = { tabIndex, paneId, horizontal ->
                        tabController.tabs.getOrNull(tabIndex)?.let { session.splitPane(it.id, paneId, horizontal) }
                    },
                    onChipLaunchAI = { tabIndex, paneId, assistantId ->
                        tabController.tabs.getOrNull(tabIndex)?.let { session.launchAI(it.id, paneId, assistantId) }
                    },
                    // Local group customization (box header right-click): blank name reverts
                    // to the link's host; null color reverts to the default remote cyan.
                    onRename = { session.customName.value = it.trim().ifBlank { null } },
                    onSetColor = { hex -> session.accent.value = hex },
                    // The same share link this group mirrors, in the web viewer.
                    onOpenInBrowser = { HyperlinkDetector.openUrl(session.link) },
                    onCopyLink = { tabBarClipboard.setText(androidx.compose.ui.text.AnnotatedString(session.link)) },
                    onRequestControl = { session.requestControl() },
                    nested = nested,
                    windowSections = windowSections,
                    // This remote's MCP — pill in the group header; click opens its toggle/attach
                    // menu (control-gated, else the view-only request-control prompt).
                    mcpShown = session.mcpStatus.value != null,
                    mcpRunning = session.mcpStatus.value?.running == true,
                    onMcpClick = {
                        session.mcpStatus.value?.let { st ->
                            mcpMenu.showMenu(0f, 0f, remoteMcpMenuItems(session, st) {
                                requestControlPrompt = { session.requestControl() }
                            })
                        }
                    },
                )
            }
            // In summary mode the active tab's single chip carries the tab id; match it
            // so it highlights. Otherwise highlight the focused split pane.
            val focusedPaneId = if (summaryMode) tabController.activeTabId
                                else tabController.activeTab?.let { splitStates[it.id]?.focusedPaneId }
            TabBar(
                groups = tabGroups,
                remoteGroups = remoteGroups,
                activeTabIndex = tabController.activeTabIndex,
                focusedPaneId = focusedPaneId,
                onPaneSelected = { tabIndex, paneId ->
                    tabController.switchToTab(tabIndex)
                    tabController.tabs.getOrNull(tabIndex)?.let { t -> splitStates[t.id]?.setFocusedPane(paneId) }
                },
                onPaneClosed = { tabIndex, paneId ->
                    val t = tabController.tabs.getOrNull(tabIndex)
                    if (t != null) {
                        val session = rm?.sessionForTab(t)
                        if (session != null) {
                            // Remote: structure is host-driven. Route the close to the host
                            // (control only) — the resulting Layout removes it locally. Never
                            // dispose a mirror locally (the next Layout would resurrect it).
                            session.closeFromChip(t.id, paneId)
                        } else {
                            val st = splitStates[t.id]
                            // paneId == t.id is a synthetic tab-level chip (summary / single
                            // pane) — close the whole tab. Real split panes close just the pane.
                            if (st == null || st.isSinglePane || paneId == t.id) tabController.closeTab(tabIndex)
                            else st.closePane(paneId)
                        }
                    }
                },
                onNewTab = {
                    // New tabs always start in home directory (no working dir inheritance)
                    // Use initial command from settings if configured
                    tabController.createTab(initialCommand = settings.initialCommand.ifEmpty { null })
                },
                onTabMoveToNewWindow = { index ->
                    val tab = tabController.tabs.getOrNull(index) ?: return@TabBar
                    val splitState = splitStates.remove(tab.id)
                    val extractedTab = tabController.extractTab(index) ?: return@TabBar
                    WindowManager.createWindowWithTab(extractedTab, splitState)
                },
                onRename = { tabIndex, paneId, newTitle ->
                    val t = tabController.tabs.getOrNull(tabIndex)
                    val remote = t?.let { rm?.sessionForTab(it) }
                    if (remote != null && t != null) {
                        // Remote chip: route to the host; the resulting Layout reflects the new
                        // title locally (don't mutate the mirror's title directly).
                        remote.renameTab(t.id, paneId, newTitle.trim())
                    } else sessionFor(tabIndex, paneId)?.let { session ->
                        val trimmed = newTitle.trim()
                        if (trimmed.isBlank()) {
                            // Clear the custom title; wireCwdTitle reverts it to the cwd.
                            session.customTitle.value = null
                        } else {
                            session.customTitle.value = trimmed
                            session.title.value = trimmed
                        }
                    }
                },
                onSetColor = { tabIndex, paneId, hex ->
                    val t = tabController.tabs.getOrNull(tabIndex)
                    val remote = t?.let { rm?.sessionForTab(it) }
                    if (remote != null && t != null) remote.setTabColor(t.id, paneId, hex)
                    else sessionFor(tabIndex, paneId)?.let { it.tabColor.value = hex }
                },
                onCloseOthers = { index ->
                    val t = tabController.tabs.getOrNull(index)
                    val remote = t?.let { rm?.sessionForTab(it) }
                    if (remote != null && t != null) remote.closeOtherTabs(t.id)
                    else tabController.closeOtherTabs(index)
                },
                onCloseBelow = { index ->
                    val t = tabController.tabs.getOrNull(index)
                    val remote = t?.let { rm?.sessionForTab(it) }
                    if (remote != null && t != null) remote.closeTabsBelow(t.id)
                    else tabController.closeTabsBelow(index)
                },
                onDuplicate = { index ->
                    val t = tabController.tabs.getOrNull(index)
                    val remote = t?.let { rm?.sessionForTab(it) }
                    if (remote != null && t != null) remote.duplicateTab(t.id)
                    else {
                        val wd = t?.workingDirectory?.value
                        tabController.createTab(workingDir = wd)
                    }
                },
                onShareTab = { index ->
                    tabController.tabs.getOrNull(index)?.let { startShare(it.id, ai.rever.bossterm.compose.share.ShareScope.TAB) }
                },
                onShareWindow = { index ->
                    tabController.tabs.getOrNull(index)?.let { startShare(it.id, ai.rever.bossterm.compose.share.ShareScope.WINDOW) }
                },
                onShareAll = { index ->
                    tabController.tabs.getOrNull(index)?.let { startShare(it.id, ai.rever.bossterm.compose.share.ShareScope.ALL) }
                },
                onSignIn = if (signInVisible) openSignIn else null,
                signInLabel = signInLabel,
                onStopShare = { index ->
                    tabController.tabs.getOrNull(index)?.let {
                        ai.rever.bossterm.compose.share.SessionShareManager.unshare(it.id)
                    }
                },
                isSharing = { index -> tabController.tabs.getOrNull(index)?.id in sharedTabIds },
                onSplitVertical = { splitActiveTab(SplitOrientation.VERTICAL) },
                onSplitHorizontal = { splitActiveTab(SplitOrientation.HORIZONTAL) },
                onSettings = onShowSettings,
                onAddRemote = { showAddRemote = true },
                orientation = if (tabBarOnLeft) ai.rever.bossterm.compose.tabs.TabBarOrientation.LEFT
                              else ai.rever.bossterm.compose.tabs.TabBarOrientation.TOP,
                verticalWidth = settings.tabBarVerticalWidth.dp
            )
            }
        }

        val mainContent: @Composable () -> Unit = {
        // Render active terminal tab with split support
        if (tabController.tabs.isNotEmpty()) {
            val activeTab = tabController.tabs[tabController.activeTabIndex]
            val splitState = getOrCreateSplitState(activeTab)

            // Update window title when active tab's title changes
            LaunchedEffect(activeTab) {
                activeTab.display.windowTitleFlow.collect { newTitle ->
                    if (newTitle.isNotEmpty()) {
                        onWindowTitleChange(newTitle)
                    }
                }
            }

            // Split operation handlers
            val onSplitHorizontal: () -> Unit = {
                // Only inherit working directory if setting is enabled
                val workingDir = if (settings.splitInheritWorkingDirectory) {
                    val session = splitState.getFocusedSession()
                    // First try OSC 7 tracked directory, then fall back to querying process
                    session?.workingDirectory?.value
                        ?: session?.processHandle?.value?.getWorkingDirectory()
                } else null
                var newSessionRef: TerminalSession? = null
                val newSession = tabController.createSessionForSplit(
                    workingDir = workingDir,
                    onProcessExit = {
                        // Auto-close the pane when process exits
                        if (splitState.isSinglePane) {
                            // Last pane - close the tab
                            val tabIndex = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                            if (tabIndex != -1) {
                                tabController.closeTab(tabIndex)
                            }
                        } else {
                            // Close just this pane
                            newSessionRef?.let { session ->
                                splitState.getAllPanes()
                                    .find { it.session === session }
                                    ?.let { pane -> splitState.closePane(pane.id) }
                            }
                        }
                    }
                )
                newSessionRef = newSession
                splitState.splitFocusedPane(SplitOrientation.HORIZONTAL, newSession, settings.splitDefaultRatio)
            }

            val onSplitVertical: () -> Unit = {
                // Only inherit working directory if setting is enabled
                val workingDir = if (settings.splitInheritWorkingDirectory) {
                    val session = splitState.getFocusedSession()
                    // First try OSC 7 tracked directory, then fall back to querying process
                    session?.workingDirectory?.value
                        ?: session?.processHandle?.value?.getWorkingDirectory()
                } else null
                var newSessionRef: TerminalSession? = null
                val newSession = tabController.createSessionForSplit(
                    workingDir = workingDir,
                    onProcessExit = {
                        // Auto-close the pane when process exits
                        if (splitState.isSinglePane) {
                            // Last pane - close the tab
                            val tabIndex = tabController.tabs.indexOfFirst { it.id == activeTab.id }
                            if (tabIndex != -1) {
                                tabController.closeTab(tabIndex)
                            }
                        } else {
                            // Close just this pane
                            newSessionRef?.let { session ->
                                splitState.getAllPanes()
                                    .find { it.session === session }
                                    ?.let { pane -> splitState.closePane(pane.id) }
                            }
                        }
                    }
                )
                newSessionRef = newSession
                splitState.splitFocusedPane(SplitOrientation.VERTICAL, newSession, settings.splitDefaultRatio)
            }

            val onClosePane: () -> Unit = {
                if (splitState.isSinglePane) {
                    // Last pane - close the tab
                    tabController.closeTab(tabController.activeTabIndex)
                } else {
                    // Close just this pane
                    splitState.closeFocusedPane()
                }
            }

            // For a mirrored remote tab, the pane-body (shell) right-click menu's structural
            // actions must target the HOST — the local split tree is rebuilt from the host's
            // Layout, so a local split/close would be wiped on the next reconcile. This mirrors
            // the browser viewer's pane menu. Normal tabs keep the local handlers.
            val remoteForActive = state?.remoteSessions?.sessionForTab(activeTab)
            val paneSplitVertical: () -> Unit =
                if (remoteForActive != null) { { remoteForActive.splitPane(activeTab.id, splitState.focusedPaneId, horizontal = false) } }
                else onSplitVertical
            val paneSplitHorizontal: () -> Unit =
                if (remoteForActive != null) { { remoteForActive.splitPane(activeTab.id, splitState.focusedPaneId, horizontal = true) } }
                else onSplitHorizontal
            val paneClose: () -> Unit =
                if (remoteForActive != null) { { remoteForActive.closeFromChip(activeTab.id, splitState.focusedPaneId) } }
                else onClosePane

            val onNavigatePane: (NavigationDirection) -> Unit = { direction ->
                splitState.navigateFocus(direction)
            }

            SplitContainer(
                splitState = splitState,
                sharedFont = sharedFont,
                isActiveTab = isActive,
                onTabTitleChange = { newTitle ->
                    // A user rename (customTitle) always wins; otherwise track the
                    // app's OSC title. Matches the background-tab collector in
                    // TabController.wireCwdTitle so active and background tabs behave alike.
                    if (activeTab.customTitle.value == null) {
                        activeTab.title.value = newTitle
                    }
                },
                onNewTab = {
                    // New tabs always start in home directory (no working dir inheritance)
                    // Use initial command from settings if configured
                    tabController.createTab(initialCommand = settings.initialCommand.ifEmpty { null })
                },
                onSwitchShell = { shell ->
                    // Windows: switch to different shell (close current, open new with selected shell)
                    val currentIndex = tabController.activeTabIndex
                    tabController.createTab(command = shell)
                    tabController.closeTab(currentIndex)
                },
                onCloseTab = {
                    tabController.closeTab(tabController.activeTabIndex)
                },
                onNextTab = {
                    tabController.nextTab()
                },
                onPreviousTab = {
                    tabController.previousTab()
                },
                onSwitchToTab = { index ->
                    if (index in tabController.tabs.indices) {
                        tabController.switchToTab(index)
                    }
                },
                onNewWindow = onNewWindow,
                onShowSettings = onShowSettings,
                onShowWelcomeWizard = onShowWelcomeWizard,
                onSplitHorizontal = paneSplitHorizontal,
                onSplitVertical = paneSplitVertical,
                onClosePane = paneClose,
                onNavigatePane = onNavigatePane,
                onNavigateNextPane = { splitState.navigateToNextPane() },
                onNavigatePreviousPane = { splitState.navigateToPreviousPane() },
                // A remote mirror pane can't be moved into a local tab (it has no local PTY).
                onMoveToNewTab = if (!splitState.isSinglePane && remoteForActive == null) {
                    {
                        // Extract the session from the split and move it to a new tab
                        val extractedSession = splitState.extractFocusedPaneSession()
                        if (extractedSession != null) {
                            // Check if we extracted the original tab's session
                            // This happens when the user moves the first pane (which is the tab itself)
                            if (extractedSession.id == activeTab.id) {
                                // The remaining session should take the original tab's position
                                // and the extracted original tab goes to a new tab
                                val remainingSession = splitState.getFocusedSession()
                                if (remainingSession != null) {
                                    // Remove the old split state (it's now invalid)
                                    splitStates.remove(activeTab.id)

                                    // Replace the tab at current position with the remaining session
                                    tabController.replaceTabAtIndex(tabController.activeTabIndex, remainingSession)

                                    // Add the extracted original tab as a new tab
                                    tabController.createTabFromExistingSession(extractedSession)
                                }
                            } else {
                                // Normal case: extracted a split session (not the original tab)
                                tabController.createTabFromExistingSession(extractedSession)
                            }
                        }
                    }
                } else null,  // Don't show option if only one pane (nothing to move)
                menuActions = menuActions,
                // Split pane settings
                splitFocusBorderEnabled = settings.splitFocusBorderEnabled,
                splitFocusBorderColor = settings.splitFocusBorderColorValue,
                splitMinimumSize = settings.splitMinimumSize,
                // Wrap onLinkClick to handle SSH URLs by opening new tab with SSH connection
                onLinkClick = { info ->
                    // Check if this is an SSH URL
                    if (info.scheme == "ssh" || info.patternId == "builtin:ssh") {
                        val sshInfo = HyperlinkDetector.parseSshConnection(info.url)
                        if (sshInfo != null) {
                            // Open new tab with SSH command
                            tabController.createTab(initialCommand = sshInfo.toCommand())
                            true // Handled
                        } else {
                            // Parse failed, delegate to user callback or default
                            onLinkClick?.invoke(info) ?: false
                        }
                    } else {
                        // Not SSH, delegate to user callback or default
                        onLinkClick?.invoke(info) ?: false
                    }
                },
                customContextMenuItems = contextMenuItems,
                // Combine user-provided items with AI assistant and VCS items
                customContextMenuItemsProvider = {
                    val userItems = contextMenuItemsProvider?.invoke() ?: contextMenuItems
                    var items = userItems

                    if (remoteForActive != null) {
                        // Remote (mirrored) pane: show the viewer's pane menu — two fit actions plus
                        // a host-routed AI Assistant submenu (runs the host's configured command,
                        // honoring its YOLO/auto-mode), launched in the focused pane. Copy/Paste/
                        // Find/Split/Close come from the base menu; local-host items (MCP attach,
                        // Share, VCS, shell customization) don't apply to a mirror, so they're omitted.
                        val focusedRemote = splitState.getFocusedSession() as? ai.rever.bossterm.compose.tabs.TerminalTab
                        // View-only: the host ignores mutating actions, so keep the menu lean —
                        // the upgrade path instead of host-routed items (the base menu's Copy/
                        // Paste/Find stay useful; its Split/Close no-op until control is granted).
                        if (!remoteForActive.canControl) {
                            items = items + ContextMenuItem(
                                id = "remote_request_control", label = "Request Control",
                                action = { remoteForActive.requestControl() }
                            )
                        }
                        // Fit host to my screen: resize the REMOTE so its grid matches this pane
                        // (like the viewer's top-bar button). Mutates the host → control only.
                        if (focusedRemote != null && remoteForActive.canControl) {
                            items = items + ContextMenuItem(
                                id = "remote_fit_host", label = "Fit host to my screen",
                                action = {
                                    if (focusedRemote.remoteFitCols >= 2 && focusedRemote.remoteFitRows >= 2)
                                        remoteForActive.resizeHost(activeTab.id, focusedRemote.remoteFitCols, focusedRemote.remoteFitRows)
                                }
                            )
                        }
                        // Fit my window to host: resize THIS window so the remote's grid renders
                        // 1:1 here — the reverse direction, and purely local (no control needed).
                        if (focusedRemote != null) {
                            items = items + ContextMenuItem(
                                id = "remote_fit_client", label = "Fit my window to host",
                                action = { fitClientWindowToHost(focusedRemote) }
                            )
                        }
                        if (remoteForActive.canControl) {
                            items = items + ContextMenuSubmenu(
                                id = "remote_ai_assistants",
                                label = "AI Assistant",
                                items = listOf(
                                    "claude-code" to "Claude Code",
                                    "codex" to "Codex",
                                    "gemini-cli" to "Gemini CLI",
                                    "opencode" to "OpenCode",
                                ).map { (aid, label) ->
                                    ContextMenuItem(
                                        id = "remote_ai_$aid", label = label,
                                        action = { remoteForActive.launchAI(activeTab.id, splitState.focusedPaneId, aid) }
                                    )
                                }
                            )
                        }
                    } else {

                    // Add AI assistant menu items
                    if (settings.aiAssistantsEnabled) {
                        val terminalWriter: (String) -> Unit = { text ->
                            splitState.getFocusedSession()?.writeUserInput(text)
                        }
                        val aiItems = aiState.menuProvider.getMenuItems(
                            terminalWriter = terminalWriter,
                            onInstallRequest = { assistant, command, npmCommand ->
                                installDialogState = AIInstallDialogParams(assistant, command, npmCommand, terminalWriter)
                            },
                            configs = settings.aiAssistantConfigs,
                            statusOverride = detectionResultsHolder.get()
                        )
                        items = items + aiItems
                    }

                    // MCP attach submenu — sits directly under the AI Assistants
                    // group so users find it near related tooling. Only rendered
                    // when the Ktor server is actually bound. Each entry fires
                    // the shared fireMcpAttach so the AttachToast surfaces the
                    // result in the same place the indicator's right-click does.
                    // Already-attached CLIs get a "✓ " prefix in their label so
                    // the user can see at a glance what's wired up.
                    if (McpTerminalRegistry.runningPort.value != null) {
                        val attached = McpTerminalRegistry.attachedTargets.value
                        val mcpAttachItems: List<ContextMenuElement> = listOf(
                            ContextMenuSubmenu(
                                id = "mcp_attach_submenu",
                                label = "Attach BossTerm MCP to…",
                                items = McpAttachTarget.entries.map { target ->
                                    val prefix = if (target in attached) "✓ " else ""
                                    ContextMenuItem(
                                        id = "mcp_attach_${target.name}",
                                        label = "$prefix${target.displayName}",
                                        action = { fireMcpAttach(target) }
                                    )
                                }
                            )
                        )
                        items = items + mcpAttachItems
                    }

                    // Session sharing (issue #276): start/stop sharing from the terminal
                    // right-click menu — this tab (incl. splits) or the whole window.
                    tabController.activeTab?.let { activeTab ->
                        val sharing = ai.rever.bossterm.compose.share.SessionShareManager.isSharing(activeTab.id)
                        items = if (sharing) {
                            items + ContextMenuItem(
                                id = "stop_share",
                                label = "Stop Sharing",
                                action = { ai.rever.bossterm.compose.share.SessionShareManager.unshare(activeTab.id) }
                            )
                        } else {
                            items + ContextMenuSubmenu(
                                id = "share_submenu",
                                label = "Share",
                                items = listOf(
                                    ContextMenuItem(
                                        id = "share_tab",
                                        label = "Tab…",
                                        action = { startShare(activeTab.id, ai.rever.bossterm.compose.share.ShareScope.TAB) }
                                    ),
                                    ContextMenuItem(
                                        id = "share_window",
                                        label = "Window…",
                                        action = { startShare(activeTab.id, ai.rever.bossterm.compose.share.ShareScope.WINDOW) }
                                    ),
                                    ContextMenuItem(
                                        id = "share_all",
                                        label = "All Windows…",
                                        action = { startShare(activeTab.id, ai.rever.bossterm.compose.share.ShareScope.ALL) }
                                    )
                                ) + (if (signInVisible) listOf(
                                    ContextMenuItem(
                                        id = "sign_in",
                                        label = signInLabel,
                                        action = openSignIn
                                    )
                                ) else emptyList())
                            )
                        }
                    }

                    // Add Version Control menu items
                    val terminalWriter: (String) -> Unit = { text ->
                        splitState.getFocusedSession()?.writeUserInput(text)
                    }
                    val vcsItems = vcsMenuProvider.getMenuItems(
                        terminalWriter = terminalWriter,
                        onInstallRequest = { toolId, command, npmCommand ->
                            // Find the tool definition and show install dialog
                            val tool = AIAssistants.findById(toolId)
                            if (tool != null) {
                                installDialogState = AIInstallDialogParams(tool, command, npmCommand, terminalWriter)
                            }
                        },
                        statusOverride = vcsStatusHolder.get()
                    )
                    items = items + vcsItems

                    // Add Shell Customization menu items (Starship, etc.)
                    val shellItems = shellMenuProvider.getMenuItems(
                        terminalWriter = terminalWriter,
                        onInstallRequest = { toolId, command, npmCommand ->
                            // Handle both install and uninstall (e.g., "starship-uninstall" -> "starship")
                            val baseToolId = toolId.removeSuffix("-uninstall")
                            val tool = AIAssistants.findById(baseToolId)
                            if (tool != null) {
                                installDialogState = AIInstallDialogParams(tool, command, npmCommand, terminalWriter)
                            }
                        },
                        statusOverride = shellStatusHolder.get(),
                        onSwitchShell = { shell ->
                            // Windows: switch to different shell
                            val currentIndex = tabController.activeTabIndex
                            tabController.createTab(command = shell)
                            tabController.closeTab(currentIndex)
                        }
                    )
                    items = items + shellItems
                    } // end non-remote (local host) menu items

                    items
                },
                onContextMenuOpen = onContextMenuOpen,
                // Combine user async callback with AI detection and VCS status refresh
                onContextMenuOpenAsync = {
                    // Run user callback first if provided
                    onContextMenuOpenAsync?.invoke()
                    // Refresh AI assistant detection before showing menu
                    // Store results in shared holder for immediate access by customContextMenuItemsProvider
                    if (settings.aiAssistantsEnabled) {
                        val freshStatus = aiState.detector.detectAll()
                        detectionResultsHolder.set(freshStatus)
                    }
                    // Refresh VCS status with current working directory
                    // Try OSC 7 tracked directory first, fallback to reading from process
                    val session = splitState.getFocusedSession()
                    val cwd = session?.workingDirectory?.value
                        ?: session?.processHandle?.value?.getWorkingDirectory()
                    vcsMenuProvider.refreshStatus(cwd)
                    vcsStatusHolder.set(vcsMenuProvider.getStatus())
                    // Refresh Shell Customization status
                    shellMenuProvider.refreshStatus()
                    shellStatusHolder.set(mapOf(
                        "starship" to (shellMenuProvider.getStatus() ?: false),
                        "oh-my-zsh" to (shellMenuProvider.getOhMyZshStatus() ?: false),
                        "prezto" to (shellMenuProvider.getPreztoStatus() ?: false),
                        "zsh" to (shellMenuProvider.getZshStatus() ?: false),
                        "bash" to (shellMenuProvider.getBashStatus() ?: false),
                        "fish" to (shellMenuProvider.getFishStatus() ?: false)
                    ))
                },
                hyperlinkRegistry = hyperlinkRegistry,
                modifier = Modifier.fillMaxSize()
            )
        }
        }

        if (tabBarOnLeft) {
            Row(modifier = Modifier.fillMaxSize()) {
                tabBarComposable()
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { mainContent() }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                tabBarComposable()
                mainContent()
            }
        }

        // Git branch indicator (Phase 7) — active tab's branch, bottom-right
        // (clearing the scrollbar on the right edge).
        if (settings.showGitBranchIndicator) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        bottom = 6.dp,
                        end = (if (settings.showScrollbar) settings.scrollbarWidth.dp else 0.dp) + 10.dp
                    )
            ) {
                ai.rever.bossterm.compose.vcs.GitBranchIndicator(
                    cwd = tabController.activeTab?.workingDirectory?.value,
                    enabled = true
                )
            }
        }

        // Semi-transparent overlay when window loses focus
        if (!isWindowFocusedState && settings.showUnfocusedOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.15f))
            )
        }

        // MCP status indicator + toast overlay. Top-right slot.
        //   - Indicator pill renders whenever the user has not hidden it
        //     (`mcpShowStatusIndicator`). When MCP is bound it shows green
        //     "MCP on"; when off, red "MCP off". The right-click menu adapts.
        //   - Toast renders whenever attachStatus is non-null. Attaches
        //     can't happen when MCP is off, so toast naturally stays
        //     dormant in that state.
        // Combined inline status strip: "● MCP | ● Sharing" (issue #276). Each segment
        // is color-coded (green = on/active, gray = off/idle) and clickable: MCP →
        // BossTerm MCP settings; Sharing → start sharing the active tab (or reopen its
        // QR/links dialog if already shared). Shown per its own toggle.
        val showMcpStatus = settings.mcpShowStatusIndicator
        val showSharingStatus = settings.sessionSharingShowIndicator
        // The active tab's remote session while it's still view-only — drives the read-only
        // pill, stacked in this same column so it sits BELOW the MCP/Sharing pills.
        val activeRemoteSession = tabController.activeTab?.let { t -> state?.remoteSessions?.sessionForTab(t) }
        val viewOnlyRemote = activeRemoteSession?.takeIf { !it.canControlState.value }
        // Even with control of the host, the active tab may mirror an upstream the HOST itself
        // can't type into (A shared view-only to B, B shared to us) — input dies at the host.
        // Informational pill only: control must be requested by the host from the origin.
        val upstreamReadOnly = if (viewOnlyRemote == null) {
            activeRemoteSession?.let { s ->
                s.upstreamRev.value // subscribe: re-evaluate when upstream info changes
                tabController.activeTab?.id?.let { id -> s.upstreamFor(id)?.takeIf { it.readOnly } }
            }
        } else null
        if (showMcpStatus || showSharingStatus || attachStatus != null || pendingShareRequests.isNotEmpty() ||
            viewOnlyRemote != null || upstreamReadOnly != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Drop below the tab bar only when it's at the top (so the strip /
                    // attach toast don't overlap the tab "+" button). A left/vertical
                    // tab bar doesn't occupy the top, so stay near the top edge.
                    .padding(
                        top = if (tabBarVisible && !tabBarOnLeft) TabBarHeight + 4.dp else 4.dp,
                        end = 8.dp
                    ),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ai.rever.bossterm.compose.share.StatusStrip(
                    showMcp = showMcpStatus,
                    mcpOn = mcpRunningPort != null,
                    onMcpClick = {
                        // Same context menu the standalone MCP pill showed: Attach ▸,
                        // <server> MCP Settings…, Turn on/off.
                        mcpMenu.showMenu(0f, 0f, ai.rever.bossterm.compose.mcp.buildIndicatorMenuItems(
                            attached = McpTerminalRegistry.attachedTargets.value,
                            isRunning = mcpRunningPort != null,
                            isUserEnabled = settings.mcpEnabled,
                            serverLabel = mcpServerLabel,
                            onAttachRequest = fireMcpAttach,
                            onShowSettings = onShowMcpSettings,
                            onTurnOffRequest = { SettingsManager.instance.updateSetting { copy(mcpEnabled = false) } },
                            onTurnOnRequest = { SettingsManager.instance.updateSetting { copy(mcpEnabled = true) } },
                        ))
                    },
                    showSharing = showSharingStatus,
                    sharingCount = sharedTabIds.size,
                    onSharingClick = {
                        // Reopen the dialog if something is shared; else offer Tab vs Window.
                        val sharedId = sharedTabIds.firstOrNull { tabController.tabs.any { t -> t.id == it } }
                            ?: sharedTabIds.firstOrNull()
                        if (sharedId != null) {
                            openShareWindow(ai.rever.bossterm.compose.share.SessionShareManager.infoFor(sharedId))
                        } else {
                            tabController.activeTab?.let { active ->
                                shareScopeMenu.showMenu(0f, 0f, listOf(
                                    ai.rever.bossterm.compose.features.ContextMenuController.MenuItem(
                                        id = "share_this_tab", label = "Share This Tab", enabled = true,
                                        action = { startShare(active.id, ai.rever.bossterm.compose.share.ShareScope.TAB) }
                                    ),
                                    ai.rever.bossterm.compose.features.ContextMenuController.MenuItem(
                                        id = "share_window", label = "Share Whole Window", enabled = true,
                                        action = { startShare(active.id, ai.rever.bossterm.compose.share.ShareScope.WINDOW) }
                                    ),
                                    ai.rever.bossterm.compose.features.ContextMenuController.MenuItem(
                                        id = "share_all", label = "Share All Windows", enabled = true,
                                        action = { startShare(active.id, ai.rever.bossterm.compose.share.ShareScope.ALL) }
                                    ),
                                ) + (if (signInVisible) listOf(
                                    ai.rever.bossterm.compose.features.ContextMenuController.MenuItem(
                                        id = "sign_in", label = signInLabel, enabled = true,
                                        action = openSignIn
                                    )
                                ) else emptyList()))
                            }
                        }
                    },
                )
                attachStatus?.let { status ->
                    AttachToast(status = status)
                }
                // Account sign-in confirmation (auto-dismisses after a few seconds).
                signInToast?.let { email ->
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(Color(0xE6252526))
                            .border(1.dp, Color(0xFF404040), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    ) {
                        androidx.compose.material3.Text(
                            "Signed in as $email",
                            color = Color(0xFF81C784),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                // Approval prompts (issue #276): one banner per waiting device.
                pendingShareRequests.forEach { req ->
                    ai.rever.bossterm.compose.share.ShareRequestToast(
                        request = req,
                        onApprove = { ai.rever.bossterm.compose.share.SessionShareManager.approveRequest(req.id) },
                        onDeny = { ai.rever.bossterm.compose.share.SessionShareManager.denyRequest(req.id) },
                    )
                }
                // Read-only indicator: the active tab mirrors a remote we can't type into.
                // Clicking requests control; vanishes the moment the grant arrives.
                viewOnlyRemote?.let { session ->
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(Color(0xE6252526))
                            .border(1.dp, Color(0xFF404040), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickable { session.requestControl() }
                    ) {
                        androidx.compose.material3.Text(
                            "View only — click to request control",
                            color = Color(0xFFB0B0B0),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                // Upstream read-only (A→B→C): we may control the host, but the host itself is
                // view-only on this tab's origin, so typing still can't land. Clicking relays a
                // control request through the host to the origin (its user sees the approval).
                upstreamReadOnly?.let { up ->
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(Color(0xE6252526))
                            .border(1.dp, Color(0xFF404040), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickable {
                                tabController.activeTab?.id?.let { id -> activeRemoteSession?.requestControlFor(id) }
                            }
                    ) {
                        androidx.compose.material3.Text(
                            "View only — click to request control of ${up.name ?: "the origin"}",
                            color = Color(0xFFB0B0B0),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    // Account sign-in window — a real top-level OS window like the share window.
    if (showSignInWindow) {
        ai.rever.bossterm.compose.auth.SignInWindow(
            onDismiss = { showSignInWindow = false },
            focusTick = signInFocusTick,
        )
    }

    // Session sharing window (issue #276): a real top-level OS window (like Settings)
    // with the QR + links + Stop, rather than an in-canvas Compose dialog.
    shareDialog?.let { info ->
        ai.rever.bossterm.compose.share.ShareWindow(
            info = info,
            onDismiss = { shareDialog = null },
            onStop = {
                ai.rever.bossterm.compose.share.SessionShareManager.unshare(info.tabId)
                shareDialog = null
            },
            onScopeChange = { scope ->
                // Re-scope in place (same tokens/links/viewers) and refresh the dialog.
                ai.rever.bossterm.compose.share.SessionShareManager.reshare(info.tabId, scope)?.let { shareDialog = it }
            },
            focusTick = shareFocusTick,
            pendingRequests = pendingShareRequests,
            onApproveRequest = { ai.rever.bossterm.compose.share.SessionShareManager.approveRequest(it) },
            onDenyRequest = { ai.rever.bossterm.compose.share.SessionShareManager.denyRequest(it) },
            tailscaleMode = settings.shareTailscaleMode,
            onTailscaleModeChange = { SettingsManager.instance.updateSetting { copy(shareTailscaleMode = it) } },
            onRefreshLink = { ai.rever.bossterm.compose.share.SessionShareManager.refreshRemoteLink() },
            sessionName = ai.rever.bossterm.compose.share.SessionShareManager.sessionNameFor(info.tabId) ?: "",
            onSessionNameChange = { ai.rever.bossterm.compose.share.SessionShareManager.setSessionName(info.tabId, it) },
        )
    }

    // Confirm-before-requesting-control (a view-only group's split/new-tab click).
    requestControlPrompt?.let { send ->
        ai.rever.bossterm.compose.remote.RequestControlPrompt(
            onConfirm = { send(); requestControlPrompt = null },
            onDismiss = { requestControlPrompt = null },
        )
    }

    // First-view size reconcile for a remote mirror (set by the activeTabId effect above).
    // The actions re-resolve the focused pane + its measurements AT CLICK TIME — exactly
    // what the right-click menu items do — rather than using the values captured when the
    // prompt was scheduled (the canvas may still have been settling then).
    remoteFitPrompt?.let { (session, container, _) ->
        ai.rever.bossterm.compose.remote.RemoteFitPrompt(
            hostName = session.customName.value ?: session.hostName.value
                ?: runCatching { java.net.URI(session.link).host }.getOrNull() ?: "the host",
            onFitMyWindow = {
                remoteFitPrompt = null
                (splitStates[container.id]?.getFocusedSession() as? ai.rever.bossterm.compose.tabs.TerminalTab)
                    ?.let { fitClientWindowToHost(it) }
            },
            onFitHost = {
                remoteFitPrompt = null
                if (session.canControlState.value) {
                    val p = splitStates[container.id]?.getFocusedSession() as? ai.rever.bossterm.compose.tabs.TerminalTab
                    if (p != null && p.remoteFitCols >= 2 && p.remoteFitRows >= 2)
                        session.resizeHost(container.id, p.remoteFitCols, p.remoteFitRows)
                } else {
                    // View-only: resizing the host needs control — confirm + request it.
                    requestControlPrompt = { session.requestControl() }
                }
            },
            onDismiss = { remoteFitPrompt = null },
        )
    }

    // Typing into a read-only mirror (no control, or read-only via an upstream host) surfaces
    // the same request-control prompt; dismissing snoozes it so it doesn't nag per keystroke.
    state?.remoteSessions?.let { mgr ->
        mgr.blockedInput.value?.let { blocked ->
            ai.rever.bossterm.compose.remote.RequestControlPrompt(
                onConfirm = {
                    if (blocked.tabId != null) blocked.session.requestControlFor(blocked.tabId)
                    else blocked.session.requestControl()
                    mgr.blockedInput.value = null
                },
                onDismiss = {
                    mgr.snoozeBlockedInputPrompt()
                    mgr.blockedInput.value = null
                },
            )
        }
    }

    // Disconnect → reconnect dialog (like the web viewer's overlay): prompt for the first
    // remote session that lost its connection and hasn't had this failure dismissed.
    state?.remoteSessions?.let { mgr ->
        mgr.sessions.firstOrNull {
            it.statusState.value is ai.rever.bossterm.compose.remote.RemoteStatus.Failed && !it.failureDismissed.value
        }?.let { failed ->
            ai.rever.bossterm.compose.remote.RemoteDisconnectedDialog(
                name = failed.customName.value ?: failed.hostName.value
                    ?: runCatching { java.net.URI(failed.link).host ?: failed.link }.getOrDefault(failed.link),
                message = (failed.statusState.value as? ai.rever.bossterm.compose.remote.RemoteStatus.Failed)?.message,
                onReconnect = { failed.reconnect() },
                onDisconnect = { mgr.disconnect(failed) },
                onDismiss = { failed.failureDismissed.value = true },
            )
        }
    }

    // "Add remote": connect to another BossTerm's shared session and mirror its tabs here.
    // Requires an external TabbedTerminalState (it owns the RemoteSessionManager + tab list).
    if (showAddRemote) {
        if (state != null) {
            ai.rever.bossterm.compose.remote.AddRemoteDialog(
                manager = state.remoteSessions,
                onDismiss = { showAddRemote = false },
            )
        } else {
            showAddRemote = false
        }
    }

    // AI Assistant Installation Wizard (command interception, context menu, and programmatic API)
    val coroutineScope = rememberCoroutineScope()

    // Tool installation wizard (replaces confirmation dialog + install dialog)
    ToolInstallWizardHost(
        params = toolWizardParams,
        onDismiss = {
            // Clear line on dismiss (user cancelled)
            toolWizardParams?.clearLine?.invoke()
            toolWizardParams = null
            // Refresh detection when wizard closes
            coroutineScope.launch {
                aiState.detector.detectAll()
            }
        },
        onComplete = { success ->
            // clearLine is called inside ToolInstallWizard on success
            toolWizardParams = null
            // Refresh detection after installation
            coroutineScope.launch {
                aiState.detector.detectAll()
            }
        }
    )

    // Legacy context menu installs (uses AIInstallDialogHost for backward compatibility)
    AIInstallDialogHost(
        params = installDialogState,
        coroutineScope = coroutineScope,
        detector = aiState.detector,
        onDismiss = { installDialogState = null }
    )

    // From programmatic API
    state?.let { s ->
        AIInstallDialogHost(
            params = s.aiInstallRequest,
            coroutineScope = coroutineScope,
            detector = aiState.detector,
            onDismiss = { s.cancelAIInstallation() }
        )
    }
}

/** One-shot Swing timer (EDT) — used to let layout settle between fit passes. */
private fun swingTimerOnce(delayMs: Int, action: () -> Unit) {
    val t = javax.swing.Timer(delayMs) { action() }
    t.isRepeats = false
    t.start()
}

/**
 * "Fit my window to host": resize THIS window so [focused]'s pane renders the remote's
 * current grid 1:1 — the reverse of "Fit host to my screen". Purely local, needs no control.
 *
 * Multi-pass, because a single nudge can land off-target:
 *  - pass 0 clears any previous font shrink so cell metrics re-measure at the user's font;
 *  - pass 1 applies the window delta (per-cell px × the grid delta — chrome cancels out);
 *  - when the host grid CAN'T fit this screen (smaller/differently-scaled monitor), the
 *    window grows to the screen limit and the MIRROR's font shrinks so the whole grid
 *    still renders (the native analogue of the web viewer's fit-screen);
 *  - passes 2-3 re-measure after layout settles and correct rounding / per-monitor-scale
 *    drift of a column or two.
 */
private fun fitClientWindowToHost(focused: ai.rever.bossterm.compose.tabs.TerminalTab, attempt: Int = 0) {
    if (attempt == 0 && focused.fontSizeOverride.value != null) {
        focused.fontSizeOverride.value = null
        swingTimerOnce(240) { fitClientWindowToHost(focused, attempt = 1) }
        return
    }
    val cw = focused.terminal.cellWidthPx
    val ch = focused.terminal.cellHeightPx
    if (cw <= 0f || ch <= 0f) return
    val grid = focused.display.termSize.value
    val hostCols = grid.columns; val hostRows = grid.rows
    val curCols = focused.remoteFitCols; val curRows = focused.remoteFitRows
    if (hostCols < 2 || hostRows < 2 || curCols < 2 || curRows < 2) return
    if (hostCols == curCols && hostRows == curRows) return // converged — 1:1
    val tw = WindowManager.windows.firstOrNull { it.isWindowFocused.value && it.awtWindow != null }
        ?: WindowManager.windows.firstOrNull { it.awtWindow != null } ?: return
    val win = tw.awtWindow ?: return
    javax.swing.SwingUtilities.invokeLater {
        runCatching {
            val gc = win.graphicsConfiguration
            val sx = gc?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
            val sy = gc?.defaultTransform?.scaleY?.takeIf { it > 0 } ?: 1.0
            val wantW = (win.width + (hostCols - curCols) * cw / sx).toInt()
            val wantH = (win.height + (hostRows - curRows) * ch / sy).toInt()
            var newW = wantW; var newH = wantH
            gc?.bounds?.let { b ->
                val maxW = (b.x + b.width - win.x).coerceAtLeast(480)
                val maxH = (b.y + b.height - win.y).coerceAtLeast(320)
                newW = newW.coerceIn(480, maxW); newH = newH.coerceIn(320, maxH)
            }
            if (newW != win.width || newH != win.height) {
                // Through Compose's WindowState when wired (frame + surface move
                // together — no unpainted strip), else setSize + heal nudge.
                ai.rever.bossterm.compose.share.MirrorShare.applyWindowSize(tw, win, newW, newH)
            }
            if (newW < wantW || newH < wantH) {
                // Screen-clamped: the host grid is too big for this monitor at the current
                // font. Shrink the mirror's font so the full grid fits the clamped window.
                val availCols = curCols + (newW - win.width) * sx / cw
                val availRows = curRows + (newH - win.height) * sy / ch
                val scale = minOf(availCols / hostCols, availRows / hostRows).toFloat()
                if (scale < 0.999f) {
                    val curFont = focused.fontSizeOverride.value
                        ?: SettingsManager.instance.settings.value.fontSize
                    focused.fontSizeOverride.value = (curFont * scale).coerceAtLeast(6f)
                }
            }
            // Verify after layout settles; correct residual drift (rounding, mixed-DPI).
            if (attempt < 3) swingTimerOnce(420) { fitClientWindowToHost(focused, attempt + 1) }
        }
    }
}

/**
 * Toggle + Attach▸ menu items for a connected remote's MCP (the "MCP" pill in its tab-group
 * header). Mirrors the local MCP indicator menu minus the native "Settings…". Control-gated:
 * when we're view-only on the remote, an action runs [onNeedControl] (the request-control
 * prompt) instead. [st.attached] are McpAttachTarget persistence keys (✓-marked).
 */
private fun remoteMcpMenuItems(
    s: ai.rever.bossterm.compose.remote.RemoteSession,
    st: ai.rever.bossterm.compose.remote.RemoteSession.RemoteMcpStatus,
    onNeedControl: () -> Unit,
): List<ai.rever.bossterm.compose.features.ContextMenuController.MenuElement> {
    val attached = st.attached.toSet()
    fun gate(act: () -> Unit) { if (s.canControlState.value) act() else onNeedControl() }
    val attachSub = ai.rever.bossterm.compose.mcp.McpAttachTarget.entries.map { t ->
        ai.rever.bossterm.compose.features.ContextMenuController.MenuItem(
            id = "rmcp_att_${t.persistenceKey}",
            label = (if (t.persistenceKey in attached) "✓ " else "") + t.displayName,
            // Attach is a no-op while the remote's MCP server is off — disable it (mirrors the
            // host's local indicator menu) so the click isn't a silent nothing.
            enabled = st.running,
            action = { gate { s.attachRemoteMcp(t.persistenceKey) } },
        )
    }
    return listOf(
        ai.rever.bossterm.compose.features.ContextMenuController.MenuSubmenu(
            id = "rmcp_attach", label = "Attach", items = attachSub
        ),
        ai.rever.bossterm.compose.features.ContextMenuController.MenuItem(
            id = "rmcp_toggle",
            label = if (st.enabled) "Turn MCP off" else "Turn MCP on",
            enabled = true,
            action = { gate { s.setRemoteMcpEnabled(!st.enabled) } },
        ),
    )
}
