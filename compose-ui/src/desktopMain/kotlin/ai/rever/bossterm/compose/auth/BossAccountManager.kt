package ai.rever.bossterm.compose.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * BossTerm's account session against BossConsole's Supabase backend (one shared user
 * pool) — magic-link sign-in over plain GoTrue REST, no Supabase SDK.
 *
 * Flow: [sendMagicLink] → GoTrue emails a link → the link's redirect step opens
 * `bossterm://auth/verify?token_hash=…&type=…` → [handleAuthDeepLink] redeems the
 * single-use token for a session → persisted via [AuthStorage], surfaced on [state].
 * On startup [state] restores from disk, refreshing the access token when stale.
 *
 * Until the backend ticket (risa-labs-inc/BossConsole#787) ships, the email is BOSS
 * Console-branded and its link opens BossConsole — the client side here is complete
 * and testable by opening the deep link manually.
 */
object BossAccountManager {

    sealed class AccountState {
        object SignedOut : AccountState()
        /** Magic link sent; waiting for the user to open it. */
        data class EmailSent(val email: String) : AccountState()
        /** Deep link received; token exchange in flight. */
        object Verifying : AccountState()
        data class SignedIn(val email: String, val userId: String) : AccountState()
        data class Error(val message: String) : AccountState()
    }

    private val log = LoggerFactory.getLogger(BossAccountManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // A stuck request must not strand the UI on "Signing you in…" / "Check your email"
    // forever — bound it so a hung server resolves to the mapAuthError(0, …) path. Mirrors
    // DesktopUpdateService's API client timeouts.
    private val http by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        }
    }

    private val _state = MutableStateFlow<AccountState>(AccountState.SignedOut)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    // Emits the email on a COMPLETED interactive sign-in (deep-link verify) only — not on
    // the silent session restore at startup — so the UI can toast exactly once.
    private val _signInEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val signInEvents: SharedFlow<String> = _signInEvents.asSharedFlow()

    init {
        scope.launch { restoreSession() }
    }

    /**
     * Email the user a sign-in link. Drives [state] to EmailSent / Error.
     * @return false when the email failed local validation and NO request was made —
     *   the UI must not start its resend cooldown then.
     */
    fun sendMagicLink(rawEmail: String): Boolean {
        val email = rawEmail.trim().lowercase()
        if (!email.matches(Regex("""[^@\s]+@[^@\s]+\.[^@\s]+"""))) {
            _state.value = AccountState.Error("Enter a valid email address.")
            return false
        }
        scope.launch {
            try {
                val redirect = URLEncoder.encode(SupabaseAuthConfig.REDIRECT_URI, StandardCharsets.UTF_8)
                val resp = gotrue("otp?redirect_to=$redirect", json.encodeToString(OtpRequest.serializer(), OtpRequest(email)))
                if (resp.status.value in 200..299) {
                    _state.value = AccountState.EmailSent(email)
                } else {
                    _state.value = AccountState.Error(errorFrom(resp, AuthPhase.SEND))
                }
            } catch (e: Exception) {
                log.warn("Magic-link send failed: {}", e.message)
                _state.value = AccountState.Error(mapAuthError(0, null, AuthPhase.SEND))
            }
        }
        return true
    }

    /**
     * Redeem an auth deep link (`bossterm://auth/verify?token_hash=…&type=…`).
     * Safe to call with any URI string — non-auth links are ignored. Runs async
     * (deep links arrive on the AWT event thread); returns immediately.
     */
    fun handleAuthDeepLink(raw: String) {
        val link = parseAuthDeepLink(raw) ?: return
        _state.value = AccountState.Verifying
        scope.launch { verify(link.tokenHash, link.type) }
    }

    /**
     * Manual "paste the sign-in link" fallback (Sign In dialog): redeem whatever the user pastes —
     * the bossterm:// deep link, the branded email's redirect URL, or the raw confirmation URL.
     * Unlike [handleAuthDeepLink] (which silently ignores non-auth URIs from the OS), this surfaces
     * a friendly error when no token can be found.
     */
    fun verifyPastedLink(raw: String) {
        val link = parseAuthInput(raw)
        if (link == null) {
            _state.value = AccountState.Error(
                "Couldn't find a sign-in token in that link — paste the whole link from the email."
            )
            return
        }
        _state.value = AccountState.Verifying
        scope.launch { verify(link.tokenHash, link.type) }
    }

    /** POST /verify and adopt the session, or surface an error. Tokens are single-use: a 401/403
     *  is terminal for this link and never retried. */
    private suspend fun verify(tokenHash: String, type: String) {
        try {
            val resp = gotrue("verify", json.encodeToString(VerifyRequest.serializer(), VerifyRequest(type, tokenHash)))
            if (resp.status.value in 200..299) {
                val session = json.decodeFromString<SessionResponse>(resp.bodyAsText())
                val email = adoptSession(session)
                if (email.isNotBlank()) _signInEvents.tryEmit(email)
            } else {
                _state.value = AccountState.Error(errorFrom(resp, AuthPhase.VERIFY))
            }
        } catch (e: Exception) {
            log.warn("Magic-link verify failed: {}", e.message)
            _state.value = AccountState.Error(mapAuthError(0, null, AuthPhase.VERIFY))
        }
    }

    /** Sign out: flip state immediately (called from a Compose onClick), then do the disk IO and
     *  best-effort server revoke off the UI thread. */
    fun signOut() {
        _state.value = AccountState.SignedOut
        scope.launch {
            val stored = AuthStorage.load()
            AuthStorage.clear()
            if (stored != null) runCatching {
                http.post("${SupabaseAuthConfig.url}/auth/v1/logout") {
                    header("apikey", SupabaseAuthConfig.anonKey)
                    header("Authorization", "Bearer ${stored.accessToken}")
                }
            }
        }
    }

    /** Back out of EmailSent / Error (e.g. "Use a different email"). No-op when signed in. */
    fun reset() {
        if (_state.value !is AccountState.SignedIn) _state.value = AccountState.SignedOut
    }

    // ---- internals ----

    private suspend fun restoreSession() {
        val stored = AuthStorage.load() ?: return
        if (stored.expiresAtEpochSec > Instant.now().epochSecond + 60) {
            _state.value = AccountState.SignedIn(stored.email, stored.userId)
            return
        }
        // Stale access token → rotate via the refresh token; failure = silent sign-out
        // (no startup error dialog for an expired session).
        try {
            val resp = gotrue(
                "token?grant_type=refresh_token",
                json.encodeToString(RefreshRequest.serializer(), RefreshRequest(stored.refreshToken))
            )
            if (resp.status.value in 200..299) {
                val session = json.decodeFromString<SessionResponse>(resp.bodyAsText())
                // Refresh responses may omit the user — keep the stored identity then.
                adoptSession(session, fallbackEmail = stored.email, fallbackUserId = stored.userId)
            } else {
                log.info("Stored session no longer refreshable; signing out")
                AuthStorage.clear()
                _state.value = AccountState.SignedOut
            }
        } catch (e: Exception) {
            // Offline at startup: keep the stored identity (UI shows signed-in) even though the
            // access token may be expired. Nothing consumes the token yet, so this is harmless
            // today. NOTE: the first feature to call an authenticated endpoint with this token
            // MUST handle a 401 by refreshing (or signing out) — there is no refresh-on-401 here.
            log.warn("Session refresh unavailable ({}); keeping cached identity", e.message)
            _state.value = AccountState.SignedIn(stored.email, stored.userId)
        }
    }

    /** @return the adopted account email, so callers can emit it without re-reading the shared
     *  [state] (which a concurrent transition on this multi-threaded scope could change). */
    private fun adoptSession(session: SessionResponse, fallbackEmail: String = "", fallbackUserId: String = ""): String {
        val email = session.user?.email?.takeIf { it.isNotBlank() } ?: fallbackEmail
        val userId = session.user?.id?.takeIf { it.isNotBlank() } ?: fallbackUserId
        AuthStorage.save(
            StoredAuth(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                expiresAtEpochSec = Instant.now().epochSecond + session.expiresIn,
                userId = userId,
                email = email,
            )
        )
        _state.value = AccountState.SignedIn(email, userId)
        return email
    }

    private suspend fun gotrue(pathAndQuery: String, body: String): HttpResponse =
        http.post("${SupabaseAuthConfig.url}/auth/v1/$pathAndQuery") {
            header("apikey", SupabaseAuthConfig.anonKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun errorFrom(resp: HttpResponse, phase: AuthPhase): String {
        val text = runCatching { json.decodeFromString<GoTrueError>(resp.bodyAsText()).text() }.getOrNull()
        return mapAuthError(resp.status.value, text, phase)
    }
}
