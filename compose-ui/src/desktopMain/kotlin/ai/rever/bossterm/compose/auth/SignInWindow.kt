package ai.rever.bossterm.compose.auth

import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.settings.components.SettingsSection
import ai.rever.bossterm.compose.settings.components.SettingsTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

private val Danger = Color(0xFFE57373)

/**
 * Account sign-in window — opened from the Share menus' 4th item. Magic-link flow
 * against BossConsole's Supabase backend (see [BossAccountManager]): enter email →
 * "check your email" → the link's `bossterm://` deep link completes the sign-in.
 * Styled like [ai.rever.bossterm.compose.share.ShareWindow] (SettingsTheme).
 */
@Composable
fun SignInWindow(
    onDismiss: () -> Unit,
    focusTick: Int = 0,
) {
    val accountState by BossAccountManager.state.collectAsState()
    var email by remember { mutableStateOf("") }
    // GoTrue rate-limits link requests (~60s per email) — mirror that client-side.
    var cooldownUntil by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cooldownUntil) {
        while (System.currentTimeMillis() < cooldownUntil) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
        nowMs = System.currentTimeMillis()
    }
    val cooldownLeft = ((cooldownUntil - nowMs) / 1000).coerceAtLeast(0)
    val send: (String) -> Unit = {
        // Start the cooldown only when a request was actually launched — a locally
        // rejected email must not lock the button for 60s.
        if (BossAccountManager.sendMagicLink(it)) {
            cooldownUntil = System.currentTimeMillis() + 60_000
        }
    }

    Window(
        onCloseRequest = onDismiss,
        title = "BossTerm — Sign In",
        resizable = false,
        state = rememberWindowState(size = DpSize(440.dp, 420.dp))
    ) {
        // Raise an already-open window when the menu item is clicked again.
        LaunchedEffect(focusTick) {
            window.toFront()
            window.requestFocus()
        }
        Surface(color = BackgroundColor, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp)) {
                    Text("Sign in to BossTerm", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "One account across BossTerm and BOSS Console.",
                        color = TextSecondary, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(20.dp))

                    when (val s = accountState) {
                        is BossAccountManager.AccountState.SignedIn -> {
                            SettingsSection("Account") {
                                Text(s.email, color = TextPrimary, fontSize = 14.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("You're signed in on this machine.", color = TextSecondary, fontSize = 12.sp)
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { BossAccountManager.signOut() }) {
                                    Text("Sign out", color = Danger, fontSize = 13.sp)
                                }
                            }
                        }

                        is BossAccountManager.AccountState.EmailSent -> {
                            SettingsSection("Check your email") {
                                Text(
                                    "We sent a sign-in link to ${s.email}. Open it on this computer and it will bring you back to BossTerm.",
                                    color = TextPrimary, fontSize = 13.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text("The link expires in about an hour.", color = TextMuted, fontSize = 11.sp)
                                Spacer(Modifier.height(14.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { send(s.email) },
                                        enabled = cooldownLeft <= 0,
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
                                    ) {
                                        Text(if (cooldownLeft > 0) "Resend (${cooldownLeft}s)" else "Resend link", fontSize = 13.sp)
                                    }
                                    TextButton(onClick = { BossAccountManager.reset() }) {
                                        Text("Use a different email", color = AccentColor, fontSize = 13.sp)
                                    }
                                }
                            }
                        }

                        is BossAccountManager.AccountState.Verifying -> {
                            SettingsSection("Signing you in…") {
                                Text("Verifying your sign-in link.", color = TextSecondary, fontSize = 13.sp)
                            }
                        }

                        else -> { // SignedOut or Error → email entry (with the error surfaced above it)
                            (s as? BossAccountManager.AccountState.Error)?.let { err ->
                                Text(err.message, color = Danger, fontSize = 12.sp)
                                Spacer(Modifier.height(12.dp))
                            }
                            SettingsTextField(
                                label = "Email",
                                value = email,
                                onValueChange = { email = it },
                                placeholder = "you@example.com",
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("We'll email you a link — no password needed.", color = TextMuted, fontSize = 11.sp)
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { send(email) },
                                enabled = email.isNotBlank() && cooldownLeft <= 0,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
                            ) {
                                Text(if (cooldownLeft > 0) "Send sign-in link (${cooldownLeft}s)" else "Send sign-in link", fontSize = 13.sp)
                            }
                        }
                    }
                }
                // Pinned footer
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White)
                    ) { Text("Close", fontSize = 13.sp) }
                }
            }
        }
    }
}
