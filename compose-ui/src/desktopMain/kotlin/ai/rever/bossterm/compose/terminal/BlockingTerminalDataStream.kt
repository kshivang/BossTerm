package ai.rever.bossterm.compose.terminal

import ai.rever.bossterm.compose.rendering.FrameLatencyProbe
import ai.rever.bossterm.terminal.TerminalDataStream
import ai.rever.bossterm.terminal.util.GraphemeUtils
import ai.rever.bossterm.terminal.util.GraphemeBoundaryUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A blocking TerminalDataStream implementation that allows appending data chunks
 * and blocks on getChar() instead of throwing EOF at chunk boundaries.
 *
 * This solves the issue where CSI sequences spanning multiple output chunks
 * were being truncated and displayed as visible text.
 *
 * Also handles incomplete grapheme clusters at chunk boundaries (e.g., surrogate
 * pairs, emoji ZWJ sequences) by buffering incomplete graphemes until the next chunk.
 */
/**
 * Performance mode for terminal data stream.
 */
enum class PerformanceMode {
    /** Optimized for interactive responsiveness - instant wake on data arrival */
    LATENCY,
    /** Optimized for bulk output - batches data for higher throughput */
    THROUGHPUT,
    /** Balance between latency and throughput */
    BALANCED;

    companion object {
        fun fromString(value: String): PerformanceMode = when (value.lowercase()) {
            "latency" -> LATENCY
            "throughput" -> THROUGHPUT
            "balanced" -> BALANCED
            else -> LATENCY // Default to latency for unknown values
        }
    }
}

