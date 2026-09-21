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
    private val wanted = MutableStateFlow(false)
    @Volatile private var shareSucceeds = true

    private val auto = AccountAutoShare(
        accountState = account,
        settings = settings,
        sharedTabIds = shared,
        tabIds = { tabs },
        // Mirrors the manager: a tab the user already shares refuses the account share.
        share = { id -> shares += id; if (shareSucceeds && id !in shared.value) { shared.value = shared.value + id; true } else false },
        unshare = { id -> unshares += id; shared.value = shared.value - id },
        setWanted = { wanted.value = it },
        pollMs = 150,
    )

    @AfterTest
    fun tearDown() = auto.stop()

    private suspend fun awaitShares(n: Int) = withTimeout(5_000) { while (shares.size < n) delay(20) }

    @Test
    fun `signed out shares nothing, signing in raises the wanted switch and starts one ALL share`() = runBlocking {
        auto.start()
        delay(400)
        assertEquals(0, shares.size)
        assertEquals(false, wanted.value)

        account.value = AccountState.SignedIn("me@x.y", "u1")
        awaitShares(1)
        assertEquals("tab-1", shares[0])
        assertEquals("tab-1", auto.autoSharedTabId)
        assertEquals(true, wanted.value)
        // The user's own switches are NOT flipped: the account share has its own enable path.
        assertEquals(false, settings.value.sessionSharingEnabled)
        assertEquals("off", settings.value.shareTailscaleMode)
        delay(500) // several polls: no second share while ours runs
        assertEquals(1, shares.size)
    }

    @Test
    fun `a user share coexists - the account share is started beside it and only ours is stopped on sign-out`() = runBlocking {
        shared.value = setOf("user-tab")
        account.value = AccountState.SignedIn("me@x.y", "u1")
        auto.start()
        awaitShares(1)
        assertEquals("tab-1", auto.autoSharedTabId)
        assertTrue("user-tab" in shared.value && "tab-1" in shared.value, "both shares live side by side")
        account.value = AccountState.SignedOut
        withTimeout(5_000) { while (unshares.isEmpty()) delay(20) }
        assertEquals(listOf("tab-1"), unshares, "a share we did not start is never touched")
        assertTrue("user-tab" in shared.value)
        assertEquals(false, wanted.value)
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
    fun `a tab the user shares by hand is skipped in favour of the next tab`() = runBlocking {
        tabs = listOf("tab-1", "tab-2")
        shared.value = setOf("tab-1") // the user's share on the first tab
        account.value = AccountState.SignedIn("me@x.y", "u1")
        auto.start()
        withTimeout(5_000) { while (auto.autoSharedTabId == null) delay(20) }
        assertEquals("tab-2", auto.autoSharedTabId)
        assertEquals(listOf("tab-1", "tab-2"), shares, "tried the user's tab, was refused, moved on")
        account.value = AccountState.SignedOut
        withTimeout(5_000) { while (unshares.isEmpty()) delay(20) }
        assertEquals(listOf("tab-2"), unshares, "the user's share on tab-1 is never touched")
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
