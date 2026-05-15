package ai.rever.bossterm.compose.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val McpOnColor = Color(0xFF4CAF50) // green
private val McpOnGlow = Color(0x33_4C_AF_50) // 20% green halo
private val McpLabelColor = Color(0xFFCFEFD4)

/**
 * Small clickable "MCP on" pill in the top-right overlay layer. The green
 * dot says the server is running; the inline label removes any ambiguity
 * for a user glancing at the corner of the window. Clicking opens the
 * Settings dialog at the MCP category so users can inspect the endpoint
 * or turn it off.
 */
@Composable
fun McpStatusIndicator(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(16.dp),
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
        Text(
            text = "MCP on",
            color = McpLabelColor,
            fontSize = 11.sp
        )
    }
}
