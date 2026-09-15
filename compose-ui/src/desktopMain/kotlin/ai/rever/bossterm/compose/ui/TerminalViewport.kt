package ai.rever.bossterm.compose.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** The text canvas, caret canvas and PTY must agree on the same content rectangle. */
internal val terminalContentInset = 4.dp

internal fun Modifier.terminalContentPadding(scrollbarWidth: Dp): Modifier = padding(
  start = terminalContentInset,
  end = terminalContentInset + scrollbarWidth,
  top = terminalContentInset,
)

/** Compose padding rounds each edge separately, in physical pixels. */
internal fun terminalContentSize(outerSize: IntSize, density: Density, scrollbarWidth: Dp): IntSize {
  val edge = with(density) { terminalContentInset.roundToPx() }
  val end = with(density) { (terminalContentInset + scrollbarWidth).roundToPx() }
  return IntSize(
    (outerSize.width - edge - end).coerceAtLeast(0),
    (outerSize.height - edge).coerceAtLeast(0),
  )
}
