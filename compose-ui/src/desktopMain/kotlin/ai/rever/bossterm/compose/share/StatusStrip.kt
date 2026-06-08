package ai.rever.bossterm.compose.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact combined status strip for the top-right overlay: `● MCP | ● Sharing`.
 * Each segment's dot is color-coded (green = on/active, gray = off/idle) and is
 * clickable. Replaces the separate MCP pill + Sharing pill so status reads on one
 * line. Each segment is shown only when its own toggle is enabled.
 */
private val ON = Color(0xFF4CAF50)
private val OFF = Color(0xFF6B6B6B)

@Composable
fun StatusStrip(
    showMcp: Boolean,
    mcpOn: Boolean,
    onMcpClick: () -> Unit,
    showSharing: Boolean,
    sharingCount: Int,
    onSharingClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Remote MCP" segment — shown when this window is connected to a remote whose MCP we mirror. */
    showRemoteMcp: Boolean = false,
    remoteMcpOn: Boolean = false,
    onRemoteMcpClick: () -> Unit = {},
) {
    if (!showMcp && !showSharing && !showRemoteMcp) return
    Surface(
        modifier = modifier,
        color = Color(0xFF2B2B2B),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF404040))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tracks whether a divider is needed before the next segment.
            var prior = false
            if (showMcp) {
                Segment(dot = if (mcpOn) ON else OFF, label = "MCP", onClick = onMcpClick)
                prior = true
            }
            if (showRemoteMcp) {
                if (prior) Text("|", color = Color(0xFF555555), fontSize = 12.sp)
                Segment(dot = if (remoteMcpOn) ON else OFF, label = "Remote MCP", onClick = onRemoteMcpClick)
                prior = true
            }
            if (showSharing) {
                if (prior) Text("|", color = Color(0xFF555555), fontSize = 12.sp)
                val label = if (sharingCount > 1) "Sharing ($sharingCount)" else "Sharing"
                Segment(dot = if (sharingCount > 0) ON else OFF, label = label, onClick = onSharingClick)
            }
        }
    }
}

@Composable
private fun Segment(dot: Color, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(label, color = Color(0xFFCCCCCC), fontSize = 11.sp)
    }
}
