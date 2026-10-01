package ai.rever.bossterm.compose.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google and Apple sign-in: a PKCE authorization-code flow in the system browser that returns
 * through `bossterm://auth/callback`, the same design as BossConsole's (see its
 * `docs/OAUTH_SIGN_IN_SETUP.md`). Everything here is pure and unit-tested; the network half is
 * [BossAccountManager.startOAuth] / [BossAccountManager.handleOAuthCallback].
 *
 * `bossterm://` is registered with the OS, so any page can open a callback link. A callback is
 * acted on only while a sign-in this process started is pending ([OAuthAttempts]) and within
 * [OAuthAttempts.TIMEOUT_MS]; and the code is useless without the verifier, which lives only in
 * this process's memory. Because anyone can send one, a callback that fails does not end the
 * sign-in: only a successful exchange, a cancel, a new start or expiry does. Codes and verifiers
 * are never logged.
 */
enum class OAuthProvider(val id: String, val displayName: String) {
    GOOGLE("google", "Google"),
    APPLE("apple", "Apple"),
}

/** `redirect_to` for the Google / Apple flow. Must be in the Supabase redirect allow-list. */
const val OAUTH_REDIRECT_URI = "bossterm://auth/callback"

/**
 * `bossterm://auth/callback`, Supabase's return from a Google or Apple sign-in. Exactly one of
 * [code] and [error] is set.
 */
data class OAuthCallback(val code: String?, val error: String?, val errorDescription: String?) {
    override fun toString(): String = "OAuthCallback(code=${if (code != null) "<redacted>" else "null"}, error=$error)"
}

private val AUTH_CODE = Regex("[A-Za-z0-9._~-]{1,512}")
private val ERROR_CODE = Regex("[a-z_]{1,64}")
private const val MAX_DESCRIPTION = 300

/**
 * Parse `bossterm://auth/callback?code=…` (or `?error=…`, query or fragment), or null when [raw]
 * is not exactly that shape. Strict like [parseAuthDeepLink]: the host and path must match, a
 * code lives in the query only, and a duplicated parameter or both a code and an error is an
 * ambiguous smuggle that refuses the link.
 *
 * GoTrue's error redirect writes `error`, `error_code` and `error_description` into BOTH the
 * query and the fragment, so the same error in both is one error; two that disagree are refused.
 * The description is read from the section the error was taken from.
 */
fun parseOAuthCallback(raw: String): OAuthCallback? {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("bossterm", ignoreCase = true)) return null
    if (uri.host != "auth" || uri.path != "/callback") return null
    val query = params(uri.rawQuery)
    val fragment = params(uri.rawFragment)
    if (fragment.containsKey("code")) return null
    if (listOf(query, fragment).any { p -> listOf("code", "error", "error_description").any { (p[it]?.size ?: 0) > 1 } }) return null
    val code = query["code"]?.first()
    val queryError = query["error"]?.first()
    val fragmentError = fragment["error"]?.first()
    val errors = listOfNotNull(queryError, fragmentError).distinct()
    return when {
        code != null && errors.isEmpty() -> code.takeIf { AUTH_CODE.matches(it) }?.let { OAuthCallback(it, null, null) }
        code == null && errors.size == 1 -> errors.single().takeIf { ERROR_CODE.matches(it) }?.let {
            val section = if (queryError != null) query else fragment
            OAuthCallback(null, it, section["error_description"]?.first()?.let(::decodeDescription))
        }
        else -> null
    }
}

/** A percent/plus-encoded error description as bounded printable text, or null if undecodable. */
private fun decodeDescription(raw: String): String? =
    runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrNull()
        ?.filter { c -> !c.isISOControl() }
        ?.take(MAX_DESCRIPTION)

private fun params(raw: String?): Map<String, List<String>> =
    if (raw.isNullOrEmpty()) emptyMap()
    else raw.split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }.groupBy({ it.first }, { it.second })

