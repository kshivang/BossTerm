package ai.rever.bossterm.terminal.model

import org.jetbrains.annotations.ApiStatus

/**
 * Allows listening for more detailed events about text buffer content changes.
 */
@ApiStatus.Experimental
interface TextBufferChangesListener {
  /**
   * Line at [fromIndex] and probably some lines after it were changed.
   *
   * @param fromIndex line index in the Text Buffer. Positive for the screen lines, negative for the history lines.
   * So, 0 line is the first line on the screen, -1 line is the last line in the history.
   */
  fun linesChanged(fromIndex: Int) {}

  /**
   * Lines scrolled off the top of the screen and were appended to the history buffer.
   *
   * A viewport anchored to the BOTTOM of the buffer (offset counted back from the live screen)
   * addresses different content after this: everything the user is looking at moved [count]
   * lines further from the bottom. Such a viewport has to add [count] to its offset to stay on
   * the same content, which is the dual of what a top-anchored viewport does for
   * [linesDiscardedFromHistory] (compare iTerm2's `PTYTextView.handleScrollbackOverflow:`).
   *
   * Fired for every append on BOTH screens - this buffer accumulates scrollback on the alternate
   * screen too, so a consumer anchoring a viewport must decide for itself whether an alt-screen
   * append is meaningful to it. The case this was added for is an ordinary shell streaming output.
   *
   * NOT a complete account of history movement. `resize` fires this when a height shrink pushes
   * lines into history, but a height GROWTH pulls lines back out with no event, so a consumer that
   * only adds up these counts will over-count across a shrink/grow cycle. Anything reconstructing
   * the history index space needs to handle resize itself rather than treating this as the sole
   * source of truth.
   *
   * Fired for every append, including the `scrollArea` hot path that a streaming process drives
   * once per output line — keep implementations cheap.
   *
   * @param count number of lines appended to the end of the history.
   */
  fun linesAddedToHistory(count: Int) {}

  /**
   * History buffer capacity was exceeded, so the Text Buffer had to discard some lines from the start of the history.
   *
   * @param lines discarded lines.
   */
  fun linesDiscardedFromHistory(lines: List<TerminalLine>) {}

  /**
   * All lines were removed from the history buffer.
   * For example, in a result of `clear` (ED - Erase in Display), or RIS (Reset to the Initial State) escape sequence.
   */
  fun historyCleared() {}

  /**
   * Text Buffer width was changed.
   */
  fun widthResized() {}
}