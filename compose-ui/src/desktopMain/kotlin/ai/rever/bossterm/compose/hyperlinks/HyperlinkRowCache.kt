package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.terminal.model.TerminalLine

/**
 * Per-buffer-row hyperlink memo for the hover path.
 *
 * Keyed by the LINE INSTANCES the answer depends on, not by a version hash. The incremental
 * snapshot builder reuses the [TerminalLine] object for a row whose content did not change, so
 * identity is an exact O(1) validity check: a row that changed is the only thing that misses. No
 * scroll offset appears anywhere in the key, which is the point - the previous cache folded the
 * offset into its hash, so every wheel tick missed and re-ran detection over the whole viewport.
 *
 * A wrapped row's answer depends on its whole logical run, so the key for those rows is every
 * line in the run. Bypassing the memo instead would re-run `collectWrappedLines` (a rejoin of
 * the entire logical line) plus the full registry sweep on every pointer-move event - and in a
 * long-output session wrapped rows are the common case, not the exception.
 *
 * [cwd] and [detectFilePaths] are part of the key because `detect` resolves relative paths
 * against them: after a `cd`, an unchanged line must produce different `file://` targets.
 *
 * Deliberately NOT Compose state. It is written from a pointer handler, and the cache it
 * replaces was `mutableStateOf` read and written inside the draw lambda - a draw-phase write to
 * state the draw observer had recorded, which re-invalidated the draw node and cost a second
 * full text render on every frame.
 *
 * Not thread-safe: confined to the UI thread, like the pointer handler that owns it.
 */
internal class HyperlinkRowCache {
  private class Entry(
    val lines: List<TerminalLine>,
    val cwd: String?,
    val detectFilePaths: Boolean,
    val links: List<Hyperlink>,
  )

  private val rows = HashMap<Int, Entry>()

  /**
   * Hyperlinks on [bufferRow], detecting only when something the answer depends on changed.
   *
   * @param runLines the line instances the answer depends on: just this row's line for an
   *   ordinary row, every line of the logical run for a wrapped one.
   */
  fun linksAt(
    bufferRow: Int,
    runLines: List<TerminalLine>,
    cwd: String?,
    detectFilePaths: Boolean,
    detect: () -> List<Hyperlink>,
  ): List<Hyperlink> {
    val hit = rows[bufferRow]
    if (hit != null &&
      hit.cwd == cwd &&
      hit.detectFilePaths == detectFilePaths &&
      hit.lines.size == runLines.size &&
      hit.lines.indices.all { hit.lines[it] === runLines[it] }
    ) {
      return hit.links
    }
    if (rows.size > MAX_ROWS) rows.clear()
    return detect().also { rows[bufferRow] = Entry(runLines, cwd, detectFilePaths, it) }
  }

  fun clear() {
    rows.clear()
  }

  private companion object {
    /** A few screenfuls. Cleared wholesale rather than evicted - this is a hover memo. */
    const val MAX_ROWS = 512
  }
}
