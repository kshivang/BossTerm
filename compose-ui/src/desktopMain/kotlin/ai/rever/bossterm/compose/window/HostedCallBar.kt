package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.voice.HostCallBar
import ai.rever.bossterm.compose.voice.HostCallPhase
import ai.rever.bossterm.compose.voice.HostVoiceCall
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** True only where an embedding window already renders the live call panel. */
val LocalCallBarHosted = staticCompositionLocalOf { false }

/** Export the existing call UI while connecting, live or failed, independent of terminal tabs. */
@Composable
fun HostedCallBar(content: @Composable (@Composable () -> Unit) -> Unit) {
    val visible by remember {
        HostVoiceCall.state.map { it.active || it.phase == HostCallPhase.Error }.distinctUntilChanged()
    }.collectAsState(false)
    val bar = remember { @Composable { HostCallBar() } }
    if (visible) content(bar)
}
