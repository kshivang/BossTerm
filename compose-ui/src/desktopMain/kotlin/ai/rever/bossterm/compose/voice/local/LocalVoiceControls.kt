package ai.rever.bossterm.compose.voice.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.settings.DialogTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.Danger
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextOnAccent
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import kotlinx.coroutines.launch

/**
 * Install / start / stop for the managed local voice runtime, plus what it is doing right now.
 *
 * Readiness is shown rather than hidden behind a spinner on the call bar because the states here
 * have genuinely different meanings and different remedies: a multi-gigabyte install, a cold start
 * that loads models into memory, and a crash all look identical from a call button, and only one of
 * them is worth waiting on.
 */
@Composable
internal fun LocalVoiceControls(
    port: Int,
    runtime: LocalVoiceRuntime = LocalVoiceRuntime.shared,
) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = "Local voice runtime", color = TextPrimary, fontSize = 13.sp)
        Text(
            text = statusText(state, port),
            color = statusColor(state),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "Runs a speech server on this machine so calls need no OpenAI key and no audio " +
                "leaves the device. Setup downloads several gigabytes of models, and the server " +
                "wants 16 GB or more of memory while it runs. Share viewers cannot use it: remote " +
                "calling stays on the OpenAI backend.",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is LocalVoiceRuntimeState.NotInstalled ->
                    ActionButton("Install", enabled = true) { runtime.install() }

                is LocalVoiceRuntimeState.Installing ->
                    ActionButton("Installing…", enabled = false) {}

                is LocalVoiceRuntimeState.Stopped ->
                    ActionButton("Start", enabled = true) {
                        scope.launch { runtime.ensureRunning(port) }
                    }

                is LocalVoiceRuntimeState.Starting ->
                    ActionButton("Starting…", enabled = false) {}

                is LocalVoiceRuntimeState.Running ->
                    ActionButton("Stop", enabled = true) { runtime.stop() }

                is LocalVoiceRuntimeState.Failed -> {
                    val failed = state as LocalVoiceRuntimeState.Failed
                    // A retry that cannot work is worse than no button: "no Python on this machine"
                    // does not become true by pressing it again.
                    if (failed.canRetry) {
                        ActionButton("Retry", enabled = true) {
                            if (LocalVoiceInstall.installed(LocalVoiceInstall.home(), isWindows())) {
                                scope.launch { runtime.ensureRunning(port) }
                            } else {
                                runtime.install()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = AccentColor,
            contentColor = TextOnAccent,
            disabledBackgroundColor = SurfaceColor,
            disabledContentColor = TextMuted,
        ),
    ) {
        Text(text = label, fontSize = 12.sp)
    }
}

private fun statusText(state: LocalVoiceRuntimeState, port: Int): String = when (state) {
    is LocalVoiceRuntimeState.NotInstalled -> "Not installed."
    is LocalVoiceRuntimeState.Installing -> state.detail
    is LocalVoiceRuntimeState.Stopped -> "Installed, not running."
    is LocalVoiceRuntimeState.Starting ->
        "Starting on port $port - first start loads models and can take a minute."
    is LocalVoiceRuntimeState.Running -> "Running on ${state.url}."
    is LocalVoiceRuntimeState.Failed -> state.message
}

private fun statusColor(state: LocalVoiceRuntimeState): Color = when (state) {
    is LocalVoiceRuntimeState.Running -> AccentColor
    is LocalVoiceRuntimeState.Failed -> Danger
    else -> TextMuted
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
