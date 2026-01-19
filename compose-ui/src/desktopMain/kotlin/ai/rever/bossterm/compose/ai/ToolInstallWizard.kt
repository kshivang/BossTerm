package ai.rever.bossterm.compose.ai

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import ai.rever.bossterm.compose.wizard.*
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.io.File

// InstallMethod enum is defined in AIAssistantInstallDialog.kt

/**
 * State for tool installation wizard.
 */
data class ToolInstallState(
    val tool: AIAssistantDefinition,
    val adminPassword: String = "",
    val installMethod: InstallMethod = InstallMethod.SCRIPT,
    val installSuccess: Boolean? = null,
    val terminalKey: Int = 0  // Increment to recreate terminal (for npm retry)
)

/**
 * Step IDs for tool installation wizard.
 */
object ToolInstallStepIds {
    const val PASSWORD = "password"
    const val INSTALLING = "installing"
    const val GH_AUTH = "gh_auth"
    const val COMPLETE = "complete"
}

/**
 * Tool installation wizard using the generic wizard framework.
 *
 * Shows a multi-step wizard for installing AI assistants and VCS tools:
 * - Linux: Password → Installing → (GhAuth if gh) → Complete
 * - macOS/Windows: Installing → (GhAuth if gh) → Complete
 *
 * @param tool The tool definition to install
 * @param installCommand The primary install command (script)
 * @param npmCommand Optional npm fallback command
 * @param terminalWriter Function to write to the calling terminal (for post-install commands)
 * @param commandToRunAfter Optional command to run after successful installation
 * @param clearLine Optional callback to clear the command line before writing
 * @param onDismiss Called when wizard is dismissed/cancelled
 * @param onComplete Called when wizard completes (success or failure)
 */
