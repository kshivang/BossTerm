package ai.rever.bossterm.compose.vcs

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
}
