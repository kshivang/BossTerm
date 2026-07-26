package ai.rever.bossterm.compose.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transport's connect → close → connect lifecycle, driven through an injected socket factory.
 *
 * This is the reasoning that carries the longest comments in `RealtimeTransport.kt` — the
 * `closeRequested` reset and the writer `generation` guard — and both exist because the transport
 * is REUSED across calls: the controller keeps one for its whole lifetime. A real handshake needs
 * OpenAI, so until the factory was injectable none of it had direct coverage.
 */
class RealtimeTransportLifecycleTest {

    /** A `java.net.http.WebSocket` that records what was sent and never touches a network. */
    private class FakeSocket : WebSocket {
        val sent = ConcurrentLinkedQueue<String>()
        /**
         * Which threads wrote to this socket — the generation guard's whole point is "exactly one".
         * Identity, not name: every writer is called "boss-voice-writer", so a set of NAMES cannot
         * tell two of them apart and silently passes with the guard removed.
         */
        val senderThreads: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        @Volatile var aborted = false
        @Volatile var closeSent = false
        /** Completed by the test to release a send, so a parked writer can be observed. */
        @Volatile var gate: CompletableFuture<WebSocket>? = null

        /** Once true, every send throws — what the JDK does after a pending text message. */
        @Volatile var failSends = false

