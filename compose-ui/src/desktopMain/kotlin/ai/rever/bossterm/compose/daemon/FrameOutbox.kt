package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

/**
 * A per-connection send queue with TWO lanes, drained by ONE writer coroutine:
 *
 *  - [sendControl] — **guaranteed delivery** (unbounded). Full-paint snapshots, session/layout lists,
 *    lifecycle (closed), and resizes go here: dropping one corrupts the mirror until the next resync,
 *    so they must never be evicted.
 *  - [sendOutput] — **best-effort** (bounded, DROP_OLDEST). Incremental PTY output goes here: a stalled
 *    client must never back-pressure the PTY, so the oldest output is dropped under load and the next
 *    snapshot/resync heals it.
 *
 * [drainTo] fully flushes the control lane before each output frame, so a snapshot always reaches the
 * client before the output it anchors — without relying on a single channel's FIFO (where a burst of
 * output could otherwise evict the snapshot). Both lanes are non-blocking ([Channel.trySend]) so
 * producers on PTY / emulator threads never suspend.
 *
 * Replaces the previous single `Channel(4096, DROP_OLDEST)` that funneled *every* frame — including
 * snapshots — through the droppable path (issue: a burst could evict a snapshot, blanking the mirror).
 */
internal class FrameOutbox(outputCapacity: Int = 4096, controlCapacity: Int = 1024) {
    // Control frames must not be DROPPED, but the lane is still BOUNDED: a wedged client (socket
    // reader stalled) plus repeated resync() — each pushing a full-scrollback Snapshot — would
    // otherwise grow the heap without limit. A client that can't even keep up with control frames is
    // unrecoverable, so on overflow we close the outbox: the writer ends, the connection drops, and
    // the GUI reconnects to a fresh snapshot. (Was Channel.UNLIMITED, which defeated the backpressure
    // guarantee the outbox exists to provide.)
    //
    // This is NOT an unbounded "reconnect storm": the reconnect is paced by DaemonSessionBridge's
    // exponential backoff (250ms→4s), and a fresh attach replays only ONE current snapshot per session
    // (resync's beginLocked snapshots a session once, on first attach) — never the dropped backlog. So
    // a persistently-wedged client settles into slow backoff retries, not a tight loop. Hard-closing a
    // truly-stuck connection is preferred over coalescing here: coalescing redundant control frames
    // would require the outbox to parse frame semantics it deliberately treats as opaque bytes.
    private val control = Channel<String>(capacity = controlCapacity)
    private val output = Channel<String>(capacity = outputCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var closed = false

    /** Enqueue a frame that must not be dropped (snapshot / list / lifecycle / resize). */
    fun sendControl(text: String) {
        if (closed) return
        if (control.trySend(text).isFailure) close() // saturated → unrecoverable client; drop it
    }

    /** Enqueue incremental output that may be dropped under back-pressure. */
    fun sendOutput(text: String) {
        if (!closed) output.trySend(text)
    }

    /**
     * Drain both lanes until either is closed, handing each frame to [emit] (which writes it to the
     * socket). Drains all pending control frames before each single output frame; suspends on both
     * when idle. Returns when a lane is closed.
     */
    suspend fun drainTo(emit: suspend (String) -> Unit) {
        while (true) {
            // 1) Flush every pending control frame first (priority).
            var drainedControl = false
            while (true) {
                val r = control.tryReceive()
                when {
                    r.isSuccess -> { emit(r.getOrThrow()); drainedControl = true }
                    r.isClosed -> return
                    else -> break // control lane empty
                }
            }
            // 2) Then a single output frame (re-checking control on the next loop).
            val o = output.tryReceive()
            when {
                o.isSuccess -> { emit(o.getOrThrow()); continue }
                o.isClosed -> return
            }
            // 3) If control had frames this pass, loop to re-check before suspending.
            if (drainedControl) continue
            // 4) Both lanes idle — suspend until either delivers (or closes).
            val closed = select<Boolean> {
                control.onReceiveCatching { r -> if (r.isClosed) true else { emit(r.getOrThrow()); false } }
                output.onReceiveCatching { r -> if (r.isClosed) true else { emit(r.getOrThrow()); false } }
            }
            if (closed) return
        }
    }

    fun close() {
        closed = true
        runCatching { control.close() }
        runCatching { output.close() }
    }
}
