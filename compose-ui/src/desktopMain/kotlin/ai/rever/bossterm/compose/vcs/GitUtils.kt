package ai.rever.bossterm.compose.vcs

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shared utility functions for Git and GitHub CLI operations.
 * Used by both context menu (VersionControlMenuProvider) and window menu (MenuActions).
 */
object GitUtils {

    /**
     * Check if a directory is inside a git repository.
     */
    fun isGitRepo(cwd: String?): Boolean {
        if (cwd == null) return false
        var process: Process? = null
        return try {
            process = ProcessBuilder("git", "-C", cwd, "rev-parse", "--git-dir")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        } finally {
            process?.inputStream?.close()
            process?.errorStream?.close()
            process?.outputStream?.close()
        }
    }

    /**
     * Check if GitHub CLI has a default repository configured for this directory.
     * Checks the git config for 'remote.origin.gh-resolved' which gh sets when
     * you run 'gh repo set-default'.
     */
    fun isGhRepoConfigured(cwd: String?): Boolean {
        if (cwd == null) return false
        var process: Process? = null
        return try {
            process = ProcessBuilder("git", "-C", cwd, "config", "--get", "remote.origin.gh-resolved")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0 && output.isNotEmpty()
            }
        } catch (e: Exception) {
            false
        } finally {
            process?.inputStream?.close()
            process?.errorStream?.close()
            process?.outputStream?.close()
        }
    }

    /**
     * Get the current branch name.
     */
    fun getCurrentBranch(cwd: String?): String? {
        if (cwd == null) return null
        var process: Process? = null
        return try {
            process = ProcessBuilder("git", "-C", cwd, "rev-parse", "--abbrev-ref", "HEAD")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0 && output.isNotEmpty() && output != "HEAD") {
                output
            } else null
        } catch (e: Exception) {
            null
        } finally {
            process?.inputStream?.close()
            process?.errorStream?.close()
            process?.outputStream?.close()
        }
    }

    /**
     * Get list of local branches.
     */
    fun getLocalBranches(cwd: String?): List<String> {
        if (cwd == null) return emptyList()
        var process: Process? = null
        return try {
            process = ProcessBuilder("git", "-C", cwd, "branch", "--format=%(refname:short)")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                emptyList()
            } else if (process.exitValue() == 0) {
                output.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            process?.inputStream?.close()
            process?.errorStream?.close()
            process?.outputStream?.close()
        }
    }

    // === Git Commands ===

    /**
     * Build a git command that runs in the specified directory.
     * @param cmd The git subcommand and arguments (e.g., "status", "log --oneline -10")
     * @param cwd The working directory (if null, runs without -C flag)
     * @return The full command string with newline
     */
    fun gitCommand(cmd: String, cwd: String?): String {
        return if (cwd != null) {
            "git -C \"$cwd\" $cmd\n"
        } else {
            "git $cmd\n"
        }
    }

    /**
     * Build a gh command that runs in the specified directory.
     * @param cmd The gh subcommand and arguments (e.g., "pr list", "issue create")
     * @param cwd The working directory (if null, runs without cd)
     * @return The full command string with newline
     */
    fun ghCommand(cmd: String, cwd: String?): String {
        return if (cwd != null) {
            "cd \"$cwd\" && gh $cmd\n"
        } else {
            "gh $cmd\n"
        }
    }

    // === Common Git Command Strings ===

    object Commands {
        const val INIT = "init"
        const val CLONE = "clone "
        const val STATUS = "status"
        const val DIFF = "diff"
        const val LOG = "log --oneline -10"
        const val ADD_ALL = "add ."
        const val ADD_PATCH = "add -p"
        const val RESET = "reset HEAD"
        const val COMMIT = "commit"
        const val COMMIT_AMEND = "commit --amend"
        const val PUSH = "push"
        const val PULL = "pull"
        const val FETCH = "fetch --all"
        const val BRANCH = "branch -a"
        const val CHECKOUT_PREV = "checkout -"
        const val CHECKOUT_NEW = "checkout -b "
        const val STASH = "stash"
        const val STASH_POP = "stash pop"
    }

    object GhCommands {
        const val AUTH_STATUS = "auth status"
        const val AUTH_LOGIN = "auth login"
        const val SET_DEFAULT = "repo set-default"
        const val REPO_CLONE = "repo clone "
        const val PR_LIST = "pr list"
        const val PR_STATUS = "pr status"
        const val PR_CREATE = "pr create"
        const val PR_VIEW_WEB = "pr view --web"
        const val ISSUE_LIST = "issue list"
        const val ISSUE_CREATE = "issue create"
        const val REPO_VIEW_WEB = "repo view --web"
    }
}
