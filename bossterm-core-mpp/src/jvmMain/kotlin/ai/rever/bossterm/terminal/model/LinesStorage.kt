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
  rotateRegion(y, lastLine, panDown = true, magnitude = count, filler = filler)
}

/**
 * Rotate the rows of a scrolling region, filling the vacated end with blanks.
 *
 * [panDown] moves content toward the bottom margin (SD / IL): the bottom rows fall out past
 * [lastLine] and blanks appear at [y]. Otherwise it moves toward the top (SU / DL / line feed): the
 * top rows leave and blanks appear at the bottom margin. Rows outside `[y, lastLine]` are PINNED and
 * never move.
 *
 * One function rather than two because the directions are the same rotation mirrored. They were
 * previously two hand-written index computations, and each reached past a margin in its own way -
 * see the commit that introduced this for the index math that failed. Three bounds keep both
 * honest:
 *
 *  - the region is materialized up to [lastLine] before anything is measured, so the bottom margin
 *    is the REGION's, never however far painting has reached. Letting the painted row count define
 *    it drops the rows that should shift into the unpainted part, and rendering never materializes
 *    trailing rows ([ai.rever.bossterm.terminal.model.pool.IncrementalSnapshotBuilder] iterates
 *    `0 until size`), so a short screen stays short: reachable, not theoretical. Two consequences
 *    worth knowing - the first scroll grows `size` to the region bottom permanently, and on a
 *    partly painted MAIN screen the rows handed back for scrollback then include the blanks a
 *    full-height screen really has.
 *  - [magnitude] is clamped to the region height, so an oversized count blanks the region and
 *    touches nothing outside it. Moving `count` rows while removing fewer changed the screen height.
 *  - `lastLine < y` is a no-op. Callers reach this with a cursor-derived [y] (IL/DL), which can sit
 *    outside the region entirely.
 *
 * Direction is a flag rather than a signed count so there is no sign to get wrong, and so the
 * accumulator below can be allocated only on the side that returns it.
 *
 * @param y first row of the region.
 * @param lastLine last row of the region; rows after it are unaffected.
 * @param panDown true to move content toward [lastLine] (SD / IL), false toward [y] (SU / DL).
 * @param magnitude rows to move; clamped to the region height, ignored at zero or below.
 * @return the rows that left the region, top-to-bottom, when [panDown] is false. Empty when it is
 *   true: nothing downstream consumes those, and SD is a scroll hot path.
 */
private fun LinesStorage.rotateRegion(
  y: Int,
  lastLine: Int,
  panDown: Boolean,
  magnitude: Int,
  filler: TerminalLine.TextEntry,
): List<TerminalLine> {
  if (magnitude <= 0 || y < 0 || lastLine < y) return emptyList()

  // `get` fills the storage up to the index, so after this the region's bottom margin IS
  // `lastLine`. Deliberately not re-clamped to `size - 1` afterwards: that clamp is what let the
  // painted row count define the margin. The second check covers a capacity-limited storage that
  // cannot grow that far, where a no-op beats an out-of-bounds removal.
  if (lastLine >= size) get(lastLine)
  if (lastLine >= size) return emptyList()

  val moved = magnitude.coerceAtMost(lastLine - y + 1)
  // The rotation takes rows from one end of the region and opens the same number at the other.
  val bottomStart = lastLine - moved + 1
  val removeIndex = if (panDown) bottomStart else y
  val insertIndex = if (panDown) y else bottomStart

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
  return rotateRegion(y, lastLine, panDown = false, magnitude = count, filler = filler)
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
