package ai.rever.bossterm.compose.cli

import ai.rever.bossterm.compose.settings.SettingsTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog for installing/uninstalling the bossterm CLI tool.
 */
@Composable
fun CLIInstallDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    isFirstRun: Boolean = false
) {
    if (!visible) return

    val scope = rememberCoroutineScope()
    var isInstalled by remember { mutableStateOf(CLIInstaller.isInstalled()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Install Command Line Tool",
        resizable = false,
        state = rememberDialogState(size = DpSize(450.dp, 320.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SettingsTheme.BackgroundColor)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column {
                Text(
                    text = if (isFirstRun) "Welcome to BossTerm!" else "Command Line Tool",
                    color = SettingsTheme.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isInstalled) {
                        "The 'bossterm' command is installed and ready to use."
                    } else {
                        "Install the 'bossterm' command to open BossTerm from your terminal."
                    },
                    color = SettingsTheme.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Usage examples:",
                    color = SettingsTheme.TextMuted,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Column(
                    modifier = Modifier
                        .background(SettingsTheme.SurfaceColor, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text("bossterm", color = SettingsTheme.Success, fontSize = 12.sp)
                    Text("bossterm ~/Projects", color = SettingsTheme.Success, fontSize = 12.sp)
                    Text("bossterm -d /path/to/dir", color = SettingsTheme.Success, fontSize = 12.sp)
                }
            }

            // Status message
            if (statusMessage != null) {
                Text(
                    text = statusMessage!!,
                    color = if (statusMessage!!.startsWith("Error")) SettingsTheme.Danger else SettingsTheme.Success,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SettingsTheme.AccentColor,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }

                if (isInstalled) {
                    // Uninstall button
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                statusMessage = null
                                val result = withContext(Dispatchers.IO) {
                                    CLIInstaller.uninstall()
                                }
                                isLoading = false
                                when (result) {
                                    is CLIInstaller.InstallResult.Success -> {
                                        statusMessage = "Uninstalled successfully"
                                        isInstalled = false
                                    }
                                    is CLIInstaller.InstallResult.SuccessWithWarning -> {
                                        statusMessage = result.message
                                        isInstalled = false
                                    }
                                    is CLIInstaller.InstallResult.Cancelled -> {
                                        statusMessage = "Uninstall cancelled"
                                    }
                                    is CLIInstaller.InstallResult.Error -> {
                                        statusMessage = "Error: ${result.message}"
                                    }
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = SettingsTheme.BorderColor,
                            contentColor = SettingsTheme.TextPrimary
                        ),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("Uninstall", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Install/Reinstall button
                if (!isInstalled || CLIInstaller.needsUpdate()) {
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                statusMessage = null
                                val result = withContext(Dispatchers.IO) {
                                    CLIInstaller.install()
                                }
                                isLoading = false
                                when (result) {
                                    is CLIInstaller.InstallResult.Success -> {
                                        statusMessage = "Installed successfully! You can now use 'bossterm' command."
                                        isInstalled = true
                                    }
                                    is CLIInstaller.InstallResult.SuccessWithWarning -> {
                                        statusMessage = result.message
                                        isInstalled = true
                                    }
                                    is CLIInstaller.InstallResult.Cancelled -> {
                                        statusMessage = "Installation cancelled"
                                    }
                                    is CLIInstaller.InstallResult.Error -> {
                                        statusMessage = "Error: ${result.message}"
                                    }
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = SettingsTheme.AccentColor,
                            contentColor = SettingsTheme.TextOnAccent
                        ),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(
                            if (isInstalled) "Update" else "Install",
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Close/Skip button
                Button(
                    onClick = onDismiss,
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = SettingsTheme.BorderColor,
                        contentColor = SettingsTheme.TextPrimary
                    ),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        if (isFirstRun && !isInstalled) "Skip" else "Close",
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
