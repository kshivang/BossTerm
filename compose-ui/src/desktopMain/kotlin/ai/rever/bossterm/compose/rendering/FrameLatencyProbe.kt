package ai.rever.bossterm.compose.rendering

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Opt-in glass-to-pixel latency instrumentation, gated on `BOSSTERM_FRAME_PROBE=1`.
 *
 * The existing `benchmark/` suite measures how fast the emulator *consumes* bytes - it
 * cannot see the time between a byte landing in the PTY and the pixel that byte produces.
 * That interval is where the redraw debounce, the data-stream poll timeout and the paint
 * cost all live, so it needs its own measurement before any of them are touched.
 *
 * What each series means:
 *
 * - [byteToPaint] - a PTY chunk's arrival (stamped in `BlockingTerminalDataStream.append`,
 *   before the queue wait) to the end of the paint pass that first draws it. This is the
 *   number a user feels. It is *draw-issued*, not photons: it excludes GPU present and
 *   vsync, so it is a lower bound on real latency and must be anchored against an external
 *   capture before any conclusion rests on the absolute value. Deltas between two builds
 *   measured the same way are sound regardless.
 * - [paintCost] - wall time inside `renderTerminal`. The number that decides whether a
 *   throttle is needed at all.
 * - [snapshotCost] - wall time the UI thread spends holding the terminal buffer lock to
 *   capture a frame. Expected to scale with scrollback depth, which is the shape that
 *   makes a long-lived session feel worse than a fresh one.
 * - [drawCallsPerFrame] - `drawText` invocations per paint. The multiplier on text layout.
 *
 * All recording happens on the UI thread (the paint pass); [markArrival] is the one method
 * called from the PTY/emulator threads and only performs a CAS.
 *
 * When disabled, every method here is an `enabled` check and a return, and no timestamps
 * are taken. Nothing in this file allocates on the hot path.
 */
object FrameLatencyProbe {

    const val ENV_FLAG: String = "BOSSTERM_FRAME_PROBE"

    /** Sentinel for "no un-rendered chunk is pending". */
    private const val NONE = Long.MIN_VALUE

    /**
     * Whether any measurement happens at all.
     *
     * Seeded from the environment and settable only from this module, so tests can exercise
     * the recording paths without a process-wide env var. Nothing in production flips it:
     * the sampler thread is started from `init` against the env-derived value, so a later
     * write enables recording but never spawns a writer.
     */
    @Volatile
    var enabled: Boolean = System.getenv(ENV_FLAG).let { it == "1" || it.equals("true", ignoreCase = true) }
        internal set

    /**
     * Arrival time of the oldest PTY chunk not yet drawn, or [NONE].
     *
     * Only the *oldest* is kept: a paint draws the whole buffer, so a frame that renders
     * chunk N also renders every chunk before it, and the latency that matters is the one
     * the earliest byte experienced. Later arrivals inside the same frame lose the CAS and
     * are deliberately dropped rather than overwriting an older, worse sample.
     */
    private val pendingArrival = AtomicLong(NONE)

    /** Reset per paint by [beginFrame], read by [endFrame]. */
    private val drawCalls = AtomicInteger(0)

    val byteToPaint: Histogram = Histogram()
    val paintCost: Histogram = Histogram()
    val snapshotCost: Histogram = Histogram()
    val drawCallsPerFrame: Histogram = Histogram()

    /** Paints that drew no newly-arrived data (blink, resize, selection, scroll). */
    private val idleFrames = AtomicLong(0)

    private val startedAtNanos = System.nanoTime()

    /**
     * Where the sampler writes. Overridable so two builds can be measured into separate
     * files in one scripted run.
     */
    private val outFile: File =
        File(System.getenv("BOSSTERM_FRAME_PROBE_OUT") ?: "${System.getProperty("user.home")}/.bossterm/frame-probe.json")

    /**
     * Touch this file to zero the histograms before a workload; the sampler consumes and
     * deletes it. A file rather than a signal or an MCP tool because the probe must add no
     * product surface: nothing here is reachable, or even constructed, without the env flag.
     */
    private val resetFile: File = File(outFile.absoluteFile.parentFile, "frame-probe.reset")

    init {
        if (enabled) startSampler()
    }

