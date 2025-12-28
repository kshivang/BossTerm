package ai.rever.bossterm.compose.terminal

import ai.rever.bossterm.terminal.TerminalDataStream
import ai.rever.bossterm.terminal.util.GraphemeUtils
import ai.rever.bossterm.terminal.util.GraphemeBoundaryUtils
import java.io.IOException
import java.util.concurrent.BlockingQueue
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

    private val buffer = StringBuilder()
    private var position = 0
    private val dataQueue: BlockingQueue<String> = LinkedBlockingQueue()
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
                dataQueue.offer(completeData)
                // Invoke debug callback only for complete data
                debugCallback?.invoke(completeData)
            }
        } else {
            // All graphemes are complete
            dataQueue.offer(fullData)
            debugCallback?.invoke(fullData)
        }
    }

    /**
     * Signal that no more data will be appended.
     * Offers a sentinel value to wake any blocking take() call.
     */
    fun close() {
        closed = true
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
                val chunk = if (closed) {
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

            return buffer[position++]
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

                val chunk = when (performanceMode) {
                    // LATENCY: Non-blocking - return immediately with what we have
                    PerformanceMode.LATENCY -> dataQueue.poll()
                    // THROUGHPUT: Wait longer for better batching
                    PerformanceMode.THROUGHPUT -> dataQueue.poll(10, TimeUnit.MILLISECONDS)
                    // BALANCED: Short wait for moderate batching
                    PerformanceMode.BALANCED -> dataQueue.poll(5, TimeUnit.MILLISECONDS)
                }
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
