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
import ai.rever.bossterm.compose.voice.StampCachedValue
import ai.rever.bossterm.compose.voice.VoiceTurnDetection
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
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
    /**
     * Key presence from a source that crosses processes. [VoiceAgentStorage.keyPresentFlow] only
     * reflects THIS process's own save/clear plus its startup probe, which is right in the GUI and
     * wrong in the daemon's share window.
     */
    keyPresentOverride: Boolean? = null,
) {
    SettingsSection(title = "Boss Calling (voice)") {
        SettingsToggle(
            label = "Enable Boss Calling",
            checked = settings.voiceCallEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(voiceCallEnabled = it)) },
            description = "Show a \"Call BossTerm\" button — in this window and in the share viewer — " +
                    "that starts a voice conversation with an AI agent (OpenAI Realtime) able to " +
                    "inspect this session and run commands. Starting a call sends your tab titles " +
                    "and working directories to OpenAI, and anything the agent then reads from the " +
                    "terminal goes with it. On, but idle until you set the API key below; calling " +
                    "from a share also requires control of it."
        )
        SettingsToggle(
            label = "Allow calls from share viewers",
            checked = settings.voiceCallShareEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(voiceCallShareEnabled = it)) },
            description = "Their call is billed to your OpenAI key and you cannot end it from here — " +
                    "the audio runs between their browser and OpenAI, so stopping the share cuts the " +
                    "agent's access to this machine but not the session they already started. With " +
                    "that in mind: this lets someone holding a control-share link start a voice call " +
                    "that can run commands here. Turn it off to keep Boss Calling for yourself in " +
                    "this window only — a different trust boundary from calling your own terminal.",
            enabled = settings.voiceCallEnabled,
        )
        if (statusLine) VoiceAvailabilityLine(settings, keyPresentOverride)
        if (showAgentOptions) {
            SettingsToggle(
                label = "Show the Call BossTerm indicator",
                checked = settings.voiceShowStatusIndicator,
                onCheckedChange = { onSettingsChange(settings.copy(voiceShowStatusIndicator = it)) },
                description = "Show the \"Call BossTerm\" segment in the tab-bar status strip, " +
                        "alongside the MCP and Sharing indicators. It is also how you START a call " +
                        "from this window, so turning it off leaves calling available only to share " +
                        "viewers."
            )
            SettingsDropdown(
                label = "Model",
                options = listOf(
                    "gpt-realtime-2.1", "gpt-realtime-2.1-mini", "gpt-realtime-2", "gpt-realtime",
                ),
                selectedOption = settings.voiceCallModel,
                onOptionSelected = { onSettingsChange(settings.copy(voiceCallModel = it)) },
                description = "OpenAI Realtime model for the voice agent."
            )
            SettingsToggle(
                label = "Run commands in the focused terminal",
                checked = settings.voiceRunInFocusedPane,
                onCheckedChange = { onSettingsChange(settings.copy(voiceRunInFocusedPane = it)) },
                description = "Let the agent run shell commands in the pane you're looking at, the " +
                        "way you would type them yourself. When something is already running there, " +
                        "it falls back to a separate split so it can't interrupt you. Turn this off " +
                        "to keep every agent command in its own split.",
            )
            SettingsToggle(
                label = "Give the agent every MCP tool",
                checked = settings.voiceExposeAllTools,
                onCheckedChange = { onSettingsChange(settings.copy(voiceExposeAllTools = it)) },
                description = "In-app calls advertise your whole MCP tool surface — the same tools " +
                        "your editor or CLI agent gets — instead of a curated nine. Share viewers " +
                        "always keep the curated set: a guest's tool arguments are filtered against " +
                        "it, and that filter is what keeps them inside the shared session.",
            )
            SettingsToggle(
                label = "Don't listen while the agent is talking",
                checked = settings.voiceEchoSuppression,
                onCheckedChange = { onSettingsChange(settings.copy(voiceEchoSuppression = it)) },
                description = "On speakers, your microphone hears the agent's own voice and it ends " +
                        "up talking to itself. In-app calls have no echo cancellation, so the fix is " +
                        "to stop listening while it speaks. Turn this off if you're on headphones — " +
                        "there's no echo to cancel, and you'll be able to interrupt it by talking.",
            )
            SettingsDropdown(
                label = "Microphone sensitivity",
                options = VoiceTurnDetection.ALL.map { VoiceTurnDetection.label(it) },
                selectedOption = VoiceTurnDetection.label(settings.voiceMicSensitivity),
                onOptionSelected = { chosen ->
                    val value = VoiceTurnDetection.ALL.firstOrNull { VoiceTurnDetection.label(it) == chosen }
                        ?: VoiceTurnDetection.NORMAL
                    onSettingsChange(settings.copy(voiceMicSensitivity = value))
                },
                description = "How readily the agent treats sound as you speaking. Turn this down if " +
                        "a fan, keyboard or background chatter keeps interrupting it. Automatic lets " +
                        "the model judge when you've finished a thought — better at not cutting you " +
                        "off mid-sentence, but it has no threshold to lower for a noisy room.",
            )
            SettingsDropdown(
                label = "Voice",
                options = listOf("marin", "cedar", "alloy", "echo", "sage", "verse"),
                selectedOption = settings.voiceCallVoice,
                onOptionSelected = { onSettingsChange(settings.copy(voiceCallVoice = it)) },
                description = "The agent's speaking voice."
            )
        }
        if (showAgentOptions) {
            VoiceAgentInstructionsField(
                value = settings.voiceAgentExtraInstructions,
                onChange = { onSettingsChange(settings.copy(voiceAgentExtraInstructions = it)) },
            )
        }
        VoiceApiKeyField(keyPresentOverride)
    }
}

