package ai.rever.bossterm.compose.wizard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.rememberTabbedTerminalState
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.coroutines.delay

/**
 * Pre-built step content composables for common wizard patterns.
 */
object WizardStepBuilders {

    /**
     * Welcome/intro step with centered title and description.
     */
    @Composable
    fun WelcomeContent(
        title: String,
        description: String,
        icon: (@Composable () -> Unit)? = null
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            icon?.invoke()
            if (icon != null) {
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = description,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = TextSecondary
            )
        }
    }

    /**
     * Password input step with show/hide toggle.
     */
    @Composable
    fun PasswordContent(
        password: String,
        onPasswordChange: (String) -> Unit,
        title: String = "Administrator Password",
        description: String = "Some installations require administrator privileges.",
        passwordLabel: String = getPlatformPasswordLabel()
    ) {
        var passwordVisible by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }

        // Auto-focus password field when this composable appears
        LaunchedEffect(Unit) {
            delay(100) // Small delay to ensure UI is ready
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Ignore focus errors
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = SurfaceColor,
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = passwordLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        visualTransformation = if (passwordVisible)
                            VisualTransformation.None
                        else
                            PasswordVisualTransformation(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible)
                                        Icons.Filled.Visibility
                                    else
                                        Icons.Filled.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Hide" else "Show",
                                    tint = TextMuted
                                )
                            }
                        },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = TextPrimary,
                            cursorColor = AccentColor,
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = BorderColor,
                            backgroundColor = BackgroundColor
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your password is stored only in memory and never saved to disk.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }

    /**
     * Terminal installation step with embedded terminal.
     */
    @Composable
    fun TerminalInstallContent(
        title: String = "Installing...",
        description: String = "Please wait while we install your selected tools.",
        installCommand: String,
        environment: Map<String, String> = emptyMap(),
        onComplete: (success: Boolean, exitCode: Int) -> Unit,
        onOutput: ((String) -> Unit)? = null,
        onTryNpm: (() -> Unit)? = null,
        showNpmFallback: Boolean = false
    ) {
        var isRunning by remember { mutableStateOf(true) }
        var installSuccess by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            ) {
                EmbeddableTerminal(
                    initialCommand = installCommand,
                    environment = environment,
                    onInitialCommandComplete = { success, exitCode ->
                        isRunning = false
                        installSuccess = success
                        onComplete(success, exitCode)
                    },
                    onOutput = onOutput,
                    settingsOverride = TerminalSettingsOverride(fontSize = 12f),
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentColor,
                    backgroundColor = SurfaceColor
                )
            } else if (!installSuccess) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Installation failed. Please check the output above.",
                        fontSize = 14.sp,
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier.weight(1f)
                    )
                    if (showNpmFallback && onTryNpm != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onTryNpm,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = AccentColor
                            ),
                            border = BorderStroke(1.dp, AccentColor)
                        ) {
                            Text("Try npm")
                        }
                    }
                }
            }
        }
    }

    /**
     * GitHub authentication step with OTP detection and copy button.
     */
    @Composable
    fun GhAuthContent(
        onComplete: () -> Unit,
        onSkip: () -> Unit
    ) {
        var isRunning by remember { mutableStateOf(true) }
        var detectedOtp by remember { mutableStateOf<String?>(null) }
        var resetKey by remember { mutableStateOf(0) }
        val clipboardManager = LocalClipboardManager.current

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "GitHub Authentication",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Complete the authentication flow below. A browser will open for you to authorize.",
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            // OTP Copy section (appears when detected)
            AnimatedVisibility(
                visible = detectedOtp != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    backgroundColor = Color(0xFF2D4F2D),
                    border = BorderStroke(2.dp, Color(0xFF4CAF50)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Code: ${detectedOtp ?: ""}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(Modifier.width(16.dp))
                        Button(
                            onClick = {
                                detectedOtp?.let { otp ->
                                    clipboardManager.setText(AnnotatedString(otp))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFF4CAF50),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Copy Code")
                        }
                    }
                }
            }

            // Instruction (hidden when OTP detected)
            AnimatedVisibility(
                visible = detectedOtp == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = "Copy the one-time code when it appears below!",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFFFFD700),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Terminal (keyed to reset when needed)
            key(resetKey) {
                val terminalState = rememberTabbedTerminalState(autoDispose = false)

                // Poll for OTP pattern every 500ms
                LaunchedEffect(isRunning) {
                    while (isRunning && detectedOtp == null) {
                        delay(500)
                        val matches = terminalState.findPatternRegex("[A-Z0-9]{4}-[A-Z0-9]{4}")
                        if (matches.isNotEmpty()) {
                            detectedOtp = matches.first().text
                        }
                    }
                }

                // Cleanup terminal state
                DisposableEffect(Unit) {
                    onDispose {
                        terminalState.dispose()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                ) {
                    TabbedTerminal(
                        state = terminalState,
                        initialCommand = "gh auth login",
                        onInitialCommandComplete = { success, exitCode ->
                            isRunning = false
                            if (success && exitCode == 0) {
                                onComplete()
                            }
                        },
                        onExit = { },
                        onContextMenuOpen = { },
                        settingsOverride = TerminalSettingsOverride(fontSize = 12f),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Reset and Skip buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = {
                        resetKey++
                        detectedOtp = null
                        isRunning = true
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        backgroundColor = Color.Transparent,
                        contentColor = TextPrimary
                    ),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Text("Reset")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onSkip,
                    colors = ButtonDefaults.outlinedButtonColors(
                        backgroundColor = Color.Transparent,
                        contentColor = TextPrimary
                    ),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Text("Skip")
                }
            }
        }
    }

    /**
     * Completion step with success checkmark.
     */
    @Composable
    fun CompleteContent(
        title: String = "Complete!",
        description: String,
        showCheckmark: Boolean = true
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (showCheckmark) {
                Text(
                    text = "✓",
                    fontSize = 48.sp,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = description,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = TextSecondary
            )
        }
    }

    /**
     * Get platform-appropriate password label.
     */
    private fun getPlatformPasswordLabel(): String {
        return when {
            ShellCustomizationUtils.isMacOS() -> "macOS Password"
            ShellCustomizationUtils.isWindows() -> "Windows Password"
            else -> "System Password"
        }
    }
}
