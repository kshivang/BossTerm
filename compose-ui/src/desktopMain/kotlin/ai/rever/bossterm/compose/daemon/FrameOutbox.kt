package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

/**
 * A per-connection send queue with TWO lanes, drained by ONE writer coroutine:
 *
 *  - [sendControl] — **guaranteed delivery** (bounded channel). Full-paint snapshots, session/layout
 *    lists, lifecycle (closed), and resizes go here: dropping one corrupts the mirror until the next
 *    resync, so they must never be evicted.
 *  - [sendOutput] — **best-effort** (char-bounded deque, drop-oldest). Incremental PTY output goes
 *    here: a stalled client must never back-pressure the PTY, so the oldest output is dropped under
 *    load. Each drop reports the affected session via [onOutputDropped], so the connection can
 *    re-snapshot it (the drop would otherwise silently corrupt the mirror until reconnect).
 *
 * [drainTo] fully flushes the control lane before each output emission, so a snapshot always reaches
 * the client before the output it anchors. On drain, consecutive queued chunks for the SAME session
 * are **coalesced** into one [Frame.Output] (bounded by [MAX_COALESCED_CHARS]) — only what is already
 * queued is merged, never awaited, so coalescing adds zero latency while collapsing a backlog of
 * small PTY chunks into few large frames (fewer websocket frames + syscalls under bulk output).
 * Both lanes are non-blocking on the producer side, so PTY / emulator threads never suspend.
 *
 * The output lane is bounded in CHARS (not frames): frame counts made the real memory bound depend
 * on chunk size (4096 frames × 64KB-max chunks), while a char budget is what the heap actually holds.
 */