/** RFC 7636 PKCE: a 43-character verifier and its S256 challenge. */
object Pkce {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun verifier(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
}

/** GoTrue's authorize URL for [provider] with the PKCE [challenge]. */
fun oauthAuthorizeUrl(supabaseUrl: String, provider: OAuthProvider, redirectUri: String, challenge: String): String =
    "${supabaseUrl.trimEnd('/')}/auth/v1/authorize?provider=${provider.id}" +
        "&redirect_to=${URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)}" +
        "&code_challenge=$challenge&code_challenge_method=s256"

/**
 * The command that opens [provider]'s sign-in page in a specific browser, or null for the default.
 * Apple opens in Safari on macOS: only Safari offers the Mac's own Apple Account with Touch ID on
 * Apple's page, and the native Sign in with Apple sheet is limited by Apple to Mac App Store apps.
 */
fun oauthBrowserCommand(provider: OAuthProvider, isMacOS: Boolean, url: String): List<String>? =
    if (provider == OAuthProvider.APPLE && isMacOS) listOf("open", "-a", "Safari", url) else null

/** Readable text for a provider-reported [error]. */
fun describeOAuthError(provider: OAuthProvider, error: String): String = when (error) {
    "access_denied" -> "${provider.displayName} sign-in was cancelled."
    "temporarily_unavailable", "server_error" -> "${provider.displayName} sign-in is having trouble right now. Please try again in a minute."
    else -> "${provider.displayName} sign-in failed. Please try again."
}

/** Readable text for a failed code exchange with HTTP [status]. */
fun describeOAuthExchangeFailure(provider: OAuthProvider, status: Int): String = when (status) {
    400, 401, 403, 404 -> "That ${provider.displayName} sign-in expired or was already used. Please try again."
    429 -> "Too many attempts. Please wait a minute and try again."
    else -> "${provider.displayName} sign-in failed. Check your connection and try again."
}

/**
 * The single sign-in in flight. [start] replaces any earlier one (its verifier is gone, so its
 * callback can no longer be exchanged). A callback [claim]s it without consuming it, so a forged
 * callback that fails cannot end the user's own sign-in; only [finish] after a successful
 * exchange, [cancel], a new [start] or expiry does. One exchange runs at a time.
 *
 * Every attempt is a new object, so a caller holding one can tell whether it is still the one
 * pending: an exchange that returns after a cancel or a new start is not adopted.
 */
class OAuthAttempts(private val clock: () -> Long = System::currentTimeMillis) {
    class Attempt(val provider: OAuthProvider, val verifier: String, val authorizeUrl: String, val startedAtMs: Long) {
        /** A callback's code is being exchanged; further callbacks are refused until it settles. */
        var exchanging: Boolean = false
            internal set
    }

    sealed interface Claim {
        data class Live(val attempt: Attempt) : Claim
        data class Expired(val provider: OAuthProvider) : Claim
        /** A callback for this sign-in is already being exchanged. */
        object Busy : Claim
        object None : Claim
    }

    @Volatile private var current: Attempt? = null

    @Synchronized
    fun start(provider: OAuthProvider, supabaseUrl: String): Attempt {
        val verifier = Pkce.verifier()
        val url = oauthAuthorizeUrl(supabaseUrl, provider, OAUTH_REDIRECT_URI, Pkce.challenge(verifier))
        return Attempt(provider, verifier, url, clock()).also { current = it }
    }

    /**
     * The pending attempt for one callback. Only expiry consumes it; with [forExchange] the
     * attempt is marked as exchanging until [settle] or [finish].
     */
    @Synchronized
    fun claim(forExchange: Boolean): Claim {
        val attempt = current ?: return Claim.None
        return when {
            attempt.exchanging -> Claim.Busy
            isStale(attempt) -> {
                current = null
                Claim.Expired(attempt.provider)
            }
            else -> {
                if (forExchange) attempt.exchanging = true
                Claim.Live(attempt)
            }
        }
    }

    /** [attempt]'s exchange failed; true when it is still the one pending, so a notice belongs to it. */
    @Synchronized
    fun settle(attempt: Attempt): Boolean {
        attempt.exchanging = false
        return current === attempt
    }

    /** [attempt]'s exchange succeeded: consume it, true only when it was still the one pending. */
    @Synchronized
    fun finish(attempt: Attempt): Boolean {
        attempt.exchanging = false
        if (current !== attempt) return false
        current = null
        return true
    }

    /** End a pending attempt past [TIMEOUT_MS] with no exchange running; its provider, or null. */
    @Synchronized
    fun expireIfStale(): OAuthProvider? {
        val attempt = current?.takeIf { !it.exchanging && isStale(it) } ?: return null
        current = null
        return attempt.provider
    }

    /** The pending attempt without consuming it, for "Reopen browser". */
    fun peek(): Attempt? = current

    @Synchronized
    fun cancel() {
        current = null
    }

    private fun isStale(attempt: Attempt): Boolean = clock() - attempt.startedAtMs > TIMEOUT_MS

    companion object {
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}

@Serializable
internal data class PkceExchangeRequest(
    @SerialName("auth_code") val authCode: String,
    @SerialName("code_verifier") val codeVerifier: String,
)
