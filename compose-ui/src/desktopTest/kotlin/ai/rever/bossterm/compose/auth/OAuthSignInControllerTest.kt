package ai.rever.bossterm.compose.auth

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decisions [BossAccountManager] delegates for Google / Apple sign-in, with a fake browser,
 * network and session store. The window must never be left on a sign-in that no longer exists,
 * or on "Verifying" once the exchange is over.
 */
class OAuthSignInControllerTest {
    private val code = "7f1c2a9e-4b1d-4a57-9d2e-3b8f0c6a1e22"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var state: AccountState = AccountState.SignedOut
    private val opened: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val adopted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val signedIn: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** When set, the browser open waits here, so a test can press again while it runs. */
    @Volatile private var openGate: CompletableDeferred<Unit>? = null

    /** When set, the exchange waits here after it starts. */
    @Volatile private var exchangeGate: CompletableDeferred<Unit>? = null
    private val exchangeEntered = CompletableDeferred<Unit>()
    @Volatile private var exchangeResult: () -> Pair<Int, String> = {
        200 to """{"access_token":"at","refresh_token":"rt","user":{"id":"u1","email":"me@example.com"}}"""
    }
    @Volatile private var adoptFailure: Exception? = null

    private val controller = OAuthSignInController(
        attempts = OAuthAttempts(),
        scope = scope,
        supabaseUrl = { "https://api.risaboss.com" },
        state = { state },
        setState = { state = it },
        openBrowser = { provider, url ->
            opened += Thread.currentThread().name to "${provider.id} $url"
            openGate?.let { runBlocking { it.await() } }
            true
        },
        exchange = { _, _ ->
            exchangeEntered.complete(Unit)
            exchangeGate?.await()
            exchangeResult()
        },
        adoptSession = { session ->
            adoptFailure?.let { throw it }
            adopted += session.accessToken
            state = AccountState.SignedIn("me@example.com", "u1")
            "me@example.com"
        },
        onSignedIn = { signedIn += it },
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun codeLink(value: String = code) = "bossterm://auth/callback?code=$value"

    private val errorLink = "bossterm://auth/callback?error=access_denied&error_description=User+cancelled"

    /** Poll [condition] for up to five seconds; the controller works on its own scope. */
    private fun eventually(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun `the browser opens off the calling thread`() {
        val caller = Thread.currentThread().name
        controller.start(OAuthProvider.GOOGLE)
        assertIs<AccountState.OAuthPending>(state)
        eventually { opened.size == 1 }
        assertNotEquals(caller, opened.single().first)
    }

    @Test
    fun `a second press while the browser is opening is ignored`() {
        openGate = CompletableDeferred()
        controller.start(OAuthProvider.GOOGLE)
        val first = assertIs<AccountState.OAuthPending>(state)
        controller.start(OAuthProvider.APPLE)
        controller.start(OAuthProvider.GOOGLE)

        assertEquals(first, state)
        checkNotNull(openGate).complete(Unit)
        eventually { opened.size == 1 }

        // Once the first press has finished, a new one is a deliberate restart.
        openGate = null
        eventually {
            controller.start(OAuthProvider.APPLE)
            (state as? AccountState.OAuthPending)?.provider == OAuthProvider.APPLE
        }
    }

    @Test
    fun `a forged error shows a notice and the real callback still signs in`() {
        controller.start(OAuthProvider.APPLE)
        controller.handleCallback(errorLink)
        assertEquals("Apple sign-in was cancelled.", assertIs<AccountState.OAuthPending>(state).notice)

        controller.handleCallback(codeLink())
        eventually { signedIn.isNotEmpty() }
        assertEquals(listOf("at"), adopted)
        assertIs<AccountState.SignedIn>(state)
    }

    @Test
    fun `a refused exchange returns to the pending section, and reopening clears the notice`() {
        controller.start(OAuthProvider.GOOGLE)
        exchangeResult = { 400 to """{"error":"invalid_grant"}""" }
        controller.handleCallback(codeLink())

        eventually { (state as? AccountState.OAuthPending)?.notice != null }
        assertEquals(
            "That Google sign-in expired or was already used. Please try again.",
            (state as AccountState.OAuthPending).notice,
        )
        controller.reopen()
        assertNull(assertIs<AccountState.OAuthPending>(state).notice)
    }

    @Test
    fun `cancelling during the exchange discards the session and stays signed out`() {
        controller.start(OAuthProvider.GOOGLE)
        exchangeGate = CompletableDeferred()
        controller.handleCallback(codeLink())
        runBlocking { withTimeout(5_000) { exchangeEntered.await() } }
        assertIs<AccountState.Verifying>(state)

        // The pending section is gone while verifying, so the Sign In window's reset is the cancel.
        controller.cancel()
        state = AccountState.SignedOut
        checkNotNull(exchangeGate).complete(Unit)

        runBlocking { delay(200) }
        assertTrue(adopted.isEmpty())
        assertTrue(signedIn.isEmpty())
        assertIs<AccountState.SignedOut>(state)
    }

    @Test
    fun `an exchange whose coroutine is cancelled does not leave the window on Verifying`() {
        controller.start(OAuthProvider.GOOGLE)
        exchangeGate = CompletableDeferred()
        controller.handleCallback(codeLink())
        runBlocking { withTimeout(5_000) { exchangeEntered.await() } }

        scope.cancel()

        eventually { state is AccountState.OAuthPending }
        assertNull((state as AccountState.OAuthPending).notice)
    }

    @Test
    fun `a callback on a cancelled scope does not leave the window on Verifying`() {
        controller.start(OAuthProvider.GOOGLE)
        eventually { opened.size == 1 }
        exchangeGate = CompletableDeferred()
        scope.cancel()

        controller.handleCallback(codeLink())

        eventually { state is AccountState.OAuthPending }
        // Not wedged as "exchanging": the next callback is claimed rather than refused as busy.
        exchangeGate = null
        controller.handleCallback(errorLink)
        assertEquals("Google sign-in was cancelled.", (state as AccountState.OAuthPending).notice)
    }

    @Test
    fun `a start on a cancelled scope does not lock out later presses`() {
        scope.cancel()
        controller.start(OAuthProvider.GOOGLE)
        eventually { opened.size == 1 }

        controller.start(OAuthProvider.APPLE)
        assertEquals(OAuthProvider.APPLE, assertIs<AccountState.OAuthPending>(state).provider)
    }

    @Test
    fun `a session that cannot be saved ends in an error, not on Verifying`() {
        controller.start(OAuthProvider.APPLE)
        adoptFailure = IllegalStateException("disk full")
        controller.handleCallback(codeLink())

        eventually { state is AccountState.Error }
        assertEquals("Apple sign-in failed. Please try again.", (state as AccountState.Error).message)
        assertTrue(signedIn.isEmpty())
    }

    @Test
    fun `a second callback while one is exchanging is ignored`() {
        controller.start(OAuthProvider.GOOGLE)
        exchangeGate = CompletableDeferred()
        controller.handleCallback(codeLink())
        runBlocking { withTimeout(5_000) { exchangeEntered.await() } }

        controller.handleCallback(codeLink("second-code"))
        controller.handleCallback(errorLink)
        assertIs<AccountState.Verifying>(state)

        checkNotNull(exchangeGate).complete(Unit)
        eventually { signedIn.isNotEmpty() }
        assertEquals(listOf("at"), adopted)
    }

    @Test
    fun `a callback with nothing pending changes nothing`() {
        controller.handleCallback(codeLink())
        controller.handleCallback(errorLink)
        assertIs<AccountState.SignedOut>(state)
    }
}
