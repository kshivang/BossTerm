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
import kotlinx.serialization.json.long
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
        data class Ok(val clientSecret: String, val expiresAtMs: Long?) : MintResult()
        data object Unauthorized : MintResult()
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
        instructions: String,
        tools: JsonArray,
    ): MintResult {
        val body = buildJsonObject {
            putJsonObject("expires_after") {
                put("anchor", "created_at")
                put("seconds", 600)
            }
            putJsonObject("session") {
                put("type", "realtime")
                put("model", model)
                putJsonArray("output_modalities") { add("audio") }
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("turn_detection") { put("type", "semantic_vad") }
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
                resp.status.value in 200..299 -> parseMint(resp.bodyAsText())
                else -> {
                    // Log OpenAI's own explanation + request id: an HTTP code alone can't tell a
                    // bad request body from an account-side fault (a broken project 500s on every
                    // endpoint, including /v1/models), and the request id is what OpenAI support
                    // asks for. Host-side only — the viewer's message stays generic, since this
                    // can carry account detail and the caller is a remote guest.
                    log.warn(
                        "Voice session mint failed: HTTP {} (request-id {}) — {}",
                        resp.status.value,
                        resp.headers["x-request-id"] ?: "none",
                        errorDetail(resp.bodyAsText()),
                    )
                    MintResult.Failed("OpenAI returned HTTP ${resp.status.value}")
                }
            }
        }.getOrElse {
            log.warn("Voice session mint failed: {}", it.javaClass.simpleName)
            MintResult.Failed("Could not reach OpenAI (${it.javaClass.simpleName})")
        }
    }

    /** OpenAI's `error.message` if the body is its usual error envelope, else a clipped raw body. */
    private fun errorDetail(text: String): String {
        val message = runCatching {
            (json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)?.stringField("message")
        }.getOrNull()
        return message ?: text.take(300).replace('\n', ' ').ifBlank { "(empty body)" }
    }

    private fun parseMint(text: String): MintResult {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return MintResult.Failed("Unparseable mint response")
        val secret = root.stringField("value")
        // Beta-shaped fallback, in case the account is still routed to the old response shape.
            ?: (root["client_secret"] as? JsonObject)?.stringField("value")
            ?: return MintResult.Failed("Mint response had no client secret")
        val expiresAtSec = runCatching { root["expires_at"]?.jsonPrimitive?.long }.getOrNull()
        return MintResult.Ok(secret, expiresAtSec?.let { it * 1000 })
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
