package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.ServerMessage

/** Joins independent group text and private raster lanes without letting images rewind text. */
internal class RelayGraphicsGate(
    private val apply: suspend (ServerMessage) -> Unit,
    private val resync: (String) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val requestGraphics: suspend (String, Long) -> Unit = { _, _ -> },
) {
    private data class State(
        var barrier: Long? = null,
        var barrierSince: Long = 0,
        var applied: Long = -1,
        var latestPreview: ServerMessage.PaneSnapshot? = null,
        val output: ArrayDeque<ServerMessage> = ArrayDeque(),
        val graphics: MutableMap<Long, ServerMessage.PaneGraphics> = mutableMapOf(),
        var outputBytes: Int = 0,
        var graphicsBytes: Long = 0,
    )
    private val panes = mutableMapOf<String, State>()
    private var graphicsBytes = 0L

    fun reset(pane: String) { panes.remove(pane)?.let { graphicsBytes -= it.graphicsBytes } }

    /** Complete snapshots supersede queued deltas, but the matching raster may already have arrived. */
    suspend fun snapshot(message: ServerMessage.PaneSnapshot, requestMissingGraphics: Boolean = false) {
        val current = panes[message.paneId]
        if (requestMissingGraphics && message.graphicsSequence != null && current?.barrier != null) {
            // Finish one raster before requesting another: a high preview FPS must not
            // perpetually supersede an image whose transfer needs more than one interval.
            if (bytes(message) > 512 * 1024) { recover(message.paneId); return }
            current.latestPreview = message
            return
        }
        val matching = message.graphicsSequence?.let { panes[message.paneId]?.graphics?.get(it) }
        reset(message.paneId)
        val state = panes.getOrPut(message.paneId) { State() }
        if (matching != null) retain(state, matching)
        apply(message)
        if (message.graphicsSequence == null) {
            // Complete text-only previews also authoritatively remove an earlier image overlay.
            apply(ServerMessage.PaneGraphics(message.paneId, 0, true))
        }
        barrier(state, message.graphicsSequence)
        drain(message.paneId, state)
        if (requestMissingGraphics) state.barrier?.let { requestGraphics(message.paneId, it) }
    }

    suspend fun output(message: ServerMessage) {
        val pane = pane(message)
        val state = panes.getOrPut(pane) { State() }
        val bytes = bytes(message)
        if (state.outputBytes.toLong() + bytes > 512 * 1024 || state.output.size >= 256) {
            recover(pane); return
        }
        state.output.addLast(message)
        state.outputBytes += bytes
        drain(pane, state)
    }

    suspend fun graphics(message: ServerMessage.PaneGraphics) {
        val sequence = message.relaySequence ?: return recover(message.paneId)
        require(sequence >= 0)
        val state = panes.getOrPut(message.paneId) { State() }
        if (sequence <= state.applied || sequence < (state.barrier ?: -1) || sequence in state.graphics) return
        val size = graphicsSize(message)
        if (graphicsBytes + size > 32L * 1024 * 1024 || state.graphics.size >= 32) {
            recover(message.paneId); return
        }
        retain(state, message)
        drain(message.paneId, state)
    }

    /** A stalled raster transfer must not freeze the terminal indefinitely. */
    fun expire() {
        panes.filterValues { it.barrier != null && now() - it.barrierSince >= 120_000 }
            .keys.toList().forEach(::recover)
    }

    private fun recover(pane: String) { reset(pane); resync(pane) }
    private fun retain(state: State, message: ServerMessage.PaneGraphics) {
        val size = graphicsSize(message)
        state.graphics[message.relaySequence!!] = message
        state.graphicsBytes += size; graphicsBytes += size
    }
    private fun discard(state: State, sequence: Long): ServerMessage.PaneGraphics? {
        val message = state.graphics.remove(sequence) ?: return null
        val size = graphicsSize(message)
        state.graphicsBytes -= size; graphicsBytes -= size
        return message
    }
    private fun barrier(state: State, sequence: Long?) {
        require(sequence == null || sequence >= 0)
        state.barrier = sequence
        state.barrierSince = now()
        if (sequence != null) state.graphics.keys.filter { it < sequence }.toList().forEach { discard(state, it) }
    }
    private suspend fun drain(pane: String, state: State) {
        while (true) {
            val sequence = state.barrier
            if (sequence != null) {
                val graphics = discard(state, sequence) ?: return
                if (graphics.resyncRequired) { recover(pane); return }
                apply(graphics)
                state.applied = sequence
                state.barrier = null
                state.latestPreview?.let { latest ->
                    state.latestPreview = null
                    snapshot(latest, requestMissingGraphics = true)
                    return
                }
            }
            val message = state.output.removeFirstOrNull() ?: return
            state.outputBytes -= bytes(message)
            apply(message)
            barrier(state, when (message) {
                is ServerMessage.PaneSnapshot -> message.graphicsSequence
                is ServerMessage.PaneRepaint -> message.graphicsSequence
                else -> null
            })
        }
    }

    private fun pane(message: ServerMessage): String = when (message) {
        is ServerMessage.PaneOutput -> message.paneId
        is ServerMessage.PaneRepaint -> message.paneId
        is ServerMessage.PaneSnapshot -> message.paneId
        else -> error("Not a pane output message")
    }
    private fun bytes(message: ServerMessage): Int = when (message) {
        is ServerMessage.PaneOutput -> message.data.toByteArray(Charsets.UTF_8).size
        is ServerMessage.PaneRepaint -> message.data.toByteArray(Charsets.UTF_8).size
        is ServerMessage.PaneSnapshot -> message.data.toByteArray(Charsets.UTF_8).size
        else -> 0
    }
    private fun graphicsSize(message: ServerMessage.PaneGraphics): Long =
        message.images.sumOf { it.data.length.toLong() } + message.cells.size.toLong() * 128 + 4096
}
