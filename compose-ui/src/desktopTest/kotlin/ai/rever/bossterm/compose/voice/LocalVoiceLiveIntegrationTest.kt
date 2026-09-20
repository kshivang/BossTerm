package ai.rever.bossterm.compose.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in evidence against a real speech-to-speech server.
 *
 * Ordinary CI skips this class because it requires a separately started, model-loaded server. Run
 * it with `BOSSTERM_LOCAL_VOICE_LIVE_URL=ws://127.0.0.1:8765/v1/realtime` and the focused Gradle
 * test selector. The endpoint deliberately has no API key, exercising the production JDK transport
 * path that omits Authorization for the local backend.
 */
class LocalVoiceLiveIntegrationTest {

    @Test
    fun `real JDK transport establishes a keyless session and completes a tool round trip`() = runBlocking {
        val url = System.getenv(LIVE_URL_ENV)?.trim().orEmpty()
        if (url.isEmpty()) return@runBlocking

        val transport = JdkRealtimeTransport()
        val sessionUpdated = CompletableDeferred<Unit>()
        val toolCall = CompletableDeferred<Triple<String, String, String>>()
        val followupDone = CompletableDeferred<Unit>()
        var outputSent = false

        try {
            transport.connect(
                VoiceEndpoint(url, VoiceEndpointResolver.LOCAL_DEFAULT_MODEL, null, VoiceBackend.LOCAL),
                events = { text ->
                    val event = Json.parseToJsonElement(text).jsonObject
                    when (event["type"]?.jsonPrimitive?.content) {
                        "session.updated" -> sessionUpdated.complete(Unit)
                        "response.function_call_arguments.done" -> toolCall.complete(
                            Triple(
                                event.getValue("call_id").jsonPrimitive.content,
                                event.getValue("name").jsonPrimitive.content,
                                event.getValue("arguments").jsonPrimitive.content,
                            ),
                        )
                        "response.done" -> if (outputSent) followupDone.complete(Unit)
                    }
                },
                onClosed = { reason ->
                    val failure = AssertionError("local voice socket closed: ${reason ?: "no reason"}")
                    if (!sessionUpdated.isCompleted) sessionUpdated.completeExceptionally(failure)
                    if (!toolCall.isCompleted) toolCall.completeExceptionally(failure)
                    if (!followupDone.isCompleted) followupDone.completeExceptionally(failure)
                },
            )

            assertTrue(transport.send(sessionUpdate()))
            withTimeout(LIVE_TIMEOUT_MS) { sessionUpdated.await() }
            assertTrue(transport.send("""{"type":"response.create"}"""))

            val (callId, name, arguments) = withTimeout(LIVE_TIMEOUT_MS) { toolCall.await() }
            assertEquals("validation_echo", name)
            assertTrue(arguments.contains("hello"), "tool arguments were $arguments")

            outputSent = true
            assertTrue(
                transport.send(
                    buildJsonObject {
                        put("type", "conversation.item.create")
                        putJsonObject("item") {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", "TOOL_OUTPUT_CONSUMED")
                        }
                    }.toString(),
                ),
            )
            assertTrue(transport.send("""{"type":"response.create"}"""))
            withTimeout(LIVE_TIMEOUT_MS) { followupDone.await() }
        } finally {
            transport.close()
        }
    }

    private fun sessionUpdate(): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("instructions", "Call validation_echo exactly once with text hello. Then say its output.")
            putJsonArray("output_modalities") { add(kotlinx.serialization.json.JsonPrimitive("audio")) }
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", 24_000)
                    }
                }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", 24_000)
                    }
                    put("voice", "alloy")
                }
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "function")
                    put("name", "validation_echo")
                    put("description", "Echo text. Always use when asked.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("text")) }
                    }
                })
            }
            put("tool_choice", "required")
        }
    }.toString()

    companion object {
        private const val LIVE_URL_ENV = "BOSSTERM_LOCAL_VOICE_LIVE_URL"
        private const val LIVE_TIMEOUT_MS = 180_000L
    }
}
