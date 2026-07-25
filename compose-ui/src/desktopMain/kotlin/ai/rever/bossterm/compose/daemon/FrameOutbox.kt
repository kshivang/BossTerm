package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * A per-connection send queue with TWO lanes, drained by ONE writer coroutine:
 *
 *  - [sendControl] — **guaranteed delivery** (bounded channel). Session/layout lists, lifecycle
 *    (closed), and resizes go here: dropping one corrupts the mirror until the next resync, so they
 *    must never be evicted. [sendSnapshot] and [sendRecoverableControl] share the lane but trade a
 *    momentarily-full backlog for a deferred heal / resync sentinel instead of the connection.
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
    private val controlCapacityBytes: Long = DEFAULT_CONTROL_CAPACITY_BYTES,
) {
    /** What [drainTo] hands the socket writer. */
    sealed interface Frame {
        /** A JSON control frame (session/group lists, lifecycle, share state, …). */
        data class Text(val text: String) : Frame

        /** A pre-encoded binary control frame (snapshot). */
        class Binary(val bytes: ByteArray) : Frame

        /**
         * Ordered pane bytes encoded at send time. Repaints are queue barriers and remain distinct
         * from surrounding incremental output so the viewer can preserve its scroll position.
         */
        data class Output(
            val sessionId: String,
            val data: String,
            val repaint: Boolean = false,
        ) : Frame
    }

    // Control frames must not be DROPPED, but the lane is still BOUNDED: a wedged client (socket
    // reader stalled) plus repeated resync() — each pushing a full-scrollback Snapshot — would
    // otherwise grow the heap without limit. A client that can't even keep up with control frames is
    // unrecoverable, so on overflow we close the outbox: the writer ends, the connection drops, and
    // the GUI reconnects to a fresh snapshot. (Reconnects are paced by DaemonSessionBridge's
    // exponential backoff, so a persistently-wedged client settles into slow retries.)
    private val control = Channel<Frame>(capacity = controlCapacity)
    private val controlBytes = AtomicLong()

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
        private val log = LoggerFactory.getLogger(FrameOutbox::class.java)

        /** Max control frames drained before yielding to one output emission (fairness; see [drainTo]). */
        const val CONTROL_BURST = 64

        /** Output-lane budget. ~8MB of UTF-16 heap; a backlog beyond this means a badly stalled
         *  client, and dropped output is healed by [onOutputDropped]'s re-snapshot. */
        const val DEFAULT_OUTPUT_CAPACITY_CHARS = 4 * 1024 * 1024

        /**
         * Heap-oriented bound for large snapshots/graphics frames, independent of frame count.
         * The web-share path forwards at most 16 MiB of rasters per pane; base64 expands that by
         * 4/3 and the queued JVM String uses two bytes per char (~43 MiB before JSON). 64 MiB
         * admits one legal full-graphics frame while bounding a stalled connection.
         */
        const val DEFAULT_CONTROL_CAPACITY_BYTES = 64L * 1024 * 1024

        /** Secondary bound on queued output FRAMES: the char budget bounds payload heap, but each
         *  queued chunk also costs an object + deque slot, so a pathological stream of tiny chunks
         *  could hold millions of objects while staying under the char budget. Evicts oldest-first
         *  past either bound. */
        const val MAX_OUTPUT_FRAMES = 8192

        /** Cap on one coalesced output emission, so a huge backlog still yields to control frames. */
        const val MAX_COALESCED_CHARS = 256 * 1024
    }

    /** Enqueue a frame that must not be dropped (list / lifecycle / resize). */
    fun sendControl(frame: Frame) {
        if (closed || trySendControlFrame(frame)) return
        val bytes = estimatedBytes(frame)
        log.warn(
            "Closing stalled outbox: control frame/backlog needs {} bytes (capacity {})",
            bytes,
            controlCapacityBytes,
        )
        close()
    }

    /**
     * Enqueue a full-paint snapshot for [sessionId] — guaranteed while the lane has room, but a
     * merely-BACKLOGGED lane must not cost the connection.
     *
     * [controlCapacityBytes] bounds the whole queued control backlog, and a window/global share
     * beginning every pane at once (each with a full styled scrollback) can queue snapshot bytes
     * faster than a slow link drains them. That backlog is transient, so report the session as
     * dropped instead of closing: the connection's [onOutputDropped] heal re-snapshots it once the
     * writer has caught up. Only a single frame too large to EVER fit the ceiling is unrecoverable.
     */
    fun sendSnapshot(sessionId: String, frame: Frame) {
        if (closed || trySendControlFrame(frame)) return
        val bytes = estimatedBytes(frame)
        if (bytes > controlCapacityBytes) {
            log.warn(
                "Closing stalled outbox: snapshot frame needs {} bytes (capacity {})",
                bytes,
                controlCapacityBytes,
            )
            close()
            return
        }
        log.warn(
            "Deferring {} snapshot ({} bytes): control backlog is at the {}-byte ceiling",
            sessionId,
            bytes,
            controlCapacityBytes,
        )
        onOutputDropped?.invoke(sessionId)
    }

    /**
     * Try a large but recoverable graphics frame. If it cannot fit, enqueue [recoveryFrame]
     * instead; the lightweight resync-required sentinel makes the viewer request a full frame
     * after the writer drains, without inventing a revision or sacrificing the connection.
     */
    fun sendRecoverableControl(frame: Frame, recoveryFrame: Frame) {
        if (closed || trySendControlFrame(frame)) return
        log.warn(
            "Dropping recoverable graphics frame ({} bytes); enqueueing resync metadata",
            estimatedBytes(frame),
        )
        sendControl(recoveryFrame)
    }

    private fun trySendControlFrame(frame: Frame): Boolean {
        val bytes = estimatedBytes(frame)
        if (bytes > controlCapacityBytes || !reserveControl(bytes)) return false
        if (control.trySend(frame).isSuccess) return true
        controlBytes.addAndGet(-bytes)
        return false
    }

    private fun reserveControl(bytes: Long): Boolean {
        while (true) {
            val current = controlBytes.get()
            if (bytes > controlCapacityBytes - current) return false
            if (controlBytes.compareAndSet(current, current + bytes)) return true
        }
    }

    private fun estimatedBytes(frame: Frame): Long = when (frame) {
        is Frame.Text -> frame.text.length.toLong() * 2
        is Frame.Binary -> frame.bytes.size.toLong()
        is Frame.Output -> frame.data.length.toLong() * 2
    }

    private fun releaseControl(frame: Frame) {
        controlBytes.addAndGet(-estimatedBytes(frame))
    }

    /** Enqueue incremental output that may be dropped under back-pressure. */
    fun sendOutput(sessionId: String, data: String) {
        enqueueOutput(sessionId, data, repaint = false)
    }

    /** Enqueue an authoritative screen repaint in the same ordered lane as pane output. */
    fun sendRepaint(sessionId: String, data: String) {
        enqueueOutput(sessionId, data, repaint = true)
    }

    private fun enqueueOutput(sessionId: String, data: String, repaint: Boolean) {
        if (closed || data.isEmpty()) return
        var dropped: MutableSet<String>? = null
        synchronized(outputLock) {
            outputQueue.addLast(Frame.Output(sessionId, data, repaint))
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
        if (first.repaint) return first
        val queuedNext = outputQueue.firstOrNull()
        if (queuedNext?.sessionId != first.sessionId || queuedNext.repaint) return first
        val sb = StringBuilder(first.data)
        while (sb.length < MAX_COALESCED_CHARS) {
            val next = outputQueue.firstOrNull() ?: break
            if (next.sessionId != first.sessionId || next.repaint) break
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
                    r.isSuccess -> {
                        val frame = r.getOrThrow()
                        releaseControl(frame)
                        emit(frame)
                        drainedControl = true
                        burst++
                    }
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
                control.onReceiveCatching { r ->
                    if (r.isClosed) {
                        true
                    } else {
                        val frame = r.getOrThrow()
                        releaseControl(frame)
                        emit(frame)
                        false
                    }
                }
                // A wake signal (or wake-close) just re-runs the loop, which sweeps both lanes.
                wake.onReceiveCatching { false }
            }
            // Close observed while suspended: CONTINUE (not return) so the next pass does one
            // final sweep of both lanes — an output chunk that raced close() past its wake signal
            // is still flushed, and step 4 then terminates. Returning here would strand it.
            if (ended) continue
        }
    }

    fun close() {
        closed = true
        runCatching { control.close() }
        runCatching { wake.close() }
    }
}
