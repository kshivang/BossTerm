package ai.rever.bossterm.compose.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a translucent hover card (the tab preview) currently sits, in SCREEN pixels, or
 * null when none is showing. A popup cannot blur what lies behind it (a popup layer has
 * no access to the window's pixels), so the card publishes its bounds here and the
 * window root blurs its own content inside that rectangle before the card paints over it.
 * Screen coordinates on both sides, so it holds whether the popup is a layer or a window.
 */
val LocalHoverCardBackdrop = staticCompositionLocalOf<MutableState<Rect?>?> { null }

/**
 * Blur this node's content inside [screenRect] (see [LocalHoverCardBackdrop]) with the
 * card's corner radius; everything outside stays sharp. Same recipe as the sidebar drawer:
 * record once, blur a copy, swap the region. No-op while no card is showing or when the
 * accessibility preferences ask for no transparency.
 */
@Composable
internal fun Modifier.hoverCardBackdropBlur(screenRect: Rect?, corner: Dp = 10.dp): Modifier {
    val preferences = rememberMacChromePreferences()
    if (screenRect == null || preferences.reduceTransparency || preferences.increaseContrast) return this
    var origin by remember { mutableStateOf<Offset?>(null) }
    val source = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return onGloballyPositioned { origin = it.positionOnScreen() }
        .drawWithContent {
            val o = origin
            if (o == null) { drawContent(); return@drawWithContent }
            val local = screenRect.translate(-o.x, -o.y).intersect(Rect(Offset.Zero, size))
            if (local.isEmpty) { drawContent(); return@drawWithContent }
            source.record { this@drawWithContent.drawContent() }
            blurred.renderEffect = BlurEffect(18.dp.toPx(), 18.dp.toPx(), TileMode.Clamp)
            blurred.record { drawLayer(source) }
            val panel = Path().apply { addRoundRect(RoundRect(local, CornerRadius(corner.toPx()))) }
            clipPath(panel, ClipOp.Difference) { drawLayer(source) }
            clipPath(panel) { drawLayer(blurred) }
        }
}
