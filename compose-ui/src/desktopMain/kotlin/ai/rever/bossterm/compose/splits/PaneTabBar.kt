package ai.rever.bossterm.compose.splits

import ai.rever.bossterm.compose.TerminalSession
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Per-split sub-tab strip (Phase 5b). Lists the sessions of a single pane with a
 * close affordance for extras and a "+" to add a new session. Only rendered when
 * the per-split-tabs feature is enabled.
 */
@Composable
fun PaneTabBar(
    sessions: List<TerminalSession>,
    activeIndex: Int,
    heightDp: Dp,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .background(Color(0xFF1E1E1E))
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sessions.forEachIndexed { index, session ->
            Row(
                modifier = Modifier
                    .background(if (index == activeIndex) Color(0xFF2D2D2D) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.title.value.ifBlank { "shell" },
                    color = if (index == activeIndex) Color.White else Color(0xFFBBBBBB),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                if (index > 0) {
                    Text(
                        text = "  ✕",
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onClose(index) },
                    )
                }
            }
        }
        Text(
            text = "  +  ",
            color = Color(0xFFBBBBBB),
            fontSize = 14.sp,
            modifier = Modifier.clickable { onAdd() },
        )
    }
}
