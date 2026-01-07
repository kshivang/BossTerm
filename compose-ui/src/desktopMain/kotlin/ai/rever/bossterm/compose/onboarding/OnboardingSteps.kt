package ai.rever.bossterm.compose.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted

/**
 * Step indicator showing progress through the wizard.
 */
@Composable
fun StepIndicator(
    currentStep: OnboardingStep,
    steps: List<OnboardingStep>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            val isActive = step.index == currentStep.index
            val isComplete = step.index < currentStep.index

            // Step circle
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isActive -> AccentColor
                            isComplete -> AccentColor.copy(alpha = 0.6f)
                            else -> SurfaceColor
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isComplete) "✓" else "${index + 1}",
                    color = if (isActive || isComplete) Color.White else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Connector line (not after last step)
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(2.dp)
                        .background(
                            if (step.index < currentStep.index)
                                AccentColor.copy(alpha = 0.6f)
                            else
                                SurfaceColor
                        )
                )
            }
        }
    }
}

/**
 * Welcome step.
 */
@Composable
fun WelcomeStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to BossTerm!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Let's set up your terminal environment.\nThis wizard will help you configure your shell, install useful tools, and get you started with AI coding assistants.",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = TextSecondary
        )
    }
}

/**
 * Shell selection step.
 */
