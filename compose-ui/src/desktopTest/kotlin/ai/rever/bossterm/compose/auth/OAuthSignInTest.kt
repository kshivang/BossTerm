package ai.rever.bossterm.compose.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthSignInTest {
    private val code = "7f1c2a9e-4b1d-4a57-9d2e-3b8f0c6a1e22"

    @Test
    fun `reads the PKCE code from bossterm auth callback`() {
        val callback = parseOAuthCallback("bossterm://auth/callback?code=$code")
        assertEquals(code, callback?.code)
        assertNull(callback?.error)
    }

    @Test
    fun `reads a provider error with its decoded description`() {
        val callback = parseOAuthCallback("bossterm://auth/callback?error=access_denied&error_description=User+cancelled%20it")
        assertEquals("access_denied", callback?.error)
        assertEquals("User cancelled it", callback?.errorDescription)
        assertNull(callback?.code)
    }

    @Test
    fun `accepts GoTrue's error redirect, which repeats the error in the query and the fragment`() {
        val callback = parseOAuthCallback(
            "bossterm://auth/callback?error=access_denied&error_code=user_cancelled&error_description=Query+text" +
                "#error=access_denied&error_code=user_cancelled&error_description=Fragment+text&sb",
        )
        assertEquals("access_denied", callback?.error)
        assertEquals("Query text", callback?.errorDescription)
    }

    @Test
    fun `a fragment-only error takes its description from the fragment`() {
        val callback = parseOAuthCallback("bossterm://auth/callback?error_description=planted#error=server_error&error_description=real")
        assertEquals("real", callback?.errorDescription)
    }

    @Test
    fun `a description is bounded and stripped of control characters, and a malformed escape refuses the link`() {
        val long = parseOAuthCallback("bossterm://auth/callback?error=server_error&error_description=${"a".repeat(1000)}")
        assertEquals(300, long?.errorDescription?.length)
        val controls = parseOAuthCallback("bossterm://auth/callback?error=server_error&error_description=line%0Aone%1B%5B2J")
        assertEquals("lineone[2J", controls?.errorDescription)
        // java.net.URI rejects the escape before anything is decoded.
        assertNull(parseOAuthCallback("bossterm://auth/callback?error=server_error&error_description=bad%ZZ"))
    }

    @Test
    fun `refuses ambiguous and lookalike callbacks`() {
        assertNull(parseOAuthCallback("bossterm://auth/callback"))
        assertNull(parseOAuthCallback("bossterm://auth/callback?code=$code&error=access_denied"))
        assertNull(parseOAuthCallback("bossterm://auth/callback?code=$code&code=other"))
        assertNull(parseOAuthCallback("bossterm://auth/callback#code=$code"))
        assertNull(parseOAuthCallback("bossterm://auth/verify?code=$code"))
        assertNull(parseOAuthCallback("bossterm://AUTH/callback?code=$code"))
        assertNull(parseOAuthCallback("boss://auth/callback?code=$code"))
        assertNull(parseOAuthCallback("bossterm://auth/callback?code=a%26type%3Drecovery"))
        assertNull(parseOAuthCallback("bossterm://auth/callback?error=Not-A-Code"))
        assertNull(parseOAuthCallback("bossterm://auth/callback?error=access_denied#error=server_error"))
    }

    @Test
    fun `the magic-link parser still ignores OAuth callbacks and vice versa`() {
        assertNull(parseAuthDeepLink("bossterm://auth/callback?code=$code"))
        assertNull(parseOAuthCallback("bossterm://auth/verify?token_hash=abc&type=magiclink"))
    }

    @Test
    fun `toString never prints the code`() {
        assertFalse(parseOAuthCallback("bossterm://auth/callback?code=$code").toString().contains(code))
    }

    @Test
    fun `S256 challenge matches the RFC 7636 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifiers are 43 url-safe characters and fresh each time`() {
        val a = Pkce.verifier()
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(a))
        assertNotEquals(a, Pkce.verifier())
    }

    @Test
    fun `authorize URL carries the provider, encoded redirect and S256 challenge`() {
        assertEquals(
            "https://api.risaboss.com/auth/v1/authorize?provider=apple" +
                "&redirect_to=bossterm%3A%2F%2Fauth%2Fcallback&code_challenge=abc&code_challenge_method=s256",
            oauthAuthorizeUrl("https://api.risaboss.com/", OAuthProvider.APPLE, OAUTH_REDIRECT_URI, "abc"),
        )
    }

    @Test
    fun `Apple opens in Safari on macOS and nothing else picks a browser`() {
        assertEquals(listOf("open", "-a", "Safari", "u"), oauthBrowserCommand(OAuthProvider.APPLE, isMacOS = true, url = "u"))
        assertNull(oauthBrowserCommand(OAuthProvider.GOOGLE, isMacOS = true, url = "u"))
        assertNull(oauthBrowserCommand(OAuthProvider.APPLE, isMacOS = false, url = "u"))
    }

    @Test
    fun `an exchange claims the attempt without consuming it, and finish consumes it`() {
        val attempts = OAuthAttempts()
        val started = attempts.start(OAuthProvider.GOOGLE, "https://api.risaboss.com")
        assertTrue(started.authorizeUrl.contains("code_challenge=${Pkce.challenge(started.verifier)}"))

        val live = assertIs<OAuthAttempts.Claim.Live>(attempts.claim(forExchange = true))
        assertEquals(started.verifier, live.attempt.verifier)
        assertIs<OAuthAttempts.Claim.Busy>(attempts.claim(forExchange = true))

        assertTrue(attempts.finish(live.attempt))
        assertIs<OAuthAttempts.Claim.None>(attempts.claim(forExchange = true))
    }

    @Test
    fun `a failed exchange or a provider error leaves the attempt pending`() {
        val attempts = OAuthAttempts()
        attempts.start(OAuthProvider.APPLE, "https://api.risaboss.com")

        // An error callback does not mark anything.
        val errored = assertIs<OAuthAttempts.Claim.Live>(attempts.claim(forExchange = false))
        assertTrue(attempts.settle(errored.attempt))

        val forged = assertIs<OAuthAttempts.Claim.Live>(attempts.claim(forExchange = true))
        assertTrue(attempts.settle(forged.attempt))

        val real = assertIs<OAuthAttempts.Claim.Live>(attempts.claim(forExchange = true))
        assertTrue(attempts.finish(real.attempt))
    }

    @Test
    fun `nothing pending means a callback is ignored`() {
        assertIs<OAuthAttempts.Claim.None>(OAuthAttempts().claim(forExchange = true))
    }

    @Test
    fun `an attempt older than ten minutes is expired, and consumed`() {
        var now = 0L
        val attempts = OAuthAttempts { now }
        attempts.start(OAuthProvider.APPLE, "https://api.risaboss.com")
        now = OAuthAttempts.TIMEOUT_MS + 1
        assertEquals(OAuthProvider.APPLE, assertIs<OAuthAttempts.Claim.Expired>(attempts.claim(forExchange = true)).provider)
        assertIs<OAuthAttempts.Claim.None>(attempts.claim(forExchange = true))
    }

    @Test
    fun `the pending section's poll expires a stale attempt but never one mid-exchange`() {
        var now = 0L
        val attempts = OAuthAttempts { now }
        assertNull(attempts.expireIfStale())

        attempts.start(OAuthProvider.GOOGLE, "https://api.risaboss.com")
        now = OAuthAttempts.TIMEOUT_MS - 1
        assertNull(attempts.expireIfStale())
        now = OAuthAttempts.TIMEOUT_MS + 1
        assertEquals(OAuthProvider.GOOGLE, attempts.expireIfStale())
        assertNull(attempts.peek())

        now = 0L
        attempts.start(OAuthProvider.GOOGLE, "https://api.risaboss.com")
        val exchanging = assertIs<OAuthAttempts.Claim.Live>(attempts.claim(forExchange = true))
        now = OAuthAttempts.TIMEOUT_MS + 1
        assertNull(attempts.expireIfStale())
        assertTrue(attempts.finish(exchanging.attempt))
    }

    @Test
    fun `an exchange that returns after a cancel or a new start is not adopted`() {
        val attempts = OAuthAttempts()
        attempts.start(OAuthProvider.GOOGLE, "https://api.risaboss.com")
        val cancelled = assertIs<OAuthAttempts.Claim.Live>(attempts.claim(forExchange = true)).attempt
        attempts.cancel()
        assertFalse(attempts.finish(cancelled))

        attempts.start(OAuthProvider.GOOGLE, "https://api.risaboss.com")
        val replaced = assertIs<OAuthAttempts.Claim.Live>(attempts.claim(forExchange = true)).attempt
        val second = attempts.start(OAuthProvider.APPLE, "https://api.risaboss.com")
        assertFalse(attempts.finish(replaced))
        assertFalse(attempts.settle(replaced))
        assertEquals(second, attempts.peek())
    }

    @Test
    fun `cancel and a new start both drop the earlier attempt`() {
        val attempts = OAuthAttempts()
        attempts.start(OAuthProvider.GOOGLE, "https://api.risaboss.com")
        attempts.cancel()
        assertIs<OAuthAttempts.Claim.None>(attempts.claim(forExchange = true))

        val first = attempts.start(OAuthProvider.GOOGLE, "https://api.risaboss.com")
        val second = attempts.start(OAuthProvider.APPLE, "https://api.risaboss.com")
        val live = assertIs<OAuthAttempts.Claim.Live>(attempts.claim(forExchange = true))
        assertEquals(second.verifier, live.attempt.verifier)
        assertNotEquals(first.verifier, live.attempt.verifier)
    }

    @Test
    fun `error texts are readable`() {
        assertEquals("Apple sign-in was cancelled.", describeOAuthError(OAuthProvider.APPLE, "access_denied"))
        assertEquals(
            "That Google sign-in expired or was already used. Please try again.",
            describeOAuthExchangeFailure(OAuthProvider.GOOGLE, 400),
        )
    }
}
