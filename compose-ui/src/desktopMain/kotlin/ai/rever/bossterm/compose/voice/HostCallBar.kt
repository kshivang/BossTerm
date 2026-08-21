package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.theme.BossUiTheme
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

// Getters, not vals - see the note in McpStatusIndicator. `Blue` and `Green` are
// status DOT fills here, so the fill tokens are the right ones; `Danger` is also
// used as label text and as a button tint, which `alert` covers in both roles.
private val Bg: Color get() = BossUiTheme.current.panel
// The button sits ON the bar, so it needs the rung above `Bg`. It was #1E1E1E on a
// #2B2B2B bar - recessed; pointing both at `panel` left it with only its border.
private val ButtonBg: Color get() = BossUiTheme.current.raised
private val Line: Color get() = BossUiTheme.current.line
private val Label: Color get() = BossUiTheme.current.chalk
private val Muted: Color get() = BossUiTheme.current.muted
private val Green: Color get() = BossUiTheme.current.ok
private val Blue: Color get() = BossUiTheme.current.data
private val Amber: Color get() = BossUiTheme.current.warn
private val Danger: Color get() = BossUiTheme.current.alert

/**
 * The in-app call strip, shown under the status pills while a call is up: a live microphone level
 * meter, what the agent is doing, Mute and End.
 *
 * Deliberately name-free — every string here is about the call in progress ("Listening…", "End
 * call"), not about the product — so it reads the same whatever an embedder called the pill above
 * it (`TabbedTerminal`'s `callLabel`). "Boss is speaking" names the AGENT, which is called Boss on
 * both surfaces and in both builds.
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
        color = ButtonBg,
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
 *
 * The two settings are separate parameters because they mean different things, and one combined
 * `pillEnabled` flag could not express both rules at once:
 *  - [featureEnabled] off (Boss Calling itself) hides everything except a call already in progress,
 *    so turning it off doesn't leave a "… · failed" pill with nothing behind it, and
 *    doesn't strand a live call with no way to end it;
 *  - [indicatorEnabled] off (just the pill) additionally keeps [CallSegmentState.Failed] visible —
 *    the error segment is the only place the failure can be dismissed, so hiding it left the call
 *    parked in Error with no Dismiss.
 */
internal fun HostCallState.segmentState(
    featureEnabled: Boolean,
    indicatorEnabled: Boolean,
    keyPresent: Boolean,
): CallSegmentState =
    when {
        // Ordering is load-bearing: the phase checks below must not resurrect a pill the feature
        // switch just turned off, so this comes first and only spares a call in progress.
        !featureEnabled && phase != HostCallPhase.Live && phase != HostCallPhase.Connecting ->
            CallSegmentState.Hidden
        phase == HostCallPhase.Error -> CallSegmentState.Failed
        phase == HostCallPhase.Connecting -> CallSegmentState.Connecting
        phase == HostCallPhase.Live && working -> CallSegmentState.Working
        phase == HostCallPhase.Live && speaking -> CallSegmentState.Speaking
        phase == HostCallPhase.Live -> CallSegmentState.Live
        !indicatorEnabled -> CallSegmentState.Hidden
        keyPresent -> CallSegmentState.Ready
        else -> CallSegmentState.NeedsKey
    }
