package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import ai.rever.bossterm.compose.window.LocalWindowChromeOpacity
import ai.rever.bossterm.compose.window.LocalInTitleBar
import ai.rever.bossterm.compose.window.LocalTitleBarTheme
import ai.rever.bossterm.compose.window.ActionCapsule
import ai.rever.bossterm.compose.window.MacToolbarIcon
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import ai.rever.bossterm.compose.window.McpIcon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact combined status strip for the top-right overlay:
 * `● MCP | ● Sharing | ● Call BossTerm`. Each segment's dot is color-coded (green = on/active,
 * amber = busy, gray = off/idle) and is clickable. Replaces the separate pills so status reads on
 * one line. Each segment is shown only when its own toggle is enabled.
 *
 * The call segment's wording is the embedder's — see `TabbedTerminal`'s `callLabel` parameter.
 */
// Getters, not vals: a file-scope val freezes at class-init and cannot follow a
// theme switch. These four are status dot/pill fills, so the fill tokens apply.
private val ON: Color get() = BossUiTheme.current.ok
private val OFF: Color get() = BossUiTheme.current.muted
private val BUSY: Color get() = BossUiTheme.current.warn
private val LIVE_TALK: Color get() = BossUiTheme.current.data

/**
 * What the in-app call button says when the embedder has not renamed it — i.e. always, in plain
 * `bossterm-app`.
 *
 * "Boss Calling" is the FEATURE and is fixed everywhere (the settings section, the macOS microphone
 * prompt, the docs). This is only what the button is CALLED, which is the one part an embedder is
 * putting in front of users who have never heard of BossTerm.
 */
const val DEFAULT_CALL_LABEL: String = "Call BossTerm"

/**
 * The embedder's `callLabel` resolved for rendering — never blank.
 *
 * `null` means "standalone", matching `contextMenuItemsProvider` and friends. Blank is folded into
 * the same case rather than rendering an empty pill: an embedder computing this from its own
 * branding should get BossTerm's name back, not a dot with nothing after it.
 */
internal fun resolveCallLabel(callLabel: String?): String =
    callLabel?.trim()?.takeUnless { it.isEmpty() } ?: DEFAULT_CALL_LABEL

/** What the call segment should show — derived from the in-app call state. */
enum class CallSegmentState {
    Hidden,
    /** Enabled but no API key yet — the pill still shows, and clicking it asks for one. */
    NeedsKey,
    Ready, Connecting, Live, Speaking, Working, Failed,
}

/**
 * The call segment's text, all in one place so the label an embedder sets reaches every state and
 * not just the idle one.
 *
 * [CallSegmentState.NeedsKey] deliberately reads the same as [CallSegmentState.Ready]: "set this up"
 * is the click, not a warning to stare at. [CallSegmentState.Connecting] deliberately drops the
 * label — "Calling…" is the whole story at that moment, and it is the one state that stays coherent
 * whatever the product is called.
 */
internal fun callSegmentLabel(state: CallSegmentState, callLabel: String): String = when (state) {
    CallSegmentState.Connecting -> "Calling…"
    CallSegmentState.Working -> "$callLabel · working"
    CallSegmentState.Speaking, CallSegmentState.Live -> "$callLabel · live"
    CallSegmentState.Failed -> "$callLabel · failed"
    else -> callLabel
}

