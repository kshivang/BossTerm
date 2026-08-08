package ai.rever.bossterm.compose.ui

import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.TextBufferChangesListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts lines appended to history since the viewport last took a frame.
 *
 * The scroll offset is measured back from the LIVE BOTTOM of the buffer, so appending to history
 * moves the content the user is reading that many rows further from the anchor. Compensating means
 * knowing how many lines arrived between frames, and this is where that count lives.
 *
 * Deliberately an object rather than a counter inside the composable:
 *
 *  - its lifetime matches `scrollOffset`, which is session-scoped. A composition-scoped bank is
 *    dropped when a tab goes to the background while the offset it corrects survives, so a
 *    backgrounded tab drifts and lands somewhere else when you switch back.
 *  - the two decisions worth getting right - the alternate-screen exclusion and when the bank is
 *    cleared - become ordinary assertions instead of logic buried in a composable.
 *
 * Counting happens on the emulator thread inside the buffer lock; [drain] happens on the UI thread.
 * An atomic is the whole synchronisation: the listener must never touch Compose state, because
 * taking the snapshot lock under the buffer lock is the inversion `ComposeTerminalDisplay` warns
 * about.
 */
class HistoryAppendBank(private val buffer: TerminalTextBuffer) : TextBufferChangesListener {

    private val pending = AtomicInteger(0)

    /**
     * Alternate-screen appends are excluded HERE, at fire time, not when the count is drained.
     *
     * This runs synchronously inside the append, so `isUsingAlternateBuffer` describes the very
     * lines being reported. Deciding on the UI thread a frame later races both ways: main-screen
     * appends banked just before a TUI starts would be dropped, and alt-screen appends banked just
     * before it exits would be folded into the main-screen offset - the exact thing the exclusion
     * exists to prevent.
     *
     * The alternate screen really does accumulate scrollback in this buffer (`useAlternateBuffer`
     * swaps in a live storage and `scrollArea` has no alt guard), but it is discarded when the TUI
     * exits, so an offset derived from it would address a buffer that no longer exists.
     */
    override fun linesAddedToHistory(count: Int) {
        if (!buffer.isUsingAlternateBuffer) pending.addAndGet(count)
    }

    override fun historyCleared() {
        clear()
    }

    /** Take the count accumulated since the last call, resetting to zero. */
    fun drain(): Int = pending.getAndSet(0)

    /**
     * Forget anything banked.
     *
     * Called when the viewport leaves the live bottom: appends that arrived while following the
     * bottom are not compensation for a scrolled viewport, and folding them makes the first
     * scrolled frame jump.
     */
    fun clear() {
        pending.set(0)
    }
}