@Composable
fun ToolInstallWizard(
    tool: AIAssistantDefinition,
    installCommand: String,
    npmCommand: String? = null,
    terminalWriter: ((String) -> Unit)? = null,
    commandToRunAfter: String? = null,
    clearLine: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onComplete: (success: Boolean) -> Unit
) {
    val isGhTool = tool.id == AIAssistantIds.GH
    val isBrewTool = tool.id == AIAssistantIds.BREW

    // Define wizard steps
    val steps = remember(tool.id) {
        buildList {
            // Password step - Linux always needs sudo, macOS needs sudo for brew
            val needsPassword = ShellCustomizationUtils.isLinux() ||
                (ShellCustomizationUtils.isMacOS() && isBrewTool)
            if (needsPassword) {
                add(WizardStep<ToolInstallState>(
                    id = ToolInstallStepIds.PASSWORD,
                    displayName = "Password",
                    canProceed = { it.adminPassword.isNotEmpty() }
                ))
            }

            // Installing step - always shown but not in indicator
            add(WizardStep<ToolInstallState>(
                id = ToolInstallStepIds.INSTALLING,
                displayName = "Installing",
                isVisible = false,  // Hide from step indicator
                canProceed = { it.installSuccess == true }
            ))

            // GH Auth step - only for GitHub CLI
            if (isGhTool) {
                add(WizardStep<ToolInstallState>(
                    id = ToolInstallStepIds.GH_AUTH,
                    displayName = "GitHub Auth",
                    isVisible = false,  // Hide from step indicator
                    canSkip = true
                ))
            }

            // Complete step
            add(WizardStep<ToolInstallState>(
                id = ToolInstallStepIds.COMPLETE,
                displayName = "Complete",
                isVisible = false
            ))
        }
    }

    val wizardState = rememberWizardState(
        steps = steps,
        initialState = ToolInstallState(tool = tool)
    )

    // Determine current install command based on method
    val currentInstallCommand = remember(wizardState.state.installMethod, wizardState.state.terminalKey) {
        if (wizardState.state.installMethod == InstallMethod.NPM && npmCommand != null) {
            npmCommand
        } else {
            installCommand
        }
    }

    WizardDialog(
        state = wizardState,
        title = "Install ${tool.displayName}",
        size = DpSize(650.dp, 500.dp),
        onDismiss = onDismiss,
        onComplete = {
            // Handle post-install actions
            val success = wizardState.state.installSuccess == true
            if (success && terminalWriter != null) {
                // Clear command line first (remove the typed command that triggered wizard)
                clearLine?.invoke()

                // Build a single command to avoid race conditions between separate terminalWriter calls
                if (isBrewTool && commandToRunAfter != null) {
                    // For brew: source shellenv AND run the command in ONE line
                    terminalWriter("echo '✓ ${tool.displayName} installed successfully!' && eval \"\$(/opt/homebrew/bin/brew shellenv 2>/dev/null)\" && eval \"\$(/usr/local/bin/brew shellenv 2>/dev/null)\"; $commandToRunAfter\n")
                } else if (isBrewTool) {
                    // Brew install without command to run after
                    terminalWriter("echo '✓ ${tool.displayName} installed successfully!' && eval \"\$(/opt/homebrew/bin/brew shellenv 2>/dev/null)\" && eval \"\$(/usr/local/bin/brew shellenv 2>/dev/null)\"\n")
                } else if (commandToRunAfter != null) {
                    // Non-brew tool with command to run after
                    terminalWriter("echo '✓ ${tool.displayName} installed successfully!'\n")
                    val escapedCmd = commandToRunAfter.replace("'", "'\\''")
                    // Source nvm explicitly since $SHELL -l -c doesn't source .zshrc/.bashrc
                    terminalWriter("export NVM_DIR=\"\$HOME/.nvm\" && [ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\" && $escapedCmd\n")
                } else {
                    // Just echo success
                    terminalWriter("echo '✓ ${tool.displayName} installed successfully!'\n")
                }
            }
            onComplete(success)
        },
        showStepIndicator = { step ->
            // Only show indicator for password step (since others are hidden)
            step.isVisible && wizardState.visibleSteps.size > 1
        },
        primaryButtonText = { step ->
            when (step.id) {
                ToolInstallStepIds.PASSWORD -> "Install"
                ToolInstallStepIds.COMPLETE -> "Close"
                else -> "Next"
            }
        },
        showPrimaryButton = { step ->
            // Hide primary button for GH Auth step (it has its own Skip button)
            step.id != ToolInstallStepIds.GH_AUTH
        },
        showBackButton = { step ->
            // Only allow back on password step
            step.id == ToolInstallStepIds.PASSWORD && wizardState.canGoBack
        }
    ) { step, state ->
        when (step.id) {
            ToolInstallStepIds.PASSWORD -> {
                WizardStepBuilders.PasswordContent(
                    password = state.state.adminPassword,
                    onPasswordChange = { pwd ->
                        state.updateState { copy(adminPassword = pwd) }
                    },
                    title = "Administrator Password",
                    description = "Installation of ${tool.displayName} requires administrator privileges."
                )
            }

            ToolInstallStepIds.INSTALLING -> {
                // Key the composable to recreate terminal when retrying with npm
                key(state.state.terminalKey) {
                    // Helper to check if brew is actually installed (same as welcome wizard)
                    fun isBrewInstalled(): Boolean {
                        return File("/opt/homebrew/bin/brew").exists() ||
                               File("/usr/local/bin/brew").exists()
                    }

                    WizardStepBuilders.TerminalInstallContent(
                        title = "Installing ${tool.displayName}...",
                        description = if (state.state.installMethod == InstallMethod.NPM) {
                            "Retrying installation using npm..."
                        } else {
                            "Please wait while we install ${tool.displayName}."
                        },
                        installCommand = currentInstallCommand,
                        environment = if (state.state.adminPassword.isNotEmpty()) {
                            mapOf("BOSSTERM_SUDO_PWD" to state.state.adminPassword)
                        } else {
                            emptyMap()
                        },
                        onComplete = { success, _ ->
                            // For brew, check if binary actually exists (more reliable than exit code)
                            val effectiveSuccess = if (isBrewTool) {
                                isBrewInstalled()
                            } else {
                                success
                            }
                            state.updateState { copy(installSuccess = effectiveSuccess) }
                            if (effectiveSuccess) {
                                state.next()
                            }
                        },
                        // For brew: when success message detected, verify brew exists and advance immediately
                        onOutput = if (isBrewTool) { output ->
                            if (output.contains("Homebrew installed and PATH configured!") && isBrewInstalled()) {
                                state.updateState { copy(installSuccess = true) }
                                state.next()
                            }
                        } else null,
                        showNpmFallback = state.state.installSuccess == false && npmCommand != null && state.state.installMethod == InstallMethod.SCRIPT,
                        onTryNpm = if (npmCommand != null) {
                            {
                                state.updateState {
                                    copy(
                                        installMethod = InstallMethod.NPM,
                                        installSuccess = null,
                                        terminalKey = terminalKey + 1
                                    )
                                }
                            }
                        } else null
                    )
                }
            }

            ToolInstallStepIds.GH_AUTH -> {
                WizardStepBuilders.GhAuthContent(
                    onComplete = { state.next() },
                    onSkip = { state.next() }
                )
            }

            ToolInstallStepIds.COMPLETE -> {
                WizardStepBuilders.CompleteContent(
                    title = "Installation Complete!",
                    description = "${tool.displayName} has been installed successfully.\n" +
                            if (commandToRunAfter != null) {
                                "The command will run automatically when you close this dialog."
                            } else {
                                "You can now use it from the terminal."
                            }
                )
            }
        }
    }
}

/**
 * Parameters for showing the tool install wizard.
 * Used to pass context from command interception or context menu.
 */
data class ToolInstallWizardParams(
    val tool: AIAssistantDefinition,
    val installCommand: String,
    val npmCommand: String?,
    val terminalWriter: ((String) -> Unit)?,
    val commandToRunAfter: String? = null,
    val clearLine: (() -> Unit)? = null
)

/**
 * Host composable for showing ToolInstallWizard from state.
 * Similar to AIInstallDialogHost but uses the new wizard.
 */
@Composable
fun ToolInstallWizardHost(
    params: ToolInstallWizardParams?,
    onDismiss: () -> Unit,
    onComplete: (success: Boolean) -> Unit
) {
    params?.let { p ->
        ToolInstallWizard(
            tool = p.tool,
            installCommand = p.installCommand,
            npmCommand = p.npmCommand,
            terminalWriter = p.terminalWriter,
            commandToRunAfter = p.commandToRunAfter,
            clearLine = p.clearLine,
            onDismiss = onDismiss,
            onComplete = onComplete
        )
    }
}
