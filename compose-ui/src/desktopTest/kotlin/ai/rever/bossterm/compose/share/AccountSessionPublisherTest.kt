package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [AccountSessionPublisher] against a fake PostgREST on loopback and asserts on the requests
 * it makes: what is upserted, when rows are deleted, that a 401 refreshes once, and that nothing at
 * all is sent while signed out or opted out. The share side is stubbed through the constructor
 * lambdas, so no [SessionShareManager] singleton is touched.
 */
class AccountSessionPublisherTest {

    private data class Req(val method: String, val pathAndQuery: String, val bearer: String?, val prefer: String?, val body: String)

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Req>()
    @Volatile private var nextStatus = 204
    @Volatile private var failFirstWith: Int? = null

    private val tabs = MutableStateFlow<Set<String>>(emptySet())
    private val remote = MutableStateFlow<String?>(null)
    private val account = MutableStateFlow<AccountState>(AccountState.SignedOut)
    private val enabled = MutableStateFlow(true)
    private val tokens = CopyOnWriteArrayList<Boolean>() // forceRefresh flags seen

    private lateinit var publisher: AccountSessionPublisher
    private val http = HttpClient(CIO) { expectSuccess = false }

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += Req(
                ex.requestMethod,
                ex.requestURI.toString(),
                ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer "),
                ex.requestHeaders.getFirst("Prefer"),
                body,
            )
            val status = failFirstWith?.also { failFirstWith = null } ?: nextStatus
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
        publisher = AccountSessionPublisher(
            sharedTabIds = tabs,
            remoteUrl = remote,
            accountState = account,
            enabled = enabled,
            infoFor = { tabId ->
                val base = remote.value ?: "http://192.168.1.2:7677"
                SessionShareManager.ShareInfo(
                    tabId = tabId,
                    url = "$base/?t=view-$tabId#k=secret",
                    token = "view-$tabId",
                    controlUrl = "$base/?t=ctl-$tabId#k=secret",
                    secure = base.startsWith("https"),
                    scope = ShareScope.TAB,
                    e2eCode = "deadbeef",
                    accountUrl = "$base/?t=acct-$tabId#k=secret",
                )
            },
            sessionNameFor = { "deploy" },
            deviceName = { "me_mac" },
            accessToken = { force -> tokens += force; if (force) "tok-2" else "tok-1" },
            restBaseUrl = "http://127.0.0.1:${server.address.port}/rest/v1",
            anonKey = "anon",
            appVersion = "1.2.3",
            heartbeatMs = 200,
            http = http,
        )
    }

    @AfterTest
    fun tearDown() {
        publisher.stop()
        server.stop(0)
        http.close()
    }

    private suspend fun awaitRequests(n: Int, timeoutMs: Long = 5_000) = withTimeout(timeoutMs) {
        while (requests.size < n) delay(20)
    }

    @Test
    fun `nothing is sent while signed out, then one upsert per share once signed in`() = runBlocking {
        publisher.start()
        tabs.value = setOf("t1")
        delay(500)
        assertEquals(0, requests.size, "signed out: no traffic at all")

        account.value = AccountState.SignedIn("me@x.y", "user-1")
        awaitRequests(1)
        val up = requests.first()
        assertEquals("POST", up.method)
        assertEquals("/rest/v1/terminal_sessions?on_conflict=user_id,share_id", up.pathAndQuery)
        assertEquals("tok-1", up.bearer)
        assertEquals("resolution=merge-duplicates,return=minimal", up.prefer)
        assertTrue(up.body.contains("\"user_id\":\"user-1\""), up.body)
        assertTrue(up.body.contains("\"share_id\":\"${AccountSessionPublisher.shareIdOf("view-t1")}\""), up.body)
        assertTrue(up.body.contains("\"control_url\":\"http://192.168.1.2:7677/?t=acct-t1#k=secret\""), "account link is the control_url")
        assertTrue(up.body.contains("\"device_name\":\"me_mac\"") && up.body.contains("\"session_name\":\"deploy\""), up.body)
        assertTrue(!up.body.contains("last_seen_at") && !up.body.contains("started_at"), "timestamps are the server's")
    }

    @Test
    fun `heartbeat re-upserts and a tunnel URL swap changes the published link`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "user-1")
        tabs.value = setOf("t1")
        publisher.start()
        awaitRequests(3) // initial + at least two heartbeats at 200ms
        assertTrue(requests.all { it.method == "POST" })

        remote.value = "https://abc.trycloudflare.com"
        val swapped = "\"control_url\":\"https://abc.trycloudflare.com/?t=acct-t1#k=secret\""
        withTimeout(5_000) { while (requests.none { it.body.contains(swapped) }) delay(20) }
        assertTrue(requests.last { it.body.contains(swapped) }.body.contains("\"secure\":true"))
    }

    @Test
    fun `unsharing deletes that row, toggling off deletes all, and nothing follows`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "user-1")
        tabs.value = setOf("t1", "t2")
        publisher.start()
        awaitRequests(2)

        tabs.value = setOf("t2")
        withTimeout(5_000) { while (requests.none { it.method == "DELETE" }) delay(20) }
        val del = requests.first { it.method == "DELETE" }
        assertEquals("/rest/v1/terminal_sessions?user_id=eq.user-1&share_id=eq.${AccountSessionPublisher.shareIdOf("view-t1")}", del.pathAndQuery)

        enabled.value = false
        val t2 = "/rest/v1/terminal_sessions?user_id=eq.user-1&share_id=eq.${AccountSessionPublisher.shareIdOf("view-t2")}"
        withTimeout(5_000) { while (requests.none { it.method == "DELETE" && it.pathAndQuery == t2 }) delay(20) }
        val after = requests.size
        delay(600) // several heartbeats
        assertEquals(after, requests.size, "opted out: heartbeats send nothing")
    }

    @Test
    fun `a 401 refreshes the token once and retries with the new bearer`() = runBlocking {
        failFirstWith = 401
        account.value = AccountState.SignedIn("me@x.y", "user-1")
        tabs.value = setOf("t1")
        publisher.start()
        awaitRequests(2)
        assertEquals("tok-1", requests[0].bearer)
        assertEquals("tok-2", requests[1].bearer)
        assertTrue(tokens.contains(true), "accessToken(forceRefresh = true) must have been asked")
    }

    @Test
    fun `a share whose upsert failed is not tracked, so unsharing it sends no DELETE`() = runBlocking {
        nextStatus = 500
        account.value = AccountState.SignedIn("me@x.y", "user-1")
        tabs.value = setOf("t1")
        publisher.start()
        awaitRequests(1)
        tabs.value = emptySet()
        delay(500)
        assertTrue(requests.none { it.method == "DELETE" }, "nothing to delete: the row never landed")
        // and once the server is healthy a new share is tracked and its end IS deleted
        nextStatus = 204
        tabs.value = setOf("t2")
        awaitRequests(requests.size + 1)
        tabs.value = emptySet()
        withTimeout(5_000) { while (requests.none { it.method == "DELETE" }) delay(20) }
    }

    @Test
    fun `signed-out state sends no network at all, even with tracked rows`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "user-1")
        tabs.value = setOf("t1")
        publisher.start()
        awaitRequests(1)
        val before = requests.size
        account.value = AccountState.SignedOut // the sign-out listener owns the delete, not reconcile
        delay(500)
        assertEquals(before, requests.size)
    }

    @Test
    fun `stop deletes this process's rows synchronously`() = runBlocking {
        account.value = AccountState.SignedIn("me@x.y", "user-1")
        tabs.value = setOf("t1")
        publisher.start()
        awaitRequests(1)
        publisher.stop()
        assertTrue(requests.any { it.method == "DELETE" && it.pathAndQuery.endsWith("share_id=eq.${AccountSessionPublisher.shareIdOf("view-t1")}") })
    }

    @Test
    fun `shareIdOf is a stable 16-hex digest`() {
        val id = AccountSessionPublisher.shareIdOf("abc")
        assertEquals(16, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]{16}")))
        assertEquals(id, AccountSessionPublisher.shareIdOf("abc"))
    }
}
