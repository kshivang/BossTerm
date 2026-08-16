package ai.rever.bossterm.compose.vcs

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlin.test.Test
import kotlin.test.assertEquals

class GitUtilsTest {

    @Test
    fun `POSIX shell quote preserves spaces quotes and interpolation characters`() {
        assertEquals(
            "'/tmp/a b/'\"'\"'c\"/\$HOME/`cmd`'",
            GitUtils.shellQuote("/tmp/a b/'c\"/\$HOME/`cmd`", isWindows = false)
        )
    }

    @Test
    fun `Windows shell quote preserves a valid Windows path`() {
        assertEquals(
            "\"C:\\Users\\Boss User\\repo\"",
            GitUtils.shellQuote("C:\\Users\\Boss User\\repo", isWindows = true)
        )
    }

    @Test
    fun `git command quotes cwd before worktree command`() {
        // No trailing separator: the builder returns command text, and whoever runs it adds the
        // Enter via submitLine. The Add Worktree… caller depends on that — it prefills the prompt
        // for the user to finish typing, and used to have to strip the newline back off.
        val expected = if (ShellCustomizationUtils.isWindows()) {
            "git -C \"/tmp/a b\" worktree add "
        } else {
            "git -C '/tmp/a b' worktree add "
        }
        assertEquals(expected, GitUtils.gitCommand("worktree add ", "/tmp/a b"))
    }

    @Test
    fun `repository state includes a symbolic branch`() {
        assertEquals(
            GitUtils.RepositoryState(branch = "feature/worktrees", isRepository = true),
            GitUtils.parseRepositoryState(exitCode = 0, output = "feature/worktrees\n")
        )
    }

    @Test
    fun `repository state keeps detached HEAD inside the repository`() {
        assertEquals(
            GitUtils.RepositoryState(branch = null, isRepository = true),
            GitUtils.parseRepositoryState(exitCode = 1, output = "")
        )
    }

    @Test
    fun `repository state rejects non-repository failures`() {
        assertEquals(
            GitUtils.RepositoryState(branch = null, isRepository = false),
            GitUtils.parseRepositoryState(exitCode = 128, output = "fatal: not a git repository")
        )
    }
}
