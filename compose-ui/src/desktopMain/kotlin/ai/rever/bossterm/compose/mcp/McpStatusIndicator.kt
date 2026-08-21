package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.theme.BossUiTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.features.ContextMenuController

// All getters, never `val`: a file-scope val is initialised at class-init and
// would pin these to whichever theme was active then, so they could not follow a
// theme switch even between two dark themes. Same rule as SettingsTheme.
//
// BOTH label colours become `chalk`, not tinted greens/reds. The pale #CFEFD4 and
// #F2C9C9 were legible only on a dark floor, and `ok`/`alert` are FILL tokens held to
// the 3:1 component floor - `alert` as 11.sp text is 4.2:1 on daylight's panel, under
// the text floor this file's neighbours are strict about. There is no "ok held to the
// text floor" token to reach for the way `signalText` is for the accent. The dot, its
// halo and its ring carry the status, so only the tint is lost.
//
// The ring is `line2` in both states rather than a darker shade of the dot. Its job is
// to define a 10.dp dot against the panel behind it, and a ring in the dot's own colour
// does not do that at all - which is what an earlier revision shipped.
private val McpOnColor: Color get() = BossUiTheme.current.ok
private val McpOnGlow: Color get() = BossUiTheme.current.ok.copy(alpha = 0.20f)
private val McpOnLabelColor: Color get() = BossUiTheme.current.chalk
private val McpOffColor: Color get() = BossUiTheme.current.alert
private val McpOffGlow: Color get() = BossUiTheme.current.alert.copy(alpha = 0.20f)
private val McpOffLabelColor: Color get() = BossUiTheme.current.chalk
private val McpDotRing: Color get() = BossUiTheme.current.line2
private val McpToastBg: Color get() = BossUiTheme.current.panel.copy(alpha = 0.80f)
private val McpToastTextColor: Color get() = BossUiTheme.current.chalk
private val McpToastSuccessColor: Color get() = BossUiTheme.current.ok
private val McpToastWarnColor: Color get() = BossUiTheme.current.warn

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
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun McpStatusIndicator(
    enabled: Boolean,
    onClick: () -> Unit,
    onHideRequest: () -> Unit = {},
    onAttachRequest: (McpAttachTarget) -> Unit = {},
    onShowSettings: () -> Unit = onClick,
    onTurnOffRequest: () -> Unit = {},
    onTurnOnRequest: () -> Unit = {},
    /** User's `settings.mcpEnabled` (intent) — drives the toggle menu label. */
    isUserEnabled: Boolean = true,
    /** Brand shown in the pill / menus / tooltip (e.g. "Boss"). Defaults to "BossTerm". */
    serverLabel: String = "BossTerm",
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    // Right-click (or left-click — same dark popup) → attach submenu,
    // settings, and a state-aware toggle. The visual state (green/on vs
    // red/off) reflects actual binding via runningPort; the menu's toggle
    // label uses isUserEnabled so an "intent is on but bind failed" case
    // still offers a meaningful action ("Turn MCP off" to reset, then on).
    //
    // Uses the project's ContextMenuController so the popup style matches
    // the terminal canvas's own right-click menu.
    val attached = McpTerminalRegistry.attachedTargets.collectAsState().value
    val runningPort = McpTerminalRegistry.runningPort.collectAsState().value
    val isRunning = runningPort != null
    val contextMenuController = remember { ContextMenuController() }

    val openMenu: () -> Unit = {
        val items = buildIndicatorMenuItems(
            attached = attached,
            isRunning = isRunning,
            isUserEnabled = isUserEnabled,
            serverLabel = serverLabel,
            onAttachRequest = onAttachRequest,
            onShowSettings = onShowSettings,
            onTurnOffRequest = onTurnOffRequest,
            onTurnOnRequest = onTurnOnRequest
        )
        contextMenuController.showMenu(0f, 0f, items)
    }

    TooltipArea(
        tooltip = { McpStatusTooltip(runningPort = runningPort, attached = attached, serverLabel = serverLabel) },
        delayMillis = 350,
        tooltipPlacement = TooltipPlacement.CursorPoint(
            offset = DpOffset(0.dp, 16.dp)
        )
    ) {
        Row(
            modifier = modifier
                .clickable(onClick = openMenu)
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Secondary) {
                        openMenu()
                    }
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val dotColor = if (isRunning) McpOnColor else McpOffColor
            val haloColor = if (isRunning) McpOnGlow else McpOffGlow
            val borderColor = McpDotRing
            val labelColor = if (isRunning) McpOnLabelColor else McpOffLabelColor
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(haloColor, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, CircleShape)
                        .border(1.dp, borderColor, CircleShape)
                )
            }
            Text(
                text = if (isRunning) "$serverLabel MCP on" else "$serverLabel MCP off",
                color = labelColor,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Build the dark-themed right-click menu for the [McpStatusIndicator].
 *
 * Layout (running):
 *   Attach ▸ (4 CLIs, "✓ " prefix for attached)
 *   MCP Settings…
 *   ─────
 *   Turn MCP off
 *
 * Layout (off):
 *   Attach ▸                ← disabled (no server to point CLIs at)
 *   MCP Settings…
 *   ─────
 *   Turn MCP on
 *
 * The toggle label uses `isUserEnabled` (intent) rather than `isRunning`
 * so a bind-failure state ("intent on, but port busy") still offers a
 * meaningful action — toggle off to reset.
 */
internal fun buildIndicatorMenuItems(
    attached: Set<McpAttachTarget>,
    isRunning: Boolean,
    isUserEnabled: Boolean,
    serverLabel: String,
    onAttachRequest: (McpAttachTarget) -> Unit,
    onShowSettings: () -> Unit,
    onTurnOffRequest: () -> Unit,
    onTurnOnRequest: () -> Unit
): List<ContextMenuController.MenuElement> {
    val attachSubmenuItems: List<ContextMenuController.MenuElement> =
        McpAttachTarget.ossFirst.map { target ->
            val prefix = if (target in attached) "✓ " else ""
            ContextMenuController.MenuItem(
                id = "mcp_attach_${target.name}",
                label = "${prefix}${target.displayName}",
                enabled = isRunning,
                action = { onAttachRequest(target) }
            )
        }
    val attachSubmenu = ContextMenuController.MenuSubmenu(
        id = "mcp_attach_submenu",
        label = "Attach",
        items = attachSubmenuItems
    )
    val settings = ContextMenuController.MenuItem(
        id = "mcp_settings",
        label = "$serverLabel MCP Settings…",
        enabled = true,
        action = onShowSettings
    )
    val separator = ContextMenuController.MenuSeparator(id = "mcp_indicator_sep")
    val toggle = if (isUserEnabled) {
        ContextMenuController.MenuItem(
            id = "mcp_turn_off",
            label = "Turn $serverLabel MCP off",
            enabled = true,
            action = onTurnOffRequest
        )
    } else {
        ContextMenuController.MenuItem(
            id = "mcp_turn_on",
            label = "Turn $serverLabel MCP on",
            enabled = true,
            action = onTurnOnRequest
        )
    }
    return listOf(attachSubmenu, settings, separator, toggle)
}

/**
 * Hover tooltip body for [McpStatusIndicator]. Surfaces the bound endpoint
 * URL and the list of CLIs that this session has attached so far. Always
 * shown via [TooltipArea]; rendered as a small dark rounded card.
 */
@Composable
private fun McpStatusTooltip(
    runningPort: Int?,
    attached: Set<McpAttachTarget>,
    serverLabel: String = "BossTerm"
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(McpToastBg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (runningPort == null) {
            Text(
                text = "$serverLabel MCP server is off",
                color = McpOffLabelColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Click the pill to turn it on, or open $serverLabel MCP Settings…",
                color = McpToastTextColor,
                fontSize = 11.sp
            )
            return@Column
        }
        Text(
            text = "$serverLabel MCP server running",
            color = McpToastSuccessColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Endpoint: http://127.0.0.1:$runningPort",
            color = McpToastTextColor,
            fontSize = 11.sp
        )
        if (attached.isEmpty()) {
            Text(
                text = "No CLIs attached yet - right-click to attach.",
                color = McpToastTextColor,
                fontSize = 11.sp
            )
        } else {
            Text(
                text = "Attached this session:",
                color = McpToastTextColor,
                fontSize = 11.sp
            )
            McpAttachTarget.ossFirst.filter { it in attached }.forEach { target ->
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
                val tail = if (r.detail.isNotEmpty()) " - ${r.detail.take(80)}" else ""
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