@Composable
fun StatusStrip(
    showMcp: Boolean,
    mcpOn: Boolean,
    onMcpClick: () -> Unit,
    showSharing: Boolean,
    sharingCount: Int,
    onSharingClick: () -> Unit,
    /**
     * Live voice calls started by REMOTE viewers.
     *
     * Rendered on the Sharing segment rather than the Call one, because it is a property of the
     * share, not of this host's own call — and because it must be visible even when Boss Calling's
     * own indicator is switched off. It is the host's only platform-independent signal that someone
     * else's agent is reading and running commands here: the notification behind it does nothing
     * outside macOS.
     */
    remoteCalls: Int = 0,
    modifier: Modifier = Modifier,
    call: CallSegmentState = CallSegmentState.Hidden,
    onCallClick: () -> Unit = {},
    /**
     * What the call segment is called. Already resolved — hosts pass `TabbedTerminal`'s `callLabel`
     * through [resolveCallLabel], so this is never null and never blank.
     */
    callLabel: String = DEFAULT_CALL_LABEL,
) {
    val showCall = call != CallSegmentState.Hidden
    if (!showMcp && !showSharing && !showCall && remoteCalls == 0) return
    StatusContainer(modifier) {
        Row(
            modifier = if (LocalInTitleBar.current) Modifier else Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (LocalInTitleBar.current) 10.dp else 8.dp)
        ) {
            if (showMcp && !LocalInTitleBar.current) {
                Segment(dot = if (mcpOn) ON else OFF, label = "MCP", onClick = onMcpClick, icon = McpIcon, active = mcpOn, tooltip = if (mcpOn) "MCP on" else "MCP off")
            }
            if (!LocalInTitleBar.current && showMcp && (showSharing || remoteCalls > 0)) {
                StatusDivider()
            }
            // A live remote call forces the segment on. Rendering it only inside `showSharing`
            // restored exactly the no-signal state RemoteVoiceCalls exists to close: a cosmetic
            // opt-out (sessionSharingShowIndicator) would hide the fact that someone else's agent is
            // running commands here.
            if (showSharing || remoteCalls > 0) {
                val label = when {
                    // A remote call outranks the viewer count: "someone is talking to an agent that
                    // can run commands here" is the more urgent fact about this share.
                    remoteCalls > 1 -> "Sharing · $remoteCalls calls"
                    remoteCalls == 1 -> "Sharing · on a call"
                    sharingCount > 1 -> "Sharing ($sharingCount)"
                    else -> "Sharing"
                }
                val dot = when {
                    remoteCalls > 0 -> LIVE_TALK
                    sharingCount > 0 -> ON
                    else -> OFF
                }
                Segment(
                    dot = dot,
                    label = label,
                    onClick = onSharingClick,
                    icon = Icons.Default.Share,
                    active = sharingCount > 0 || remoteCalls > 0,
                    // Say what the host can and cannot do. The indicator is deliberately not an
                    // action: the call's audio is browser-to-OpenAI and the host is not a party to
                    // it, so nothing here can hang it up. Stopping the share cuts the agent's TOOLS
                    // immediately, which is the part that touches this machine. An indicator that
                    // looks actionable and isn't is worse than one that explains itself.
                    tooltip = if (remoteCalls > 0) {
                        "A viewer is in a voice call with this session's agent. Stopping the share " +
                            "cuts the agent's access to this machine immediately - their audio " +
                            "session runs browser-to-OpenAI and cannot be ended from here."
                    } else {
                        if (sharingCount > 0) "Sharing active ($sharingCount)" else "Sharing inactive"
                    },
                )
            }
            if ((showMcp || showSharing || remoteCalls > 0) && showCall) {
                StatusDivider()
            }
            if (showCall) {
                Segment(
                    dot = when (call) {
                        CallSegmentState.Connecting, CallSegmentState.Working -> BUSY
                        CallSegmentState.Speaking -> LIVE_TALK
                        CallSegmentState.Live -> ON
                        CallSegmentState.Failed -> BossUiTheme.current.alert
                        else -> OFF
                    },
                    label = callSegmentLabel(call, callLabel),
                    onClick = onCallClick,
                    icon = Icons.Default.Call,
                    active = call in setOf(CallSegmentState.Live, CallSegmentState.Speaking, CallSegmentState.Working),
                    busy = call == CallSegmentState.Connecting,
                    failed = call == CallSegmentState.Failed,
                )
            }
            if (showMcp && LocalInTitleBar.current) {
                Segment(dot = if (mcpOn) ON else OFF, label = "MCP", onClick = onMcpClick,
                    icon = McpIcon, active = mcpOn, tooltip = if (mcpOn) "MCP on" else "MCP off")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Segment(dot: Color, label: String, onClick: () -> Unit, icon: ImageVector, tooltip: String? = null, active: Boolean = false, busy: Boolean = false, failed: Boolean = false) {
    if (ai.rever.bossterm.compose.window.LocalNativeToolbar.current != null) {
        val id = when {
            icon === McpIcon -> "mcp"
            icon == Icons.Default.Share -> "sharing"
            else -> "call"
        }
        val symbol = if (failed) "exclamationmark.triangle" else when (id) {
            "mcp" -> "mcp"
            "sharing" -> "square.and.arrow.up"
            else -> if (busy) "phone.connection" else "phone"
        }
        ai.rever.bossterm.compose.window.RegisterNativeToolbarAction(
            ai.rever.bossterm.compose.window.NativeToolbarAction(id, tooltip ?: label, symbol, active, onClick))
        return
    }
    val inTitleBar = LocalInTitleBar.current
    val chrome = if (inTitleBar) LocalTitleBarTheme.current else BossUiTheme.current
    val row = @Composable {
        if (inTitleBar) {
            ActionCapsule(active = active) {
                IconButton(onClick = onClick, modifier = Modifier.size(32.dp).semantics {
                    contentDescription = tooltip ?: label
                    stateDescription = when {
                        failed -> "$label: failed"
                        busy -> "$label: connecting"
                        active -> "$label: active"
                        else -> "$label: inactive"
                    }
                }) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = chrome.signalText, strokeWidth = 1.5.dp)
                    } else {
                        MacToolbarIcon(
                            if (failed) Icons.Default.Warning else icon,
                            contentDescription = tooltip ?: label,
                            tint = if (active || failed) chrome.signalText else chrome.chalk,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        } else Row(
            modifier = Modifier.clickable(onClick = onClick)
                .then(if (inTitleBar) Modifier.height(32.dp).padding(horizontal = 9.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Text(label, color = chrome.chalk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (tooltip == null && !inTitleBar) {
        row()
        return
    }
    TooltipArea(
        tooltip = {
            Text(
                text = tooltip ?: label,
                color = BossUiTheme.current.chalk,
                fontSize = 11.sp,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(BossUiTheme.current.raised, RoundedCornerShape(6.dp))
                    .border(1.dp, BossUiTheme.current.line2, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        },
        delayMillis = 350,
    ) {
        row()
    }
}

@Composable
private fun StatusContainer(modifier: Modifier, content: @Composable () -> Unit) {
    if (LocalInTitleBar.current) {
        Box(modifier) { content() }
    } else {
        Surface(
            modifier = modifier,
            color = BossUiTheme.current.panel.copy(alpha = LocalWindowChromeOpacity.current),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BossUiTheme.current.line)
        ) { content() }
    }
}

@Composable
private fun StatusDivider() {
    if (!LocalInTitleBar.current) {
        Text("|", color = BossUiTheme.current.line2, fontSize = 12.sp)
    }
}
