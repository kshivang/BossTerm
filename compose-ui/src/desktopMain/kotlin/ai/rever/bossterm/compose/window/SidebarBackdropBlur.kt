package ai.rever.bossterm.compose.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Blur the live terminal behind the drawer, never the drawer's labels or controls. */
@Composable
internal fun Modifier.sidebarBackdropBlur(enabled: Boolean, width: Dp): Modifier {
    val preferences = rememberMacChromePreferences()
    if (!enabled || preferences.reduceTransparency || preferences.increaseContrast) return this
    val source = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return drawWithContent {
        source.record { this@drawWithContent.drawContent() }
        blurred.renderEffect = BlurEffect(20.dp.toPx(), 20.dp.toPx(), TileMode.Clamp)
        blurred.record { drawLayer(source) }
        val panel = Path().apply {
            addRoundRect(RoundRect(4.dp.toPx(), 4.dp.toPx(),
                width.toPx().coerceAtMost(size.width), (size.height - 4.dp.toPx()).coerceAtLeast(4.dp.toPx()),
                CornerRadius(22.dp.toPx())))
        }
        // Replace this region instead of painting blurred text over sharp text.
        clipPath(panel, ClipOp.Difference) { drawLayer(source) }
        clipPath(panel) { drawLayer(blurred) }
    }
}
