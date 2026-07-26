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
 * Compact combined status strip for the top-right overlay:
 * `● MCP | ● Sharing | ● Call BossTerm`. Each segment's dot is color-coded (green = on/active,
 * amber = busy, gray = off/idle) and is clickable. Replaces the separate pills so status reads on
 * one line. Each segment is shown only when its own toggle is enabled.
 */
private val ON = Color(0xFF4CAF50)
private val OFF = Color(0xFF6B6B6B)
private val BUSY = Color(0xFFE5A54B)
private val LIVE_TALK = Color(0xFF4A90E2)

/** What the Call BossTerm segment should show — derived from the in-app call state. */
enum class CallSegmentState {
    Hidden,
    /** Enabled but no API key yet — the pill still shows, and clicking it asks for one. */
    NeedsKey,
    Ready, Connecting, Live, Speaking, Working, Failed,
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
) {
    val showCall = call != CallSegmentState.Hidden
    if (!showMcp && !showSharing && !showCall) return
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
            if (showMcp) {
                Segment(dot = if (mcpOn) ON else OFF, label = "MCP", onClick = onMcpClick)
            }
            if (showMcp && (showSharing || remoteCalls > 0)) {
                Text("|", color = Color(0xFF555555), fontSize = 12.sp)
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
                Segment(dot = dot, label = label, onClick = onSharingClick)
            }
            if ((showMcp || showSharing || remoteCalls > 0) && showCall) {
                Text("|", color = Color(0xFF555555), fontSize = 12.sp)
            }
            if (showCall) {
                Segment(
                    dot = when (call) {
                        CallSegmentState.Connecting, CallSegmentState.Working -> BUSY
                        CallSegmentState.Speaking -> LIVE_TALK
                        CallSegmentState.Live -> ON
                        CallSegmentState.Failed -> Color(0xFFD9534F)
                        else -> OFF
                    },
                    // NeedsKey deliberately reads the same as Ready: "set this up" is the click,
                    // not a warning to stare at.
                    label = when (call) {
                        CallSegmentState.Connecting -> "Calling…"
                        CallSegmentState.Working -> "Call BossTerm · working"
                        CallSegmentState.Speaking, CallSegmentState.Live -> "Call BossTerm · live"
                        CallSegmentState.Failed -> "Call BossTerm · failed"
                        else -> "Call BossTerm"
                    },
                    onClick = onCallClick,
                )
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
