package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.share.SessionShareManager.ShareInfo
import ai.rever.bossterm.compose.share.ShareScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class HostedShareControllerTest {
    @Test
    fun `reopen only the selected terminal user share`() {
        val first = info("window-a")
        val selected = info("window-b")
        val controller = HostedShareController(
            userShares = { setOf(first.tabId, selected.tabId) },
            infoFor = { if (it == first.tabId) first else selected },
            createShare = { _, _ -> error("Unexpected share creation") },
        )
        assertSame(selected, controller.existing(selected.tabId))
        assertNull(controller.existing("unshared-window-c"))
        assertNull(controller.existing(null))
    }

    @Test
    fun `account share is never reused for explicit tab sharing`() = runTest {
        val account = info("account-terminal", ShareScope.ALL)
        var requests = 0
        val controller = HostedShareController(
            userShares = { emptySet() },
            infoFor = { error("Account share must not be looked up") },
            createShare = { id, scope ->
                requests++
                assertEquals(account.tabId, id)
                assertEquals(ShareScope.TAB, scope)
                // The real manager rejects user-share requests for account-owned shares.
                null
            },
        )
        assertNull(controller.existing(account.tabId))
        assertNull(controller.open(account.tabId, ShareScope.TAB))
        assertEquals(1, requests)
    }

    @Test
    fun `new share preserves requested window and scope and propagates failure`() = runTest {
        val created = info("window-b", ShareScope.WINDOW)
        val controller = HostedShareController(
            userShares = { setOf("window-a") },
            infoFor = { error("Do not reuse another windows share") },
            createShare = { id, scope ->
                assertEquals("window-b", id)
                if (scope == ShareScope.WINDOW) created else null
            },
        )
        assertSame(created, controller.open("window-b", ShareScope.WINDOW))
        assertNull(controller.open("window-b", ShareScope.ALL))
    }

    private fun info(id: String, scope: ShareScope = ShareScope.TAB) =
        ShareInfo(id, "https://example.test/view", "test-token", "https://example.test/control", scope = scope)
}