    private fun startSampler() {
        Runtime.getRuntime().addShutdownHook(Thread({ writeSnapshotQuietly() }, "frame-probe-final"))
        Thread({
            while (true) {
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                    if (resetFile.exists()) {
                        resetFile.delete()
                        reset()
                    }
                    writeSnapshotQuietly()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // A diagnostic must never take the app down. Keep sampling.
                }
            }
        }, "frame-probe-sampler").apply { isDaemon = true }.start()
    }

    private fun writeSnapshotQuietly() {
        runCatching {
            val dir = outFile.absoluteFile.parentFile
            dir?.mkdirs()
            val tmp = File(dir, outFile.name + ".tmp")
            tmp.writeText(json())
            // Atomic rename so a reader never sees a half-written file.
            if (!tmp.renameTo(outFile)) {
                outFile.writeText(json())
                tmp.delete()
            }
        }
    }

    /** Machine-readable snapshot. Latencies in milliseconds. */
    fun json(): String = buildString {
        append("{\n")
        append("  \"enabled\": ").append(enabled).append(",\n")
        append("  \"uptimeSeconds\": ")
            .append(String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - startedAtNanos) / 1e9))
            .append(",\n")
        append("  \"byteToPaintMs\": ").append(byteToPaint.jsonMillis()).append(",\n")
        append("  \"paintCostMs\": ").append(paintCost.jsonMillis()).append(",\n")
        append("  \"lockedCaptureMs\": ").append(snapshotCost.jsonMillis()).append(",\n")
        append("  \"drawCallsPerFrame\": ").append(drawCallsPerFrame.jsonRaw()).append(",\n")
        append("  \"idleFrames\": ").append(idleFrames.get()).append("\n")
        append("}\n")
    }

    private const val SAMPLE_INTERVAL_MS = 1_000L

    /**
     * Stamp a PTY chunk's arrival. Called from the data stream the moment a chunk is
     * handed over, ahead of the queue wait, so the poll timeout is inside the measurement
     * rather than hidden before it.
     */
    fun markArrival(nanos: Long) {
        if (!enabled) return
        // Keep the earliest pending arrival; a later chunk must not reset the clock.
        pendingArrival.compareAndSet(NONE, nanos)
    }

    /** Timestamp for a later `record*` call, or 0 when the probe is off. */
    fun startTiming(): Long = if (enabled) System.nanoTime() else 0L

    /** Start of a paint pass. Returns the start timestamp to hand back to [endFrame]. */
    fun beginFrame(): Long {
        if (!enabled) return 0L
        drawCalls.set(0)
        return System.nanoTime()
    }

    /** End of a paint pass. [startNanos] is whatever [beginFrame] returned. */
    fun endFrame(startNanos: Long) {
        if (!enabled) return
        val now = System.nanoTime()
        paintCost.recordNanos(now - startNanos)
        drawCallsPerFrame.record(drawCalls.get().toLong())
        val arrival = pendingArrival.getAndSet(NONE)
        if (arrival == NONE) idleFrames.incrementAndGet() else byteToPaint.recordNanos(now - arrival)
    }

    /**
     * Wall time the UI thread spent holding the terminal buffer lock to capture a frame.
     *
     * The whole locked region, not just `createIncrementalSnapshot`, because the cost that
     * matters is how long the emulator is blocked - and it is the same region either way.
     */
    fun recordSnapshot(startNanos: Long) {
        if (!enabled) return
        snapshotCost.recordNanos(System.nanoTime() - startNanos)
    }

    /** One `drawText` issued by the current paint. */
    fun countDrawCall() {
        if (!enabled) return
        drawCalls.incrementAndGet()
    }

    /** Drop every sample. Call between workloads so runs do not contaminate each other. */
    fun reset() {
        byteToPaint.reset()
        paintCost.reset()
        snapshotCost.reset()
        drawCallsPerFrame.reset()
        idleFrames.set(0)
        pendingArrival.set(NONE)
        drawCalls.set(0)
    }

    /** Human-readable report. Latencies in milliseconds, draw calls as raw counts. */
    fun report(): String = buildString {
        appendLine("BossTerm frame probe (enabled=$enabled)")
        if (!enabled) {
            appendLine("  Set $ENV_FLAG=1 before launching to collect samples.")
            return@buildString
        }
        appendLine("  byte->paint   ${byteToPaint.summaryMillis()}")
        appendLine("  paint cost    ${paintCost.summaryMillis()}")
        appendLine("  snapshot cost ${snapshotCost.summaryMillis()}")
        appendLine("  drawText/frame ${drawCallsPerFrame.summaryRaw()}")
        appendLine("  frames with no new data: ${idleFrames.get()}")
        appendLine("  NOTE: byte->paint is draw-issued, excluding GPU present and vsync.")
    }

    /**
     * Fixed-bucket log histogram: eight sub-buckets per octave, so a reported value is
     * never more than 1/8 below the true one. (Four sub-buckets would be 1/4, since the
     * widest bucket relative to its own floor is the first in an octave: width 2^k/N at
     * value 2^k.) Chosen over storing raw samples because recording sits in the paint
     * pass - it must not allocate, and must not grow without bound over a long soak.
     *
     * Quantiles report the bucket FLOOR, so every number this produces is a slight
     * under-estimate. That is the safe direction for a latency claim: it cannot manufacture
     * an improvement that is not there.
     */
    class Histogram {
        private val counts = AtomicLongArray(BUCKETS)
        private val total = AtomicLong(0)
        private val sum = AtomicLong(0)
        private val max = AtomicLong(0)

        fun recordNanos(nanos: Long) = record(nanos / 1_000)

        fun record(value: Long) {
            val v = if (value < 0) 0 else value
            counts.incrementAndGet(indexOf(v))
            total.incrementAndGet()
            sum.addAndGet(v)
            max.accumulateAndGet(v) { a, b -> if (a >= b) a else b }
        }

        fun reset() {
            for (i in 0 until BUCKETS) counts.set(i, 0)
            total.set(0)
            sum.set(0)
            max.set(0)
        }

        fun count(): Long = total.get()

        /** Lower bound of the bucket holding the [q]th quantile, in the recorded unit. */
        fun quantile(q: Double): Long {
            val n = total.get()
            if (n == 0L) return 0
            val target = Math.ceil(q * n).toLong().coerceIn(1, n)
            var seen = 0L
            for (i in 0 until BUCKETS) {
                seen += counts.get(i)
                if (seen >= target) return valueOf(i)
            }
            return max.get()
        }

        fun summaryMillis(): String {
            val n = count()
            if (n == 0L) return "no samples"
            fun ms(us: Long) = String.format(java.util.Locale.ROOT, "%.2f", us / 1000.0)
            return "n=$n p50=${ms(quantile(0.50))} p95=${ms(quantile(0.95))} " +
                "p99=${ms(quantile(0.99))} max=${ms(max.get())} mean=${ms(sum.get() / n)} (ms)"
        }

        /** `{"n":..,"p50":..,...}` with microsecond samples rendered as milliseconds. */
        fun jsonMillis(): String = json { us -> String.format(java.util.Locale.ROOT, "%.3f", us / 1000.0) }

        /** Same shape, but the samples are plain counts rather than durations. */
        fun jsonRaw(): String = json { it.toString() }

        private fun json(fmt: (Long) -> String): String {
            val n = count()
            if (n == 0L) return "{\"n\": 0}"
            return "{\"n\": $n, \"p50\": ${fmt(quantile(0.50))}, \"p95\": ${fmt(quantile(0.95))}, " +
                "\"p99\": ${fmt(quantile(0.99))}, \"max\": ${fmt(max.get())}, \"mean\": ${fmt(sum.get() / n)}}"
        }

        fun summaryRaw(): String {
            val n = count()
            if (n == 0L) return "no samples"
            return "n=$n p50=${quantile(0.50)} p95=${quantile(0.95)} " +
                "p99=${quantile(0.99)} max=${max.get()} mean=${sum.get() / n}"
        }

        private companion object {
            /** 8 sub-buckets per octave; see the class doc for the resulting bound. */
            const val SUB_BITS = 3
            const val SUB_COUNT = 1 shl SUB_BITS
            const val SUB_MASK = (SUB_COUNT - 1).toLong()
            const val BUCKETS = 256

            fun indexOf(v: Long): Int {
                if (v < SUB_COUNT) return v.toInt()
                val octave = 63 - java.lang.Long.numberOfLeadingZeros(v)
                val sub = (v ushr (octave - SUB_BITS)) and SUB_MASK
                val index = ((octave - SUB_BITS + 1) shl SUB_BITS) + sub.toInt()
                return if (index >= BUCKETS) BUCKETS - 1 else index
            }

            fun valueOf(index: Int): Long {
                if (index < SUB_COUNT) return index.toLong()
                val octave = (index ushr SUB_BITS) - 1 + SUB_BITS
                val sub = (index and (SUB_COUNT - 1)).toLong()
                return (SUB_COUNT.toLong() or sub) shl (octave - SUB_BITS)
            }
        }
    }
}
