package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabBarWorktreeActionTest {

    @Test
    fun `worktree action remains enabled while repository detection is pending`() {
        assertTrue(canCreateWorktree(null))
    }

    @Test
    fun `worktree action disables only for a confirmed non-repository`() {
        assertTrue(canCreateWorktree(true))
        assertFalse(canCreateWorktree(false))
    }
}
