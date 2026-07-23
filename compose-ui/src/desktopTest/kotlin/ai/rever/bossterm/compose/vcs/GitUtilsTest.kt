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
        val expected = if (ShellCustomizationUtils.isWindows()) {
            "git -C \"/tmp/a b\" worktree add \n"
        } else {
            "git -C '/tmp/a b' worktree add \n"
        }
        assertEquals(expected, GitUtils.gitCommand("worktree add ", "/tmp/a b"))
    }
}
