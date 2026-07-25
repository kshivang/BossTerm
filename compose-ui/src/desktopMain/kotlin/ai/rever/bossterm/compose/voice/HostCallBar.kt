package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.share.CallSegmentState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF2B2B2B)
private val Line = Color(0xFF404040)
private val Label = Color(0xFFCCCCCC)
private val Muted = Color(0xFF999999)
private val Green = Color(0xFF4CAF50)
private val Blue = Color(0xFF4A90E2)
private val Amber = Color(0xFFE5A54B)
private val Danger = Color(0xFFD9534F)

/**
 * The in-app call strip, shown under the status pills while a "Call BossTerm" call is up: a live
 * microphone level meter, what the agent is doing, Mute and End.
 *
 * The meter is real mic loudness (from [HostVoiceCall.level]) rather than a canned animation — with
 * no browser tab to look at, it is the only way to tell "it can't hear me" from "it's thinking".
 */
@Composable
internal fun HostCallBar(modifier: Modifier = Modifier) {
    val state by HostVoiceCall.state.collectAsState()
    if (!state.active && state.phase != HostCallPhase.Error) return

    Surface(
        modifier = modifier,
        color = Bg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (state.phase == HostCallPhase.Error) Danger else Line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.phase == HostCallPhase.Error) {
                Text(state.error ?: "Call failed", color = Danger, fontSize = 11.sp)
                BarButton("Dismiss", onClick = HostVoiceCall::dismissError)
                return@Row
            }

            LevelMeter(
                muted = state.muted,
                color = when {
                    state.working -> Amber
                    state.speaking -> Blue
                    state.muted -> Muted
                    else -> Green
                },
            )
            Text(
                text = state.activity ?: when {
                    state.phase == HostCallPhase.Connecting -> "Connecting…"
                    state.speaking -> "Boss is speaking"
                    state.muted -> "Muted"
                    else -> "Listening…"
                },
                color = Label,
                fontSize = 11.sp,
            )
            BarButton(if (state.muted) "Unmute" else "Mute", onClick = HostVoiceCall::toggleMute)
            BarButton("End call", onClick = HostVoiceCall::end, tint = Danger)
        }
    }
}

/**
 * Twelve bars driven by the mic level. Collects [HostVoiceCall.level] HERE rather than reading it
 * from the call state, so the 25 Hz stream only ever recomposes this meter.
 */
@Composable
private fun LevelMeter(muted: Boolean, color: Color) {
    // No animateFloatAsState: the source already arrives smoothed at ~25 Hz, and animating it kept a
    // frame-rate animation running for the whole call to add nothing.
    val level by HostVoiceCall.level.collectAsState()
    val animated = if (muted) 0f else level.coerceIn(0f, 1f)
    Row(
        modifier = Modifier.height(16.dp).width(84.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(12) { i ->
            // A rough spectrum shape: middle bars react most, so speech looks like speech.
            val weight = 1f - (kotlin.math.abs(i - 5.5f) / 7f)
            val h = (2f + animated * 14f * weight).coerceIn(2f, 16f)
            Box(
                Modifier
                    .width(4.dp)
                    .height(h.dp)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit, tint: Color = Label) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (tint == Label) Line else tint),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 9.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
            Text(label, color = tint, fontSize = 11.sp)
        }
    }
}

/**
 * Map call state onto the status strip's segment, so the pill and the bar never disagree.
 *
 * A host with the feature on but no key still gets a pill ([CallSegmentState.NeedsKey]) — clicking
 * it collects the key and places the call, rather than hiding the feature until they find Settings.
 */
internal fun HostCallState.segmentState(enabled: Boolean, keyPresent: Boolean): CallSegmentState =
    when {
        phase == HostCallPhase.Error -> CallSegmentState.Failed
        phase == HostCallPhase.Connecting -> CallSegmentState.Connecting
        phase == HostCallPhase.Live && working -> CallSegmentState.Working
        phase == HostCallPhase.Live && speaking -> CallSegmentState.Speaking
        phase == HostCallPhase.Live -> CallSegmentState.Live
        !enabled -> CallSegmentState.Hidden
        keyPresent -> CallSegmentState.Ready
        else -> CallSegmentState.NeedsKey
    }
