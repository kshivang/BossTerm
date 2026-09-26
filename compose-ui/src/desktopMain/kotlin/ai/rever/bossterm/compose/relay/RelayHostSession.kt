package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One room/socket for the whole app; each admitted peer retains its own approval and private lane. */
internal class RelayHostSession(
    endpoint: String,
    val room: String,
    userId: String,
    ticketProvider: suspend () -> String? = { AccountRelayTickets.mint(userId, room, "host") },
    allowLoopbackForTests: Boolean = false,
    private val readyChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transitions = Mutex()
    private val lifecycleLock = Any()
    private val identity = RelayOutputCrypto.newHostIdentity()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val publishers = ConcurrentHashMap<String, Deferred<RelayPanePublisher>>()
    private val activePublishers = ConcurrentHashMap<String, RelayPanePublisher>()
    private val demands = ConcurrentHashMap<String, Pair<Boolean, Int>>()
    private val outputRates = ConcurrentHashMap<String, Int>()
    private val nextGraphicsFragment = AtomicLong(System.nanoTime())
    private val previewGraphics = RelayPreviewGraphicsCache()
    private val connection = RelayConnection(endpoint, room, ticketProvider, ::message, allowLoopbackForTests)
    @Volatile private var closed = false

    suspend fun run() {
        try { connection.run() } finally { close() }
    }

    private fun send(message: JsonObject) { check(connection.send(message)) { "Relay connection unavailable" } }
    private fun revoke(id: String) {
        if (!closed) runCatching { send(buildJsonObject { put("op", "revoke"); put("peer", id) }) }
        removePeer(id)
    }

    private suspend fun message(message: JsonObject) {
        when (message["op"]?.jsonPrimitive?.content) {
            "welcome" -> readyChanged(true)
            "peerCredit" -> {
                val peer = peers[message["peer"]?.jsonPrimitive?.content] ?: return
                val credit = runCatching { RelayPeerCodec.json.parseToJsonElement(message.getValue("payload").jsonPrimitive.content).jsonObject }.getOrNull() ?: return
                peer.credit(credit["credit"]?.jsonPrimitive?.content ?: return, credit["part"]?.jsonPrimitive?.intOrNull ?: return)
            }
            "signal" -> {
                val id = message["peer"]?.jsonPrimitive?.content ?: return
                try {
                    val packet = RelayPeerCodec.json.decodeFromString(RelayPeerPacket.serializer(), message.getValue("payload").jsonPrimitive.content)
                    val peer = peers[id] ?: run {
                        val token = packet.token?.takeIf { it.length in 1..256 } ?: error("Missing share token")
                        check(peers.size < 128)
                        Peer(id, token).also { peers[id] = it; it.start() }
                    }
                    if (packet.token != null) require(packet.token == peer.token)
                    peer.decoder.accept(packet)?.let { check(peer.offer(it)) { "Peer input queue full" } }
                } catch (_: Exception) { revoke(id) }
            }
            "leave" -> message["peer"]?.jsonPrimitive?.content?.let(::removePeer)
            "subscribe", "resync" -> {
                val peer = peers[message["peer"]?.jsonPrimitive?.content] ?: return
                val pane = message["pane"]?.jsonPrimitive?.content ?: return
                val share = peer.share ?: return
                if (pane !in share.relayPaneIds()) { revoke(peer.id); return }
                val mode = message["mode"]?.jsonPrimitive?.content
                if (mode != null) peer.modes[pane] = mode
                peer.updateVisibility()
                val generation = peer.generations.merge(pane, 1L, Long::plus)!!
                if (peer.modes[pane] == null || peer.modes[pane] == "hidden") return
                scope.launch {
                    transitions.withLock { snapshot(peer, pane, share, generation) }
                }
            }

            "interests" -> {
                val next = message["panes"]?.jsonObject ?: return
                demands.clear()
                outputRates.clear()
                for ((pane, value) in next) {
                    val data = value.jsonObject
                    val live = data["live"]?.jsonPrimitive?.booleanOrNull ?: false
                    val fps = data["previewFps"]?.jsonPrimitive?.intOrNull ?: 0
                    require(fps in 0..30)
                    demands[pane] = live to fps
                    val outputFps = data["outputFps"]?.jsonPrimitive?.intOrNull ?: if (live) 60 else 0
                    require(outputFps in 0..60)
                    outputRates[pane] = outputFps
                }
                scope.launch {
                    transitions.withLock { restoreDemand() }
                }
            }
        }
    }

    private suspend fun publisher(pane: String, share: MirrorShare): RelayPanePublisher =
        publishers.computeIfAbsent(pane) {
            scope.async {
                share.openRelayPane(room, pane, identity, { output ->
                    connection.send(buildJsonObject {
                        put("op", "output")
                        for ((key, value) in ShareProtocol.json.encodeToJsonElement(RelayOutput.serializer(), output).jsonObject) put(key, value)
                    })
                }, { close() }, { graphics, kind ->
                    if (kind == "preview") previewGraphics.put(graphics)
                    else peers.values.forEach { it.enqueueGraphics(graphics, kind) }
                }).also {
                    synchronized(lifecycleLock) {
                        if (closed) it.close() else {
                            activePublishers[pane] = it
                            val demand = demands[pane] ?: (false to 0)
                            it.demand(demand.first, demand.second, outputRates[pane] ?: if (demand.first) 60 else 0)
                        }
                    }
                }
            }
        }.await()

    private fun removePeer(id: String) {
        val removed = peers.remove(id) ?: return
        removed.dispose()
        val panes = removed.grantedPanes()
        scope.launch {
            transitions.withLock {
                refreshAfterRevocation(panes)
            }
        }
    }

    /** Caller holds transitions so key rotation cannot overtake a private snapshot. */
    private suspend fun refreshAfterRevocation(panes: Set<String>) {
        previewGraphics.clear()
        val retained = peers.values.flatMap { it.share?.relayPaneIds().orEmpty() }.toSet()
        for (pane in activePublishers.keys - retained) {
            activePublishers.remove(pane)?.close()
            publishers.remove(pane)?.cancel()
            demands.remove(pane)
            outputRates.remove(pane)
        }
        // Grants reset every subscribed pane's snapshot gate. Refresh all of them,
        // including panes whose keys did not change, before restoring output.
        activePublishers.values.forEach { it.demand(false, 0) }
        for (pane in panes) activePublishers[pane]?.rotateKey()
        for (peer in peers.values) {
            val share = peer.share ?: continue
            peer.grant(share)
            for (pane in peer.visiblePanes()) {
                val generation = peer.generations.merge(pane, 1L, Long::plus)!!
                snapshot(peer, pane, share, generation)
            }
        }
        restoreDemand()
    }

    private fun restoreDemand() {
        activePublishers.forEach { (pane, publisher) ->
            val demand = demands[pane] ?: (false to 0)
            publisher.demand(demand.first, demand.second, outputRates[pane] ?: if (demand.first) 60 else 0)
        }
    }

    private suspend fun snapshot(peer: Peer, pane: String, share: MirrorShare, generation: Long) {
        try {
            publisher(pane, share).withSnapshot { snapshot ->
                if (peers[peer.id] === peer && peer.generations[pane] == generation) {
                    peer.sendPrivate?.invoke(ShareProtocol.json.encodeToString(RelayPaneSnapshot.serializer(), snapshot),
                        RelaySnapshotBoundary(pane, snapshot.epoch, snapshot.sequence))
                    snapshot.graphics?.let { peer.enqueueGraphics(it) }
                }
            }
        } catch (_: TimeoutCancellationException) { revoke(peer.id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { revoke(peer.id) }
    }

    private inner class Peer(val id: String, val token: String) : ShareViewerTransport, RelayViewerLifecycle {
        private val job = SupervisorJob(scope.coroutineContext[Job])
        override val coroutineContext = scope.coroutineContext + job
        private val queued = AtomicLong()
        private val input = Channel<Frame>(128, onUndeliveredElement = { queued.addAndGet(-it.data.size.toLong()) })
        val decoder = RelayPeerCodec.Decoder()
        @Volatile private var fragmentCredit: Pair<String, CompletableDeferred<Unit>>? = null
        fun credit(fragment: String, part: Int) {
            fragmentCredit?.takeIf { it.first == "$fragment:$part" }?.second?.complete(Unit)
        }
        @Volatile var share: MirrorShare? = null
        @Volatile private var viewer: ViewerConnection? = null
        @Volatile var sendPrivate: (suspend (String, RelaySnapshotBoundary?) -> Unit)? = null
        val generations = ConcurrentHashMap<String, Long>()
        val modes = ConcurrentHashMap<String, String>()
        private val requestedPreviewGraphics = ConcurrentHashMap<String, Long>()
        fun visiblePanes() = granted.filter { modes[it] != null && modes[it] != "hidden" }
        fun updateVisibility() { viewer?.relayVisiblePanes = visiblePanes().toSet() }
        fun enqueueGraphics(message: ServerMessage.PaneGraphics, kind: String? = null) {
            val current = viewer ?: return
            if (!current.supportsPaneGraphics || message.paneId !in current.relayVisiblePanes) return
            if (kind == "preview" && modes[message.paneId] != "preview") return
            if (kind == "live" && modes[message.paneId] == "preview") return
            if (!current.outbox.trySendWithoutEviction(ShareProtocol.encodeServer(message))) {
                current.outbox.trySend(ShareProtocol.encodeServer(message.resyncSentinel()))
            }
        }
        @Volatile private var granted = emptySet<String>()
        fun grantedPanes(): Set<String> = granted
        override val incoming: ReceiveChannel<Frame> = object : ReceiveChannel<Frame> by input {
            override suspend fun receive(): Frame = input.receive().also { queued.addAndGet(-it.data.size.toLong()) }
            override fun iterator(): ChannelIterator<Frame> {
                val iterator = input.iterator()
                return object : ChannelIterator<Frame> {
                    override suspend fun hasNext() = iterator.hasNext()
                    override fun next() = iterator.next().also { queued.addAndGet(-it.data.size.toLong()) }
                }
            }
        }
        fun offer(frame: Frame): Boolean {
            if (queued.addAndGet(frame.data.size.toLong()) > 2 * 1024 * 1024) { queued.addAndGet(-frame.data.size.toLong()); return false }
            if (input.trySend(frame).isFailure) { queued.addAndGet(-frame.data.size.toLong()); return false }
            return true
        }
        fun start() { launch { SessionShareManager.serveRelayViewer(token, this@Peer, this@Peer) } }
        override suspend fun send(frame: Frame) {
            val credited = frame.data.size > 64 * 1024
            for (packet in RelayPeerCodec.packets(frame, credited = credited)) {
                if (credited) {
                    // Leave half the host frame budget for terminal output, even when many
                    // viewers acknowledge a large raster at local-network speed.
                    val now = System.nanoTime()
                    val slot = nextGraphicsFragment.updateAndGet { maxOf(it, now) + 2_000_000L }
                    delay(((slot - now + 999_999L) / 1_000_000L).coerceAtLeast(1))
                }
                val receipt = if (credited) CompletableDeferred<Unit>() else null
                if (receipt != null) fragmentCredit = "${packet.id}:${packet.part}" to receipt
                try {
                    this@RelayHostSession.send(buildJsonObject {
                        put("op", "signal"); put("peer", id)
                        put("payload", RelayPeerCodec.json.encodeToString(RelayPeerPacket.serializer(), packet))
                    })
                    // One fragment in flight per peer bounds raster traffic independently from
                    // terminal output. A stopped viewer is removed without blocking other peers.
                    if (receipt != null) withTimeout(10_000) { receipt.await() }
                } finally { if (receipt != null) fragmentCredit = null }
            }
        }
        override suspend fun snapshot(frame: Frame, pane: String, epoch: String, sequence: Long) {
            val packet = RelayPeerCodec.encode(frame, single = true).single()
            val message = buildJsonObject {
                put("op", "snapshot"); put("peer", id); put("pane", pane); put("epoch", epoch); put("seq", sequence); put("payload", packet)
            }
            check(message.toString().toByteArray(Charsets.UTF_8).size <= RelayConnection.MAX_WIRE_BYTES) { "Snapshot too large" }
            this@RelayHostSession.send(message)
        }
        override suspend fun close(reason: CloseReason) { revoke(id) }
        override suspend fun admitted(share: MirrorShare, viewer: ViewerConnection, sendPrivate: suspend (String, RelaySnapshotBoundary?) -> Unit) {
            transitions.withLock {
                check(!closed && peers[id] === this@Peer) { "Relay peer disconnected during admission" }
                this.share = share; this.viewer = viewer; this.sendPrivate = sendPrivate; grant(share)
            }
        }
        fun grant(share: MirrorShare) {
            granted = share.relayPaneIds()
            modes.keys.retainAll(granted)
            requestedPreviewGraphics.keys.retainAll(granted)
            updateVisibility()
            this@RelayHostSession.send(buildJsonObject {
                put("op", "grant"); put("peer", id); put("panes", JsonArray(granted.map(::JsonPrimitive)))
            })
        }
        override fun layoutChanged(share: MirrorShare) {
            if (this.share !== share) return
            scope.launch {
                transitions.withLock {
                    if (peers[id] !== this@Peer || granted == share.relayPaneIds()) return@withLock
                    val removed = granted - share.relayPaneIds()
                    if (removed.isNotEmpty()) {
                        refreshAfterRevocation(removed)
                        return@withLock
                    }
                    grant(share)
                    for (pane in visiblePanes()) {
                        val generation = generations.merge(pane, 1L, Long::plus)!!
                        snapshot(this@Peer, pane, share, generation)
                    }
                }
            }
        }
        override fun graphicsResync(pane: String, relaySequence: Long?) {
            val current = viewer ?: return
            if (pane !in current.relayVisiblePanes) return
            if (relaySequence != null) {
                if (relaySequence < 0 || modes[pane] != "preview") return
                val previous = requestedPreviewGraphics[pane]
                if (previous != null && relaySequence <= previous) return
                requestedPreviewGraphics[pane] = relaySequence
                previewGraphics.get(pane, relaySequence)?.let {
                    enqueueGraphics(it, "preview")
                    return
                }
            }
            // A cache miss restores a new text+raster boundary; never attach today's image to old text.
            if (!current.graphicsResyncLimiter.tryAcquire(pane)) {
                current.outbox.trySend(ShareProtocol.encodeServer(ServerMessage.GraphicsResyncDenied(pane,
                    current.graphicsResyncLimiter.retryAfterMillis(pane))))
                return
            }
            launch {
                try {
                    val currentShare = share ?: return@launch
                    val generation = generations.merge(pane, 1L, Long::plus)!!
                    transitions.withLock { snapshot(this@Peer, pane, currentShare, generation) }
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { revoke(id) }
            }
        }
        override fun disconnected() { if (!closed) revoke(id) }
        fun dispose() { fragmentCredit?.second?.cancel(); input.cancel(); job.cancel() }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
        }
        readyChanged(false)
        connection.close()
        peers.values.forEach { it.dispose() }; peers.clear()
        activePublishers.values.forEach { it.close() }; activePublishers.clear()
        publishers.clear(); previewGraphics.clear(); scope.cancel()
    }
}
