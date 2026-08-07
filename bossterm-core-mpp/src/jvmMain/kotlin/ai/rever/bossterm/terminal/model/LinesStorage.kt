package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.StyledTextConsumer
import kotlin.math.min

/**
 * Base interface for storing terminal lines.
 * For the first line of the storage, we use the term top, for the last line - bottom.
 * Supports adding/removing lines from the top and bottom.
 * Also, accessing lines by index.
 */
interface LinesStorage : Iterable<TerminalLine> {
  /** Count of the available lines in the storage */
  val size: Int

  /**
   * Throws [IndexOutOfBoundsException] of index is negative.
   * If index is grater than [size], storage should be filled with empty lines until [index] line.
   */
  operator fun get(index: Int): TerminalLine

  /** @return -1 if there is no such line */
  fun indexOf(line: TerminalLine): Int

  /**
   * Adds a new line to the start of the storage.
   * If the implementation limits the max capacity of the storage,
   * and storage is full, then the line should not be added.
   */
  fun addToTop(line: TerminalLine)

  /**
   * Adds a new line to the end of the storage.
   * If the implementation limits the max capacity of the storage
   * and storage is full, the first line from the top should be removed.
   */
  fun addToBottom(line: TerminalLine)

  /**
   * Removes a single line from the start of the storage.
   * @throws NoSuchElementException if storage is empty.
   */
  fun removeFromTop(): TerminalLine

  /**
   * Removes a single line from the end of the storage.
   * @throws NoSuchElementException if storage is empty.
   */
  fun removeFromBottom(): TerminalLine

  fun clear()

  /**
   * Inserts a line at the given index, shifting subsequent lines down.
   * More efficient than removeFromTop/addToTop pattern for targeted insertions.
   *
   * @param index Position to insert at (0-based)
   * @param line The line to insert
   * @throws IndexOutOfBoundsException if index < 0 or index > size
   */
  fun insertAt(index: Int, line: TerminalLine)

  /**
   * Removes and returns the line at the given index, shifting subsequent lines up.
   *
   * @param index Position to remove from (0-based)
   * @return The removed line
   * @throws IndexOutOfBoundsException if index < 0 or index >= size
   */
  fun removeAt(index: Int): TerminalLine

  companion object {
    const val DEFAULT_MAX_LINES_COUNT: Int = 5000
  }
}

fun LinesStorage.addAllToTop(lines: List<TerminalLine>) {
  for (ind in lines.lastIndex downTo 0) {
    addToTop(lines[ind])
  }
}

fun LinesStorage.addAllToBottom(lines: List<TerminalLine>) {
  for (line in lines) {
    addToBottom(line)
  }
}

fun LinesStorage.removeFromTop(count: Int): List<TerminalLine> {
  return perform(count, false) {
    removeFromTop()
  }
}

fun LinesStorage.removeFromBottom(count: Int): List<TerminalLine> {
  return perform(count, true) {
    removeFromBottom()
  }
}

private inline fun LinesStorage.perform(count: Int, reverse: Boolean, operation: (() -> TerminalLine)): List<TerminalLine> {
  if (count < 0) {
    throw IllegalArgumentException("Count must be >= 0")
  }
  if (count == 0) {
    return emptyList()
  }
  return when (val actualCount = min(count, size)) {
    0 -> emptyList()
    1 -> listOf(operation())
    else -> {
      val result = ArrayList<TerminalLine>(actualCount)
      repeat(actualCount) {
        result.add(operation())
      }
      if (reverse) {
        result.reverse()
      }
      result
    }
  }
}

fun LinesStorage.removeBottomEmptyLines(maxCount: Int): Int {
  var removedCount = 0
  var ind: Int = size - 1
  while (removedCount < maxCount && ind >= 0 && this[ind].isNulOrEmpty) {
    ind--
    removedCount++
  }
  removeFromBottom(removedCount)
  return removedCount
}

/**
 * @param startRow value passed as a corresponding parameter of the [StyledTextConsumer] methods.
 */
fun LinesStorage.processLines(yStart: Int, count: Int, consumer: StyledTextConsumer, startRow: Int = -size) {
  require(yStart >= 0) { "yStart is $yStart, should be >0" }
  val maxY = min(yStart + count, size)
  for (y in yStart until maxY) {
    this[y].process(y, consumer, startRow)
  }
}

/**
 * Pans the `[y, lastLine]` region DOWN by [count]: the bottom rows fall out past `lastLine` and
 * blanks appear at [y]. Rows outside the region are pinned. See [rotateRegion] - [count] is clamped
 * to the region height, so fewer than [count] blanks appear when the region is shorter than that.
 *
 * @param y        first row of the region; rows before it are unaffected.
 * @param count    rows to pan down, clamped to the region height.
 * @param lastLine last row of the region; rows after it are unaffected.
 */
