package ai.rever.bossterm.compose.session

import ai.rever.bossterm.terminal.TerminalDataStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes complete emulator operations, output publication, and snapshot subscriptions.
 * A raw reader tap runs ahead of the emulator; using it with a model snapshot can duplicate
 * already-painted bytes and omit older bytes still waiting in the emulator queue. */
internal class ParsedOutputPublisher(private val source: TerminalDataStream) : TerminalDataStream {
    private val lock = ReentrantLock()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
    private val consumed = StringBuilder()

    /** The first character is fetched before taking the lock so an idle stream never blocks a
     * snapshot. Subsequent reads within an escape sequence belong to the same operation. */
    fun process(first: Char, operation: (Char) -> Unit) = lock.withLock {
        consumed.setLength(0)
        consumed.append(first)
        operation(first)
        if (consumed.isNotEmpty()) {
            val data = consumed.toString()
            listeners.toList().forEach { listener -> runCatching { listener(data) } }
        }
    }

    fun mutate(operation: () -> Unit) = lock.withLock { operation() }

    fun add(listener: (String) -> Unit) = lock.withLock { listeners.addIfAbsent(listener); Unit }
    // Removal must remain nonblocking when a parser waits inside an incomplete escape.
    fun remove(listener: (String) -> Unit) { listeners.remove(listener) }

    /** Register and enqueue the baseline while publication is paused. A deliberately incomplete
     * escape can park the emulator inside an operation; fail this connection after a bounded wait
     * rather than holding its attach coroutine (and daemon registry) indefinitely. */
    fun attach(listener: (String) -> Unit, snapshot: () -> Unit) {
        check(lock.tryLock(1, TimeUnit.SECONDS)) { "terminal parser is waiting for an incomplete escape sequence" }
        try {
            snapshot()
            listeners.addIfAbsent(listener)
        } finally {
            lock.unlock()
        }
    }

    override val char: Char
        get() = source.char.also { consumed.append(it) }

    override fun readNonControlCharacters(maxChars: Int): String? =
        source.readNonControlCharacters(maxChars)?.also { consumed.append(it) }

    override fun pushChar(c: Char) {
        // Lookahead is not yet applied to the model. Publish it only when the next operation
        // consumes the pushed character; otherwise a snapshot could anchor before its effect.
        if (consumed.isNotEmpty()) consumed.setLength(consumed.length - 1)
        source.pushChar(c)
    }

    override fun pushBackBuffer(bytes: CharArray?, length: Int) {
        if (bytes == null) return
        consumed.setLength((consumed.length - length).coerceAtLeast(0))
        source.pushBackBuffer(bytes, length)
    }

    override val isEmpty: Boolean get() = source.isEmpty
}
