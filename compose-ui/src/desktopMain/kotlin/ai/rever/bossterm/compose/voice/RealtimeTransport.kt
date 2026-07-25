package ai.rever.bossterm.compose.voice

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage

/**
 * The host's own connection to the OpenAI Realtime API, as JSON events in both directions.
 *
 * Abstracted so [HostVoiceCallController] can be driven by a fake in tests — the controller is a
 * protocol state machine and shouldn't need a socket (or a microphone) to be exercised.
 */
internal interface RealtimeTransport {

    /** Open the connection. Throws on failure; the caller surfaces it to the UI. */
    suspend fun connect(model: String, apiKey: String, events: (String) -> Unit, onClosed: (String?) -> Unit)

    /** Send one client event (already-encoded JSON). Silently dropped once closed. */
    fun send(json: String)

    fun close()
}

/**
 * [RealtimeTransport] over the JDK's own WebSocket client.
 *
 * Deliberately not Ktor: `java.net.http` has had a WebSocket client since Java 11, so the whole
 * in-app call path needs no new dependency. The host connects with its **standard API key** —
 * unlike the browser, there is no untrusted party here, so no ephemeral secret is involved.
 *
 * Text frames arrive in fragments, so [onText] accumulates until `last` before handing an event up;
 * a partially-delivered event would otherwise fail to parse.
 */
internal class JdkRealtimeTransport : RealtimeTransport {

    private val log = LoggerFactory.getLogger(JdkRealtimeTransport::class.java)

    @Volatile private var socket: WebSocket? = null
    private val pending = StringBuilder()

    override suspend fun connect(
        model: String,
        apiKey: String,
        events: (String) -> Unit,
        onClosed: (String?) -> Unit,
    ) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        val listener = object : WebSocket.Listener {
            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                ws.request(1)
                pending.append(data)
                if (last) {
                    val whole = pending.toString()
                    pending.setLength(0)
                    runCatching { events(whole) }
                        .onFailure { log.warn("Voice event handler failed: {}", it.javaClass.simpleName) }
                }
                return null
            }

            override fun onClose(ws: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
                onClosed(reason?.takeIf { it.isNotBlank() })
                return null
            }

            override fun onError(ws: WebSocket, error: Throwable?) {
                log.warn("Voice socket error: {}", error?.javaClass?.simpleName)
                onClosed(error?.javaClass?.simpleName)
            }
        }
        socket = client.newWebSocketBuilder()
            .header("Authorization", "Bearer $apiKey")
            .connectTimeout(Duration.ofSeconds(15))
            .buildAsync(URI.create("$REALTIME_WS_URL?model=$model"), listener)
            .join()
    }

    override fun send(json: String) {
        val ws = socket ?: return
        runCatching { ws.sendText(json, true) }
            .onFailure { log.warn("Voice send failed: {}", it.javaClass.simpleName) }
    }

    override fun close() {
        val ws = socket ?: return
        socket = null
        runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
        runCatching { ws.abort() }
    }

    private companion object {
        const val REALTIME_WS_URL = "wss://api.openai.com/v1/realtime"
    }
}
