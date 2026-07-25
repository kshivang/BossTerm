package ai.rever.bossterm.compose.settings.sections

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.Danger
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextOnAccent
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.SettingsDropdown
import ai.rever.bossterm.compose.settings.components.SettingsSection
import ai.rever.bossterm.compose.settings.components.SettingsToggle
import ai.rever.bossterm.compose.voice.StoredVoiceConfig
import ai.rever.bossterm.compose.voice.VoiceAgentStorage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Boss Calling (voice)" controls, shared by Settings → Session Sharing and the Share window.
 *
 * It lives in both places on purpose: the Share window is where a host is actually handing out a
 * link, and a Call button that silently never appears (no key → the host advertises itself as
 * unavailable) is impossible to debug from the viewer's side. [showAgentOptions] drops the
 * model/voice pickers for the compact Share-window rendering.
 *
 * @param statusLine rendered under the toggle — the Share window uses it to say, in the host's own
 *   words, whether viewers can call right now.
 */
@Composable
internal fun BossCallingSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    showAgentOptions: Boolean = true,
    statusLine: Boolean = false,
) {
    SettingsSection(title = "Boss Calling (voice)") {
        SettingsToggle(
            label = "Enable Boss Calling",
            checked = settings.voiceCallEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(voiceCallEnabled = it)) },
            description = "Show a \"Call BossTerm\" button in the share viewer that connects the remote user by " +
                    "voice to an AI agent (OpenAI Realtime) that can inspect this session and run " +
                    "commands. On, but idle until you set the API key below — the button stays " +
                    "hidden without one. Calling also requires control of the share."
        )
        if (statusLine) VoiceAvailabilityLine(settings)
        if (showAgentOptions) {
            SettingsDropdown(
                label = "Model",
                options = listOf(
                    "gpt-realtime-2.1", "gpt-realtime-2.1-mini", "gpt-realtime-2", "gpt-realtime",
                ),
                selectedOption = settings.voiceCallModel,
                onOptionSelected = { onSettingsChange(settings.copy(voiceCallModel = it)) },
                description = "OpenAI Realtime model for the voice agent."
            )
            SettingsDropdown(
                label = "Voice",
                options = listOf("marin", "cedar", "alloy", "echo", "sage", "verse"),
                selectedOption = settings.voiceCallVoice,
                onOptionSelected = { onSettingsChange(settings.copy(voiceCallVoice = it)) },
                description = "The agent's speaking voice."
            )
        }
        VoiceApiKeyField()
    }
}

/**
 * [BossCallingSection] for windows that don't carry a settings snapshot (the Share window): reads
 * the live settings and persists each change immediately, rather than on a dialog's Save.
 */
@Composable
internal fun BossCallingSection(showAgentOptions: Boolean = true, statusLine: Boolean = true) {
    val settings by SettingsManager.instance.settings.collectAsState()
    BossCallingSection(
        settings = settings,
        onSettingsChange = { SettingsManager.instance.updateSettings(it) },
        showAgentOptions = showAgentOptions,
        statusLine = statusLine,
    )
}

/**
 * Mirrors what the viewer is told (`voiceStatus`), so "why is there no Call button?" is answerable
 * from the host window. Same rules as [ai.rever.bossterm.compose.voice.VoiceCallService.status].
 */
@Composable
private fun VoiceAvailabilityLine(settings: TerminalSettings) {
    val keyPresent by VoiceAgentStorage.keyPresentFlow.collectAsState()
    val (text, color) = when {
        !settings.voiceCallEnabled -> "Off — viewers see no Call BossTerm button." to TextMuted
        !keyPresent -> "No API key yet — viewers see no Call BossTerm button until you add one below." to Danger
        else -> "Ready — a viewer with control sees the Call BossTerm button." to AccentColor
    }
    Text(text = text, color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
}

/**
 * Masked entry for the OpenAI API key. Deliberately NOT a settings field: the key persists to
 * chmod-600 `~/.bossterm/voice.json` via [VoiceAgentStorage] (settings.json is world-readable and
 * ends up in bug reports), and the stored key is never echoed back into the field — only a
 * "key is set" state is shown.
 */
@Composable
private fun VoiceApiKeyField() {
    val keyPresent by VoiceAgentStorage.keyPresentFlow.collectAsState()
    var input by remember { mutableStateOf("") }
    // Writing the key touches the filesystem (mkdirs + atomic move), so it goes off the UI thread;
    // `error` surfaces a write that failed rather than letting the field claim a key is set.
    var error by remember { mutableStateOf<String?>(null) }
    val io = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = "OpenAI API key", color = TextPrimary, fontSize = 13.sp)
        Text(
            text = "Used only to mint short-lived call tokens; the key itself never leaves this app. " +
                    "Stored owner-only in ~/.bossterm/voice.json — never in settings.json." +
                    if (keyPresent) " A key is currently set ✓" else "",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        error?.let {
            Text(text = it, color = Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
            cursorBrush = SolidColor(AccentColor),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundColor, RoundedCornerShape(4.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    if (input.isEmpty()) {
                        Text(
                            text = if (keyPresent) "•••••••• (enter a new key to replace)" else "sk-…",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        // Same shape as every other settings action row (see SettingsFilePicker): one accent-filled
        // primary at 36.dp, and a quiet TextButton beside it — Danger-tinted here because clearing
        // the key turns the feature off for every viewer.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Button(
                onClick = {
                    val k = input.trim()
                    if (k.isNotEmpty()) {
                        io.launch {
                            val saved = withContext(Dispatchers.IO) {
                                VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = k))
                            }
                            error = if (saved) null else "Couldn't write ~/.bossterm/voice.json — key not saved."
                            if (saved) input = ""
                        }
                    }
                },
                enabled = input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(backgroundColor = AccentColor, contentColor = TextOnAccent),
                modifier = Modifier.height(36.dp),
            ) { Text("Save key", fontSize = 12.sp) }
            if (keyPresent) {
                TextButton(onClick = {
                    io.launch {
                        val cleared = withContext(Dispatchers.IO) { VoiceAgentStorage.clear() }
                        error = if (cleared) null else "Couldn't remove ~/.bossterm/voice.json."
                        input = ""
                    }
                }) {
                    Text("Clear key", color = Danger, fontSize = 12.sp)
                }
            }
        }
    }
}
