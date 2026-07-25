package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.Danger
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asked for in place when someone clicks "Call BossTerm" with no key stored: sending them off to
 * hunt through Settings for the one thing standing between them and a call is the wrong answer, so
 * the key is collected right here and the call starts as soon as it saves.
 *
 * Storage is the same chmod-600 `~/.bossterm/voice.json` the settings field writes, so a key added
 * here shows as set everywhere else too.
 */
@Composable
internal fun VoiceKeyDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val io = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun save() {
        val key = input.trim()
        if (key.isEmpty() || saving) return
        saving = true
        io.launch {
            val ok = withContext(Dispatchers.IO) {
                VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = key))
            }
            saving = false
            if (ok) {
                input = ""
                onSaved()
            } else {
                error = "Couldn't write ~/.bossterm/voice.json — key not saved."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundColor,
        title = { Text("Call BossTerm", color = TextPrimary, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.width(380.dp)) {
                Text(
                    "Boss Calling needs your OpenAI API key to start a voice call. It is stored " +
                        "owner-only in ~/.bossterm/voice.json — never in settings.json — and is used " +
                        "only to open the call.",
                    color = TextMuted,
                    fontSize = 11.sp,
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(AccentColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceColor, RoundedCornerShape(4.dp))
                                .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            if (input.isEmpty()) Text("sk-…", color = TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(focus),
                )
                error?.let {
                    Text(it, color = Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = input.isNotBlank() && !saving) {
                Text(
                    if (saving) "Saving…" else "Save & call",
                    color = if (input.isNotBlank()) AccentColor else TextMuted,
                    fontSize = 13.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted, fontSize = 13.sp) }
        },
    )
}
