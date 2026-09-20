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
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
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
    externalUrl: String = "",
    runtime: LocalVoiceRuntime = LocalVoiceRuntime.shared,
) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val external = LocalVoiceInstall.parseExternalUrl(externalUrl)
    val externalIsRemote = external != null && !LocalVoiceInstall.isLoopbackUrl(external)

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
        // A configured server wins over the managed one, so saying so is the difference between
        // "Install did nothing" and "Install is not what this call uses". Shown only when the value
        // parses: a malformed one is reported through the call path, and repeating it here as a
        // status would read as a runtime failure rather than a settings typo.
        if (external != null) {
            Text(
                text = "Using the server set in settings.json ($external). The managed runtime below " +
                    "is not started or stopped, and is not what a call connects to.",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (externalIsRemote) {
            // The one claim this feature makes that a remote URL contradicts. Not a refusal —
            // pointing at a bigger machine on the LAN is the documented reason the setting exists —
            // but it must not be silently false either.
            Text(
                text = "That address is not this machine, so call audio is sent over the network to " +
                    "it. Use wss:// unless the link is trusted, and note the server performs no " +
                    "authentication: anyone who can reach it can use it.",
                color = Danger,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = "Runs speech recognition, Qwen3-4B, and speech synthesis on this machine, so the " +
                "managed runtime needs no provider key and keeps call content on-device. Plan for " +
                "about 16 GB of model downloads and at least 24 GB of available memory. These are " +
                "conservative totals from the shipped Parakeet, Qwen3-4B, and Qwen3-TTS model sizes; " +
                "actual cache and peak memory vary by platform. Share viewers cannot use it: remote " +
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
                }            }
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

// Delegates rather than re-deriving from `os.name`: BossTerm's platform test lives in
// ShellCustomizationUtils, and a second spelling of it here is how the two eventually disagree.
private fun isWindows(): Boolean = ShellCustomizationUtils.isWindows()
