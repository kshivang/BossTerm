package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class HostAccountSessionsTest {
    private class Host : HostAccountSessions {
        override val state = MutableStateFlow<AccountState>(AccountState.SignedIn("a@example.test", "a"))
        val writes = CopyOnWriteArrayList<String>()
        val deletes = CopyOnWriteArrayList<String>()
        var rows: suspend () -> String = { "[]" }
        override suspend fun upsert(userId: String, rowJson: String): Boolean { writes += rowJson; return true }
        override suspend fun delete(userId: String, shareId: String): Boolean { deletes += shareId; return true }
        override suspend fun list(userId: String, since: String) = rows()
    }

    @Test fun `failed host initialization can disable standalone auth before install`() {
        AccountSessionSource.disconnect()
        assertNotNull(AccountSessionSource.host)
        assertEquals(AccountState.SignedOut, AccountSessionSource.state.value)
        assertEquals("Sign in to BossConsole", AccountSessionSource.signInHint)
    }

    @Test fun `host publishing and cleanup never read standalone tokens`() = runBlocking {
        val host = Host()
        val tabs = MutableStateFlow(setOf("tab"))
        val enabled = MutableStateFlow(true)
        val publisher = AccountSessionPublisher(
            sharedTabIds = tabs, remoteUrl = MutableStateFlow(null), accountState = host.state,
            enabled = enabled, infoFor = { SessionShareManager.ShareInfo(
                tabId = it, url = "https://example.test/?t=view", token = "view",
                controlUrl = "https://example.test/?t=control", secure = true,
                accountUrl = "https://example.test/?t=account",
            ) }, sessionNameFor = { "shell" }, deviceName = { "BossConsole" },
            accessToken = { error("Standalone token read") }, cachedAccessToken = { error("Standalone disk read") },
            restBaseUrl = "http://127.0.0.1:1", anonKey = "unused", appVersion = "test", host = host,
            heartbeatMs = 50,
        )
        try {
            publisher.start()
            withTimeout(5000) { while (host.writes.isEmpty()) delay(10) }
            enabled.value = false
            withTimeout(5000) { while (host.deletes.isEmpty()) delay(10) }
            assertEquals(AccountSessionPublisher.shareIdOf("view"), host.deletes.single())
        } finally { publisher.stop() }
    }

    @Test fun `host directory discards a response from the account that signed out`() = runBlocking {
        val host = Host()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        host.rows = { started.complete(Unit); release.await(); """[{"share_id":"old","device_name":"old","control_url":"https://example.test/?t=old"}]""" }
        val directory = AccountSessionDirectory(host.state, { error("Standalone token read") }, { false }, "http://127.0.0.1:1", "unused", host = host)
        try {
            val refresh = launch { directory.refresh() }
            started.await()
            host.state.value = AccountState.SignedOut
            release.complete(Unit)
            refresh.join()
            assertTrue(directory.sessions.value.isEmpty())
        } finally { directory.stop() }
    }

    @Test fun `host facade follows reinstallation and stays signed out on disposal`() = runBlocking {
        val first = Host()
        AccountSessionSource.install(first, this)
        val identity = AccountSessionSource.state
        val transport = assertNotNull(AccountSessionSource.host)
        try {
            assertEquals(first.state.value, identity.value)
            first.state.value = AccountState.SignedOut
            AccountSessionSource.refreshHostIdentity()
            assertEquals(AccountState.SignedOut, identity.value, "cleanup sees sign-out before the async collector runs")
            AccountSessionSource.disconnect()
            assertEquals(AccountState.SignedOut, identity.value)
            assertFalse(transport.upsert("a", "{}"))
            val second = Host().apply { state.value = AccountState.SignedIn("b@example.test", "b") }
            AccountSessionSource.install(second, this)
            assertSame(identity, AccountSessionSource.state)
            assertEquals(second.state.value, identity.value)
            assertTrue(transport.upsert("b", "{}"))
            assertTrue(first.writes.isEmpty())
            assertEquals(listOf("{}"), second.writes)
        } finally { AccountSessionSource.disconnect() }
    }
}
