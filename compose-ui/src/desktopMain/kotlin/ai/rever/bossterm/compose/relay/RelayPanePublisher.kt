package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.ShareProtocol
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyPair

/** Pairwise encrypted as a whole; the relay's outer sequence/epoch must match these fields. */
@Serializable
internal data class RelayPaneSnapshot(
    val room: String,
    val pane: String,
    val epoch: String,
    val sequence: Long,
    val key: RelayOutputKey,
    val screen: String,
    @Transient val graphics: ServerMessage.PaneGraphics? = null,
)

internal data class RelayGraphicsCapture(val graphics: ServerMessage.PaneGraphics, val repaint: String? = null)

/** Publishes each representation once, independently of the number of admitted viewers. */
internal class RelayPanePublisher(
    private val room: String,
    private val pane: String,
    private val stream: BlockingTerminalDataStream,
    private val identity: KeyPair,
    /** Runs on the emulator thread. Null means a DEC synchronized update is still in progress. */
    private val capture: (history: Boolean) -> String?,
    private val publish: (RelayOutput) -> Boolean,
    private val failed: (Exception) -> Unit,
    private val captureGraphics: ((full: Boolean) -> RelayGraphicsCapture?)? = null,
    private val publishGraphics: (ServerMessage.PaneGraphics, kind: String) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val snapshotMutex = Mutex()
    private var snapshotInFlight = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var crypto = RelayOutputCrypto.Publisher(room, pane, identity)
    private var subscription: BlockingTerminalDataStream.ProcessedSubscription<Unit>? = null
    private val pending = StringBuilder()
    private var flush: Job? = null
    private var preview: Job? = null
    private var graphics: Job? = null
    private var live = false
    private var outputFps = 0
    private var previewFps = 0
    private var dirty = true
    private var graphicsSequence = 0L
    private var closed = false

    suspend fun start() {
        val observer = stream.observeProcessedOutput(::applied, ::fail, graphicsFiltered = true) { Unit }
        synchronized(lock) {
            if (closed) observer.close() else {
                check(subscription == null) { "Publisher already started" }
                subscription = observer
            }
        }
    }

    fun demand(live: Boolean, previewFps: Int, outputFps: Int = if (live) 60 else 0) = synchronized(lock) {
        require(previewFps in 0..30 && outputFps in 0..60)
        require(!live || outputFps > 0)
        if (closed) return@synchronized
        this.live = live
        if (this.outputFps != outputFps) {
            this.outputFps = outputFps
            flush?.cancel(); flush = null
            scheduleFlushLocked()
        }
        if (!live) { pending.setLength(0); flush?.cancel(); flush = null }
        if (this.previewFps != previewFps) {
            this.previewFps = previewFps
            preview?.cancel()
            preview = if (previewFps == 0) null else scope.launch {
                while (isActive) {
                    delay(1000L / previewFps)
                    if (!synchronized(lock) { dirty && !closed }) continue
                    try {
                        stream.atCheckpoint {
                            synchronized(lock) {
                                if (!closed && !snapshotInFlight && dirty && this@RelayPanePublisher.previewFps > 0) {
                                    capture(false)?.let { screen ->
                                        val images = captureGraphics?.invoke(true)?.graphics?.takeIf { it.cells.isNotEmpty() || it.resyncRequired }
                                            ?.copy(relaySequence = ++graphicsSequence)
                                        val text = withGraphicsBarrier(screen, images?.relaySequence)
                                        images?.let { publishGraphics(it, "preview") }
                                        check(publish(crypto.encrypt("preview", text))) { "Relay output queue full" }
                                        dirty = false
                                    }
                                }
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        // A partial escape instruction may legitimately be waiting for more PTY data.
                        continue
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { fail(e); break }
                }
            }
        }
        if ((live || previewFps > 0) && captureGraphics != null && graphics?.isActive != true) {
            graphics = scope.launch {
                while (isActive) {
                    delay(synchronized(lock) {
                        val fps = maxOf(this@RelayPanePublisher.outputFps.coerceAtMost(10), this@RelayPanePublisher.previewFps).coerceAtLeast(1)
                        (1000L + fps - 1) / fps
                    })
                    try {
                        stream.atCheckpoint {
                            synchronized(lock) {
                                if (!closed && !snapshotInFlight && (this@RelayPanePublisher.live || this@RelayPanePublisher.previewFps > 0)) {
                                    captureGraphics.invoke(false)?.let { update ->
                                        dirty = true
                                        if (this@RelayPanePublisher.live) {
                                            flushLocked()
                                            val images = update.graphics.copy(relaySequence = ++graphicsSequence)
                                            val text = ShareProtocol.encodeServer(ServerMessage.PaneRepaint(pane, update.repaint.orEmpty(), images.relaySequence))
                                            check(publish(crypto.encrypt("live", text))) { "Relay output queue full" }
                                            publishGraphics(images, "live")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: TimeoutCancellationException) { continue }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { fail(e); break }
                }
            }
        } else if (!live && previewFps == 0) { graphics?.cancel(); graphics = null }
    }

    private fun applied(output: String) = synchronized(lock) {
        if (closed) return@synchronized
        dirty = true
        if (!live) return@synchronized
        pending.append(output)
        check(pending.length <= 2 * 1024 * 1024) { "Snapshot delivery stalled" }
        if (snapshotInFlight) return@synchronized
        scheduleFlushLocked()
    }

    private fun scheduleFlushLocked() {
        if (closed || !live || snapshotInFlight || pending.isEmpty() || flush?.isActive == true) return
        flush = scope.launch {
            delay((1000L + outputFps - 1) / outputFps.coerceAtLeast(1))
            try { synchronized(lock) { flushLocked() } } catch (e: Exception) { fail(e) }
        }
    }

    private fun flushLocked() {
        flush?.cancel(); flush = null
        if (snapshotInFlight) return
        if (closed || !live) { pending.setLength(0); return }
        while (pending.isNotEmpty()) {
            var count = minOf(pending.length, CHUNK_CHARS)
            if (count < pending.length && pending[count - 1].isHighSurrogate()) count--
            val output = ShareProtocol.encodeServer(ServerMessage.PaneOutput(pane, pending.substring(0, count)))
            check(publish(crypto.encrypt("live", output))) { "Relay output queue full" }
            pending.delete(0, count)
        }
    }

    /** Enqueue the private snapshot before releasing any subsequent group deltas. */
    suspend fun <T> withSnapshot(deliver: suspend (RelayPaneSnapshot) -> T): T = snapshotMutex.withLock {
        try {
            withTimeout(5_000) {
                var snapshot: RelayPaneSnapshot? = null
                while (snapshot == null) {
                    snapshot = stream.atCheckpoint {
                        synchronized(lock) {
                            check(!closed) { "Relay publisher closed" }
                            flushLocked()
                            capture(true)?.let { screen ->
                                snapshotInFlight = true
                                val images = captureGraphics?.invoke(true)?.graphics?.takeIf { it.cells.isNotEmpty() || it.resyncRequired }
                                    ?.copy(relaySequence = ++graphicsSequence)
                                RelayPaneSnapshot(room, pane, crypto.outputKey.epoch, crypto.liveSequence, crypto.outputKey,
                                    withGraphicsBarrier(screen, images?.relaySequence), images)
                            }
                        }
                    }
                    if (snapshot == null) delay(25)
                }
                deliver(snapshot)
            }
        } finally {
            synchronized(lock) {
                snapshotInFlight = false
                try { flushLocked() } catch (e: Exception) { fail(e) }
            }
        }
    }

    internal suspend fun snapshot(): RelayPaneSnapshot = withSnapshot { it }

    private fun withGraphicsBarrier(screen: String, sequence: Long?): String = if (sequence == null) screen else
        ShareProtocol.encodeServer((ShareProtocol.decodeServer(screen) as ServerMessage.PaneSnapshot).copy(graphicsSequence = sequence))

    /** Regrant/resnapshot every remaining viewer before restoring demand after revocation. */
    suspend fun rotateKey() = snapshotMutex.withLock { synchronized(lock) {
        pending.setLength(0); flush?.cancel(); flush = null
        live = false
        preview?.cancel(); preview = null; previewFps = 0
        graphics?.cancel(); graphics = null
        crypto = RelayOutputCrypto.Publisher(room, pane, identity)
        dirty = true
    } }

    private fun fail(error: Exception) {
        close()
        runCatching { failed(error) }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        subscription?.close(); subscription = null
        pending.setLength(0)
        scope.cancel()
    }

    companion object { private const val CHUNK_CHARS = 16_384 }
}
