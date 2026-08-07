package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.StyledTextConsumer
import kotlin.math.abs
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
  if (count <= 0) return
  rotateRegion(y, lastLine, delta = count, filler = filler)
}

/**
 * Rotate the rows of a scrolling region by [delta], filling the vacated end with blanks.
 *
 * Positive [delta] pans content DOWN (SD / IL): the bottom rows fall out past the bottom margin and
 * blanks appear at [y]. Negative pans UP (SU / DL / line feed): the top rows leave and blanks appear
 * at the bottom margin. Rows outside `[y, lastLine]` are PINNED and never move.
 *
 * One function rather than two because the directions are the same rotation with opposite signs.
 * They were previously two hand-written index computations, and each reached past a margin in its
 * own way - see the commit that introduced this for the exact index math that failed. Three bounds
 * keep both honest:
 *
 *  - the region is materialized up to [lastLine] before it is measured. Clamping to `size - 1`
 *    instead lets the PAINTED row count define the margin, so rows that should shift into the
 *    unpainted part are dropped. Rendering never materializes trailing rows
 *    ([ai.rever.bossterm.terminal.model.pool.IncrementalSnapshotBuilder] iterates `0 until size`),
 *    so a short screen stays short: reachable, not theoretical. Note this makes the first scroll
 *    grow `size` to the region bottom permanently, and on a partly painted MAIN screen the rows
 *    handed back for scrollback then include the blanks a full-height screen really has.
 *  - the move is clamped to the region height, so an oversized count blanks the region and touches
 *    nothing outside it. Moving `count` rows while removing fewer changed the screen's height.
 *  - `lastLine < y` is a no-op. Callers reach this with a cursor-derived [y] (IL/DL), which can sit
 *    outside the region entirely.
 *
 * @param y first row of the region.
 * @param lastLine last row of the region; rows after it are unaffected.
 * @param delta signed number of rows to move; clamped to the region height.
 * @return the rows that left the region, top-to-bottom, for a negative [delta]. Always empty for a
 *   positive [delta]: nothing downstream consumes it, and SD is a scroll hot path.
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
  val moved = abs(delta).coerceAtMost(regionBottom - y + 1)
  if (moved <= 0) return emptyList()

  val panDown = delta > 0
  val removeIndex = if (panDown) regionBottom - moved + 1 else y
  val insertIndex = if (panDown) y else regionBottom - moved + 1

  val removed = if (panDown) null else ArrayList<TerminalLine>(moved)
  repeat(moved) {
    val line = removeAt(removeIndex)
    removed?.add(line)
  }
  repeat(moved) { insertAt(insertIndex, TerminalLine(filler)) }
  return removed ?: emptyList()
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
