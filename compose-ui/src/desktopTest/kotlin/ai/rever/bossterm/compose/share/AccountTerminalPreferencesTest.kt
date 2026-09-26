package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun `synchronous reset rejects an in-flight response even after returning to the same owner`() = runBlocking {
        val original = AccountState.SignedIn("a@test", "a")
        val account = MutableStateFlow<AccountState>(original)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        var offline = false
        val service = AccountTerminalPreferences(account, { _, _ ->
            check(!offline)
            if (++calls > 1) { started.complete(Unit); finish.await() }
            """{"unfocused_mode":"preview","unfocused_fps":30,"revision":1}"""
        }, "https://example.test/settings")
        service.refresh()
        assertEquals(30, service.preferences.value.unfocused_fps)
        val refresh = launch { service.refresh() }
        started.await()
        try {
            service.resetAccount()
            assertEquals(TerminalViewingPreferences(), service.preferences.value)
            account.value = AccountState.SignedOut
            account.value = original
        } finally { finish.complete(Unit) }
        refresh.join()
        assertEquals(TerminalViewingPreferences(), service.preferences.value)
        offline = true
        service.refresh()
        assertEquals(TerminalViewingPreferences(), service.preferences.value, "old response must not repopulate the cleared cache")
    }

    @Test fun `signed out refresh clears displayed preferences without waiting for an old RPC`() = runBlocking {
        val account = MutableStateFlow<AccountState>(AccountState.SignedIn("a@test", "a"))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val service = AccountTerminalPreferences(account, { _, _ ->
            if (++calls > 1) { started.complete(Unit); finish.await() }
            """{"unfocused_mode":"preview","unfocused_fps":10,"revision":1}"""
        }, "https://example.test/settings")
        service.refresh()
        val refresh = launch { service.refresh() }
        started.await()
        try {
            account.value = AccountState.SignedOut
            withTimeout(1000) { service.refresh() }
            assertEquals(TerminalViewingPreferences(), service.preferences.value)
        } finally { finish.complete(Unit) }
        refresh.join()
        assertEquals(TerminalViewingPreferences(), service.preferences.value)
    }

    @Test fun `stop invalidates an outstanding settings handoff even for the same owner`() = runBlocking {
        val account = MutableStateFlow<AccountState>(AccountState.SignedIn("a@test", "a"))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val service = AccountTerminalPreferences(account, { _, function ->
            if (function == "mint_user_settings_handoff") {
                started.complete(Unit); finish.await()
                """{"token":"${"a".repeat(43)}"}"""
            } else """{"unfocused_mode":"preview","unfocused_fps":10,"revision":1}"""
        }, "https://example.test/settings")
        service.refresh()
        val handoff = async { runCatching { service.settingsUrl() } }
        started.await()
        try {
            service.stop()
            assertEquals(TerminalViewingPreferences(), service.preferences.value)
        } finally { finish.complete(Unit) }
        assertEquals("Account changed", handoff.await().exceptionOrNull()?.message)
    }
}
