package ai.rever.bossterm.compose.vcs

import ai.rever.bossterm.compose.ContextMenuElement
import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/**
 * Provides context menu items for version control operations (Git and GitHub CLI).
 *
 * Detects if git and gh are installed and shows appropriate menu items:
 * - If installed: Shows submenu with common commands
 * - If not installed: Shows install option with link to download
 */
class VersionControlMenuProvider {

    /**
     * Cached installation status to avoid repeated `which` calls.
     * Updated each time getMenuItems is called with refresh=true.
     */
    private var gitInstalled: Boolean? = null
    private var ghInstalled: Boolean? = null

    /**
     * Cached list of git branches (local and remote tracking).
     * Updated on each context menu open.
     */
    private var gitBranches: List<String> = emptyList()
    private var currentBranch: String? = null

    /**
     * Detect if a command is installed by checking `which`.
     */
    private fun isCommandInstalled(command: String): Boolean {
        return try {
            val process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Refresh installation status for git and gh, and fetch git branches.
     */
    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        gitInstalled = isCommandInstalled("git")
        ghInstalled = isCommandInstalled("gh")

        // Fetch git branches if git is installed
        if (gitInstalled == true) {
            fetchGitBranches()
        }
    }

    /**
     * Fetch local git branches and current branch.
     */
    private fun fetchGitBranches() {
        try {
            // Get current branch
            val headProcess = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .redirectErrorStream(true)
                .start()
            currentBranch = headProcess.inputStream.bufferedReader().readText().trim()
                .takeIf { headProcess.waitFor() == 0 && it.isNotEmpty() && it != "HEAD" }

            // Get all local branches
            val branchProcess = ProcessBuilder("git", "branch", "--format=%(refname:short)")
                .redirectErrorStream(true)
                .start()
            val output = branchProcess.inputStream.bufferedReader().readText()
            if (branchProcess.waitFor() == 0) {
                gitBranches = output.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .sortedWith(compareBy(
                        // Sort: current branch first, then main/master/dev, then alphabetically
                        { it != currentBranch },
                        { it != "main" && it != "master" },
                        { it != "dev" && it != "develop" },
                        { it }
                    ))
            }
        } catch (e: Exception) {
            gitBranches = emptyList()
            currentBranch = null
        }
    }

    /**
     * Get cached or fresh installation status.
     */
    fun getStatus(): Pair<Boolean, Boolean> {
        if (gitInstalled == null) gitInstalled = isCommandInstalled("git")
        if (ghInstalled == null) ghInstalled = isCommandInstalled("gh")
        return Pair(gitInstalled ?: false, ghInstalled ?: false)
    }

    /**
     * Generate context menu items for version control.
     *
     * @param terminalWriter Function to write commands to the terminal
     * @param statusOverride Optional pre-computed status (gitInstalled, ghInstalled)
     * @return List of context menu elements for the Version Control section
     */
    fun getMenuItems(
        terminalWriter: (String) -> Unit,
        statusOverride: Pair<Boolean, Boolean>? = null
    ): List<ContextMenuElement> {
        val (isGitInstalled, isGhInstalled) = statusOverride ?: getStatus()

        val vcsItems = mutableListOf<ContextMenuElement>()

        // Git submenu
        vcsItems.add(buildGitMenu(isGitInstalled, terminalWriter))

        // GitHub CLI submenu
        vcsItems.add(buildGhMenu(isGhInstalled, terminalWriter))

        return listOf(
            ContextMenuSubmenu(
                id = "vcs_menu",
                label = "Version Control",
                items = vcsItems
            )
        )
    }

    /**
     * Build Git submenu with common commands or install option.
     */
    private fun buildGitMenu(isInstalled: Boolean, terminalWriter: (String) -> Unit): ContextMenuElement {
        if (!isInstalled) {
            return ContextMenuSubmenu(
                id = "git_submenu",
                label = "Git",
                items = listOf(
                    ContextMenuItem(
                        id = "git_install",
                        label = "Install Git",
                        action = {
                            openUrl("https://git-scm.com/downloads")
                        }
                    )
                )
            )
        }

        val gitCommands = listOf(
            ContextMenuSection(id = "git_status_section", label = "Status"),
            ContextMenuItem(
                id = "git_status",
                label = "git status",
                action = { terminalWriter("git status\n") }
            ),
            ContextMenuItem(
                id = "git_diff",
                label = "git diff",
                action = { terminalWriter("git diff\n") }
            ),
            ContextMenuItem(
                id = "git_log",
                label = "git log --oneline -10",
                action = { terminalWriter("git log --oneline -10\n") }
            ),
            ContextMenuSection(id = "git_staging_section", label = "Staging"),
            ContextMenuItem(
                id = "git_add_all",
                label = "git add .",
                action = { terminalWriter("git add .\n") }
            ),
            ContextMenuItem(
                id = "git_add_interactive",
                label = "git add -p",
                action = { terminalWriter("git add -p\n") }
            ),
            ContextMenuItem(
                id = "git_reset",
                label = "git reset HEAD",
                action = { terminalWriter("git reset HEAD\n") }
            ),
            ContextMenuSection(id = "git_commit_section", label = "Commit"),
            ContextMenuItem(
                id = "git_commit",
                label = "git commit",
                action = { terminalWriter("git commit\n") }
            ),
            ContextMenuItem(
                id = "git_commit_amend",
                label = "git commit --amend",
                action = { terminalWriter("git commit --amend\n") }
            ),
            ContextMenuSection(id = "git_remote_section", label = "Remote"),
            ContextMenuItem(
                id = "git_push",
                label = "git push",
                action = { terminalWriter("git push\n") }
            ),
            ContextMenuItem(
                id = "git_pull",
                label = "git pull",
                action = { terminalWriter("git pull\n") }
            ),
            ContextMenuItem(
                id = "git_fetch",
                label = "git fetch --all",
                action = { terminalWriter("git fetch --all\n") }
            ),
            ContextMenuSection(id = "git_branch_section", label = "Branches"),
            ContextMenuItem(
                id = "git_branch",
                label = "git branch -a",
                action = { terminalWriter("git branch -a\n") }
            ),
            buildCheckoutSubmenu(terminalWriter),
            buildSwitchSubmenu(terminalWriter)
        )

        return ContextMenuSubmenu(
            id = "git_submenu",
            label = "Git",
            items = gitCommands
        )
    }

    /**
     * Build git checkout submenu with dynamic branches.
     */
    private fun buildCheckoutSubmenu(terminalWriter: (String) -> Unit): ContextMenuElement {
        val items = mutableListOf<ContextMenuElement>()

        // Previous branch option
        items.add(
            ContextMenuItem(
                id = "git_checkout_prev",
                label = "Previous branch (-)",
                action = { terminalWriter("git checkout -\n") }
            )
        )

        // Dynamic branch list
        if (gitBranches.isNotEmpty()) {
            items.add(ContextMenuSection(id = "git_checkout_branches_section", label = "Branches"))

            // Show up to 10 branches
            gitBranches.take(10).forEach { branch ->
                val label = if (branch == currentBranch) "● $branch (current)" else branch
                val enabled = branch != currentBranch
                items.add(
                    ContextMenuItem(
                        id = "git_checkout_branch_$branch",
                        label = label,
                        enabled = enabled,
                        action = { terminalWriter("git checkout $branch\n") }
                    )
                )
            }

            // Show "more branches" hint if there are more
            if (gitBranches.size > 10) {
                items.add(
                    ContextMenuItem(
                        id = "git_checkout_more",
                        label = "... ${gitBranches.size - 10} more (type manually)",
                        enabled = false,
                        action = {}
                    )
                )
            }
        }

        // New branch and other options
        items.add(ContextMenuSection(id = "git_checkout_actions_section", label = "Actions"))
        items.add(
            ContextMenuItem(
                id = "git_checkout_new",
                label = "New branch (-b) ...",
                action = { terminalWriter("git checkout -b ") }
            )
        )
        items.add(
            ContextMenuItem(
                id = "git_checkout_other",
                label = "Other branch ...",
                action = { terminalWriter("git checkout ") }
            )
        )
        items.add(ContextMenuSection(id = "git_checkout_discard_section"))
        items.add(
            ContextMenuItem(
                id = "git_checkout_discard",
                label = "Discard all changes (-- .)",
                action = { terminalWriter("git checkout -- .\n") }
            )
        )

        return ContextMenuSubmenu(
            id = "git_checkout_submenu",
            label = "git checkout",
            items = items
        )
    }

    /**
     * Build git switch submenu with dynamic branches.
     */
    private fun buildSwitchSubmenu(terminalWriter: (String) -> Unit): ContextMenuElement {
        val items = mutableListOf<ContextMenuElement>()

        // Previous branch option
        items.add(
            ContextMenuItem(
                id = "git_switch_prev",
                label = "Previous branch (-)",
                action = { terminalWriter("git switch -\n") }
            )
        )

        // Dynamic branch list
        if (gitBranches.isNotEmpty()) {
            items.add(ContextMenuSection(id = "git_switch_branches_section", label = "Branches"))

            // Show up to 10 branches
            gitBranches.take(10).forEach { branch ->
                val label = if (branch == currentBranch) "● $branch (current)" else branch
                val enabled = branch != currentBranch
                items.add(
                    ContextMenuItem(
                        id = "git_switch_branch_$branch",
                        label = label,
                        enabled = enabled,
                        action = { terminalWriter("git switch $branch\n") }
                    )
                )
            }

            // Show "more branches" hint if there are more
            if (gitBranches.size > 10) {
                items.add(
                    ContextMenuItem(
                        id = "git_switch_more",
                        label = "... ${gitBranches.size - 10} more (type manually)",
                        enabled = false,
                        action = {}
                    )
                )
            }
        }

        // New branch option
        items.add(ContextMenuSection(id = "git_switch_actions_section", label = "Actions"))
        items.add(
            ContextMenuItem(
                id = "git_switch_create",
                label = "Create new branch (-c) ...",
                action = { terminalWriter("git switch -c ") }
            )
        )
        items.add(
            ContextMenuItem(
                id = "git_switch_other",
                label = "Other branch ...",
                action = { terminalWriter("git switch ") }
            )
        )

        return ContextMenuSubmenu(
            id = "git_switch_submenu",
            label = "git switch",
            items = items
        )
    }

    /**
     * Build GitHub CLI submenu with common commands or install option.
     */
    private fun buildGhMenu(isInstalled: Boolean, terminalWriter: (String) -> Unit): ContextMenuElement {
        if (!isInstalled) {
            return ContextMenuSubmenu(
                id = "gh_submenu",
                label = "GitHub CLI",
                items = listOf(
                    ContextMenuItem(
                        id = "gh_install",
                        label = "Install GitHub CLI",
                        action = {
                            openUrl("https://cli.github.com/")
                        }
                    )
                )
            )
        }

        val ghCommands = listOf(
            ContextMenuSection(id = "gh_auth_section", label = "Auth"),
            ContextMenuItem(
                id = "gh_auth_status",
                label = "gh auth status",
                action = { terminalWriter("gh auth status\n") }
            ),
            ContextMenuItem(
                id = "gh_auth_login",
                label = "gh auth login",
                action = { terminalWriter("gh auth login\n") }
            ),
            ContextMenuSection(id = "gh_pr_section", label = "Pull Requests"),
            ContextMenuItem(
                id = "gh_pr_list",
                label = "gh pr list",
                action = { terminalWriter("gh pr list\n") }
            ),
            ContextMenuItem(
                id = "gh_pr_status",
                label = "gh pr status",
                action = { terminalWriter("gh pr status\n") }
            ),
            ContextMenuItem(
                id = "gh_pr_create",
                label = "gh pr create",
                action = { terminalWriter("gh pr create\n") }
            ),
            ContextMenuItem(
                id = "gh_pr_checkout",
                label = "gh pr checkout ...",
                action = { terminalWriter("gh pr checkout ") }
            ),
            ContextMenuItem(
                id = "gh_pr_view",
                label = "gh pr view --web",
                action = { terminalWriter("gh pr view --web\n") }
            ),
            ContextMenuSection(id = "gh_issue_section", label = "Issues"),
            ContextMenuItem(
                id = "gh_issue_list",
                label = "gh issue list",
                action = { terminalWriter("gh issue list\n") }
            ),
            ContextMenuItem(
                id = "gh_issue_create",
                label = "gh issue create",
                action = { terminalWriter("gh issue create\n") }
            ),
            ContextMenuItem(
                id = "gh_issue_view",
                label = "gh issue view ...",
                action = { terminalWriter("gh issue view ") }
            ),
            ContextMenuSection(id = "gh_repo_section", label = "Repository"),
            ContextMenuItem(
                id = "gh_repo_view",
                label = "gh repo view --web",
                action = { terminalWriter("gh repo view --web\n") }
            ),
            ContextMenuItem(
                id = "gh_repo_clone",
                label = "gh repo clone ...",
                action = { terminalWriter("gh repo clone ") }
            )
        )

        return ContextMenuSubmenu(
            id = "gh_submenu",
            label = "GitHub CLI",
            items = ghCommands
        )
    }

    /**
     * Open URL in default browser.
     */
    private fun openUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (e: Exception) {
            // Silently fail - user can manually visit the URL
        }
    }
}
