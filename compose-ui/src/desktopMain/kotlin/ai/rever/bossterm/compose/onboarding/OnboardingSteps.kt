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
import ai.rever.bossterm.compose.ai.AIAssistantIds
import ai.rever.bossterm.compose.ai.AIAssistants
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
 * Prerequisites step for Windows package managers.
 * Shows winget and Chocolatey status and allows installing them.
 */
@Composable
fun PrerequisitesStep(
    installedTools: InstalledTools,
    onRefreshTools: () -> Unit
) {
    var showWingetInstall by remember { mutableStateOf(false) }
    var showChocoInstall by remember { mutableStateOf(false) }

    val wingetInstallCommand = """
        powershell -Command "& {
            Write-Host 'Installing winget (Windows Package Manager)...' -ForegroundColor Cyan
            Write-Host ''
            Write-Host 'Note: If this fails, please install App Installer from the Microsoft Store.' -ForegroundColor Yellow
            Write-Host 'URL: https://aka.ms/getwinget' -ForegroundColor Yellow
            Write-Host ''
            try {
                ${'$'}progressPreference = 'silentlyContinue'
                ${'$'}latestRelease = Invoke-RestMethod -Uri 'https://api.github.com/repos/microsoft/winget-cli/releases/latest'
                ${'$'}assetUrl = ${'$'}latestRelease.assets | Where-Object { ${'$'}_.name -match '\.msixbundle${'$'}' } | Select-Object -First 1 -ExpandProperty browser_download_url
                if (${'$'}assetUrl) {
                    Write-Host 'Downloading winget...' -ForegroundColor Cyan
                    Invoke-WebRequest -Uri ${'$'}assetUrl -OutFile `"${'$'}env:TEMP\winget.msixbundle`"
                    Write-Host 'Installing...' -ForegroundColor Cyan
                    Add-AppxPackage -Path `"${'$'}env:TEMP\winget.msixbundle`"
                    Remove-Item `"${'$'}env:TEMP\winget.msixbundle`" -Force
                    Write-Host ''
                    Write-Host 'winget installed successfully!' -ForegroundColor Green
                    Write-Host 'Please click Refresh Status to update.' -ForegroundColor Cyan
                } else {
                    throw 'Could not find installer URL'
                }
            } catch {
                Write-Host 'Installation failed. Please install manually from Microsoft Store.' -ForegroundColor Red
                Write-Host 'Search for App Installer in Microsoft Store.' -ForegroundColor Yellow
            }
        }"
    """.trimIndent().replace("\n", " ")

    val chocoInstallCommand = """
        powershell -Command "& {
            Write-Host 'Installing Chocolatey...' -ForegroundColor Cyan
            Write-Host ''
            try {
                Set-ExecutionPolicy Bypass -Scope Process -Force
                [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072
                iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))
                Write-Host ''
                Write-Host 'Chocolatey installed successfully!' -ForegroundColor Green
                Write-Host 'Please click Refresh Status to update.' -ForegroundColor Cyan
            } catch {
                Write-Host 'Installation failed.' -ForegroundColor Red
                Write-Host ${'$'}_.Exception.Message -ForegroundColor Red
            }
        }"
    """.trimIndent().replace("\n", " ")

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Package Managers",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "BossTerm uses package managers to install tools like Git, Starship, and AI assistants. We recommend having at least one package manager available.",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Winget card
        PackageManagerCard(
            title = "winget",
            description = "Windows Package Manager - Built into Windows 10/11. Recommended for most users.",
            isInstalled = installedTools.winget,
            isRecommended = true,
            onInstallClick = { showWingetInstall = true }
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Chocolatey card
        PackageManagerCard(
            title = "Chocolatey",
            description = "Community-driven package manager with a large software catalog.",
            isInstalled = installedTools.chocolatey,
            isRecommended = false,
            onInstallClick = { showChocoInstall = true }
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Status message
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = if (installedTools.hasWindowsPackageManager) {
                Color(0xFF1E3A1E)  // Dark green
            } else {
                Color(0xFF3A3A1E)  // Dark yellow/warning
            }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (installedTools.hasWindowsPackageManager) "✓" else "⚠",
                    fontSize = 20.sp,
                    color = if (installedTools.hasWindowsPackageManager) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFFFC107)
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (installedTools.hasWindowsPackageManager) {
                        "You have a package manager installed. You can proceed with setup."
                    } else {
                        "No package manager detected. Some tool installations may not work without one."
                    },
                    fontSize = 14.sp,
                    color = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Refresh button
        OutlinedButton(
            onClick = onRefreshTools,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = Color.Transparent,
                contentColor = TextPrimary
            ),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Text("Refresh Status")
        }

        // Installation terminals
        if (showWingetInstall) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Installing winget...",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            ) {
                EmbeddableTerminal(
                    initialCommand = wingetInstallCommand,
                    onInitialCommandComplete = { _, _ ->
                        // User should click Refresh Status after installation
                    },
                    settingsOverride = TerminalSettingsOverride(fontSize = 11f),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (showChocoInstall) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Installing Chocolatey...",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            ) {
                EmbeddableTerminal(
                    initialCommand = chocoInstallCommand,
                    onInitialCommandComplete = { _, _ ->
                        // User should click Refresh Status after installation
                    },
                    settingsOverride = TerminalSettingsOverride(fontSize = 11f),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Card for displaying package manager status with install option.
 */
@Composable
private fun PackageManagerCard(
    title: String,
    description: String,
    isInstalled: Boolean,
    isRecommended: Boolean,
    onInstallClick: () -> Unit
) {
    val bgColor = if (isInstalled) AccentColor.copy(alpha = 0.15f) else SurfaceColor

    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = bgColor,
        border = if (isInstalled) {
            BorderStroke(2.dp, AccentColor)
        } else {
            BorderStroke(1.dp, BorderColor)
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    if (isInstalled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Installed", fontSize = 10.sp, color = Color.White)
                        }
                    }
                    if (isRecommended && !isInstalled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(AccentColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Recommended", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            if (!isInstalled) {
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onInstallClick,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = AccentColor,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Install", fontSize = 12.sp)
                }
            }
        }
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
    val isWindows = ai.rever.bossterm.compose.shell.ShellCustomizationUtils.isWindows()

    // Determine user's current shell
    val currentShellPath = System.getenv("SHELL") ?: ""
    val currentShellName = currentShellPath.substringAfterLast("/").lowercase()

    // Platform-specific shell options
    val shellOptions = if (isWindows) {
        listOf(ShellChoice.POWERSHELL, ShellChoice.CMD)
    } else {
        listOf(ShellChoice.ZSH, ShellChoice.BASH, ShellChoice.FISH)
    }

    // Check if current shell is one of the main options (Unix only)
    val isCurrentShellKnown = !isWindows && currentShellName in listOf("zsh", "bash", "fish")

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
            text = if (isWindows) {
                "Select a shell for your terminal. PowerShell is recommended for its modern features."
            } else {
                "Select a shell for your terminal. Zsh is recommended for its features and plugin ecosystem."
            },
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Show platform-appropriate shell options
        shellOptions.forEach { choice ->
            val isInstalled = when (choice) {
                ShellChoice.ZSH -> installedTools.zsh
                ShellChoice.BASH -> installedTools.bash
                ShellChoice.FISH -> installedTools.fish
                ShellChoice.POWERSHELL -> installedTools.powershell
                ShellChoice.CMD -> installedTools.cmd
                else -> false
            }
            val isCurrent = if (isWindows) {
                // On Windows, PowerShell is typically the default modern shell
                choice == ShellChoice.POWERSHELL
            } else {
                currentShellName == choice.command
            }

            SelectionCard(
                title = choice.displayName,
                description = choice.description,
                isSelected = selections.shell == choice,
                isRecommended = if (isWindows) choice == ShellChoice.POWERSHELL else choice == ShellChoice.ZSH,
                badge = when {
                    isCurrent -> "Current"
                    isInstalled -> "Installed"
                    else -> null
                },
                onClick = { onSelectionChange(choice) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Only show "Keep Current" if using a different shell (not zsh, bash, or fish) - Unix only
        if (!isWindows && !isCurrentShellKnown && currentShellPath.isNotEmpty()) {
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
    val isWindows = ai.rever.bossterm.compose.shell.ShellCustomizationUtils.isWindows()
    val currentShellPath = System.getenv("SHELL") ?: ""
    val currentShellName = currentShellPath.substringAfterLast("/").lowercase()
    val isZshSelected = selections.shell == ShellChoice.ZSH ||
        (selections.shell == ShellChoice.KEEP_CURRENT && currentShellName == "zsh")

    // Platform-specific customization options
    val customizationOptions = if (isWindows) {
        listOf(
            ShellCustomizationChoice.STARSHIP,
            ShellCustomizationChoice.OH_MY_POSH,
            ShellCustomizationChoice.NONE
        )
    } else {
        listOf(
            ShellCustomizationChoice.STARSHIP,
            ShellCustomizationChoice.OH_MY_ZSH,
            ShellCustomizationChoice.PREZTO,
            ShellCustomizationChoice.NONE
        )
    }

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

        // Show platform-appropriate customization options
        customizationOptions.forEach { choice ->
            val isDisabled = !isWindows && choice.requiresZsh && !isZshSelected
            val isCurrent = when (choice) {
                ShellCustomizationChoice.STARSHIP -> installedTools.starship
                ShellCustomizationChoice.OH_MY_ZSH -> installedTools.ohMyZsh
                ShellCustomizationChoice.PREZTO -> installedTools.prezto
                ShellCustomizationChoice.OH_MY_POSH -> installedTools.ohMyPosh
                else -> false  // Don't mark NONE as current - there could be other customizations we don't detect
            }

            SelectionCard(
                title = choice.displayName,
                description = if (isDisabled) "${choice.description} (Requires Zsh)" else choice.description,
                isSelected = selections.shellCustomization == choice,
                isRecommended = choice == ShellCustomizationChoice.STARSHIP,
                isDisabled = isDisabled,
                badge = if (isCurrent) "Installed" else null,
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

    // Build assistants list from the registry with installed status
    val assistants = AIAssistants.AI_ASSISTANTS.map { definition ->
        val isInstalled = when (definition.id) {
            AIAssistantIds.CLAUDE_CODE -> installedTools.claudeCode
            AIAssistantIds.GEMINI_CLI -> installedTools.gemini
            AIAssistantIds.CODEX -> installedTools.codex
            AIAssistantIds.OPENCODE -> installedTools.opencode
            else -> false
        }
        AIAssistant(definition.id, definition.displayName, definition.description, isInstalled)
    }

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
    val isWindows = ai.rever.bossterm.compose.shell.ShellCustomizationUtils.isWindows()

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
        val shellWillInstall = selections.shell != ShellChoice.KEEP_CURRENT && !isWindows && !when (selections.shell) {
            ShellChoice.ZSH -> installedTools.zsh
            ShellChoice.BASH -> installedTools.bash
            ShellChoice.FISH -> installedTools.fish
            ShellChoice.POWERSHELL -> installedTools.powershell
            ShellChoice.CMD -> installedTools.cmd
            ShellChoice.KEEP_CURRENT -> true
        }
        ReviewItem(
            category = "Shell",
            value = selections.shell.displayName,
            willInstall = shellWillInstall
        )

        // Shell Customization
        if (selections.shellCustomization != ShellCustomizationChoice.NONE &&
            selections.shellCustomization != ShellCustomizationChoice.KEEP_EXISTING
        ) {
            val willInstall = !when (selections.shellCustomization) {
                ShellCustomizationChoice.STARSHIP -> installedTools.starship
                ShellCustomizationChoice.OH_MY_ZSH -> installedTools.ohMyZsh
                ShellCustomizationChoice.PREZTO -> installedTools.prezto
                ShellCustomizationChoice.OH_MY_POSH -> installedTools.ohMyPosh
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
                AIAssistantIds.CLAUDE_CODE -> installedTools.claudeCode
                AIAssistantIds.GEMINI_CLI -> installedTools.gemini
                AIAssistantIds.CODEX -> installedTools.codex
                AIAssistantIds.OPENCODE -> installedTools.opencode
                else -> true
            }
            if (!isInstalled) {
                // Get display name from registry, fallback to ID
                val name = AIAssistants.findById(id)?.displayName ?: id
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
    val isWindows = ai.rever.bossterm.compose.shell.ShellCustomizationUtils.isWindows()

    // On Windows, shells are built-in, so don't count them as "to install"
    if (selections.shell != ShellChoice.KEEP_CURRENT && !isWindows) {
        val shellInstalled = when (selections.shell) {
            ShellChoice.ZSH -> installed.zsh
            ShellChoice.BASH -> installed.bash
            ShellChoice.FISH -> installed.fish
            ShellChoice.POWERSHELL -> installed.powershell
            ShellChoice.CMD -> installed.cmd
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
            ShellCustomizationChoice.OH_MY_POSH -> installed.ohMyPosh
            else -> true
        }
        if (!customInstalled) return true
    }
    if (selections.installGit && !installed.git) return true
    if (selections.installGitHubCLI && !installed.gh) return true
    selections.aiAssistants.forEach { id ->
        val aiInstalled = when (id) {
            AIAssistantIds.CLAUDE_CODE -> installed.claudeCode
            AIAssistantIds.GEMINI_CLI -> installed.gemini
            AIAssistantIds.CODEX -> installed.codex
            AIAssistantIds.OPENCODE -> installed.opencode
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
            text = "Please wait while we install your selected tools.\n🔐 Note: When typing your password, the cursor won't move - this is normal.",
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
