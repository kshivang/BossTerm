package ai.rever.bossterm.compose.menu

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Holds callback functions for menu bar actions.
 * These callbacks are wired up by TabbedTerminal when it initializes.
 */
class MenuActions {
    // Git repository state (for conditional menu rendering)
    val isGitRepo: MutableState<Boolean> = mutableStateOf(false)
    val isGhConfigured: MutableState<Boolean> = mutableStateOf(false)
    // File menu actions
    var onNewTab: (() -> Unit)? = null
    var onCloseTab: (() -> Unit)? = null

    // Edit menu actions
    var onCopy: (() -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onFind: (() -> Unit)? = null

    // View menu actions
    var onToggleDebug: (() -> Unit)? = null

    // Window menu actions
    var onNextTab: (() -> Unit)? = null
    var onPreviousTab: (() -> Unit)? = null

    // Shell menu actions (split panes)
    var onSplitVertical: (() -> Unit)? = null
    var onSplitHorizontal: (() -> Unit)? = null
    var onClosePane: (() -> Unit)? = null

    // Tools menu actions - AI Assistants
    var onLaunchClaudeCode: (() -> Unit)? = null
    var onLaunchGemini: (() -> Unit)? = null
    var onLaunchCodex: (() -> Unit)? = null
    var onLaunchOpenCode: (() -> Unit)? = null

    // Tools menu actions - Version Control - Git
    var onGitInit: (() -> Unit)? = null
    var onGitClone: (() -> Unit)? = null
    var onGitStatus: (() -> Unit)? = null
    var onGitDiff: (() -> Unit)? = null
    var onGitLog: (() -> Unit)? = null
    var onGitAddAll: (() -> Unit)? = null
    var onGitAddPatch: (() -> Unit)? = null
    var onGitReset: (() -> Unit)? = null
    var onGitCommit: (() -> Unit)? = null
    var onGitCommitAmend: (() -> Unit)? = null
    var onGitPush: (() -> Unit)? = null
    var onGitPull: (() -> Unit)? = null
    var onGitFetch: (() -> Unit)? = null
    var onGitBranch: (() -> Unit)? = null
    var onGitCheckoutPrev: (() -> Unit)? = null
    var onGitCheckoutNew: (() -> Unit)? = null
    var onGitStash: (() -> Unit)? = null
    var onGitStashPop: (() -> Unit)? = null

    // Tools menu actions - Version Control - GitHub CLI
    var onGhAuthStatus: (() -> Unit)? = null
    var onGhAuthLogin: (() -> Unit)? = null
    var onGhSetDefault: (() -> Unit)? = null
    var onGhRepoClone: (() -> Unit)? = null
    var onGhPrList: (() -> Unit)? = null
    var onGhPrStatus: (() -> Unit)? = null
    var onGhPrCreate: (() -> Unit)? = null
    var onGhPrView: (() -> Unit)? = null
    var onGhIssueList: (() -> Unit)? = null
    var onGhIssueCreate: (() -> Unit)? = null
    var onGhRepoView: (() -> Unit)? = null

    // Tools menu actions - Shell Customization
    var onEditZshrc: (() -> Unit)? = null
    var onEditBashrc: (() -> Unit)? = null
    var onEditFishConfig: (() -> Unit)? = null
    var onReloadShellConfig: (() -> Unit)? = null
    var onStarshipEditConfig: (() -> Unit)? = null
    var onStarshipPresets: (() -> Unit)? = null
    var onOhMyZshUpdate: (() -> Unit)? = null
    var onOhMyZshThemes: (() -> Unit)? = null
    var onOhMyZshPlugins: (() -> Unit)? = null
    var onPreztoUpdate: (() -> Unit)? = null
    var onPreztoEditConfig: (() -> Unit)? = null
    var onPreztoListThemes: (() -> Unit)? = null
    var onPreztoShowModules: (() -> Unit)? = null
}
