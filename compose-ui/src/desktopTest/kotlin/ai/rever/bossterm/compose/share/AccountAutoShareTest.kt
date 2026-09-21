package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AccountAutoShare] against fake share/settings seams: when it starts a share, when it does not,
 * when it stops only its own share, and that it enables sharing + Cloudflare on the way in.
 */
class AccountAutoShareTest {

    private val account = MutableStateFlow<AccountState>(AccountState.SignedOut)
    private val settings = MutableStateFlow(TerminalSettings(sessionSharingEnabled = false, shareTailscaleMode = "off"))
    private val shared = MutableStateFlow<Set<String>>(emptySet())
    private var tabs = listOf("tab-1")
    private val shares = CopyOnWriteArrayList<String>()
    private val unshares = CopyOnWriteArrayList<String>()
    @Volatile private var shareSucceeds = true

    private val auto = AccountAutoShare(
        accountState = account,
        settings = settings,
        sharedTabIds = shared,
        firstTabId = { tabs.firstOrNull() },
        share = { id -> shares += id; if (shareSucceeds) { shared.value = shared.value + id; true } else false },
        unshare = { id -> unshares += id; shared.value = shared.value - id },
        updateSettings = { f -> settings.value = f(settings.value) },
        pollMs = 150,
    )

    @AfterTest
    fun tearDown() = auto.stop()

    private suspend fun awaitShares(n: Int) = withTimeout(5_000) { while (shares.size < n) delay(20) }

    @Test
    fun `signed out shares nothing, signing in starts one ALL share and enables sharing over cloudflare`() = runBlocking {
        auto.start()
        delay(400)
        assertEquals(0, shares.size)

        account.value = AccountState.SignedIn("me@x.y", "u1")
        awaitShares(1)
        assertEquals("tab-1", shares[0])
        assertEquals("tab-1", auto.autoSharedTabId)
        assertTrue(settings.value.sessionSharingEnabled)
        assertEquals("cloudflare", settings.value.shareTailscaleMode)
        delay(500) // several polls: no second share while ours runs
        assertEquals(1, shares.size)
    }

    @Test
    fun `an existing user share is respected - nothing stacked on top and nothing unshared on sign-out`() = runBlocking {
        shared.value = setOf("user-tab")
        account.value = AccountState.SignedIn("me@x.y", "u1")
        auto.start()
        delay(500)
        assertEquals(0, shares.size)
        assertNull(auto.autoSharedTabId)
        account.value = AccountState.SignedOut
        delay(300)
        assertEquals(0, unshares.size, "a share we did not start is never touched")
    }

    @Test
    fun `sign-out or toggle-off stops the share it started, and only that one`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "u1")
        auto.start()
        awaitShares(1)
        shared.value = shared.value + "user-tab" // user shares something else too
        settings.value = settings.value.copy(autoShareToAccount = false)
        withTimeout(5_000) { while (unshares.isEmpty()) delay(20) }
        assertEquals(listOf("tab-1"), unshares)
        assertTrue("user-tab" in shared.value)
        assertNull(auto.autoSharedTabId)
    }

    @Test
    fun `a share that ended on its own is started again on the next poll`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "u1")
        auto.start()
        awaitShares(1)
        shared.value = emptySet() // window closed, share ended
        awaitShares(2)
        assertEquals("tab-1", auto.autoSharedTabId)
    }

    @Test
    fun `no tab yet means wait, not fail`() = runBlocking {
        tabs = emptyList()
        account.value = AccountState.SignedIn("me@x.y", "u1")
        auto.start()
        delay(400)
        assertEquals(0, shares.size)
        tabs = listOf("late-tab")
        awaitShares(1)
        assertEquals("late-tab", shares[0])
    }
}