@Composable
fun ShellSelectionStep(
    selections: OnboardingSelections,
    installedTools: InstalledTools,
    onSelectionChange: (ShellChoice) -> Unit
) {
    // Determine user's current shell
    val currentShellPath = System.getenv("SHELL") ?: ""
    val currentShellName = currentShellPath.substringAfterLast("/").lowercase()

    // Check if current shell is one of the main options
    val isCurrentShellKnown = currentShellName in listOf("zsh", "bash", "fish")

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Choose Your Shell",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Select a shell for your terminal. Zsh is recommended for its features and plugin ecosystem.",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Show Zsh, Bash, Fish options
        listOf(ShellChoice.ZSH, ShellChoice.BASH, ShellChoice.FISH).forEach { choice ->
            val isInstalled = when (choice) {
                ShellChoice.ZSH -> installedTools.zsh
                ShellChoice.BASH -> installedTools.bash
                ShellChoice.FISH -> installedTools.fish
                else -> false
            }
            val isCurrent = currentShellName == choice.command

            SelectionCard(
                title = choice.displayName,
                description = choice.description,
                isSelected = selections.shell == choice,
                isRecommended = choice == ShellChoice.ZSH,
                badge = when {
                    isCurrent -> "Current"
                    isInstalled -> "Installed"
                    else -> null
                },
                onClick = { onSelectionChange(choice) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Only show "Keep Current" if using a different shell (not zsh, bash, or fish)
        if (!isCurrentShellKnown && currentShellPath.isNotEmpty()) {
            SelectionCard(
                title = "Keep Current ($currentShellName)",
                description = "Continue using your current shell: $currentShellPath",
                isSelected = selections.shell == ShellChoice.KEEP_CURRENT,
                isRecommended = false,
                badge = "Current",
                onClick = { onSelectionChange(ShellChoice.KEEP_CURRENT) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Shell customization step.
 */
@Composable
fun ShellCustomizationStep(
    selections: OnboardingSelections,
    installedTools: InstalledTools,
    onSelectionChange: (ShellCustomizationChoice) -> Unit
) {
    val currentShellPath = System.getenv("SHELL") ?: ""
    val currentShellName = currentShellPath.substringAfterLast("/").lowercase()
    val isZshSelected = selections.shell == ShellChoice.ZSH ||
        (selections.shell == ShellChoice.KEEP_CURRENT && currentShellName == "zsh")

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Shell Customization",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enhance your shell with a customized prompt or framework.",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Show main customization options: Starship, Oh My Zsh, Prezto, None
        listOf(
            ShellCustomizationChoice.STARSHIP,
            ShellCustomizationChoice.OH_MY_ZSH,
            ShellCustomizationChoice.PREZTO,
            ShellCustomizationChoice.NONE
        ).forEach { choice ->
            val isDisabled = choice.requiresZsh && !isZshSelected
            val isCurrent = when (choice) {
                ShellCustomizationChoice.STARSHIP -> installedTools.starship
                ShellCustomizationChoice.OH_MY_ZSH -> installedTools.ohMyZsh
                ShellCustomizationChoice.PREZTO -> installedTools.prezto
                else -> false  // Don't mark NONE as current - there could be other customizations we don't detect
            }

            SelectionCard(
                title = choice.displayName,
                description = if (isDisabled) "${choice.description} (Requires Zsh)" else choice.description,
                isSelected = selections.shellCustomization == choice,
                isRecommended = choice == ShellCustomizationChoice.STARSHIP,
                isDisabled = isDisabled,
                badge = if (isCurrent) "Current" else null,
                onClick = { if (!isDisabled) onSelectionChange(choice) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Version control step.
 */
@Composable
fun VersionControlStep(
    selections: OnboardingSelections,
    installedTools: InstalledTools,
    onGitChange: (Boolean) -> Unit,
    onGhChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Version Control",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Install version control tools for managing your code.",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        CheckboxCard(
            title = "Git",
            description = "Distributed version control system",
            isChecked = selections.installGit || installedTools.git,
            isDisabled = installedTools.git,
            badge = if (installedTools.git) "Installed" else null,
            onCheckedChange = { if (!installedTools.git) onGitChange(it) }
        )
        Spacer(modifier = Modifier.height(8.dp))

        CheckboxCard(
            title = "GitHub CLI",
            description = "Work with GitHub from the command line",
            isChecked = selections.installGitHubCLI || installedTools.gh,
            isDisabled = installedTools.gh,
            badge = if (installedTools.gh) "Installed" else null,
            onCheckedChange = { if (!installedTools.gh) onGhChange(it) }
        )
    }
}

/**
 * AI Assistants step.
 */
@Composable
fun AIAssistantsStep(
    selections: OnboardingSelections,
    installedTools: InstalledTools,
    onSelectionChange: (Set<String>) -> Unit
) {
    data class AIAssistant(
        val id: String,
        val name: String,
        val description: String,
        val isInstalled: Boolean
    )

    val assistants = listOf(
        AIAssistant("claude-code", "Claude Code", "Anthropic's AI coding assistant", installedTools.claudeCode),
        AIAssistant("gemini-cli", "Gemini CLI", "Google's AI coding assistant", installedTools.gemini),
        AIAssistant("codex", "Codex", "OpenAI's coding assistant", installedTools.codex),
        AIAssistant("opencode", "OpenCode", "Open-source AI coding assistant", installedTools.opencode)
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "AI Coding Assistants",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Install AI-powered coding assistants to help with your development.",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        assistants.forEach { assistant ->
            val isSelected = assistant.id in selections.aiAssistants || assistant.isInstalled
            CheckboxCard(
                title = assistant.name,
                description = assistant.description,
                isChecked = isSelected,
                isDisabled = assistant.isInstalled,
                badge = if (assistant.isInstalled) "Installed" else null,
                onCheckedChange = { checked ->
                    if (!assistant.isInstalled) {
                        val newSet = if (checked) {
                            selections.aiAssistants + assistant.id
                        } else {
                            selections.aiAssistants - assistant.id
                        }
                        onSelectionChange(newSet)
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Review step showing summary of selections.
 */
@Composable
fun ReviewStep(
    selections: OnboardingSelections,
    installedTools: InstalledTools
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Review Your Selections",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Shell
        ReviewItem(
            category = "Shell",
            value = selections.shell.displayName,
            willInstall = selections.shell != ShellChoice.KEEP_CURRENT && !when (selections.shell) {
                ShellChoice.ZSH -> installedTools.zsh
                ShellChoice.BASH -> installedTools.bash
                ShellChoice.FISH -> installedTools.fish
                ShellChoice.KEEP_CURRENT -> true
            }
        )

        // Shell Customization
        if (selections.shellCustomization != ShellCustomizationChoice.NONE &&
            selections.shellCustomization != ShellCustomizationChoice.KEEP_EXISTING
        ) {
            val willInstall = !when (selections.shellCustomization) {
                ShellCustomizationChoice.STARSHIP -> installedTools.starship
                ShellCustomizationChoice.OH_MY_ZSH -> installedTools.ohMyZsh
                ShellCustomizationChoice.PREZTO -> installedTools.prezto
                else -> true
            }
            ReviewItem(
                category = "Customization",
                value = selections.shellCustomization.displayName,
                willInstall = willInstall
            )
        }

        // Git Tools
        if (selections.installGit && !installedTools.git) {
            ReviewItem(category = "Version Control", value = "Git", willInstall = true)
        }
        if (selections.installGitHubCLI && !installedTools.gh) {
            ReviewItem(category = "Version Control", value = "GitHub CLI", willInstall = true)
        }

        // AI Assistants
        selections.aiAssistants.forEach { id ->
            val isInstalled = when (id) {
                "claude-code" -> installedTools.claudeCode
                "gemini-cli" -> installedTools.gemini
                "codex" -> installedTools.codex
                "opencode" -> installedTools.opencode
                else -> true
            }
            if (!isInstalled) {
                val name = when (id) {
                    "claude-code" -> "Claude Code"
                    "gemini-cli" -> "Gemini CLI"
                    "codex" -> "Codex"
                    "opencode" -> "OpenCode"
                    else -> id
                }
                ReviewItem(category = "AI Assistant", value = name, willInstall = true)
            }
        }

        // Show message if nothing to install
        val hasInstalls = hasAnyInstallation(selections, installedTools)
        if (!hasInstalls) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = SurfaceColor
            ) {
                Text(
                    text = "All selected tools are already installed. Click Finish to complete setup.",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

private fun hasAnyInstallation(selections: OnboardingSelections, installed: InstalledTools): Boolean {
    if (selections.shell != ShellChoice.KEEP_CURRENT) {
        val shellInstalled = when (selections.shell) {
            ShellChoice.ZSH -> installed.zsh
            ShellChoice.BASH -> installed.bash
            ShellChoice.FISH -> installed.fish
            ShellChoice.KEEP_CURRENT -> true
        }
        if (!shellInstalled) return true
    }
    if (selections.shellCustomization != ShellCustomizationChoice.NONE &&
        selections.shellCustomization != ShellCustomizationChoice.KEEP_EXISTING
    ) {
        val customInstalled = when (selections.shellCustomization) {
            ShellCustomizationChoice.STARSHIP -> installed.starship
            ShellCustomizationChoice.OH_MY_ZSH -> installed.ohMyZsh
            ShellCustomizationChoice.PREZTO -> installed.prezto
            else -> true
        }
        if (!customInstalled) return true
    }
    if (selections.installGit && !installed.git) return true
    if (selections.installGitHubCLI && !installed.gh) return true
    selections.aiAssistants.forEach { id ->
        val aiInstalled = when (id) {
            "claude-code" -> installed.claudeCode
            "gemini-cli" -> installed.gemini
            "codex" -> installed.codex
            "opencode" -> installed.opencode
            else -> true
        }
        if (!aiInstalled) return true
    }
    return false
}

@Composable
private fun ReviewItem(
    category: String,
    value: String,
    willInstall: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = category,
            fontSize = 14.sp,
            color = TextSecondary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            if (willInstall) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(AccentColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Install", fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}

/**
 * Installing step with embedded terminal.
 */
@Composable
fun InstallingStep(
    installCommand: String,
    onComplete: () -> Unit
) {
    var isRunning by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "Installing...",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Please wait while we install your selected tools.\nNote: When typing your password, the cursor won't move - this is normal.",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Embedded terminal for installation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
        ) {
            EmbeddableTerminal(
                initialCommand = installCommand,
                onInitialCommandComplete = { success, exitCode ->
                    isRunning = false
                    onComplete()
                },
                settingsOverride = TerminalSettingsOverride(
                    fontSize = 12f
                ),
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
        }
    }
}

/**
 * GitHub authentication step.
 * Shows an embedded terminal to run `gh auth login`.
 */
@Composable
fun GhAuthStep(
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    var isRunning by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "GitHub Authentication",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Let's authenticate with GitHub to enable full CLI functionality.",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Embedded terminal for gh auth login
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
        ) {
            EmbeddableTerminal(
                initialCommand = "gh auth login",
                onInitialCommandComplete = { success, exitCode ->
                    isRunning = false
                    if (success && exitCode == 0) {
                        onComplete()
                    }
                    // If auth failed/cancelled, user can still click buttons
                },
                settingsOverride = TerminalSettingsOverride(
                    fontSize = 12f
                ),
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
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
            if (!isRunning) {
                Button(
                    onClick = onComplete,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = AccentColor,
                        contentColor = Color.White
                    )
                ) {
                    Text("Continue")
                }
            }
        }
    }
}

/**
 * Complete step.
 */
@Composable
fun CompleteStep(
    onRelaunch: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Setup Complete!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Your terminal is ready to use.\nYou can access the welcome wizard anytime from Help > Welcome Wizard.",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(
                    backgroundColor = Color.Transparent,
                    contentColor = TextPrimary
                ),
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Text("Dismiss")
            }
            Button(
                onClick = onRelaunch,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AccentColor,
                    contentColor = Color.White
                )
            ) {
                Text("Relaunch Terminal")
            }
        }
    }
}

/**
 * Selection card with radio button style.
 */
@Composable
fun SelectionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    isRecommended: Boolean = false,
    isDisabled: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit
) {
    val alpha = if (isDisabled) 0.5f else 1f
    val bgColor = if (isSelected) AccentColor.copy(alpha = 0.15f * alpha) else SurfaceColor.copy(alpha = alpha)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDisabled) { onClick() },
        backgroundColor = bgColor,
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, AccentColor)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, BorderColor.copy(alpha = alpha))
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = { if (!isDisabled) onClick() },
                enabled = !isDisabled,
                colors = RadioButtonDefaults.colors(
                    selectedColor = AccentColor,
                    unselectedColor = TextMuted
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary.copy(alpha = alpha)
                    )
                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(AccentColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Recommended", fontSize = 10.sp, color = Color.White)
                        }
                    }
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(TextMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(badge, fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondary.copy(alpha = alpha)
                )
            }
        }
    }
}

/**
 * Checkbox card for multi-select options.
 */
@Composable
fun CheckboxCard(
    title: String,
    description: String,
    isChecked: Boolean,
    isDisabled: Boolean = false,
    badge: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (isDisabled) 0.5f else 1f
    val bgColor = if (isChecked) AccentColor.copy(alpha = 0.15f * alpha) else SurfaceColor.copy(alpha = alpha)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDisabled) { onCheckedChange(!isChecked) },
        backgroundColor = bgColor,
        border = if (isChecked && !isDisabled) {
            androidx.compose.foundation.BorderStroke(2.dp, AccentColor)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, BorderColor.copy(alpha = alpha))
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { if (!isDisabled) onCheckedChange(it) },
                enabled = !isDisabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentColor,
                    uncheckedColor = TextMuted,
                    checkmarkColor = Color.White
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary.copy(alpha = alpha)
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(TextMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(badge, fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondary.copy(alpha = alpha)
                )
            }
        }
    }
}