/**
 * Free-text added to the agent's rules on both surfaces.
 *
 * Deliberately additive rather than a full prompt editor: the built-in rules encode which tools this
 * surface actually has (they branch on the advertised set), so replacing them wholesale from a text
 * box would let someone send the agent after tools that are not there. An embedder that genuinely
 * needs that has [ai.rever.bossterm.compose.voice.VoiceAgentCustomization.rulesOverride].
 */
@Composable
private fun VoiceAgentInstructionsField(value: String, onChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = "Extra instructions for the agent", color = TextPrimary, fontSize = 13.sp)
        Text(
            text = "Added to what the agent already knows about your terminal. Good for what this " +
                    "machine is FOR — the stack you work in, conventions to follow, things to avoid.",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = false,
            maxLines = 4,
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
                    if (value.isEmpty()) {
                        Text(
                            text = "e.g. This is a Kotlin/Compose repo — prefer ./gradlew over raw java.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

/**
 * [BossCallingSection] for windows that don't carry a settings snapshot (the Share window): reads
 * the live settings and persists each change immediately, rather than on a dialog's Save.
 *
 * Both halves are deliberately cross-process aware, because one caller is the DAEMON's share window
 * and the daemon is a separate process whose `SettingsManager` has no reload-on-change:
 *
 *  - **Writing** goes through [SettingsManager.mergeChangedFields], not `updateSettings`. A whole
 *    -object write from an in-memory copy frozen at daemon start would persist that entire stale
 *    snapshot — so flipping this one toggle in the daemon window reverted every GUI-side settings
 *    change made since the daemon booted. Merging applies only the field that actually changed.
 *  - **Reading** re-reads from disk on a slow tick instead of trusting the frozen flow, so the
 *    window doesn't report "No API key yet — viewers see no Call BossTerm button" while the server,
 *    which reads the same stamps, is correctly advertising the call as available. That contradiction
 *    would land in the one panel whose entire job is answering "why is there no Call button?".
 */
@Composable
internal fun BossCallingSection(showAgentOptions: Boolean = true, statusLine: Boolean = true) {
    val inMemory by SettingsManager.instance.settings.collectAsState()
    val settingsCache = remember {
        StampCachedValue(
            stamp = { VoiceAgentStorage.fileStamp(SettingsManager.instance.settingsFilePath()) },
            read = { SettingsManager.instance.readFromDisk() },
        )
    }
    val keyCache = remember {
        StampCachedValue(stamp = { VoiceAgentStorage.keyStamp() }, read = { VoiceAgentStorage.keyPresent() })
    }
    // A couple of stats behind the stamp cache, only while this window is open — the same cost the
    // daemon's own 5s voice-status poll already pays.
    // Keyed on `inMemory`: with no key the producer launches once and its `?: inMemory` fallback
    // stays pinned to the first composition's snapshot, so an unreadable settings.json would keep
    // showing stale values even after this process's own copy moved on.
    val onDisk by produceState(inMemory, inMemory) {
        while (true) {
            value = withContext(Dispatchers.IO) { settingsCache.get() } ?: inMemory
            delay(CROSS_PROCESS_REFRESH_MS)
        }
    }
    val keyOnDisk by produceState(VoiceAgentStorage.keyPresentFlow.value) {
        while (true) {
            value = withContext(Dispatchers.IO) { keyCache.get() } ?: false
            delay(CROSS_PROCESS_REFRESH_MS)
        }
    }
    // Optimistic: the poll is up to CROSS_PROCESS_REFRESH_MS behind, so rendering `onDisk` alone
    // made a switch you just flipped visibly bounce back until the next read.
    var pending by remember { mutableStateOf<TerminalSettings?>(null) }
    val shown = pending ?: onDisk
    LaunchedEffect(onDisk) { if (pending == onDisk) pending = null }

    BossCallingSection(
        settings = shown,
        onSettingsChange = { edited ->
            pending = edited
            SettingsManager.instance.mergeChangedFields(onDisk, edited)
        },
        showAgentOptions = showAgentOptions,
        statusLine = statusLine,
        keyPresentOverride = keyOnDisk,
    )
}

/** Slow enough to be free, fast enough that a key added in the GUI shows up while you're looking. */
private const val CROSS_PROCESS_REFRESH_MS = 2_000L

/**
 * Mirrors what the viewer is told (`voiceStatus`), so "why is there no Call button?" is answerable
 * from the host window. Same rules as [ai.rever.bossterm.compose.voice.VoiceCallService.status].
 */
@Composable
private fun VoiceAvailabilityLine(settings: TerminalSettings, keyPresentOverride: Boolean?) {
    val inProcessKey by VoiceAgentStorage.keyPresentFlow.collectAsState()
    val keyPresent = keyPresentOverride ?: inProcessKey
    val (text, color) = when {
        !settings.voiceCallEnabled -> "Off — no calls from this window or from viewers." to TextMuted
        !settings.voiceCallShareEnabled ->
            "In-app only — viewers see no Call BossTerm button." to TextMuted
        !keyPresent -> "No API key yet — viewers see no Call BossTerm button until you add one below." to Danger
        // The viewer shows the button to every device; control is enforced when they click it.
        else -> "Ready — viewers see the Call BossTerm button (calling needs control)." to AccentColor
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
private fun VoiceApiKeyField(keyPresentOverride: Boolean? = null) {
    val inProcessKey by VoiceAgentStorage.keyPresentFlow.collectAsState()
    // Either source turning true means a key exists: the override lags by up to one refresh tick,
    // so a key saved in THIS window must not read as absent until the next poll.
    val keyPresent = inProcessKey || (keyPresentOverride ?: false)
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
                            val saved = withContext(Dispatchers.IO + NonCancellable) {
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
                        val cleared = withContext(Dispatchers.IO + NonCancellable) { VoiceAgentStorage.clear() }
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
