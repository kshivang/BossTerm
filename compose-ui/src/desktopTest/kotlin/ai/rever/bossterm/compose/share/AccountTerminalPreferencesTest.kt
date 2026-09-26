package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountTerminalPreferencesTest {
    @Test fun `cache never carries preferences into another account`() = runBlocking {
        val account = MutableStateFlow<AccountState>(AccountState.SignedIn("a@test", "a"))
        var offline = false
        val service = AccountTerminalPreferences(account, { _, _ ->
            check(!offline)
            """{"unfocused_mode":"preview","unfocused_fps":10,"revision":1}"""
        }, "https://example.test/settings")
        service.refresh()
        assertEquals("preview", service.preferences.value.unfocused_mode)
        offline = true
        account.value = AccountState.SignedIn("b@test", "b")
        service.refresh()
        assertEquals(TerminalViewingPreferences(), service.preferences.value)
        account.value = AccountState.SignedIn("a@test", "a")
        service.refresh()
        assertEquals(10, service.preferences.value.unfocused_fps)
        account.value = AccountState.SignedOut
        service.refresh()
        assertEquals(TerminalViewingPreferences(), service.preferences.value)
    }

    @Test fun `late response cannot overwrite settings after identity change`() = runBlocking {
        val account = MutableStateFlow<AccountState>(AccountState.SignedIn("a@test", "a"))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val service = AccountTerminalPreferences(account, { _, _ ->
            started.complete(Unit); finish.await()
            """{"unfocused_mode":"preview","unfocused_fps":30,"revision":1}"""
        }, "https://example.test/settings")
        val refresh = launch { service.refresh() }
        started.await()
        account.value = AccountState.SignedOut
        finish.complete(Unit)
        refresh.join()
        assertEquals(TerminalViewingPreferences(), service.preferences.value)
    }

    @Test fun `settings handoff rejects malformed bearer token`() = runBlocking {
        val account = MutableStateFlow<AccountState>(AccountState.SignedIn("a@test", "a"))
        val service = AccountTerminalPreferences(account, { _, _ -> """{"token":"bad&redirect=elsewhere"}""" }, "https://example.test/settings")
        assertFailsWith<IllegalArgumentException> { service.settingsUrl() }
        Unit
    }
}
