package ai.rever.bossterm.compose.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The host's own connection to the OpenAI Realtime API, as JSON events in both directions.
 *
 * Abstracted so [HostVoiceCallController] can be driven by a fake in tests — the controller is a
 * protocol state machine and shouldn't need a socket (or a microphone) to be exercised.
 */
internal interface RealtimeTransport {

    /** Open the connection. Throws on failure; the caller surfaces it to the UI. */
    suspend fun connect(model: String, apiKey: String, events: (String) -> Unit, onClosed: (String?) -> Unit)

    /**
     * Send one client event (already-encoded JSON). Silently dropped once closed.
     *
     * [evictable] marks frames that may be discarded under back-pressure — mic audio, where a stale
     * chunk is worth less than an unbounded queue. Protocol frames must NOT be evictable: losing a
     * `function_call_output` wedges that tool round permanently (the round is already settled, so
     * `response.create` fires against a call the model never got an output for).
     */
    fun send(json: String, evictable: Boolean = false)

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

    /**
     * Outgoing events, drained by one writer thread.
     *
     * `WebSocket.sendText` throws IllegalStateException if a previous text send hasn't completed —
     * the JDK contract is to wait on the returned future. This socket has two concurrent producers
     * (the capture thread at ~25 mic frames/second, and tool coroutines sending function results),
     * so fire-and-forget sends would collide and, with the failure swallowed, present as "the agent
     * can't hear me" with nothing but a class name in the log. One consumer that waits for each
     * future removes the collision entirely.
     */
    // Two lanes: protocol frames are guaranteed, audio is droppable. One mixed drop-oldest queue
    // would evict a queued function_call_output as readily as a stale mic chunk.
    private val outgoing = ArrayBlockingQueue<String>(OUTGOING_CAPACITY)
    private val outgoingAudio = ArrayBlockingQueue<String>(OUTGOING_AUDIO_CAPACITY)

    /** @suppress Test seam: the lane split is correctness-critical and needs no socket to check. */
    internal fun queuedForTest(): Pair<List<String>, List<String>> =
        outgoing.toList() to outgoingAudio.toList()

    /** @suppress Test seam: pretend the socket is up so [send] enqueues. */
    internal fun openForTest() { open = true }
    @Volatile private var writer: Thread? = null
    @Volatile private var open = false

    /** Set by [close]; makes a connect that is still in flight abort its socket instead of keeping it. */
    @Volatile private var closeRequested = false

    override suspend fun connect(
        model: String,
        apiKey: String,
        events: (String) -> Unit,
        onClosed: (String?) -> Unit,
    ) {
        // Reset per connect: a stale `closeRequested` made the instance single-use — the next
        // connect completed the handshake, aborted the live socket, and threw the cancellation the
        // controller treats as clean teardown, leaving it stuck at "Connecting…" with no error.
        closeRequested = false
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
        // join() parks a thread for up to the connect timeout, so keep it off the caller's
        // dispatcher. The model is URL-encoded: it comes from a dropdown today, but settings.json
        // is user-editable and a stray character would throw URISyntaxException out of connect()
        // instead of surfacing as a failed call.
        val url = "$REALTIME_WS_URL?model=" + URLEncoder.encode(model, StandardCharsets.UTF_8)
        // NonCancellable so the handshake either produces a socket we own or throws. Cancelling
        // mid-join used to let join() finish and hand back a LIVE socket while withContext threw
        // before the assignment — leaving `socket` null, close() a no-op, and a billed realtime
        // session open to OpenAI until the process exited.
        val ws = withContext(Dispatchers.IO + NonCancellable) {
            sharedClient.newWebSocketBuilder()
                .header("Authorization", "Bearer $apiKey")
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), listener)
                .join()
        }
        // Hung up while we were connecting (either by cancellation or by close() racing the
        // assignment below): own it just long enough to shut it down.
        if (!currentCoroutineContext().isActive || closeRequested) {
            runCatching { ws.abort() }
            throw CancellationException("voice call ended during connect")
        }
        socket = ws
        open = true
        writer = Thread({
            while (open) {
                // Protocol frames first, then audio: a backlog of mic chunks must not delay a
                // function_call_output.
                val json = outgoing.poll()
                    ?: runCatching { outgoingAudio.poll(200, TimeUnit.MILLISECONDS) }.getOrNull()
                    ?: continue
                val ws = socket ?: break
                // join() per frame is the point: the next sendText may not start until this one
                // completes.
                // Bounded wait: a wedged socket must not park the writer forever (and interrupting
                // a thread inside CompletableFuture.join() would not release it).
                runCatching { ws.sendText(json, true).orTimeout(15, TimeUnit.SECONDS).join() }
                    .onFailure { log.warn("Voice send failed: {}", it.javaClass.simpleName) }
            }
        }, "boss-voice-ws-writer").apply { isDaemon = true; start() }
    }

    override fun send(json: String, evictable: Boolean) {
        if (!open) return
        if (evictable) {
            // Drop the oldest audio when the socket can't keep up: stale mic chunks are worth less
            // than an unbounded queue, and losing one is inaudible.
            if (!outgoingAudio.offer(json)) {
                outgoingAudio.poll()
                outgoingAudio.offer(json)
            }
            return
        }
        // Protocol frames are guaranteed while the lane has room; past it the socket is hopelessly
        // behind, so surface it rather than silently dropping a tool result.
        if (!outgoing.offer(json)) {
            log.warn("Voice control queue full ({}); dropping a protocol frame", OUTGOING_CAPACITY)
        }
    }

    override fun close() {
        closeRequested = true
        open = false
        // Wait briefly for the writer to fall out of its loop. It may already be inside a
        // sendText().join() that neither `open = false` nor interrupt() can retract, and a reusable
        // transport means the next connect() would otherwise overlap that window.
        writer?.let { w ->
            w.interrupt()
            runCatching { w.join(WRITER_SHUTDOWN_MS) }
        }
        // A truncated final fragment would otherwise corrupt the first event of the next connect —
        // reachable, since the controller keeps one transport for its whole lifetime.
        pending.setLength(0)
        writer = null
        outgoing.clear()
        outgoingAudio.clear()
        val ws = socket ?: return
        socket = null
        // Let the close frame actually flush before aborting: abort() straight after sendClose
        // usually kills the connection first, so the peer sees a hard drop.
        runCatching {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                .orTimeout(2, TimeUnit.SECONDS)
                .whenComplete { _, _ -> runCatching { ws.abort() } }
        }.onFailure { runCatching { ws.abort() } }
    }

    internal companion object {
        const val REALTIME_WS_URL = "wss://api.openai.com/v1/realtime"

        /** How long close() waits for the writer to unwind before abandoning it. */
        const val WRITER_SHUTDOWN_MS = 500L

        /** Protocol frames in flight; small because they are answered promptly. */
        const val OUTGOING_CAPACITY = 64

        /** ~10s of mic frames; beyond that the socket is stalled and old audio is worthless. */
        const val OUTGOING_AUDIO_CAPACITY = 256

        /**
         * One client for the process. A per-connect client is never closed (HttpClient only became
         * closeable in Java 21), so building one per call leaked a selector thread and an executor
         * each time.
         */
        val sharedClient: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        }
    }
}
