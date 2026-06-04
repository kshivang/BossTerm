package ai.rever.bossterm.compose

import androidx.compose.foundation.background
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

            // Store reference in tab for ProperTerminal to access
            tab.aiCommandInterceptor = interceptor

            interceptorSetupTracker.add(tab.id)
        }

        // Clean up tracker for closed tabs
        val currentTabIds = tabController.tabs.map { it.id }.toSet()
        interceptorSetupTracker.removeAll { it !in currentTabIds }
    }

    // Cleanup split states when tabs are closed
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
    val sharedTabIds by ai.rever.bossterm.compose.share.SessionShareManager.sharedTabIds.collectAsState()
    // Start (or reopen) a share for a tab. TAB = this tab + its splits; WINDOW = all tabs.
    // First use auto-enables the feature; the server binds on demand.
    fun startShare(tabId: String, scope: ai.rever.bossterm.compose.share.ShareScope) {
        if (sharedTabIds.contains(tabId)) {
            shareDialog = ai.rever.bossterm.compose.share.SessionShareManager.infoFor(tabId)
            return
        }
        if (!settings.sessionSharingEnabled) {
            SettingsManager.instance.updateSetting { copy(sessionSharingEnabled = true) }
        }
        mcpScope.launch {
            val info = ai.rever.bossterm.compose.share.SessionShareManager.share(tabId, scope)
            if (info != null) shareDialog = info
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

            // Resolve the per-tab accent color: a manual color (Color ▸ menu) always
            // wins; otherwise, when color-by-directory is on, derive a stable accent
            // from the session's cwd. Reads MutableState so the bar recomposes live.
            fun colorHexFor(session: TerminalSession): String? {
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

            // Resolve the session a chip refers to: a split pane by id, or the tab
            // itself for the synthetic tab-level chip (summary mode / not-yet-split).
            fun sessionFor(tabIndex: Int, paneId: String): TerminalSession? {
                val tab = tabController.tabs.getOrNull(tabIndex) ?: return null
                return splitStates[tab.id]?.getAllPanes()?.firstOrNull { it.id == paneId }?.session ?: tab
            }

            // Each tab contributes a group of pane-chips (one chip per split pane).
            // In summary mode (or before a tab is split) a single chip represents the
            // whole tab, labeled with the tab's title.
            val tabGroups = tabController.tabs.mapIndexed { index, tab ->
                val st = splitStates[tab.id]
                val panes = if (st != null && !summaryMode) {
                    st.getAllPanes().map { p ->
                        ai.rever.bossterm.compose.tabs.TabBarPane(p.id, p.session.title.value, colorHexFor(p.session))
                    }
                } else {
                    listOf(ai.rever.bossterm.compose.tabs.TabBarPane(tab.id, tab.title.value, colorHexFor(tab)))
                }
                ai.rever.bossterm.compose.tabs.TabBarGroup(index, panes)
            }
            // In summary mode the active tab's single chip carries the tab id; match it
            // so it highlights. Otherwise highlight the focused split pane.
            val focusedPaneId = if (summaryMode) tabController.activeTabId
                                else tabController.activeTab?.let { splitStates[it.id]?.focusedPaneId }
            TabBar(
                groups = tabGroups,
                activeTabIndex = tabController.activeTabIndex,
                focusedPaneId = focusedPaneId,
                onPaneSelected = { tabIndex, paneId ->
                    tabController.switchToTab(tabIndex)
                    tabController.tabs.getOrNull(tabIndex)?.let { t -> splitStates[t.id]?.setFocusedPane(paneId) }
                },
                onPaneClosed = { tabIndex, paneId ->
                    val t = tabController.tabs.getOrNull(tabIndex)
                    if (t != null) {
                        val st = splitStates[t.id]
                        // paneId == t.id is a synthetic tab-level chip (summary / single
                        // pane) — close the whole tab. Real split panes close just the pane.
                        if (st == null || st.isSinglePane || paneId == t.id) tabController.closeTab(tabIndex)
                        else st.closePane(paneId)
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
                    sessionFor(tabIndex, paneId)?.let { session ->
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
                    sessionFor(tabIndex, paneId)?.let { it.tabColor.value = hex }
                },
                onCloseOthers = { tabController.closeOtherTabs(it) },
                onCloseBelow = { tabController.closeTabsBelow(it) },
                onDuplicate = { index ->
                    val wd = tabController.tabs.getOrNull(index)?.workingDirectory?.value
                    tabController.createTab(workingDir = wd)
                },
                onShareTab = { index ->
                    tabController.tabs.getOrNull(index)?.let { startShare(it.id, ai.rever.bossterm.compose.share.ShareScope.TAB) }
                },
                onShareWindow = { index ->
                    tabController.tabs.getOrNull(index)?.let { startShare(it.id, ai.rever.bossterm.compose.share.ShareScope.WINDOW) }
                },
                onStopShare = { index ->
                    tabController.tabs.getOrNull(index)?.let {
                        ai.rever.bossterm.compose.share.SessionShareManager.unshare(it.id)
                    }
                },
                isSharing = { index -> tabController.tabs.getOrNull(index)?.id in sharedTabIds },
                onSplitVertical = { splitActiveTab(SplitOrientation.VERTICAL) },
                onSplitHorizontal = { splitActiveTab(SplitOrientation.HORIZONTAL) },
                onSettings = onShowSettings,
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

            val onNavigatePane: (NavigationDirection) -> Unit = { direction ->
                splitState.navigateFocus(direction)
            }

            SplitContainer(
                splitState = splitState,
                sharedFont = sharedFont,
                isActiveTab = isActive,
                onTabTitleChange = { newTitle ->
                    activeTab.title.value = newTitle
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
                onSplitHorizontal = onSplitHorizontal,
                onSplitVertical = onSplitVertical,
                onClosePane = onClosePane,
                onNavigatePane = onNavigatePane,
                onNavigateNextPane = { splitState.navigateToNextPane() },
                onNavigatePreviousPane = { splitState.navigateToPreviousPane() },
                onMoveToNewTab = if (!splitState.isSinglePane) {
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
                            items + ContextMenuItem(
                                id = "share_tab",
                                label = "Share Tab…",
                                action = { startShare(activeTab.id, ai.rever.bossterm.compose.share.ShareScope.TAB) }
                            ) + ContextMenuItem(
                                id = "share_window",
                                label = "Share Window…",
                                action = { startShare(activeTab.id, ai.rever.bossterm.compose.share.ShareScope.WINDOW) }
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
        if (showMcpStatus || showSharingStatus || attachStatus != null) {
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
                            shareDialog = ai.rever.bossterm.compose.share.SessionShareManager.infoFor(sharedId)
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
                                ))
                            }
                        }
                    },
                )
                attachStatus?.let { status ->
                    AttachToast(status = status)
                }
            }
        }
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
            }
        )
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
