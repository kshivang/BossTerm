package ai.rever.bossterm.compose.ui

import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.TextBufferChangesListener
import java.util.concurrent.atomic.AtomicBoolean
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
    private val cleared = AtomicBoolean(false)

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

    /**
     * History went away entirely - `CSI 3 J`, which is what plain `clear` emits, reaches this.
     *
     * Recorded rather than just zeroing the count, because a viewport scrolled up is now pointing
     * into a history of size 0 and would render blank until the user scrolled again. The offset
     * itself cannot be written from here: this runs on the emulator thread inside the buffer lock,
     * and touching Compose state there is the lock inversion this class exists to avoid. So the
     * fact is banked and the UI thread acts on it in the same drain as the counts.
     */
    override fun historyCleared() {
        cleared.set(true)
        pending.set(0)
    }

    /**
     * What has accumulated so far, WITHOUT taking it.
     *
     * Split from [consume] because the reader computes during composition, and a composition can
     * be discarded before it applies. Taking the count there would silently swallow those appends
     * on a discarded frame and leave exactly the permanent drift this class exists to prevent.
     * `StableRenderFrameHolder` guards the frame capture against the same hazard.
     */
    fun peek(): HistoryDelta = HistoryDelta(cleared = cleared.get(), appended = pending.get())

    /**
     * Take back exactly what [peek] reported, once it has actually been applied.
     *
     * Subtracts rather than zeroing, so appends that landed between the peek and the commit stay
     * banked for the next frame instead of being lost.
     */
    fun consume(delta: HistoryDelta) {
        if (delta.appended != 0) pending.addAndGet(-delta.appended)
        if (delta.cleared) cleared.set(false)
    }

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

/**
 * What happened to history between two frames.
 *
 * @param cleared history was emptied, so a scrolled-up viewport is addressing lines that no longer
 *   exist and must return to the live bottom.
 * @param appended lines added since the last drain, excluding the alternate screen.
 */
data class HistoryDelta(val cleared: Boolean, val appended: Int)
