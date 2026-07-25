package ai.rever.bossterm.compose.share

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coalesces authoritative pane repaints while their byte/rate limiter is closed.
 *
 * [admit] returns null when the captured data may be sent now, or the delay before it should be
 * retried. The pending value is a capture function, not an encoded screen: every retry captures
 * the latest terminal state so ordinary output cannot overtake a stale deferred repaint.
 */
internal class DeferredPaneRepaint(
    private val scope: CoroutineScope,
    private val admit: (data: String) -> Long?,
    private val send: (data: String) -> Unit,
) {
    private val lock = Any()
    private var pending: (() -> String)? = null
    private var worker: Job? = null

    fun offer(capture: () -> String) {
        synchronized(lock) {
            pending = capture
            startWorkerLocked()
        }
    }

    fun cancel() {
        synchronized(lock) {
            pending = null
            worker?.cancel()
        }
    }

    private fun startWorkerLocked() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                drain()
            } finally {
                val completedWorker = currentCoroutineContext()[Job]
                synchronized(lock) {
                    if (worker === completedWorker) {
                        worker = null
                        if (pending != null && scope.isActive) startWorkerLocked()
                    }
                }
            }
        }
    }

    private suspend fun drain() {
        while (scope.isActive) {
            val capture = synchronized(lock) { pending } ?: return
            val latestScreen = capture()
            var sendNow = false
            var retryAfterMs: Long? = null
            synchronized(lock) {
                // A newer placement arrived while this screen was being encoded. Discard the
                // obsolete capture and immediately recapture through the latest supplier.
                if (pending === capture) {
                    val retry = admit(latestScreen)
                    if (retry == null) {
                        pending = null
                        sendNow = true
                    } else {
                        retryAfterMs = retry.coerceAtLeast(1L)
                    }
                }
            }
            when {
                sendNow -> send(latestScreen)
                retryAfterMs != null -> delay(retryAfterMs!!)
            }
        }
    }
}
