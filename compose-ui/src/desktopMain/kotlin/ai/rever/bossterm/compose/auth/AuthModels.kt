package ai.rever.bossterm.compose.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

// ---- GoTrue wire types (lenient — fields beyond what we read are ignored) ----

@Serializable
internal data class OtpRequest(
    val email: String,
    @SerialName("create_user") val createUser: Boolean = true,
)

@Serializable
internal data class VerifyRequest(
    /** GoTrue email_action_type, passed through VERBATIM from the deep link — a brand-new
     *  user's first magic link arrives as `signup`, not `magiclink`. */
    val type: String,
    @SerialName("token_hash") val tokenHash: String,
)

@Serializable
internal data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
internal data class AuthUser(
    val id: String = "",
    val email: String = "",
)

@Serializable
internal data class SessionResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val user: AuthUser? = null,
)

/** GoTrue error bodies vary by version — read every spelling of the human-readable text, all
 *  optional. (The machine `error_code` is intentionally not modelled: it's not user-facing and
 *  unknown keys are ignored during decode.) */
@Serializable
internal data class GoTrueError(
    val msg: String? = null,
    val message: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
) {
    fun text(): String? = errorDescription ?: msg ?: message
}

// ---- Persisted session (~/.bossterm/auth.json) ----

@Serializable
data class StoredAuth(
    val accessToken: String,
    val refreshToken: String,
    /** Absolute expiry (epoch seconds), computed from expires_in at issue time. */
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String,
)

// ---- Deep link parsing (pure; unit-tested) ----

/** The auth callback parsed out of `bossterm://auth/verify?token_hash=…&type=…`. */
data class AuthDeepLink(val tokenHash: String, val type: String)

/**
 * Parse a `bossterm://auth/verify` deep link, or null when [raw] isn't one.
 * NOTE `URI("bossterm://auth/verify")` parses as host=`auth`, path=`/verify` —
 * the scheme's first segment is the authority, not part of the path.
 * Accepts `token_hash` (the contract) and `token` (the param name BossConsole's
 * current redirect function emits) interchangeably; `type` defaults to `magiclink`.
 */
fun parseAuthDeepLink(raw: String): AuthDeepLink? {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("bossterm", ignoreCase = true)) return null
    if (!uri.host.equals("auth", ignoreCase = true) || uri.path != "/verify") return null
    val params = (uri.rawQuery ?: return null)
        .split('&')
        .mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null
            else p.take(i) to decodeQueryComponent(p.substring(i + 1))
        }
        .toMap()
    val tokenHash = params["token_hash"]?.takeIf { it.isNotBlank() }
        ?: params["token"]?.takeIf { it.isNotBlank() }
        ?: return null
    return AuthDeepLink(tokenHash = tokenHash, type = params["type"]?.takeIf { it.isNotBlank() } ?: "magiclink")
}

/**
 * Lenient parse for the MANUAL "paste the sign-in link" fallback — a superset of
 * [parseAuthDeepLink] that pulls the token out of whatever the user pastes: the clean
 * `bossterm://auth/verify` deep link (the redirect page's "Open BossTerm" link), the
 * branded email's redirect URL, or the raw Supabase confirmation URL — percent-encoded
 * or not. Returns null when no token is found.
 *
 * Like BossConsole's manual path, this is deliberate substring extraction rather than
 * strict URI parsing: the email link's `?url=<ConfirmationURL>` embeds `token=…&type=…`
 * and GoTrue's text/template emits it UNencoded, so the params sit at the top level —
 * scanning for `token=` finds them in every shape. (Keep [parseAuthDeepLink] strict for
 * the OS deep-link path; this is only for user-pasted input.)
 */
fun parseAuthInput(raw: String): AuthDeepLink? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    // Clean bossterm:// deep link first.
    parseAuthDeepLink(trimmed)?.let { return it }
    // Else scan the raw text, then a once-decoded copy (covers a %26type%3D… email URL). The decode
    // keeps '+' literal so a base64 token isn't corrupted (see [decodeQueryComponent]).
    for (s in listOf(trimmed, decodeQueryComponent(trimmed))) {
        val token = paramValue(s, "token_hash") ?: paramValue(s, "token")
        if (token != null) return AuthDeepLink(tokenHash = token, type = paramValue(s, "type") ?: "magiclink")
    }
    return null
}

/**
 * Value of `<name>=…` up to the next `&`, whitespace, `#`, or end — decoded; null if absent/blank.
 * The `<name>=` must sit at a real parameter boundary (start-of-string, or after `?`/`&`/`#`/whitespace)
 * so `paramValue(s, "token")` can't latch onto the `token=` *inside* `access_token=`/`refresh_token=`
 * — e.g. an implicit-flow callback fragment `#access_token=…&refresh_token=…&token=…` would otherwise
 * yield the access token. Scans every occurrence and takes the first boundary-anchored one.
 */
private fun paramValue(s: String, name: String): String? {
    val key = "$name="
    var from = 0
    while (from <= s.length) {
        val at = s.indexOf(key, from)
        if (at < 0) return null
        val prev = if (at == 0) null else s[at - 1]
        if (prev == null || prev == '?' || prev == '&' || prev == '#' || prev.isWhitespace()) {
            val start = at + key.length
            var end = start
            // Stop at any query/fragment delimiter. '?' too: a nested '?' never appears inside a
            // real token (hex/base64url), so treating it as a boundary can only trim junk.
            while (end < s.length && s[end] != '&' && s[end] != '?' && s[end] != '#' && !s[end].isWhitespace()) end++
            val v = s.substring(start, end)
            return if (v.isBlank()) null else decodeQueryComponent(v)
        }
        from = at + 1
    }
    return null
}

/**
 * Decode percent-escapes (%XX) in a URI query-component value while keeping `+` LITERAL.
 * [URLDecoder.decode] applies `application/x-www-form-urlencoded` rules and turns `+` into a space —
 * wrong for a generic URI query (RFC 3986) and, crucially, for auth tokens: a standard-base64
 * `token_hash` can contain `+`, which a space would silently corrupt. We pre-escape literal `+` to
 * `%2B` so the decoder leaves it intact (a real `%2B` is unaffected), and fall back to the raw
 * string on a malformed escape. Tokens never legitimately contain a space, so nothing is lost.
 */
private fun decodeQueryComponent(s: String): String =
    runCatching { URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8) }.getOrDefault(s)

// ---- Error mapping (pure; unit-tested) ----

/** Map a GoTrue failure to the user-facing message shown in the sign-in window. */
fun mapAuthError(status: Int, bodyText: String?, phase: AuthPhase): String = when {
    status == 429 -> "Please wait a minute before requesting another link."
    phase == AuthPhase.VERIFY && (status == 401 || status == 403) ->
        "That link has expired or was already used. Request a new one."
    else -> bodyText?.takeIf { it.isNotBlank() }
        ?: when (phase) {
            AuthPhase.SEND -> "Couldn't send the sign-in link. Check your connection and try again."
            AuthPhase.VERIFY -> "Couldn't verify the sign-in link. Request a new one."
        }
}

enum class AuthPhase { SEND, VERIFY }
