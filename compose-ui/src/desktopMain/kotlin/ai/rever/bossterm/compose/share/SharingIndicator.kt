package ai.rever.bossterm.compose.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
 * Small green "● Sharing" pill shown while ≥1 tab is being shared (issue #276).
 * A persistent, visible cue that a terminal is exposed — a security affordance the
 * user can always see. Gated by [TerminalSettings.sessionSharingShowIndicator].
 */
@Composable
fun SharingIndicator(count: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier,
        color = Color(0xFF13361B),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF4CAF50))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(7.dp).background(Color(0xFF4CAF50), CircleShape))
            Text(
                text = if (count <= 1) "Sharing" else "Sharing ($count)",
                color = Color(0xFFB9F6CA),
                fontSize = 11.sp
            )
        }
    }
}
