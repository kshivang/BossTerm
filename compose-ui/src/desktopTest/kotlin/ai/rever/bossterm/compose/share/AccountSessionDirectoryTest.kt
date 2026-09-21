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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AccountSessionDirectory] against a fake PostgREST: own shares filtered, signed-out silence, polling. */
class AccountSessionDirectoryTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var body = "[]"
    private val account = MutableStateFlow<AccountState>(AccountState.SignedOut)
    private val http = HttpClient(CIO) { expectSuccess = false }
    private lateinit var dir: AccountSessionDirectory

    private fun row(id: String, device: String, token: String, scope: String = "ALL") =
        """{"share_id":"$id","device_name":"$device","session_name":null,"scope":"$scope",
           "control_url":"https://x.trycloudflare.com/?t=$token#k=s","secure":true,"e2e_code":"deadbeef",
           "app_version":"1.2.3","last_seen_at":"2026-09-21T04:29:09.123456+00:00"}"""

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests += ex.requestMethod + " " + ex.requestURI + " auth=" + ex.requestHeaders.getFirst("Authorization")
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        dir = AccountSessionDirectory(
            accountState = account,
            accessToken = { "tok-1" },
            ownsToken = { it == "mine" },
            restBaseUrl = "http://127.0.0.1:${server.address.port}/rest/v1",
            anonKey = "anon",
            pollMs = 200,
            http = http,
        )
    }

    @AfterTest
    fun tearDown() {
        dir.stop(); server.stop(0); http.close()
    }

    @Test
    fun `signed out fetches nothing and lists nothing`() = runBlocking {
        body = "[${row("a", "other", "theirs")}]"
        dir.start()
        delay(500)
        assertEquals(0, requests.size)
        assertTrue(dir.sessions.value.isEmpty())
    }

    @Test
    fun `signed in lists other devices, drops this device's own share, and parses fields`() = runBlocking {
        body = "[${row("a", "other-mac", "theirs")},${row("b", "me", "mine", "TAB")}]"
        account.value = AccountState.SignedIn("me@x.y", "u1")
        dir.start()
        withTimeout(5_000) { while (dir.sessions.value.isEmpty()) delay(20) }
        val s = dir.sessions.value
        assertEquals(1, s.size)
        assertEquals("other-mac", s[0].deviceName)
        assertEquals("ALL", s[0].scope)
        assertEquals("deadbeef", s[0].e2eCode)
        assertEquals("2026-09-21T04:29:09.123456Z", s[0].lastSeen.toString())
        assertNull(dir.lastError.value)
        val req = requests.first()
        assertTrue(req.contains("/rest/v1/terminal_sessions?"), req)
        assertTrue(req.contains("last_seen_at=gt."), "freshness filter")
        assertTrue(req.endsWith("auth=Bearer tok-1"), "the user's own JWT, not a service key")
    }

    @Test
    fun `polls, and a session that vanished is removed`() = runBlocking {
        body = "[${row("a", "other-mac", "theirs")}]"
        account.value = AccountState.SignedIn("me@x.y", "u1")
        dir.start()
        withTimeout(5_000) { while (dir.sessions.value.isEmpty()) delay(20) }
        body = "[]"
        withTimeout(5_000) { while (dir.sessions.value.isNotEmpty()) delay(20) }
        assertTrue(requests.size >= 2)
    }

    @Test
    fun `sign-out clears the list without another request`() = runBlocking {
        body = "[${row("a", "other-mac", "theirs")}]"
        account.value = AccountState.SignedIn("me@x.y", "u1")
        dir.start()
        withTimeout(5_000) { while (dir.sessions.value.isEmpty()) delay(20) }
        val n = requests.size
        account.value = AccountState.SignedOut
        withTimeout(5_000) { while (dir.sessions.value.isNotEmpty()) delay(20) }
        delay(500)
        assertEquals(n, requests.size)
    }

    @Test
    fun `tokenOf reads the t parameter`() {
        assertEquals("abc-_1", AccountSessionDirectory.tokenOf("https://h.example/?t=abc-_1#k=zzz"))
        assertNull(AccountSessionDirectory.tokenOf("https://h.example/"))
    }
}
