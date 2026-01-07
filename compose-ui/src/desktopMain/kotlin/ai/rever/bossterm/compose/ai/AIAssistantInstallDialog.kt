package ai.rever.bossterm.compose.ai

import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dialog state for AI assistant installation.
 */
sealed class InstallDialogState {
    object Installing : InstallDialogState()
    object Success : InstallDialogState()
    data class Error(val exitCode: Int) : InstallDialogState()
}

/**
 * Installation method being used.
 */
enum class InstallMethod { SCRIPT, NPM }

/**
 * Dialog for installing AI assistants with an embedded terminal.
 *
 * Shows installation progress in a terminal, auto-closes on success,
 * and displays error with dismiss option on failure. If script installation
 * fails and npm option is available, offers to try npm instead.
 *
 * @param assistant The AI assistant to install
 * @param installCommand The installation command to run (script method)
 * @param npmInstallCommand Optional npm install command as fallback
 * @param onDismiss Called when dialog should close
 * @param onInstallComplete Called when installation completes (success or failure)
 */
@Composable
fun AIAssistantInstallDialog(
    assistant: AIAssistantDefinition,
    installCommand: String,
    npmInstallCommand: String? = null,
    onDismiss: () -> Unit,
    onInstallComplete: (success: Boolean) -> Unit = {}
) {
    var dialogState by remember { mutableStateOf<InstallDialogState>(InstallDialogState.Installing) }
    var currentMethod by remember { mutableStateOf(InstallMethod.SCRIPT) }
    var terminalKey by remember { mutableStateOf(0) } // Used to force terminal recreation

    // Determine current command based on method
    val currentCommand = when (currentMethod) {
        InstallMethod.SCRIPT -> installCommand
        InstallMethod.NPM -> npmInstallCommand ?: installCommand
    }

    // Check if npm fallback is available (only show if script failed and npm exists)
    val canTryNpm = npmInstallCommand != null &&
                    currentMethod == InstallMethod.SCRIPT &&
                    dialogState is InstallDialogState.Error

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .width(700.dp)
                .height(450.dp)
                .clip(RoundedCornerShape(12.dp)),
            color = Color(0xFF1E1E1E),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D2D))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Installing ${assistant.displayName}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Close button - always clickable to allow canceling
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Terminal content - always visible to show installation output
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (dialogState is InstallDialogState.Success) {
                        // Success state with close button
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "✓",
                                color = Color(0xFF4CAF50),
                                fontSize = 48.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Installation complete!",
                                color = Color(0xFF4CAF50),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF4CAF50)
                                )
                            ) {
                                Text("Close", color = Color.White)
                            }
                        }
                    } else {
                        // Show terminal for installing and error states
                        // Using key to force terminal recreation when retrying with different method
                        key(terminalKey) {
                            EmbeddableTerminal(
                                initialCommand = currentCommand,
                                onInitialCommandComplete = { success, exitCode ->
                                    println("DEBUG: AIAssistantInstallDialog - onInitialCommandComplete: success=$success, exitCode=$exitCode, method=$currentMethod")
                                    if (success) {
                                        dialogState = InstallDialogState.Success
                                        onInstallComplete(true)
                                    } else {
                                        dialogState = InstallDialogState.Error(exitCode)
                                        onInstallComplete(false)
                                    }
                                },
                                settingsOverride = TerminalSettingsOverride(
                                    showScrollbar = true,
                                    scrollbarWidth = 8f
                                ),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Error footer bar - shows when installation failed
                if (dialogState is InstallDialogState.Error) {
                    val errorState = dialogState as InstallDialogState.Error
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF5C2020))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = Color(0xFFE57373),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (currentMethod == InstallMethod.SCRIPT)
                                    "Script installation failed (exit code: ${errorState.exitCode})"
                                else
                                    "npm installation failed (exit code: ${errorState.exitCode})",
                                color = Color(0xFFE57373),
                                fontSize = 13.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Show "Try npm" button if script failed and npm is available
                            if (canTryNpm) {
                                Button(
                                    onClick = {
                                        // Switch to npm and restart
                                        currentMethod = InstallMethod.NPM
                                        dialogState = InstallDialogState.Installing
                                        terminalKey++ // Force new terminal
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFF1976D2)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Try npm", color = Color.White, fontSize = 13.sp)
                                }
                            }
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF3C3C3C)
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Dismiss", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

}

/**
 * Parameters for AI assistant installation dialog.
 * Shared data class used by both context menu and programmatic API.
 *
 * @param assistant The AI assistant to install
 * @param command The installation command to run
 * @param npmCommand Optional npm fallback command
 * @param terminalWriter Function to write to the parent terminal
 * @param commandToRunAfter Optional command to execute after successful installation
 *                          (e.g., the original command the user typed that triggered install)
 */
data class AIInstallDialogParams(
    val assistant: AIAssistantDefinition,
    val command: String,
    val npmCommand: String?,
    val terminalWriter: (String) -> Unit,
    val commandToRunAfter: String? = null
)

/**
 * Renders an AI assistant installation dialog with standard behavior.
 * Handles showing the dialog, refreshing detection on dismiss, and writing
 * success/failure messages to the terminal.
 *
 * If [AIInstallDialogParams.commandToRunAfter] is set, the command will be
 * executed in the parent terminal after successful installation (with shell
 * sourcing to pick up PATH changes).
 *
 * @param params Installation parameters (assistant, command, terminalWriter)
 * @param coroutineScope Scope for launching detection refresh
 * @param detector AI assistant detector for refreshing status
 * @param onDismiss Additional cleanup to perform on dialog dismiss (e.g., clearing state)
 */
@Composable
fun AIInstallDialogHost(
    params: AIInstallDialogParams?,
    coroutineScope: CoroutineScope,
    detector: AIAssistantDetector,
    onDismiss: () -> Unit
) {
    params?.let { p ->
        AIAssistantInstallDialog(
            assistant = p.assistant,
            installCommand = p.command,
            npmInstallCommand = p.npmCommand,
            onDismiss = {
                onDismiss()
                // Refresh detection when dialog closes
                coroutineScope.launch {
                    detector.detectAll()
                }
            },
            onInstallComplete = { success ->
                // Write result to parent terminal using echo for proper ANSI handling
                if (success) {
                    p.terminalWriter("echo -e '\\033[32m✓ ${p.assistant.displayName} installed successfully!\\033[0m'\n")
                    // If there's a command to run after install, source shell and execute it
                    p.commandToRunAfter?.let { cmd ->
                        // Source shell profile to pick up PATH changes, then run original command
                        // Escape single quotes in the command for safe embedding
                        val escapedCmd = cmd.replace("'", "'\\''")
                        // Use a fresh login shell to get updated PATH, then run the command
                        p.terminalWriter("\$SHELL -l -c '$escapedCmd'\n")
                    }
                } else {
                    p.terminalWriter("echo -e '\\033[31m✗ ${p.assistant.displayName} installation failed.\\033[0m'\n")
                }
            }
        )
    }
}
