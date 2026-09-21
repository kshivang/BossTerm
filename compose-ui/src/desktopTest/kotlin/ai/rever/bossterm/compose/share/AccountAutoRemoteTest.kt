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
import kotlin.test.assertTrue

/** [AccountAutoRemote] against fake window/manager seams. */
class AccountAutoRemoteTest {

    private val account = MutableStateFlow<AccountState>(AccountState.SignedOut)
    private val settings = MutableStateFlow(TerminalSettings())
    private val directory = MutableStateFlow<List<AccountSession>>(emptyList())
    /** null = no window yet */
    @Volatile private var connected: MutableSet<String>? = mutableSetOf()
    @Volatile private var window: Any? = "window-1"
    private val connects = CopyOnWriteArrayList<String>()
    private val disconnects = CopyOnWriteArrayList<String>()

    private fun session(device: String, token: String, scope: String = "ALL") = AccountSession(
        shareId = token.hashCode().toString(16).padStart(16, '0').take(16), deviceName = device, sessionName = null, scope = scope,
        controlUrl = "https://x.trycloudflare.com/?t=$token#k=s", secure = true, e2eCode = "deadbeef", appVersion = "1", lastSeen = null,
    )

    private val auto = AccountAutoRemote(
        accountState = account,
        settings = settings,
        directory = directory,
        connectedTokens = { connected?.toSet() },
        connect = { url, _ -> val t = AccountSessionDirectory.tokenOf(url)!!; connects += t; connected?.add(t) == true },
        disconnect = { t -> disconnects += t; connected?.remove(t) },
        deviceName = { "me (BossTerm)" },
        windowKey = { window },
        pollMs = 150,
    )

    @AfterTest
    fun tearDown() = auto.stop()

    private suspend fun awaitConnects(n: Int) = withTimeout(5_000) { while (connects.size < n) delay(20) }

    @Test
    fun `signed in attaches every listed session once and does not re-attach`() = runBlocking {
        directory.value = listOf(session("laptop", "aaa"), session("desk", "bbb"))
        auto.start()
        delay(400)
        assertEquals(0, connects.size, "signed out: nothing attached")
        account.value = AccountState.SignedIn("me@x.y", "u1")
        awaitConnects(2)
        assertEquals(setOf("aaa", "bbb"), connects.toSet())
        delay(500)
        assertEquals(2, connects.size, "already connected sessions are left alone across polls")
        assertEquals(setOf("aaa", "bbb"), auto.attachedTokens)
    }

    @Test
    fun `a session the user disconnected by hand is not re-attached while its row persists, but a new share is`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "u1")
        directory.value = listOf(session("laptop", "aaa"))
        auto.start()
        awaitConnects(1)
        connected!!.remove("aaa") // the user pressed Disconnect
        delay(500)
        assertEquals(1, connects.size, "must not undo the user's disconnect")
        directory.value = listOf(session("laptop", "ccc")) // that device started a new share
        awaitConnects(2)
        assertEquals("ccc", connects.last())
    }

    @Test
    fun `a hand disconnect survives the row blipping out of the directory and back`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "u1")
        directory.value = listOf(session("laptop", "aaa"))
        auto.start()
        awaitConnects(1)
        connected!!.remove("aaa")
        delay(400)
        directory.value = emptyList() // heartbeat lag / token refresh emptied the list
        delay(400)
        directory.value = listOf(session("laptop", "aaa")) // same token is back
        delay(500)
        assertEquals(1, connects.size, "same token after a blip is still the user's disconnect")
    }

    @Test
    fun `only the other device's ALL share is attached, not its hand-made TAB or WINDOW shares`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "u1")
        directory.value = listOf(session("laptop", "all", "ALL"), session("laptop", "tab", "TAB"), session("laptop", "win", "WINDOW"))
        auto.start()
        awaitConnects(1)
        delay(500)
        assertEquals(listOf("all"), connects)
    }

    @Test
    fun `a new primary window forgets what the old one attached without treating it as a hand disconnect`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "u1")
        directory.value = listOf(session("laptop", "aaa"))
        auto.start()
        awaitConnects(1)
        connected = mutableSetOf() // the old window closed; the new primary has nothing yet
        window = "window-2"
        awaitConnects(2)
        assertEquals(listOf("aaa", "aaa"), connects)
    }

    @Test
    fun `a session the user connected by hand is respected and never disconnected by us`() = runBlocking {
        connected!!.add("aaa") // user connected it from the Remote Sessions window
        account.value = AccountState.SignedIn("me@x.y", "u1")
        directory.value = listOf(session("laptop", "aaa"))
        auto.start()
        delay(500)
        assertEquals(0, connects.size)
        account.value = AccountState.SignedOut
        delay(400)
        assertEquals(0, disconnects.size, "not ours to disconnect")
    }

    @Test
    fun `sign-out and toggle-off detach only what this attached`() = runBlocking {
        connected!!.add("user")
        account.value = AccountState.SignedIn("me@x.y", "u1")
        directory.value = listOf(session("laptop", "aaa"))
        auto.start()
        awaitConnects(1)
        settings.value = settings.value.copy(autoConnectAccountSessions = false)
        withTimeout(5_000) { while (disconnects.isEmpty()) delay(20) }
        assertEquals(listOf("aaa"), disconnects)
        assertTrue("user" in connected!!)
        assertTrue(auto.attachedTokens.isEmpty())
    }

    @Test
    fun `no window yet means wait, then attach once one exists`() = runBlocking {
        connected = null
        account.value = AccountState.SignedIn("me@x.y", "u1")
        directory.value = listOf(session("laptop", "aaa"))
        auto.start()
        delay(400)
        assertEquals(0, connects.size)
        connected = mutableSetOf()
        awaitConnects(1)
    }
}
