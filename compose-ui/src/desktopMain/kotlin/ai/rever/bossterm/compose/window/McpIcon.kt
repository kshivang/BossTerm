package ai.rever.bossterm.compose.window

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Official MCP mark, without the favicon background:
// https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/favicon.svg
internal val McpIcon: ImageVector by lazy {
    ImageVector.Builder("MCP", 24.dp, 24.dp, 180f, 180f).apply {
        addPath(
            pathData = PathParser().parsePathString("M23.5996 85.2532L86.2021 22.6507C94.8457 14.0071 108.86 14.0071 117.503 22.6507C126.147 31.2942 126.147 45.3083 117.503 53.9519L70.2254 101.23").toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 11.0667f,
            strokeLineCap = StrokeCap.Round
        )
        addPath(
            pathData = PathParser().parsePathString("M70.8789 100.578L117.504 53.952C126.148 45.3083 140.163 45.3083 148.806 53.952L149.132 54.278C157.776 62.9216 157.776 76.9357 149.132 85.5792L92.5139 142.198C89.6327 145.079 89.6327 149.75 92.5139 152.631L104.14 164.257").toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 11.0667f,
            strokeLineCap = StrokeCap.Round
        )
        addPath(
            pathData = PathParser().parsePathString("M101.853 38.3013L55.553 84.6011C46.9094 93.2447 46.9094 107.258 55.553 115.902C64.1966 124.546 78.2106 124.546 86.8543 115.902L133.154 69.6025").toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 11.0667f,
            strokeLineCap = StrokeCap.Round
        )
    }.build()
}