class BlockingTerminalDataStream(
    /**
     * Performance mode controlling latency vs throughput tradeoff.
     * - LATENCY: Uses blocking take() for instant wake on data (best for interactive use)
     * - THROUGHPUT: Uses poll(100ms) for better batching (best for bulk output)
     * - BALANCED: Uses poll(10ms) as middle ground
     */
    val performanceMode: PerformanceMode = PerformanceMode.LATENCY
) : TerminalDataStream {
    companion object {
        /**
         * Sentinel value used to wake up blocking take() on close.
         * Uses a unique string that cannot appear in normal terminal output.
         */
        private const val CLOSE_SENTINEL = "\u0000CLOSE_SENTINEL\u0000"
    }

    // Requests run on the emulator thread between complete instructions. A marker wakes a
    // quiet terminal without injecting a terminal character or interrupting a partial CSI/OSC.
    private val checkpointMarker = String(charArrayOf('\u0000', 'C', 'P', '\u0000'))
    private data class CheckpointRequest(val run: () -> Unit, val fail: () -> Unit)
    private val checkpoints = ConcurrentLinkedQueue<CheckpointRequest>()
    private val queuedCheckpoints = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<String, CheckpointRequest>())
    private val queuedCheckpointMarkers = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<String, Boolean>()))
    private val checkpointCount = AtomicInteger()
    private data class ProcessedListener(val output: (String) -> Unit, val failed: (Exception) -> Unit, val filtered: Boolean)
    private val processedListeners = CopyOnWriteArrayList<ProcessedListener>()
    private val processedChars = StringBuilder()
    private var graphicsFilter = ai.rever.bossterm.compose.share.GraphicsOutputFilter()
    private val filteredPending = StringBuilder()
    private val filteredInstruction = StringBuilder()
    private var readingInstructionStart = false

    internal suspend fun <T> atCheckpoint(capture: () -> T): T {
        check(!closed) { "Terminal closed" }
        val result = CompletableDeferred<T>()
        if (checkpointCount.incrementAndGet() > 32) {
            checkpointCount.decrementAndGet()
            error("Too many pending terminal snapshots")
        }
        checkpoints.add(CheckpointRequest({
            if (result.isActive) try { result.complete(capture()) }
            catch (t: Throwable) { result.completeExceptionally(t) }
        }, { result.completeExceptionally(IOException("Terminal closed")) }))
        dataQueue.offer(checkpointMarker)
        if (closed) failCheckpoints()
        return try { withTimeout(5_000) { result.await() } } finally { result.cancel() }
    }

    /** Run after all earlier queued text, at its next complete instruction boundary. */
    internal suspend fun <T> atQueuedCheckpoint(capture: () -> T): T {
        check(!closed) { "Terminal closed" }
        val result = CompletableDeferred<T>()
        if (checkpointCount.incrementAndGet() > 32) {
            checkpointCount.decrementAndGet()
            error("Too many pending terminal actions")
        }
        val marker = String(charArrayOf('\u0000', 'Q', 'P', '\u0000'))
        queuedCheckpointMarkers.add(marker)
        queuedCheckpoints[marker] = CheckpointRequest({
            if (result.isActive) try { result.complete(capture()) }
            catch (t: Throwable) { result.completeExceptionally(t) }
        }, { result.completeExceptionally(IOException("Terminal closed")) })
        dataQueue.offer(marker)
        if (closed) failCheckpoints()
        return try { withTimeout(5_000) { result.await() } } finally { result.cancel() }
    }

    private fun consumeQueuedCheckpoint(chunk: String?): Boolean {
        if (!queuedCheckpointMarkers.remove(chunk)) return false
        val request = queuedCheckpoints.remove(chunk) ?: return true
        checkpoints.add(request)
        if (readingInstructionStart) runCheckpoints()
        return true
    }

    internal data class ProcessedSubscription<T>(val snapshot: T, val close: () -> Unit)

    /** Atomically capture the initial screen and start receiving only subsequently applied output. */
    internal suspend fun <T> observeProcessedOutput(
        listener: (String) -> Unit,
        onFailure: (Exception) -> Unit = {},
        graphicsFiltered: Boolean = false,
        capture: () -> T,
    ): ProcessedSubscription<T> {
        val observer = ProcessedListener(listener, onFailure, graphicsFiltered)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        try {
            val snapshot = atCheckpoint {
                val result = capture()
                if (processedListeners.isEmpty()) {
                    processedChars.setLength(0)
                    pushBackStack.asReversed().forEach { processedChars.append(it) }
                }
                if (graphicsFiltered && processedListeners.none { it.filtered }) {
                    graphicsFilter = ai.rever.bossterm.compose.share.GraphicsOutputFilter()
                    filteredPending.setLength(0)
                    filteredInstruction.setLength(0)
                    pushBackStack.asReversed().forEach { filteredPending.append(it) }
                }
                processedListeners.add(observer)
                if (cancelled.get()) processedListeners.remove(observer)
                result
            }
            return ProcessedSubscription(snapshot) { processedListeners.remove(observer) }
        } catch (t: Throwable) {
            cancelled.set(true)
            processedListeners.remove(observer)
            throw t
        }
    }

    internal fun readInstructionStart(): Char {
        runCheckpoints()
        readingInstructionStart = true
        return try { char } finally { readingInstructionStart = false }
    }

    internal fun instructionComplete() {
        if (processedListeners.isNotEmpty()) {
            val applied = processedChars.length - pushBackStack.size
            if (applied > 0) {
                val output = processedChars.substring(0, applied)
                processedChars.delete(0, applied)
                for (listener in processedListeners.filter { !it.filtered }) {
                    // Sharing must never break execution of a local terminal.
                    try { listener.output(output) } catch (e: Exception) {
                        processedListeners.remove(listener)
                        runCatching { listener.failed(e) }
                    }
                }
            }
        } else processedChars.setLength(0)
        if (processedListeners.any { it.filtered }) {
            val applied = filteredPending.length - pushBackStack.size
            if (applied > 0) {
                filteredInstruction.append(graphicsFilter.filter(filteredPending.substring(0, applied)).output)
                filteredPending.delete(0, applied)
            }
            val output = filteredInstruction.toString()
            filteredInstruction.setLength(0)
            for (listener in processedListeners.filter { it.filtered }) {
                try { listener.output(output) } catch (e: Exception) {
                    processedListeners.remove(listener)
                    runCatching { listener.failed(e) }
                }
            }
        } else {
            filteredPending.setLength(0)
            filteredInstruction.setLength(0)
        }
        runCheckpoints()
    }

    private fun runCheckpoints() {
        while (true) {
            val request = checkpoints.poll() ?: return
            checkpointCount.decrementAndGet()
            request.run()
        }
    }

    private fun failCheckpoints() {
        synchronized(queuedCheckpoints) {
            queuedCheckpoints.values.forEach { checkpointCount.decrementAndGet(); it.fail() }
            queuedCheckpoints.clear()
        }
        while (true) {
            val request = checkpoints.poll() ?: return
            checkpointCount.decrementAndGet()
            request.fail()
        }
    }

    private fun recordProcessedChar(c: Char) {
        if (processedListeners.isEmpty()) return
        if (processedListeners.any { !it.filtered } && processedChars.length >= 1024 * 1024) {
            val observers = processedListeners.filter { !it.filtered }
            processedListeners.removeAll(observers.toSet())
            processedChars.setLength(0)
            observers.forEach { runCatching { it.failed(IOException("Terminal relay instruction exceeded buffer limit")) } }
        } else if (processedListeners.any { !it.filtered }) processedChars.append(c)
        if (processedListeners.any { it.filtered }) {
            filteredPending.append(c)
            // Keep look-ahead characters unfiltered until processChar completes. Large graphics
            // strings are discarded incrementally rather than retained until their terminator.
            if (filteredPending.length >= 16_384) {
                val count = filteredPending.length - 16
                filteredInstruction.append(graphicsFilter.filter(filteredPending.substring(0, count)).output)
                filteredPending.delete(0, count)
                if (filteredInstruction.length > 1024 * 1024) {
                    val observers = processedListeners.filter { it.filtered }
                    processedListeners.removeAll(observers.toSet())
                    filteredPending.setLength(0); filteredInstruction.setLength(0)
                    observers.forEach { runCatching { it.failed(IOException("Terminal relay instruction exceeded buffer limit")) } }
                }
            }
        }
    }

    private val buffer = StringBuilder()
    private var position = 0
    private val dataQueue: BlockingQueue<String> = LinkedBlockingQueue()
    private val queuedChars = java.util.concurrent.atomic.AtomicLong()
    /** Pending chunks only; the emulator may additionally hold one bounded remote frame. */
    internal val queuedOutputChars: Long get() = queuedChars.get().coerceAtLeast(0)

    /**
     * Arrival timestamps for the chunks in [dataQueue], one per entry, in the same order.
     *
     * Kept alongside rather than inside the queue so the shipped type stays `String` and
     * nothing on the hot path changes when [FrameLatencyProbe] is off - the queue is only
     * ever written under `FrameLatencyProbe.enabled`. Stamped here, before the poll wait
     * below, so that whatever the performance mode spends waiting lands inside the
     * measurement instead of ahead of it.
     */
    private val arrivalNanos = ConcurrentLinkedQueue<Long>()

    /** Offer a chunk plus, when probing, its arrival time. Keeps the two queues aligned. */
    private fun enqueue(chunk: String) {
        queuedChars.addAndGet(chunk.length.toLong())
        if (FrameLatencyProbe.enabled) arrivalNanos.offer(System.nanoTime())
        dataQueue.offer(chunk)
    }

    /** Pair every successful take from [dataQueue] with its arrival stamp. */
    private fun took(chunk: String?): String? {
        if (chunk != null && chunk != CLOSE_SENTINEL && chunk !== checkpointMarker && !queuedCheckpointMarkers.contains(chunk)) queuedChars.addAndGet(-chunk.length.toLong())
        if (FrameLatencyProbe.enabled && chunk != null && chunk != CLOSE_SENTINEL && chunk !== checkpointMarker && !queuedCheckpointMarkers.contains(chunk)) {
            arrivalNanos.poll()?.let { stamped ->
                FrameLatencyProbe.markArrival(stamped)
                FrameLatencyProbe.markDequeued(stamped, chunk.length)
            }
        }
        return chunk
    }
    @Volatile private var closed = false
    private val pushBackStack = mutableListOf<Char>()

    /**
     * Threshold for buffer compaction. When position exceeds this value,
     * consumed data is removed from the buffer to prevent memory leaks.
     * Set to 4KB as a balance between compaction overhead and memory usage.
     */
    private val compactionThreshold = 4096

    /**
     * Compact the buffer by removing already-consumed data.
     * This prevents the StringBuilder from growing indefinitely during long sessions.
     *
     * Called periodically when position exceeds compactionThreshold.
     */
    private fun compactBuffer() {
        if (position > compactionThreshold) {
            buffer.delete(0, position)
            position = 0
        }
    }

    /**
     * Reusable StringBuilder for readNonControlCharacters to avoid allocation per call.
     * Pre-sized to 256 for typical read sizes.
     */
    private val readBuilder = StringBuilder(256)

    /**
     * Buffer for incomplete grapheme clusters at chunk boundaries.
     * When a chunk ends mid-grapheme (e.g., high surrogate without low surrogate,
     * emoji without variation selector), the incomplete part is stored here
     * and prepended to the next chunk.
     */
    private var incompleteGraphemeBuffer = ""

    /**
     * Optional debug callback invoked when data is appended.
     * Used by debug tools to capture I/O for visualization.
     */
    var debugCallback: ((String) -> Unit)? = null

    /**
     * Listeners invoked with each complete chunk of raw PTY output as it is
     * appended (before emulator processing), independent of debug mode. Used by
     * session sharing to relay the raw byte/escape stream to remote viewers, which
     * re-emulate it with xterm.js. Same data the emulator consumes, so fidelity is
     * exact. A list (not a single slot) so overlapping observers — e.g. an
     * all-windows share and a window share of the same pane — can attach and
     * detach independently without clobbering each other.
     */
    private val rawOutputListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addRawOutputListener(listener: (String) -> Unit) {
        rawOutputListeners.addIfAbsent(listener)
    }

    fun removeRawOutputListener(listener: (String) -> Unit) {
        rawOutputListeners.remove(listener)
    }

    private fun notifyRawOutput(data: String) {
        for (l in rawOutputListeners) l(data)
    }

    /**
     * Optional callback invoked when terminal state changes (data arrives from PTY).
     * Used by type-ahead system to validate/clear predictions.
     */
    var onTerminalStateChanged: (() -> Unit)? = null

    /**
     * Optional callback invoked when a new chunk of data starts being consumed.
     * Used to start batch operations in the text buffer.
     */
    var onChunkStart: (() -> Unit)? = null

    /**
     * Optional callback invoked when a chunk of data has been fully consumed.
     * Used to end batch operations in the text buffer.
     */
    var onChunkEnd: (() -> Unit)? = null

    // Track whether we're in the middle of consuming a chunk
    private var inChunk = false

    /**
     * Append a chunk of data to the stream.
     *
     * Handles incomplete grapheme clusters at chunk boundaries by:
     * 1. Prepending any buffered incomplete grapheme from the previous chunk
     * 2. Checking if this chunk ends with an incomplete grapheme
     * 3. Buffering the incomplete part for the next chunk
     * 4. Only queuing the complete grapheme portion
     *
     * This ensures surrogate pairs, emoji sequences, and combining characters
     * are never split across chunk boundaries.
     */
    fun append(data: String) {
        if (closed) return

        // Prepend any buffered incomplete grapheme from previous chunk
        val fullData = incompleteGraphemeBuffer + data
        incompleteGraphemeBuffer = ""

        // Check if the chunk ends with an incomplete grapheme
        val lastCompleteIndex = GraphemeBoundaryUtils.findLastCompleteGraphemeBoundary(fullData)

        if (lastCompleteIndex < fullData.length) {
            // Chunk ends mid-grapheme - buffer the incomplete part
            incompleteGraphemeBuffer = fullData.substring(lastCompleteIndex)
            val completeData = fullData.substring(0, lastCompleteIndex)

            if (completeData.isNotEmpty()) {
                enqueue(completeData)
                // Invoke debug callback only for complete data
                debugCallback?.invoke(completeData)
                notifyRawOutput(completeData)
            }
        } else {
            // All graphemes are complete
            enqueue(fullData)
            debugCallback?.invoke(fullData)
            notifyRawOutput(fullData)
        }
    }

    /**
     * Signal that no more data will be appended.
     * Offers a sentinel value to wake any blocking take() call.
     */
    fun close() {
        closed = true
        failCheckpoints()
        // Wake up any blocking take() call immediately
        dataQueue.offer(CLOSE_SENTINEL)
    }

    override val char: Char
        @Throws(IOException::class)
        get() {
            // First check pushback stack
            if (pushBackStack.isNotEmpty()) {
                return pushBackStack.removeAt(pushBackStack.size - 1)
            }

            // If we have data in the buffer, return it
            while (position >= buffer.length) {
                // End previous chunk if we were in one (buffer exhausted)
                if (inChunk) {
                    inChunk = false
                    onChunkEnd?.invoke()
                }

                // Compact buffer to prevent memory leak (issue #179)
                // Do this when buffer is exhausted, before waiting for more data
                compactBuffer()

                // Notify type-ahead system before blocking wait
                // This allows the type-ahead manager to validate predictions
                // against the current terminal state before we wait for more data
                onTerminalStateChanged?.invoke()

                // Need more data - behavior depends on performance mode (issue #146)
                val chunk = took(if (closed) {
                    dataQueue.poll() // Non-blocking if closed
                } else {
                    when (performanceMode) {
                        // LATENCY: Blocking take() for instant wake on data arrival
                        // Best for interactive use - eliminates 100ms poll timeout latency
                        PerformanceMode.LATENCY -> dataQueue.take()
                        // THROUGHPUT: Poll with 100ms timeout for better batching
                        // Best for bulk output - allows more data to accumulate
                        PerformanceMode.THROUGHPUT -> dataQueue.poll(100, TimeUnit.MILLISECONDS)
                        // BALANCED: Poll with 10ms timeout as middle ground
                        PerformanceMode.BALANCED -> dataQueue.poll(10, TimeUnit.MILLISECONDS)
                    }
                })

                if (consumeQueuedCheckpoint(chunk)) continue
                if (chunk === checkpointMarker) {
                    if (readingInstructionStart) runCheckpoints()
                    continue
                }

                // Check for close sentinel
                if (chunk == CLOSE_SENTINEL) {
                    throw TerminalDataStream.EOF()
                }

                if (chunk != null) {
                    // Start new chunk
                    if (!inChunk) {
                        inChunk = true
                        onChunkStart?.invoke()
                    }
                    buffer.append(chunk)
                } else if (closed && dataQueue.isEmpty()) {
                    // Stream is closed and no more data
                    throw TerminalDataStream.EOF()
                }
            }

            return buffer[position++].also(::recordProcessedChar)
        }

    override fun pushChar(c: Char) {
        pushBackStack.add(c)
    }

    override fun readNonControlCharacters(maxChars: Int): String {
        // Reuse StringBuilder to avoid allocation per call
        readBuilder.setLength(0)
        var count = 0

        while (count < maxChars) {
            // Check pushback first
            if (pushBackStack.isNotEmpty()) {
                val c = pushBackStack.removeAt(pushBackStack.size - 1)
                if (c < ' ' || c == 0x7F.toChar()) {
                    pushChar(c)
                    break
                }
                readBuilder.append(c)
                count++
                continue
            }

            // Check if we need more data - timeout depends on performance mode
            if (position >= buffer.length) {
                // Compact buffer to prevent memory leak (issue #179)
                compactBuffer()

                val chunk = took(when (performanceMode) {
                    // LATENCY: Non-blocking - return immediately with what we have
                    PerformanceMode.LATENCY -> dataQueue.poll()
                    // THROUGHPUT: Wait longer for better batching
                    PerformanceMode.THROUGHPUT -> dataQueue.poll(10, TimeUnit.MILLISECONDS)
                    // BALANCED: Short wait for moderate batching
                    PerformanceMode.BALANCED -> dataQueue.poll(5, TimeUnit.MILLISECONDS)
                })
                if (consumeQueuedCheckpoint(chunk)) break
                if (chunk === checkpointMarker) continue
                if (chunk != null && chunk != CLOSE_SENTINEL) {
                    buffer.append(chunk)
                } else {
                    break // No data available or stream closed
                }
            }

            if (position < buffer.length) {
                val c = buffer[position]
                if (c < ' ' || c == 0x7F.toChar()) {
                    break // Stop at control character
                }
                readBuilder.append(c)
                recordProcessedChar(c)
                position++
                count++
            } else {
                break
            }
        }

        return readBuilder.toString()
    }

    override fun pushBackBuffer(bytes: CharArray?, length: Int) {
        if (bytes == null) return
        // Push back in reverse order so they come out in correct order
        for (i in length - 1 downTo 0) {
            pushBackStack.add(bytes[i])
        }
    }

    override val isEmpty: Boolean
        get() = pushBackStack.isEmpty() &&
               position >= buffer.length &&
               (closed || dataQueue.isEmpty())
}