internal class FrameOutbox(
    outputCapacityChars: Int = DEFAULT_OUTPUT_CAPACITY_CHARS,
    controlCapacity: Int = 1024,
) {
    /** What [drainTo] hands the socket writer. */
    sealed interface Frame {
        /** A JSON control frame (session/group lists, lifecycle, share state, …). */
        data class Text(val text: String) : Frame

        /** A pre-encoded binary control frame (snapshot). */
        class Binary(val bytes: ByteArray) : Frame

        /** Coalesced incremental output for one session; encoded to a binary frame at send time. */
        data class Output(val sessionId: String, val data: String) : Frame
    }

    // Control frames must not be DROPPED, but the lane is still BOUNDED: a wedged client (socket
    // reader stalled) plus repeated resync() — each pushing a full-scrollback Snapshot — would
    // otherwise grow the heap without limit. A client that can't even keep up with control frames is
    // unrecoverable, so on overflow we close the outbox: the writer ends, the connection drops, and
    // the GUI reconnects to a fresh snapshot. (Reconnects are paced by DaemonSessionBridge's
    // exponential backoff, so a persistently-wedged client settles into slow retries.)
    private val control = Channel<Frame>(capacity = controlCapacity)

    // Output lane: a plain deque under a lock (not a Channel) so eviction can report WHICH session
    // lost data ([onOutputDropped]) and so [takeCoalesced] can peek/merge same-session runs.
    private val outputLock = Any()
    private val outputQueue = ArrayDeque<Frame.Output>()
    private var outputChars = 0
    private val outputCapacity = outputCapacityChars.coerceAtLeast(1)

    // Wakes an idle drainer when output arrives (CONFLATED: one pending signal is enough — the
    // drainer sweeps the whole deque per pass).
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)

    @Volatile private var closed = false

    /**
     * Invoked (outside the queue lock, on the producing thread) with the session id of each evicted
     * output chunk. The attach connection uses it to schedule a healing re-snapshot — without it a
     * drop silently corrupts the mirror until the socket reconnects.
     */
    @Volatile var onOutputDropped: ((sessionId: String) -> Unit)? = null

    internal companion object {
        /** Max control frames drained before yielding to one output emission (fairness; see [drainTo]). */
        const val CONTROL_BURST = 64

        /** Output-lane budget. ~8MB of UTF-16 heap; a backlog beyond this means a badly stalled
         *  client, and dropped output is healed by [onOutputDropped]'s re-snapshot. */
        const val DEFAULT_OUTPUT_CAPACITY_CHARS = 4 * 1024 * 1024

        /** Secondary bound on queued output FRAMES: the char budget bounds payload heap, but each
         *  queued chunk also costs an object + deque slot, so a pathological stream of tiny chunks
         *  could hold millions of objects while staying under the char budget. Evicts oldest-first
         *  past either bound. */
        const val MAX_OUTPUT_FRAMES = 8192

        /** Cap on one coalesced output emission, so a huge backlog still yields to control frames. */
        const val MAX_COALESCED_CHARS = 256 * 1024
    }

    /** Enqueue a frame that must not be dropped (snapshot / list / lifecycle / resize). */
    fun sendControl(frame: Frame) {
        if (closed) return
        if (control.trySend(frame).isFailure) close() // saturated → unrecoverable client; drop it
    }

    /** Enqueue incremental output that may be dropped under back-pressure. */
    fun sendOutput(sessionId: String, data: String) {
        if (closed || data.isEmpty()) return
        var dropped: MutableSet<String>? = null
        synchronized(outputLock) {
            outputQueue.addLast(Frame.Output(sessionId, data))
            outputChars += data.length
            // Evict oldest-first past either bound (chars for payload heap, frames for object
            // count), but always keep the newest chunk — a single over-budget chunk must still go
            // out (it's bounded upstream by the PTY reader anyway).
            while ((outputChars > outputCapacity || outputQueue.size > MAX_OUTPUT_FRAMES) && outputQueue.size > 1) {
                val evicted = outputQueue.removeFirst()
                outputChars -= evicted.data.length
                (dropped ?: mutableSetOf<String>().also { dropped = it }).add(evicted.sessionId)
            }
        }
        wake.trySend(Unit)
        dropped?.forEach { sid -> onOutputDropped?.invoke(sid) }
    }

    /**
     * Purge every queued output chunk for [sessionId]. The drop-heal calls this right before it
     * enqueues the fresh snapshot: control frames outrank output in [drainTo], so without the purge
     * a snapshot would be emitted AHEAD of that session's older queued output — output whose effect
     * the snapshot already contains — and the stale chunks would then replay below the repaint as
     * duplicated content. Not reported via [onOutputDropped] (this IS the heal). The caller must
     * have detached the session's tap first, so nothing re-enqueues pre-snapshot chunks after the
     * purge; the fresh tap's prelude flush lands after the snapshot, unaffected.
     */
    fun dropQueuedOutput(sessionId: String) {
        synchronized(outputLock) {
            var removed = 0
            outputQueue.removeAll { c -> (c.sessionId == sessionId).also { if (it) removed += c.data.length } }
            outputChars -= removed
        }
    }

    /**
     * Take the head output chunk plus every immediately-queued successor for the SAME session,
     * merged into one [Frame.Output]. Never waits for more data. Null if the lane is empty.
     */
    private fun takeCoalesced(): Frame.Output? = synchronized(outputLock) {
        val first = outputQueue.removeFirstOrNull() ?: return null
        outputChars -= first.data.length
        if (outputQueue.firstOrNull()?.sessionId != first.sessionId) return first
        val sb = StringBuilder(first.data)
        while (sb.length < MAX_COALESCED_CHARS) {
            val next = outputQueue.firstOrNull() ?: break
            if (next.sessionId != first.sessionId) break
            outputQueue.removeFirst()
            outputChars -= next.data.length
            sb.append(next.data)
        }
        Frame.Output(first.sessionId, sb.toString())
    }

    /**
     * Drain both lanes, handing each frame to [emit] (which writes it to the socket). Drains all
     * pending control frames (up to [CONTROL_BURST] per pass, so control churn can't starve output)
     * before each single coalesced output emission; suspends when idle. Returns once the outbox is
     * closed and everything buffered has been flushed.
     */
    suspend fun drainTo(emit: suspend (Frame) -> Unit) {
        while (true) {
            // 1) Flush pending control frames first (priority), capped per pass for fairness.
            var drainedControl = false
            var controlClosed = false
            var burst = 0
            while (burst < CONTROL_BURST) {
                val r = control.tryReceive()
                when {
                    r.isSuccess -> { emit(r.getOrThrow()); drainedControl = true; burst++ }
                    r.isClosed -> { controlClosed = true; break }
                    else -> break // control lane empty
                }
            }
            // 2) Then a single coalesced output emission (re-checking control on the next loop).
            val merged = takeCoalesced()
            if (merged != null) { emit(merged); continue }
            // 3) If control had frames this pass, loop to re-check before suspending.
            if (drainedControl) continue
            // 4) Both lanes idle. If the outbox is closed, everything is flushed — done.
            if (controlClosed || closed) return
            // 5) Suspend until a control frame or an output wake (or close).
            val ended = select<Boolean> {
                control.onReceiveCatching { r -> if (r.isClosed) true else { emit(r.getOrThrow()); false } }
                // A wake signal (or wake-close) just re-runs the loop, which sweeps both lanes.
                wake.onReceiveCatching { false }
            }
            if (ended) return
        }
    }

    fun close() {
        closed = true
        runCatching { control.close() }
        runCatching { wake.close() }
    }
}