        override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
            senderThreads.add(System.identityHashCode(Thread.currentThread()))
            if (failSends) throw IllegalStateException("Pending text message")
            sent.add(data.toString())
            return gate ?: CompletableFuture.completedFuture(this)
        }
        override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> =
            CompletableFuture.completedFuture(this)
        override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> =
            CompletableFuture.completedFuture(this)
        override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> =
            CompletableFuture.completedFuture(this)
        override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> {
            closeSent = true
            return CompletableFuture.completedFuture(this)
        }
        override fun request(n: Long) {}
        override fun getSubprotocol(): String = ""
        override fun isOutputClosed(): Boolean = closeSent
        override fun isInputClosed(): Boolean = closeSent
        override fun abort() { aborted = true }
    }

    private class Harness {
        val sockets = CopyOnWriteArrayList<FakeSocket>()
        val urls = CopyOnWriteArrayList<String>()
        val keys = CopyOnWriteArrayList<String>()
        var listener: WebSocket.Listener? = null
        val transport = JdkRealtimeTransport { url, apiKey, l ->
            urls.add(url)
            keys.add(apiKey)
            listener = l
            FakeSocket().also { sockets.add(it) }
        }
    }

    private fun await(timeoutMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { predicate() }.getOrDefault(false)) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun `a closed transport can be used for a second call`() = runBlocking {
        val h = Harness()
        h.transport.connect("gpt-realtime-2.1", "sk-a", events = {}, onClosed = {})
        assertTrue(h.transport.send("""{"type":"one"}"""))
        assertTrue(await { h.sockets[0].sent.contains("""{"type":"one"}""") })

        h.transport.close()
        assertTrue(h.sockets[0].closeSent, "the peer gets a close frame rather than a hard drop")

        // The reset that makes this possible: a stale closeRequested made the instance single-use —
        // the next connect completed the handshake, aborted the LIVE socket, and threw the
        // cancellation the controller reads as clean teardown, leaving it stuck at "Connecting…".
        h.transport.connect("gpt-realtime-2.1", "sk-b", events = {}, onClosed = {})
        assertEquals(2, h.sockets.size, "a second socket was opened")
        assertFalse(h.sockets[1].aborted, "and NOT aborted by the previous close")
        assertTrue(h.transport.send("""{"type":"two"}"""))
        assertTrue(await { h.sockets[1].sent.contains("""{"type":"two"}""") })
        assertFalse(h.sockets[0].sent.contains("""{"type":"two"}"""), "the old socket is not written to")
        h.transport.close()
    }

    /**
     * The generation guard. close() only waits [JdkRealtimeTransport.WRITER_SHUTDOWN_MS] for the
     * writer, and a writer parked inside `sendText(...).join()` cannot be retracted by `open = false`
     * or by interrupt. Without the per-connect generation, that lingering writer wakes up, reads the
     * shared `socket` field — now the NEXT call's socket — and starts draining the same queue as the
     * new writer. Two threads calling sendText on one socket is exactly the collision the
     * single-consumer queue exists to prevent, and the JDK answers it with IllegalStateException,
     * swallowed here into "the agent can't hear me".
     *
     * Asserted as "one thread ever writes to the new socket". The obvious version of this test —
     * counting frames — passes with the guard REMOVED, because a queue with two consumers still
     * delivers each frame exactly once; it just delivers them from two threads.
     */
    @Test
    fun `a writer left over from the previous connect never adopts the new socket`() = runBlocking {
        val h = Harness()
        h.transport.connect("gpt-realtime-2.1", "sk-a", events = {}, onClosed = {})
        val first = h.sockets[0]
        // Park writer #1 inside a send that will not complete until we say so.
        first.gate = CompletableFuture()
        assertTrue(h.transport.send("""{"type":"parked"}"""))
        assertTrue(await { first.sent.isNotEmpty() }, "writer #1 is inside sendText().join()")

        h.transport.close() // times out joining the parked writer, by design
        h.transport.connect("gpt-realtime-2.1", "sk-b", events = {}, onClosed = {})
        val second = h.sockets[1]

        // Release writer #1 only now: it wakes with the new socket installed on the shared field.
        first.gate?.complete(first)
        // Enough frames, slowly enough, that a second consumer would certainly claim some of them.
        repeat(40) { i ->
            h.transport.send("""{"type":"f$i"}""")
            Thread.sleep(5)
        }
        assertTrue(await { second.sent.size >= 40 }, "all frames reach the new socket")

        assertEquals(
            1,
            second.senderThreads.size,
            "exactly one writer may ever send on a socket; saw ${second.senderThreads}",
        )
        h.transport.close()
    }

    /**
     * A failed send is terminal for the socket, not a lost frame.
     *
     * `orTimeout` completes the FUTURE exceptionally; it does not retract the send the JDK still has
     * in flight, and the contract forbids starting the next text send before the previous completes —
     * so every later sendText on that socket throws for the rest of its life. Swallowed into a log
     * line, that left `open` true: send() kept accepting frames, the controller kept settling rounds
     * on "delivered", and response.create fired into a socket that would never carry it. A
     * permanently mute call with no error anywhere.
     */
    @Test
    fun `a send that fails ends the call instead of going quiet`() = runBlocking {
        val h = Harness()
        val closes = ConcurrentLinkedQueue<String?>()
        h.transport.connect("gpt-realtime-2.1", "sk-a", events = {}, onClosed = { closes.add(it) })
        val socket = h.sockets[0]

        // The JDK's behaviour once a send has not completed: everything after it throws.
        socket.failSends = true
        assertTrue(h.transport.send("""{"type":"one"}"""), "queued before the failure is known")

        assertTrue(await { closes.isNotEmpty() }, "the failure must reach the controller's onClosed")
        assertTrue(await { socket.aborted }, "and the dead socket is released")
        assertFalse(
            h.transport.send("""{"type":"two"}"""),
            "later frames must be refused, not accepted into a socket that cannot carry them",
        )
        h.transport.close()
    }

    /**
     * close() racing an in-flight connect. The handshake is NonCancellable precisely so it cannot
     * hand back a live socket that nothing owns: cancelling mid-join used to let join() finish while
     * withContext threw before the assignment, leaving `socket` null, close() a no-op, and a billed
     * realtime session open to OpenAI until the process exited.
     */
    @Test
    fun `hanging up during the handshake aborts the socket it was given`() = runBlocking {
        val opened = CompletableDeferred<FakeSocket>()
        val release = CompletableDeferred<Unit>()
        val sockets = CopyOnWriteArrayList<FakeSocket>()
        val transport = JdkRealtimeTransport { _, _, _ ->
            val s = FakeSocket()
            sockets.add(s)
            opened.complete(s)
            release.await() // a slow handshake
            s
        }

        var failed: Throwable? = null
        val job = CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                transport.connect("gpt-realtime-2.1", "sk-a", events = {}, onClosed = {})
            }.onFailure { failed = it }
        }
        val socket = opened.await()
        transport.close() // the user hit End while we were still connecting
        release.complete(Unit)
        job.join()

        assertTrue(failed != null, "connect must not report success for a call already hung up")
        assertTrue(await { socket.aborted }, "the socket handed back must be aborted, not leaked")
        assertFalse(transport.send("""{"type":"late"}""") && socket.sent.isNotEmpty(), "and never written to")
    }
}
