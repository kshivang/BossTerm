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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
     *
     * @return false when a GUARANTEED frame could not be queued; evictable sends always return true.
     */
    fun send(json: String, evictable: Boolean = false): Boolean

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
internal class JdkRealtimeTransport(
    /**
     * How a socket is opened. Injected so the connect/close/reconnect lifecycle — the subtlest
     * reasoning in this file, and the part that has bitten twice (see [closeRequested] and
     * [generation]) — is testable without a network: a real handshake needs OpenAI, so this was the
     * only piece of the voice path with no direct coverage.
     *
     * A dependency, not a test hook: it takes the same arguments the real builder does and has no
     * "make the transport misbehave" affordance.
     */
    private val openSocket: suspend (url: String, apiKey: String, listener: WebSocket.Listener) -> WebSocket =
        { url, apiKey, listener ->
            sharedClient.newWebSocketBuilder()
                .header("Authorization", "Bearer $apiKey")
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), listener)
                .join()
        },
) : RealtimeTransport {

    private val log = LoggerFactory.getLogger(JdkRealtimeTransport::class.java)

    @Volatile private var socket: WebSocket? = null
    /**
     * Partial text frames. A StringBuffer, not a StringBuilder: [onText] appends from the JDK
     * WebSocket reader thread while [close] clears it from the caller's, and an unlucky interleaving
     * on an unsynchronized builder gives a corrupted event or a StringIndexOutOfBoundsException out
     * of the listener.
     */
    private val pending = StringBuffer()

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
    private val lanes = OutgoingLanes()

    @Volatile private var writer: Thread? = null
    @Volatile private var open = false

    /** Set by [close]; makes a connect that is still in flight abort its socket instead of keeping it. */
    @Volatile private var closeRequested = false

    /**
     * Bumped per connect, and captured by each writer thread.
     *
     * `open` alone wasn't enough: close() can time out joining a writer parked inside
     * `sendText(...).join()` (which neither the interrupt nor `open = false` retracts), null the
     * handle, and return. A later connect() would then set `open = true` and start writer #2 while
     * #1 was still alive — and #1, on finishing its send, would re-read the shared flag, pick up the
     * NEW socket and send on it. Two concurrent sendText calls is exactly the collision the
     * single-consumer queue exists to prevent, failing the same silent way.
     */
    private val generation = AtomicInteger(0)

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
        val ws = withContext(Dispatchers.IO + NonCancellable) { openSocket(url, apiKey, listener) }
        // Hung up while we were connecting (either by cancellation or by close() racing the
        // assignment below): own it just long enough to shut it down.
        if (!currentCoroutineContext().isActive || closeRequested) {
            runCatching { ws.abort() }
            throw CancellationException("voice call ended during connect")
        }
        socket = ws
        open = true
        val gen = generation.incrementAndGet()
        writer = Thread({
            while (keepWriting(open, gen, generation.get())) {
                val json = runCatching { lanes.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
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

    override fun send(json: String, evictable: Boolean): Boolean {
        if (!open) return false
        val accepted = lanes.offer(json, evictable)
        if (!accepted) {
            log.warn("Voice control queue full ({}); dropping a protocol frame", OutgoingLanes.PROTOCOL_CAPACITY)
        }
        return accepted
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
        lanes.clear()
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
        /**
         * Whether a writer thread should keep draining: its own [myGeneration] must still be the
         * current one. A lingering writer from a previous connect would otherwise re-read the shared
         * `open` flag, pick up the NEW socket and send concurrently with its replacement — the exact
         * collision the single-consumer queue exists to prevent. Pure, so the guard is testable.
         */
        fun keepWriting(open: Boolean, myGeneration: Int, currentGeneration: Int): Boolean =
            open && myGeneration == currentGeneration

        const val REALTIME_WS_URL = "wss://api.openai.com/v1/realtime"

        /** How long close() waits for the writer to unwind before abandoning it. */
        const val WRITER_SHUTDOWN_MS = 500L


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
