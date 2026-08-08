package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.terminal.model.TerminalLine

/**
 * Per-buffer-row hyperlink memo, keyed by the LINE INSTANCE rather than a version hash.
 *
 * The incremental snapshot builder reuses the [TerminalLine] object for a row whose content did
 * not change, so identity is an exact O(1) validity check: a row that changed is the only thing
 * that misses. No scroll offset appears anywhere in the key, which is the point - the previous
 * cache folded the offset into its hash, so every wheel tick missed and re-ran detection over
 * the whole viewport.
 *
 * Deliberately NOT Compose state. It is written from a pointer handler, and the previous cache
 * was `mutableStateOf` read and written inside the draw lambda - a draw-phase write to state the
 * draw observer had recorded, which re-invalidated the draw node and cost a second full text
 * render on every frame.
 *
 * Not thread-safe: confined to the UI thread, like the pointer handler that owns it.
 */
internal class HyperlinkRowCache {
  private val rows = HashMap<Int, Pair<TerminalLine, List<Hyperlink>>>()

  /**
   * Hyperlinks on [bufferRow], detecting only if [line] is not the instance already memoized.
   *
   * [bypass] skips the memo entirely for rows whose answer depends on their neighbours - a
   * wrapped run's detection walks to the logical line's start, so an unchanged line can still
   * produce a different result when a sibling row changed. Those are the minority and this runs
   * once per mouse move, so re-detecting them is cheap and always correct.
   */
  fun linksAt(
    bufferRow: Int,
    line: TerminalLine,
    bypass: Boolean = false,
    detect: () -> List<Hyperlink>,
  ): List<Hyperlink> {
    if (bypass) return detect()
    rows[bufferRow]?.let { (cachedLine, links) -> if (cachedLine === line) return links }
    if (rows.size > MAX_ROWS) rows.clear()
    return detect().also { rows[bufferRow] = line to it }
  }

  fun clear() {
    rows.clear()
  }

  private companion object {
    /** A few screenfuls. Cleared wholesale rather than evicted - this is a hover memo. */
    const val MAX_ROWS = 512
  }
}