fun LinesStorage.insertLines(y: Int, count: Int, lastLine: Int, filler: TerminalLine.TextEntry) {
  rotateRegion(y, lastLine, delta = count, filler = filler)
}

/**
 * Rotate the rows of a scrolling region by [delta], filling the vacated end with blanks.
 *
 * Positive [delta] pans content DOWN (SD / IL): the bottom [delta] rows fall out past the bottom
 * margin and blanks appear at [y]. Negative pans UP (SU / DL / line feed): the top rows leave and
 * blanks appear at the bottom margin. Rows outside `[y, lastLine]` are PINNED and never move.
 *
 * This exists as one function because the two directions are the same rotation with opposite
 * signs, and keeping them as two hand-written index computations is exactly how they came to
 * disagree. The down direction removed at a per-iteration `lastLine.coerceAtMost(size - 1)`, which
 * walks past the margin as each removal shifts the rows above it up — with rows r1..r10, a region
 * of 2..9 and count 3 it deleted r9, then the PINNED r10, then r8, leaving r7 in row 10. Claude
 * Code drives precisely this (`ESC[2;Nr` + `ESC[nT`) and pins its prompt box below the region, so
 * the rows it never repaints were being corrupted underneath it. The up direction bounded its loop
 * by `y < size` rather than by the region, so any count past the region height destroyed the rows
 * below the bottom margin too, and fed them to scrollback on a top-anchored region.
 *
 * Two bounds keep both honest:
 *  - the region is materialized up to [lastLine] first. Clamping the region to `size - 1` instead
 *    makes the PAINTED row count define the margin, so on a partly painted screen the rows that
 *    should have shifted down into the unpainted part are dropped instead. Rendering never
 *    materializes trailing rows ([ai.rever.bossterm.terminal.model.pool.IncrementalSnapshotBuilder]
 *    iterates `0 until size`), so a short screen stays short and this is reachable, not theoretical.
 *  - the move is clamped to the region height, so an oversized count blanks the region and nothing
 *    else. Moving `count` rows while removing fewer changed the screen's height.
 *
 * @param y first row of the region.
 * @param lastLine last row of the region; rows after it are unaffected.
 * @param delta signed number of rows to move; clamped to the region height.
 * @return the rows that left the region, in top-to-bottom order. Only meaningful for a negative
 *   [delta], where the caller may hand them to scrollback.
 */
private fun LinesStorage.rotateRegion(
  y: Int,
  lastLine: Int,
  delta: Int,
  filler: TerminalLine.TextEntry,
): List<TerminalLine> {
  if (delta == 0 || y < 0 || lastLine < y) return emptyList()

  // Materialize the region before measuring it, so the margin is the region's real bottom
  // rather than however far painting happens to have reached.
  if (lastLine >= size) get(lastLine)
  val regionBottom = lastLine.coerceAtMost(size - 1)
  val moved = kotlin.math.abs(delta).coerceAtMost(regionBottom - y + 1)
  if (moved <= 0) return emptyList()

  val removeAt = if (delta > 0) regionBottom - moved + 1 else y
  val insertAt = if (delta > 0) y else regionBottom - moved + 1

  val removed = ArrayList<TerminalLine>(moved)
  repeat(moved) { removed.add(removeAt(removeAt)) }
  repeat(moved) { insertAt(insertAt, TerminalLine(filler)) }
  return removed
}

/**
 * Pans the `[y, lastLine]` region UP by [count]: the top rows leave the region and blanks appear at
 * the bottom margin. Rows outside the region are pinned. See [rotateRegion] - [count] is clamped to
 * the region height, so a larger count blanks the region rather than reaching past `lastLine`.
 *
 * @param y        first row of the region; rows before it are unaffected.
 * @param count    rows to pan up, clamped to the region height.
 * @param lastLine last row of the region; rows after it are unaffected.
 * @return the rows that left the region, top-to-bottom. The caller appends these to scrollback when
 *   the region starts at the top of the screen.
 */
fun LinesStorage.deleteLines(y: Int, count: Int, lastLine: Int, filler: TerminalLine.TextEntry): List<TerminalLine> {
  if (count <= 0) return emptyList()
  return rotateRegion(y, lastLine, delta = -count, filler = filler)
}

/**
 * @return a string where the line separator divides each terminal line text.
 */
fun LinesStorage.getLinesAsString(): String {
  val sb = StringBuilder()
  for (index in 0 until size) {
    sb.append(this[index].text)
    if (index != size - 1) {
      sb.append("\n")
    }
  }
  return sb.toString()
}
