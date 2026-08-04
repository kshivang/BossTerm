package ai.rever.bossterm.compose.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Mints ephemeral OpenAI Realtime client secrets for Boss Calling. This is the ONLY place the
 * user's API key is used; only the short-lived `ek_…` secret (scoped to one session with the
 * exact model/instructions/tools the host baked in) ever reaches the browser. The key is never
 * logged and never appears in error messages.
 */
internal class VoiceSessionBroker(
    /** Overridable for tests (point at a loopback stub server). */
    private val baseUrl: String = "https://api.openai.com",
    /** Null → the process-wide shared client (one per share would leak CIO threads). */
    private val httpOverride: HttpClient? = null,
) {

    private val http: HttpClient get() = httpOverride ?: sharedClient

    sealed class MintResult {
        data class Ok(val clientSecret: String) : MintResult()
        data object Unauthorized : MintResult()
        /** OpenAI's own 429, kept distinct so the viewer hears "wait and retry", not a status code. */
        data object RateLimited : MintResult()
        data class Failed(val message: String) : MintResult()
    }

    /**
     * `POST /v1/realtime/client_secrets` — the GA endpoint (the beta `/v1/realtime/sessions`
     * returned the secret under `client_secret.value`; GA returns it top-level as `value`).
     * Secrets are short-lived, so this is called immediately before the viewer connects.
     */
    suspend fun mint(
        apiKey: String,
        model: String,
        voice: String,
        /** See [VoiceTurnDetection]; the same value the in-app surface puts in session.update. */
        micSensitivity: String,
        instructions: String,
        tools: JsonArray,
    ): MintResult {
        val body = buildJsonObject {
            putJsonObject("expires_after") {
                put("anchor", "created_at")
                // 60s, not 600: this secret only has to survive the SDP handshake, which the viewer
                // starts immediately on receiving it. It is the one credential that leaves the host,
                // so the window in which a leaked (or replayed) one can open sessions billed to the
                // host's key should be the smallest that still works.
                put("seconds", 60)
            }
            putJsonObject("session") {
                put("type", "realtime")
                put("model", model)
                putJsonArray("output_modalities") { add("audio") }
                putJsonObject("audio") {
                    putJsonObject("input") {
                        put("turn_detection", VoiceTurnDetection.json(micSensitivity))
                    }
                    putJsonObject("output") { put("voice", voice) }
                }
                put("instructions", instructions)
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        return runCatching {
            val resp = http.post("$baseUrl/v1/realtime/client_secrets") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            when {
                resp.status == HttpStatusCode.Unauthorized || resp.status == HttpStatusCode.Forbidden -> {
                    log.warn("Voice session mint rejected ({})", resp.status.value)
                    MintResult.Unauthorized
                }
                // OpenAI's own rate limit reads the same to the caller as ours does, so it gets the
                // same "wait and retry" story rather than a bare status code they can't act on.
                resp.status == HttpStatusCode.TooManyRequests -> {
                    log.warn(
                        "Voice session mint rate-limited by OpenAI (request-id {})",
                        resp.headers["x-request-id"] ?: "none",
                    )
                    MintResult.RateLimited
                }
                resp.status.value in 200..299 -> parseMint(resp.bodyAsText())
                else -> {
                    // Log OpenAI's own explanation + request id: an HTTP code alone can't tell a
                    // bad request body from an account-side fault (a broken project 500s on every
                    // endpoint, including /v1/models), and the request id is what OpenAI support
                    // asks for. Host-side only — the viewer's message stays generic, since this
                    // can carry account detail and the caller is a remote guest.
                    log.warn(
                        "Voice session mint failed: HTTP {} (request-id {}) - {}",
                        resp.status.value,
                        resp.headers["x-request-id"] ?: "none",
                        errorDetail(resp.bodyAsText()),
                    )
                    MintResult.Failed("OpenAI returned HTTP ${resp.status.value}")
                }
            }
        }.getOrElse {
            // The class name stays HOST-side. It reaches the viewer verbatim through
            // VoiceError.message and voiceErrorText's default branch, and "the remote caller gets
            // only the status code" was the whole point of the careful 5xx handling above — a
            // SocketTimeoutException vs an SSLHandshakeException tells a stranger about the host's
            // network either way, and tells them nothing they can act on.
            log.warn("Voice session mint failed: {}", it.javaClass.simpleName)
            MintResult.Failed("Could not reach OpenAI")
        }
    }

    /** OpenAI's `error.message` if the body is its usual error envelope, else a clipped raw body. */
    internal fun errorDetail(text: String): String {
        val message = runCatching {
            (json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)?.stringField("message")
        }.getOrNull()
        return message ?: text.take(300).replace('\n', ' ').ifBlank { "(empty body)" }
    }

    internal fun parseMint(text: String): MintResult {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return MintResult.Failed("Unparseable mint response")
        val secret = root.stringField("value")
        // Beta-shaped fallback, in case the account is still routed to the old response shape.
            ?: (root["client_secret"] as? JsonObject)?.stringField("value")
            ?: return MintResult.Failed("Mint response had no client secret")
        // The secret's own expiry is deliberately not surfaced: it only has to survive the SDP
        // handshake, and a call legitimately outlives it, so exposing it would only mislead.
        return MintResult.Ok(secret)
    }

    private fun JsonObject.stringField(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.takeIf { it.isString }?.content }.getOrNull()

    private companion object {
        val log = LoggerFactory.getLogger(VoiceSessionBroker::class.java)
        val json = Json { ignoreUnknownKeys = true }

        /** One client for every broker — mints are rare and the client is stateless. */
        val sharedClient: HttpClient by lazy { defaultClient() }

        fun defaultClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    }
}
