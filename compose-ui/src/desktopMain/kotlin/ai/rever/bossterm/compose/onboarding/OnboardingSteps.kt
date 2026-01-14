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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.IconButton
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.rememberTabbedTerminalState
import ai.rever.bossterm.compose.ai.AIAssistantIds
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
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
 * Password step for collecting admin password.
 * This password will be used for all sudo commands during installations.
 */
@Composable
fun PasswordStep(
    password: String,
    onPasswordChange: (String) -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Administrator Password",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Some installations require administrator privileges. Enter your password once here, and it will be used for all installations.",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(24.dp))

        val passwordLabel = when {
            ShellCustomizationUtils.isMacOS() -> "macOS Password"
            ShellCustomizationUtils.isWindows() -> "Windows Password"
            else -> "System Password"
        }

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
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
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
                    ),
                    placeholder = { Text("Enter your password", color = TextMuted) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your password is stored only in memory during this session and is never saved to disk.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }
    }
}

/**
 * Prerequisites step for package managers.
 * Shows platform-appropriate package managers:
 * - Windows: winget and Chocolatey
 * - macOS: Homebrew
 */
@Composable
fun PrerequisitesStep(
    installedTools: InstalledTools,
    adminPassword: String,  // Password passed from wizard
    onRefreshTools: () -> Unit
) {
    // Platform detection
    val isWindows = remember { ShellCustomizationUtils.isWindows() }
    val isMac = remember { ShellCustomizationUtils.isMacOS() }

    var showWingetInstall by remember { mutableStateOf(false) }
    var showChocoInstall by remember { mutableStateOf(false) }
    var showHomebrewInstall by remember { mutableStateOf(false) }

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

    // Homebrew installation command for macOS - writes script to temp file for reliable execution
    // Uses sudo -S to read password from environment variable (passed via BOSSTERM_SUDO_PWD)
    val homebrewInstallCommand = """
cat > /tmp/bossterm_brew_install.sh << 'BREWINSTALL_EOF'
#!/bin/bash
set -e

echo "Installing Homebrew..."
echo ""
echo "This will install Homebrew, the missing package manager for macOS."
echo ""

# Authenticate sudo using password from environment variable
echo "${'$'}BOSSTERM_SUDO_PWD" | sudo -S -v 2>/dev/null

# Keep sudo credentials alive in background
(while true; do sudo -n true; sleep 50; kill -0 "$$" 2>/dev/null || exit; done) &
SUDO_KEEPALIVE_PID=${'$'}!

# Cleanup function
cleanup() {
    kill ${'$'}SUDO_KEEPALIVE_PID 2>/dev/null || true
    rm -f /tmp/bossterm_brew_install.sh
}
trap cleanup EXIT

echo ""
echo "Installing Homebrew (this may take a few minutes)..."
echo ""

# Run Homebrew installer in non-interactive mode
NONINTERACTIVE=1 /bin/bash -c "${'$'}(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

echo ""
echo "Configuring PATH..."

# Detect brew installation path
BREW_PATH=""
if [ -f "/opt/homebrew/bin/brew" ]; then
    BREW_PATH="/opt/homebrew/bin/brew"
elif [ -f "/usr/local/bin/brew" ]; then
    BREW_PATH="/usr/local/bin/brew"
fi

if [ -n "${'$'}BREW_PATH" ]; then
    SHELL_NAME=${'$'}(basename "${'$'}SHELL")

    if [ "${'$'}SHELL_NAME" = "zsh" ]; then
        PROFILE_FILE="${'$'}HOME/.zprofile"
    elif [ "${'$'}SHELL_NAME" = "bash" ]; then
        PROFILE_FILE="${'$'}HOME/.bash_profile"
        [ ! -f "${'$'}PROFILE_FILE" ] && PROFILE_FILE="${'$'}HOME/.bashrc"
    else
        PROFILE_FILE="${'$'}HOME/.profile"
    fi

    # Remove any existing brew shellenv lines (commented or not)
    if [ -f "${'$'}PROFILE_FILE" ]; then
        grep -v 'brew shellenv' "${'$'}PROFILE_FILE" > "${'$'}PROFILE_FILE.tmp" 2>/dev/null || true
        mv "${'$'}PROFILE_FILE.tmp" "${'$'}PROFILE_FILE" 2>/dev/null || true
    fi

    # Add fresh brew shellenv line
    echo "Adding Homebrew to PATH in ${'$'}PROFILE_FILE..."
    echo "eval \"\${'$'}(${'$'}BREW_PATH shellenv)\"" >> "${'$'}PROFILE_FILE"

    echo ""
    echo "Homebrew installed and PATH configured!"
    echo "Click 'Refresh Status' to verify installation."
else
    echo ""
    echo "Warning: Homebrew installed but could not find brew executable."
    echo "You may need to configure PATH manually."
fi
BREWINSTALL_EOF
chmod +x /tmp/bossterm_brew_install.sh && /tmp/bossterm_brew_install.sh
    """.trimIndent()

    // Determine if package manager is available for current platform
    val hasPackageManager = when {
        isWindows -> installedTools.hasWindowsPackageManager
        isMac -> installedTools.hasMacPackageManager
        else -> true // Linux doesn't need this step
    }

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

        if (isWindows) {
            // Windows: Show winget and Chocolatey
            PackageManagerCard(
                title = "winget",
                description = "Windows Package Manager - Built into Windows 10/11. Recommended for most users.",
                isInstalled = installedTools.winget,
                isRecommended = true,
                onInstallClick = { showWingetInstall = true }
            )
            Spacer(modifier = Modifier.height(8.dp))

            PackageManagerCard(
                title = "Chocolatey",
                description = "Community-driven package manager with a large software catalog.",
                isInstalled = installedTools.chocolatey,
                isRecommended = false,
                onInstallClick = { showChocoInstall = true }
            )
        } else if (isMac) {
            // macOS: Show Homebrew
            PackageManagerCard(
                title = "Homebrew",
                description = "The missing package manager for macOS. Required to install most developer tools.",
                isInstalled = installedTools.homebrew,
                isRecommended = true,
                onInstallClick = { showHomebrewInstall = true }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status message
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = if (hasPackageManager) {
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
                    text = if (hasPackageManager) "✓" else "⚠",
                    fontSize = 20.sp,
                    color = if (hasPackageManager) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFFFC107)
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (hasPackageManager) {
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
                    onInitialCommandComplete = { _, _ -> },
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
                    onInitialCommandComplete = { _, _ -> },
                    settingsOverride = TerminalSettingsOverride(fontSize = 11f),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (showHomebrewInstall) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Installing Homebrew...",
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
                    initialCommand = homebrewInstallCommand,
                    environment = mapOf("BOSSTERM_SUDO_PWD" to adminPassword),
                    onInitialCommandComplete = { _, _ -> },
                    onOutput = { output ->
                        if (output.contains("Homebrew installed and PATH configured!")) {
                            onRefreshTools()
                        }
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
    adminPassword: String,
    onComplete: (success: Boolean) -> Unit
) {
    var isRunning by remember { mutableStateOf(true) }
    var installSuccess by remember { mutableStateOf(false) }

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
            text = "Please wait while we install your selected tools.",
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
                environment = mapOf("BOSSTERM_SUDO_PWD" to adminPassword),
                onInitialCommandComplete = { success, exitCode ->
                    isRunning = false
                    installSuccess = success
                    onComplete(success)
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
        } else if (!installSuccess) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Installation failed. Please check the output above for errors.",
                fontSize = 14.sp,
                color = Color(0xFFFF6B6B)
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
    var detectedOtp by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val terminalState = rememberTabbedTerminalState(autoDispose = false)

    // Poll for OTP pattern every 500ms while running
    // GitHub OTP format: XXXX-XXXX (e.g., A1B2-C3D4)
    LaunchedEffect(isRunning) {
        while (isRunning && detectedOtp == null) {
            delay(500)
            // Search for pattern like "A1B2-C3D4" (alphanumeric with dash)
            val matches = terminalState.findPatternRegex("[A-Z0-9]{4}-[A-Z0-9]{4}")
            if (matches.isNotEmpty()) {
                detectedOtp = matches.first().text
            }
        }
    }

    // Cleanup terminal state when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            terminalState.dispose()
        }
    }

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
        Spacer(modifier = Modifier.height(12.dp))

        // Flowchart showing the 3-step process
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Step 1: Code Appears
            Card(
                backgroundColor = Color(0xFF2D4F2D),
                border = BorderStroke(1.dp, Color(0xFF4CAF50)),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("1. CODE APPEARS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF4CAF50))
                    Text("XXXX-XXXX", fontSize = 10.sp, color = TextMuted)
                }
            }

            // Arrow
            Text(" \u2192 ", fontSize = 16.sp, color = TextMuted, fontWeight = FontWeight.Bold)

            // Step 2: Copy Code (highlighted)
            Card(
                backgroundColor = Color(0xFF4F4F2D),
                border = BorderStroke(2.dp, Color(0xFFFFD700)),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("2. COPY CODE", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFFFD700))
                    Text("Select & Copy", fontSize = 10.sp, color = TextMuted)
                }
            }

            // Arrow
            Text(" \u2192 ", fontSize = 16.sp, color = TextMuted, fontWeight = FontWeight.Bold)

            // Step 3: Paste in Browser
            Card(
                backgroundColor = Color(0xFF2D3D4F),
                border = BorderStroke(1.dp, Color(0xFF2196F3)),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("3. PASTE", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF2196F3))
                    Text("In Browser", fontSize = 10.sp, color = TextMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bold instruction (hidden when OTP detected)
        AnimatedVisibility(
            visible = detectedOtp == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = "IMPORTANT: Copy the one-time code when it appears below!",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color(0xFFFFD700),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // OTP Copy Button (appears when code is detected)
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

        Spacer(modifier = Modifier.height(12.dp))

        val ghAuthCommand = "gh auth login"

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
        ) {
            TabbedTerminal(
                state = terminalState,
                initialCommand = ghAuthCommand,
                onInitialCommandComplete = { success, exitCode ->
                    isRunning = false
                    if (success && exitCode == 0) {
                        onComplete()
                    }
                    // If auth failed/cancelled, user can still click buttons
                },
                onExit = { /* Terminal closed - do nothing */ },
                onContextMenuOpen = { },  // Use SYNC callback to skip ALL async operations
                settingsOverride = TerminalSettingsOverride(
                    fontSize = 12f
                ),
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

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
