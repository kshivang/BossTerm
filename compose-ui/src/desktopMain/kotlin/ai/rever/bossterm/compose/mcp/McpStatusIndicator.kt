package ai.rever.bossterm.compose.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val McpOnColor = Color(0xFF4CAF50) // green
private val McpOnGlow = Color(0x33_4C_AF_50) // 20% green halo

/**
 * Small clickable dot in the top-right of the tab bar showing MCP server
 * status. Visible only when the MCP server is enabled in settings; clicking
 * opens the Settings dialog at the MCP category so users can inspect the
 * endpoint or turn it off.
 *
 * Rendered inside the tab bar so it lives next to the new-tab button. The
 * tab bar is forced visible while MCP is on (see TabbedTerminal) so the
 * indicator never gets hidden even in single-tab mode.
 */
@Composable
fun McpStatusIndicator(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    Box(
        modifier = modifier
            .size(28.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Soft halo for a "live" look without being distracting.
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(McpOnGlow, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(McpOnColor, CircleShape)
                .border(1.dp, Color(0xFF388E3C), CircleShape)
        )
    }
}
