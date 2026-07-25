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
 * [admit] returns null when [data] may be sent now, or the delay before it should be retried.
 * Newer repaint data replaces older pending data, so a busy image TUI eventually receives the
 * latest text re-anchor without building an unbounded queue of obsolete screen paints.
 */
internal class DeferredPaneRepaint(
    private val scope: CoroutineScope,
    private val admit: (data: String) -> Long?,
    private val send: (data: String) -> Unit,
) {
    private val lock = Any()
    private var pending: String? = null
    private var worker: Job? = null

    fun offer(data: String) {
        if (data.isEmpty()) return
        synchronized(lock) {
            pending = data
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
            var sendNow: String? = null
            var retryAfterMs = 0L
            synchronized(lock) {
                val latest = pending ?: return
                val retry = admit(latest)
                if (retry == null) {
                    pending = null
                    sendNow = latest
                } else {
                    retryAfterMs = retry.coerceAtLeast(1L)
                }
            }
            if (sendNow != null) {
                send(sendNow!!)
            } else {
                delay(retryAfterMs)
            }
        }
    }
}
