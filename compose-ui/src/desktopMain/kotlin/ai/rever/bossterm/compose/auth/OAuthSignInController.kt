package ai.rever.bossterm.compose.auth

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Google / Apple half of [BossAccountManager]: starting a sign-in, and turning its
 * `bossterm://auth/callback` into a session. Every effect (the browser, the network, the saved
 * session, the account state) is injected, so the decisions can be tested without opening a
 * browser or touching `~/.bossterm/auth.json`.
 *
 * Every compound step (check the attempt, then change the state) runs under the [attempts]
 * monitor, so a cancel can never interleave with a notice or an adoption and leave the window
 * showing a sign-in that no longer exists.
 */
internal class OAuthSignInController(
    private val attempts: OAuthAttempts,
    private val scope: CoroutineScope,
    private val supabaseUrl: () -> String,
    private val state: () -> AccountState,
    private val setState: (AccountState) -> Unit,
    /** Open the sign-in page; false when nothing opened. Runs on [scope], never the UI thread. */
    private val openBrowser: (OAuthProvider, String) -> Boolean,
    /** POST the PKCE exchange; the HTTP status and body. */
    private val exchange: suspend (code: String, verifier: String) -> Pair<Int, String>,
    /** Save [SessionResponse] and publish SignedIn; the account's email. Throws when it cannot be saved. */
    private val adoptSession: (SessionResponse) -> String,
    private val onSignedIn: (email: String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(OAuthSignInController::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Set while a start is opening the browser, so a second press in that window is ignored. */
    private val opening = AtomicBoolean(false)

    /**
     * Start a Google or Apple sign-in. The pending state is shown at once and the browser opens
     * off the calling thread (`open -a Safari` is waited on for up to ten seconds). A press while
     * an earlier one is still opening the browser is ignored; a later one replaces the sign-in.
     */
    fun start(provider: OAuthProvider) {
        if (!opening.compareAndSet(false, true)) return
        val attempt = try {
            synchronized(attempts) {
                attempts.start(provider, supabaseUrl()).also {
                    setState(AccountState.OAuthPending(provider, it.authorizeUrl))
                }
            }
        } catch (e: Exception) {
            opening.set(false)
            throw e
        }
        log.info("OAuth sign-in started (provider={})", provider.id)
        scope.launch {
            try {
                if (!openBrowser(provider, attempt.authorizeUrl)) log.warn("Could not open a browser for the OAuth sign-in")
            } finally {
                opening.set(false)
            }
        }
    }

    /** Reopen the pending sign-in's page, clearing a shown notice. Off the calling thread. */
    fun reopen() {
        val attempt = synchronized(attempts) {
            val attempt = attempts.peek() ?: return
            val shown = state()
            if (shown is AccountState.OAuthPending && shown.authorizeUrl == attempt.authorizeUrl) {
                setState(shown.copy(notice = null))
            }
            attempt
        }
        scope.launch { openBrowser(attempt.provider, attempt.authorizeUrl) }
    }

    /** Abandon the pending sign-in; its callback is ignored if it still arrives, even mid-exchange. */
    fun cancel() {
        synchronized(attempts) {
            attempts.cancel()
            if (state() is AccountState.OAuthPending) setState(AccountState.SignedOut)
        }
    }

    /** End a pending sign-in whose callback never came. Polled by the pending section. */
    fun expireStale() {
        synchronized(attempts) {
            val provider = attempts.expireIfStale() ?: return
            setState(AccountState.Error(tookTooLong(provider)))
        }
    }

    /**
     * Finish the pending sign-in with a `bossterm://auth/callback` link. A callback with no sign-in
     * pending is ignored (any page can open a bossterm:// link). One that fails - a provider error,
     * a code that does not exchange - shows a notice and leaves the sign-in open, so a forged link
     * cannot end it; one exchange runs at a time, and its session is adopted only if the sign-in
     * was not cancelled or replaced meanwhile.
     */
    fun handleCallback(raw: String) {
        val callback = parseOAuthCallback(raw) ?: return
        val code = callback.code
        val attempt = synchronized(attempts) {
            when (val claim = attempts.claim(forExchange = code != null)) {
                is OAuthAttempts.Claim.None -> {
                    log.warn("OAuth callback ignored: no sign-in is pending")
                    return
                }
                is OAuthAttempts.Claim.Busy -> {
                    log.warn("OAuth callback ignored: one is already being exchanged")
                    return
                }
                is OAuthAttempts.Claim.Expired -> {
                    setState(AccountState.Error(tookTooLong(claim.provider)))
                    return
                }
                is OAuthAttempts.Claim.Live -> {
                    if (code != null) setState(AccountState.Verifying)
                    claim.attempt
                }
            }
        }
        if (callback.error != null) {
            log.warn("OAuth provider returned an error (provider={}, error={})", attempt.provider.id, callback.error)
            backToPending(attempt, describeOAuthError(attempt.provider, callback.error))
            return
        }
        if (code == null) return
        scope.launch { exchangeCode(attempt, code) }
    }

    private suspend fun exchangeCode(attempt: OAuthAttempts.Attempt, code: String) {
        val session = try {
            val (status, body) = exchange(code, attempt.verifier)
            if (status !in 200..299) {
                log.warn("OAuth code exchange refused ({})", status)
                backToPending(attempt, describeOAuthExchangeFailure(attempt.provider, status))
                return
            }
            json.decodeFromString<SessionResponse>(body)
        } catch (e: CancellationException) {
            backToPending(attempt, notice = null)
            throw e
        } catch (e: Exception) {
            log.warn("OAuth code exchange failed: {}", e.message)
            backToPending(attempt, describeOAuthExchangeFailure(attempt.provider, 0))
            return
        }
        adopt(attempt, session)
    }

    /** Adopt [session] if [attempt] is still pending. Once it is consumed the window must leave Verifying either way. */
    private fun adopt(attempt: OAuthAttempts.Attempt, session: SessionResponse) {
        val email = synchronized(attempts) {
            if (!attempts.finish(attempt)) {
                log.info("OAuth session discarded: that sign-in was cancelled or replaced")
                return
            }
            try {
                adoptSession(session)
            } catch (e: Exception) {
                log.warn("Could not save the OAuth session: {}", e.message)
                setState(AccountState.Error("${attempt.provider.displayName} sign-in failed. Please try again."))
                return
            }
        }
        log.info("OAuth sign-in completed (provider={})", attempt.provider.id)
        onSignedIn(email)
    }

    /** Back to waiting on [attempt], with [notice], unless it was cancelled or replaced meanwhile. */
    private fun backToPending(attempt: OAuthAttempts.Attempt, notice: String?) {
        synchronized(attempts) {
            if (attempts.settle(attempt)) setState(AccountState.OAuthPending(attempt.provider, attempt.authorizeUrl, notice))
        }
    }

    private fun tookTooLong(provider: OAuthProvider) = "That ${provider.displayName} sign-in took too long. Please try again."
}
