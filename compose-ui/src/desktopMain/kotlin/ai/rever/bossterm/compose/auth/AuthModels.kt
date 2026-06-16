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

/** GoTrue error bodies vary by version — read every spelling, all optional. */
@Serializable
internal data class GoTrueError(
    val msg: String? = null,
    val message: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
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
            else p.take(i) to URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8)
        }
        .toMap()
    val tokenHash = params["token_hash"]?.takeIf { it.isNotBlank() }
        ?: params["token"]?.takeIf { it.isNotBlank() }
        ?: return null
    return AuthDeepLink(tokenHash = tokenHash, type = params["type"]?.takeIf { it.isNotBlank() } ?: "magiclink")
}

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
