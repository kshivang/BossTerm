package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-key conflated grid sampler for daemon-bound Resize sends. Auto-fit fires per layout tick
 * during a live window drag, and every forwarded resize reflows the daemon's full scrollback (then
 * the Resized echo reflows the mirror's) — so [request] conflates: the first request for a key is
 * forwarded as soon as its collector starts (one dispatch hop onto [scope]; the StateFlow retains
 * the value, so nothing is lost, just not strictly synchronous); during a burst the collector
 * forwards at most one grid per [minIntervalMs]; and once the burst stops the FINAL grid is always
 * delivered (a StateFlow always retains the last value, and the collector re-reads it after each
 * interval). Equal grids dedup for free (StateFlow skips value-equal updates).
 *
 * Extracted from [DaemonSessionBridge] so these invariants are unit-testable ([request]/[drop] are
 * Main-confined there, but this class is thread-safe regardless: per-key state is a StateFlow in a
 * ConcurrentHashMap).
 */
internal class ResizeSampler(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long,
    private val send: (key: String, cols: Int, rows: Int) -> Unit,
) {
    private class Entry(val grid: MutableStateFlow<Pair<Int, Int>?>, val job: Job)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Record the latest requested grid for [key]; the key's collector forwards it (conflated). */
    fun request(key: String, cols: Int, rows: Int) {
        val entry = entries.computeIfAbsent(key) {
            val grid = MutableStateFlow<Pair<Int, Int>?>(null)
            val job = scope.launch {
                grid.filterNotNull().collect { (c, r) ->
                    send(key, c, r)
                    delay(minIntervalMs) // conflation window: intermediate grids are skipped
                }
            }
            Entry(grid, job)
        }
        entry.grid.value = cols to rows
    }

    /** Stop and forget [key]'s sampler (its session/mirror closed). */
    fun drop(key: String) {
        entries.remove(key)?.job?.cancel()
    }

    /** Stop everything (bridge teardown). The owner usually also cancels [scope]. */
    fun clear() {
        entries.values.forEach { it.job.cancel() }
        entries.clear()
    }
}
