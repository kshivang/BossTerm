package ai.rever.bossterm.compose.mcp

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val McpOnColor = Color(0xFF4CAF50) // green
private val McpOnGlow = Color(0x33_4C_AF_50) // 20% green halo
private val McpLabelColor = Color(0xFFCFEFD4)
private val McpToastBg = Color(0xCC_1E_1E_1E)
private val McpToastTextColor = Color(0xFFE0E0E0)
private val McpToastSuccessColor = Color(0xFF4CAF50)
private val McpToastWarnColor = Color(0xFFFFC107)

/**
 * Lifecycle state of a one-click attach attempt, used by [AttachToast] to
 * surface progress and result inline in the window (cross-platform, unlike
 * [ai.rever.bossterm.compose.notification.NotificationService] which is
 * macOS-only).
 */
sealed class AttachStatus {
    abstract val target: McpAttachTarget

    data class Pending(override val target: McpAttachTarget) : AttachStatus()
    data class Done(val result: McpAttachResult) : AttachStatus() {
        override val target: McpAttachTarget get() = result.target
    }
}

/**
 * Small clickable "MCP on" pill in the top-right overlay layer. The green
 * dot says the server is running; the inline label removes any ambiguity
 * for a user glancing at the corner of the window. Clicking opens the
 * Settings dialog at the MCP category so users can inspect the endpoint
 * or turn it off.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun McpStatusIndicator(
    enabled: Boolean,
    onClick: () -> Unit,
    onHideRequest: () -> Unit = {},
    onAttachRequest: (McpAttachTarget) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    // Right-click → quick attach buttons + hide. Same set of CLIs as the
    // Settings panel's "Attach to AI CLI" section. Items for already-attached
    // CLIs get a "✓ " prefix so the user can see status at a glance.
    // The host wires onAttachRequest to McpCliAttacher and onHideRequest to
    // flip mcpShowStatusIndicator.
    val attached = McpTerminalRegistry.attachedTargets.collectAsState().value
    val runningPort = McpTerminalRegistry.runningPort.collectAsState().value
    ContextMenuArea(
        items = {
            McpAttachTarget.entries.map { target ->
                val prefix = if (target in attached) "✓ " else ""
                ContextMenuItem("${prefix}Attach ${target.displayName}") { onAttachRequest(target) }
            } + ContextMenuItem("Hide MCP Indicator") { onHideRequest() }
        }
    ) {
        TooltipArea(
            tooltip = { McpStatusTooltip(runningPort = runningPort, attached = attached) },
            delayMillis = 350,
            tooltipPlacement = TooltipPlacement.CursorPoint(
                offset = DpOffset(0.dp, 16.dp)
            )
        ) {
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
    }
}

/**
 * Hover tooltip body for [McpStatusIndicator]. Surfaces the bound endpoint
 * URL and the list of CLIs that this session has attached so far. Always
 * shown via [TooltipArea]; rendered as a small dark rounded card.
 */
@Composable
private fun McpStatusTooltip(
    runningPort: Int?,
    attached: Set<McpAttachTarget>
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(McpToastBg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "MCP server running",
            color = McpToastSuccessColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (runningPort != null) {
            Text(
                text = "Endpoint: http://127.0.0.1:$runningPort/mcp",
                color = McpToastTextColor,
                fontSize = 11.sp
            )
        }
        if (attached.isEmpty()) {
            Text(
                text = "No CLIs attached yet — right-click to attach.",
                color = McpToastTextColor,
                fontSize = 11.sp
            )
        } else {
            Text(
                text = "Attached this session:",
                color = McpToastTextColor,
                fontSize = 11.sp
            )
            McpAttachTarget.entries.filter { it in attached }.forEach { target ->
                Text(
                    text = "  ✓ ${target.displayName}",
                    color = McpToastSuccessColor,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Small inline pill rendered next to the [McpStatusIndicator] while an
 * attach attempt is in flight or has just completed. Cross-platform — the
 * `NotificationService` system-notification path only fires on macOS, but
 * this toast works everywhere because it lives in the Compose tree.
 *
 * The hosting composable is responsible for clearing the status after a
 * delay; this composable just renders whatever's passed in.
 */
@Composable
fun AttachToast(
    status: AttachStatus,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (status) {
        is AttachStatus.Pending ->
            "Attaching ${status.target.displayName}…" to McpToastTextColor
        is AttachStatus.Done -> when (val r = status.result) {
            is McpAttachResult.Success -> {
                val tail = if (r.detail.isNotEmpty()) " — ${r.detail.take(80)}" else ""
                "✓ ${r.target.displayName} attached$tail" to McpToastSuccessColor
            }
            is McpAttachResult.CopiedToClipboard ->
                "${r.target.displayName}: ${r.reason}" to McpToastWarnColor
        }
    }
    Box(
        modifier = modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(McpToastBg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp
        )
    }
}
