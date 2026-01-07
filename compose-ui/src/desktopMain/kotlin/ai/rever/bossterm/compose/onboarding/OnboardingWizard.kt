package ai.rever.bossterm.compose.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.ai.AIAssistantIds
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wizard step sealed class representing each step in the onboarding flow.
 */
sealed class OnboardingStep(val index: Int, val displayName: String) {
    object Welcome : OnboardingStep(0, "Welcome")
    object ShellSelection : OnboardingStep(1, "Shell")
    object ShellCustomization : OnboardingStep(2, "Customization")
    object VersionControl : OnboardingStep(3, "Git")
    object AIAssistants : OnboardingStep(4, "AI Assistants")
    object Review : OnboardingStep(5, "Review")
    object Installing : OnboardingStep(6, "Installing")
    object GhAuth : OnboardingStep(7, "GitHub Auth")
    object Complete : OnboardingStep(8, "Complete")

    companion object {
        val allSteps: List<OnboardingStep> by lazy {
            listOf(
                Welcome, ShellSelection, ShellCustomization,
                VersionControl, AIAssistants, Review, Installing, GhAuth, Complete
            )
        }
        val visibleSteps: List<OnboardingStep> by lazy {
            listOf(
                Welcome, ShellSelection, ShellCustomization,
                VersionControl, AIAssistants, Review
            )
        }
    }
}

/**
 * Shell choice options.
 */
enum class ShellChoice(
    val id: String,
    val displayName: String,
    val description: String,
    val command: String
) {
    ZSH("zsh", "Zsh", "Modern shell with powerful features, tab completion, and plugin support", "zsh"),
    BASH("bash", "Bash", "Classic Unix shell, widely compatible across systems", "bash"),
    FISH("fish", "Fish", "User-friendly shell with autosuggestions and syntax highlighting", "fish"),
    KEEP_CURRENT("keep", "Keep Current", "Use your current default shell", "")
}

/**
 * Shell customization options.
 */
enum class ShellCustomizationChoice(
    val id: String,
    val displayName: String,
    val description: String,
    val requiresZsh: Boolean
) {
    STARSHIP("starship", "Starship", "Fast, minimal, customizable prompt for any shell", false),
    OH_MY_ZSH("oh-my-zsh", "Oh My Zsh", "Framework with 300+ plugins and 150+ themes", true),
    PREZTO("prezto", "Prezto", "Lightweight Zsh configuration framework", true),
    NONE("none", "None", "Keep the default shell prompt", false),
    KEEP_EXISTING("keep", "Keep Existing", "You already have customization installed", false)
}

/**
 * User selections data class.
 */
data class OnboardingSelections(
    val shell: ShellChoice = ShellChoice.ZSH,
    val shellCustomization: ShellCustomizationChoice = ShellCustomizationChoice.STARSHIP,
    val installGit: Boolean = true,
    val installGitHubCLI: Boolean = true,
    val aiAssistants: Set<String> = AIAssistantIds.ALL_AI_ASSISTANTS
)

/**
 * Detected installed tools.
 */
data class InstalledTools(
    val zsh: Boolean = false,
    val bash: Boolean = false,
    val fish: Boolean = false,
    val starship: Boolean = false,
    val ohMyZsh: Boolean = false,
    val prezto: Boolean = false,
    val git: Boolean = false,
    val gh: Boolean = false,
    val claudeCode: Boolean = false,
    val gemini: Boolean = false,
    val codex: Boolean = false,
    val opencode: Boolean = false
) {
    val hasAnyShellCustomization: Boolean
        get() = starship || ohMyZsh || prezto
}

/**
 * First-time welcome wizard for BossTerm.
 *
 * @param onDismiss Called when wizard is closed or skipped
 * @param onComplete Called when wizard completes successfully
 * @param settingsManager Settings manager for persisting onboardingCompleted
 */
@Composable
fun OnboardingWizard(
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    settingsManager: SettingsManager
) {
    var currentStep by remember { mutableStateOf<OnboardingStep>(OnboardingStep.Welcome) }
    var selections by remember { mutableStateOf(OnboardingSelections()) }
    var installedTools by remember { mutableStateOf(InstalledTools()) }
    var isDetecting by remember { mutableStateOf(true) }
    var installCommand by remember { mutableStateOf("") }
    var installationComplete by remember { mutableStateOf(false) }
    var ghInstalledDuringWizard by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Detect installed tools on launch
    LaunchedEffect(Unit) {
        installedTools = detectInstalledTools()
        isDetecting = false
    }

    // Mark onboarding as completed on dismiss or complete
    fun markCompleted() {
        scope.launch {
            settingsManager.updateSetting {
                copy(onboardingCompleted = true)
            }
        }
    }

    DialogWindow(
        onCloseRequest = {
            markCompleted()
            onDismiss()
        },
        title = "BossTerm Setup",
        resizable = false,
        state = rememberDialogState(size = DpSize(650.dp, 550.dp))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BackgroundColor
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp)
            ) {
                // Step indicator (not shown during Installing/GhAuth/Complete)
                if (currentStep !is OnboardingStep.Installing &&
                    currentStep !is OnboardingStep.GhAuth &&
                    currentStep !is OnboardingStep.Complete) {
                    StepIndicator(
                        currentStep = currentStep,
                        steps = OnboardingStep.visibleSteps
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Step content
                Box(modifier = Modifier.weight(1f)) {
                    if (isDetecting) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AccentColor)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Detecting installed tools...", color = TextSecondary)
                            }
                        }
                    } else {
                            when (currentStep) {
                                OnboardingStep.Welcome -> WelcomeStep()
                                OnboardingStep.ShellSelection -> ShellSelectionStep(
                                    selections = selections,
                                    installedTools = installedTools,
                                    onSelectionChange = { selections = selections.copy(shell = it) }
                                )
                                OnboardingStep.ShellCustomization -> ShellCustomizationStep(
                                    selections = selections,
                                    installedTools = installedTools,
                                    onSelectionChange = { selections = selections.copy(shellCustomization = it) }
                                )
                                OnboardingStep.VersionControl -> VersionControlStep(
                                    selections = selections,
                                    installedTools = installedTools,
                                    onGitChange = { selections = selections.copy(installGit = it) },
                                    onGhChange = { selections = selections.copy(installGitHubCLI = it) }
                                )
                                OnboardingStep.AIAssistants -> AIAssistantsStep(
                                    selections = selections,
                                    installedTools = installedTools,
                                    onSelectionChange = { selections = selections.copy(aiAssistants = it) }
                                )
                                OnboardingStep.Review -> ReviewStep(
                                    selections = selections,
                                    installedTools = installedTools
                                )
                                OnboardingStep.Installing -> InstallingStep(
                                    installCommand = installCommand,
                                    onComplete = {
                                        installationComplete = true
                                        // Go to GhAuth step if gh was installed during wizard
                                        if (ghInstalledDuringWizard) {
                                            currentStep = OnboardingStep.GhAuth
                                        } else {
                                            currentStep = OnboardingStep.Complete
                                        }
                                    }
                                )
                                OnboardingStep.GhAuth -> GhAuthStep(
                                    onComplete = {
                                        currentStep = OnboardingStep.Complete
                                    },
                                    onSkip = {
                                        currentStep = OnboardingStep.Complete
                                    }
                                )
                                OnboardingStep.Complete -> CompleteStep(
                                    onRelaunch = {
                                        markCompleted()
                                        // Request app restart - cross-platform
                                        try {
                                            val restartCommand = getRestartCommand()
                                            if (restartCommand != null) {
                                                val osName = System.getProperty("os.name") ?: ""
                                                if (osName.lowercase().contains("windows")) {
                                                    Runtime.getRuntime().exec(arrayOf("cmd", "/c", "timeout /t 1 && $restartCommand"))
                                                } else {
                                                    Runtime.getRuntime().exec(arrayOf("bash", "-c", "sleep 0.5 && $restartCommand &"))
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Fallback: just exit without restart
                                        }
                                        System.exit(0)
                                    },
                                    onDismiss = {
                                        markCompleted()
                                        onComplete()
                                    }
                                )
                            }
                        }
                    }

                Spacer(modifier = Modifier.height(16.dp))

                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button (not on Welcome, Installing, GhAuth, or Complete)
                    if (currentStep.index > 0 &&
                        currentStep !is OnboardingStep.Installing &&
                        currentStep !is OnboardingStep.GhAuth &&
                        currentStep !is OnboardingStep.Complete
                    ) {
                        OutlinedButton(
                            onClick = {
                                currentStep = OnboardingStep.allSteps[currentStep.index - 1]
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = Color.Transparent,
                                contentColor = TextPrimary
                            ),
                            border = BorderStroke(1.dp, BorderColor)
                        ) {
                            Text("Back")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Skip button (only on Welcome)
                        if (currentStep is OnboardingStep.Welcome) {
                            TextButton(
                                onClick = {
                                    markCompleted()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = TextSecondary
                                )
                            ) {
                                Text("Skip Setup")
                            }
                        }

                        // Next/Install/Finish button
                        when (currentStep) {
                            OnboardingStep.Welcome -> {
                                Button(
                                    onClick = { currentStep = OnboardingStep.ShellSelection },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentColor,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("Get Started")
                                }
                            }
                            OnboardingStep.Review -> {
                                val hasSelections = hasAnySelection(selections, installedTools)
                                Button(
                                    onClick = {
                                        if (hasSelections) {
                                            // Track if gh will be installed
                                            ghInstalledDuringWizard = selections.installGitHubCLI && !installedTools.gh
                                            installCommand = buildInstallCommand(selections, installedTools)
                                            currentStep = OnboardingStep.Installing
                                        } else {
                                            markCompleted()
                                            onComplete()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentColor,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text(if (hasSelections) "Install Selected" else "Finish")
                                }
                            }
                            OnboardingStep.Installing -> {
                                // No button during installation
                            }
                            OnboardingStep.GhAuth -> {
                                // GhAuthStep handles its own buttons
                            }
                            OnboardingStep.Complete -> {
                                // CompleteStep handles its own buttons
                            }
                            else -> {
                                Button(
                                    onClick = { currentStep = OnboardingStep.allSteps[currentStep.index + 1] },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentColor,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("Next")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Check if there are any selections that require installation.
 */
private fun hasAnySelection(selections: OnboardingSelections, installed: InstalledTools): Boolean {
    if (selections.shell != ShellChoice.KEEP_CURRENT) {
        val shellInstalled = when (selections.shell) {
            ShellChoice.ZSH -> installed.zsh
            ShellChoice.BASH -> installed.bash
            ShellChoice.FISH -> installed.fish
            ShellChoice.KEEP_CURRENT -> true
        }
        if (!shellInstalled) return true
    }
    // Check if NONE is selected and there are existing customizations to uninstall
    if (selections.shellCustomization == ShellCustomizationChoice.NONE) {
        if (installed.starship || installed.ohMyZsh || installed.prezto) return true
    } else if (selections.shellCustomization != ShellCustomizationChoice.KEEP_EXISTING) {
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
    if (selections.aiAssistants.isNotEmpty()) {
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
    }
    return false
}

/**
 * Detect installed tools.
 */
suspend fun detectInstalledTools(): InstalledTools = withContext(Dispatchers.IO) {
    InstalledTools(
        zsh = isCommandInstalled("zsh"),
        bash = isCommandInstalled("bash"),
        fish = isCommandInstalled("fish"),
        starship = ShellCustomizationUtils.isStarshipInstalled() || isCommandInstalled("starship"),
        ohMyZsh = ShellCustomizationUtils.isOhMyZshInstalled(),
        prezto = ShellCustomizationUtils.isPreztoInstalled(),
        git = isCommandInstalled("git"),
        gh = isCommandInstalled("gh"),
        claudeCode = isCommandInstalled("claude"),
        gemini = isCommandInstalled("gemini"),
        codex = isCommandInstalled("codex"),
        opencode = isCommandInstalled("opencode")
    )
}

/**
 * Check if a command is installed.
 * Uses a login shell to ensure user's PATH is fully loaded (including npm, nvm, etc.)
 */
private fun isCommandInstalled(command: String): Boolean {
    var process: Process? = null
    return try {
        // Use login shell to source user's profile and get full PATH
        val shell = System.getenv("SHELL") ?: "/bin/bash"
        process = ProcessBuilder(shell, "-l", "-c", "command -v $command")
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(3, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            // Fall through to path check
        } else if (process.exitValue() == 0) {
            return true
        }

        // Fallback: check common installation paths directly
        val home = System.getProperty("user.home") ?: return false
        val commonPaths = listOf(
            "$home/.local/bin/$command",
            "$home/.npm-global/bin/$command",
            "$home/.nvm/versions/node/*/bin/$command",
            "$home/.$command/bin/$command",  // e.g., ~/.opencode/bin/opencode
            "$home/.cargo/bin/$command",
            "$home/go/bin/$command",
            "/usr/local/bin/$command",
            "/opt/homebrew/bin/$command"
        )

        commonPaths.any { path ->
            if (path.contains("*")) {
                // Handle glob patterns (e.g., nvm paths)
                val basePath = path.substringBefore("*")
                val suffix = path.substringAfter("*")
                File(basePath).takeIf { it.isDirectory }?.listFiles()?.any { dir ->
                    File(dir.absolutePath + suffix).exists()
                } ?: false
            } else {
                File(path).exists()
            }
        }
    } catch (e: Exception) {
        false
    } finally {
        process?.inputStream?.close()
        process?.errorStream?.close()
        process?.outputStream?.close()
    }
}

/**
 * Get the command to restart the current application.
 * Cross-platform: works on Linux, macOS, and Windows.
 *
 * @return The restart command string, or null if unable to determine
 */
private fun getRestartCommand(): String? {
    val osName = System.getProperty("os.name") ?: return null

    return try {
        when {
            // Linux: Use /proc/self/cmdline
            osName.lowercase().contains("linux") -> {
                val cmdlineFile = File("/proc/self/cmdline")
                if (cmdlineFile.exists()) {
                    cmdlineFile.readText().replace('\u0000', ' ').trim().takeIf { it.isNotEmpty() }
                } else null
            }

            // macOS: Use ps command to get full command
            osName.lowercase().contains("mac") -> {
                val pid = ProcessHandle.current().pid()
                var process: Process? = null
                try {
                    process = ProcessBuilder("ps", "-p", pid.toString(), "-o", "command=")
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                    val completed = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        null
                    } else if (process.exitValue() == 0 && output.isNotEmpty()) {
                        output
                    } else null
                } finally {
                    process?.inputStream?.close()
                    process?.errorStream?.close()
                    process?.outputStream?.close()
                }
            }

            // Windows: Use ProcessHandle info
            osName.lowercase().contains("windows") -> {
                val info = ProcessHandle.current().info()
                val command = info.command().orElse(null) ?: return null
                val args = info.arguments().orElse(emptyArray())
                if (args.isNotEmpty()) {
                    "\"$command\" ${args.joinToString(" ") { "\"$it\"" }}"
                } else {
                    "\"$command\""
                }
            }

            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Build the combined installation command for all selected tools.
 * Groups sudo commands together to minimize password prompts.
 *
 * @return The combined installation command, or an error message if building fails
 */
fun buildInstallCommand(selections: OnboardingSelections, installed: InstalledTools): String {
    return try {
        buildInstallCommandInternal(selections, installed)
    } catch (e: Exception) {
        "echo 'Error building installation command: ${e.message?.replace("'", "\\'")}' && exit 1"
    }
}

private fun buildInstallCommandInternal(selections: OnboardingSelections, installed: InstalledTools): String {
    val sudoCommands = mutableListOf<String>()
    val userCommands = mutableListOf<String>()
    val postInstallCommands = mutableListOf<String>()

    val osName = System.getProperty("os.name") ?: "unknown"
    val isMac = osName.lowercase().contains("mac")
    val isWindows = osName.lowercase().contains("windows")

    // Helper to get Linux install command
    fun getLinuxInstall(pkg: String): String {
        return "{ command -v apt >/dev/null 2>&1 && sudo apt install -y $pkg; } || " +
               "{ command -v dnf >/dev/null 2>&1 && sudo dnf install -y $pkg; } || " +
               "{ command -v pacman >/dev/null 2>&1 && sudo pacman -S --noconfirm $pkg; }"
    }

    // Shell installation and set as default
    if (selections.shell != ShellChoice.KEEP_CURRENT) {
        val shellInstalled = when (selections.shell) {
            ShellChoice.ZSH -> installed.zsh
            ShellChoice.BASH -> installed.bash
            ShellChoice.FISH -> installed.fish
            ShellChoice.KEEP_CURRENT -> true
        }
        val shellCmd = selections.shell.command
        if (!shellInstalled) {
            when {
                isMac -> sudoCommands.add("brew install $shellCmd")
                isWindows -> userCommands.add("echo 'Please install $shellCmd manually on Windows'")
                else -> sudoCommands.add(getLinuxInstall(shellCmd))
            }
        }
        // Set selected shell as default using chsh (requires sudo for non-interactive use)
        if (!isWindows) {
            sudoCommands.add("sudo chsh -s \$(which $shellCmd) \$USER && echo '✓ Default shell changed to $shellCmd'")
        }
    }

    // Shell customization (with conflict removal)
    // Use shared uninstall commands from ShellCustomizationUtils
    val uninstallOhMyZsh = ShellCustomizationUtils.getOhMyZshUninstallCommand()
    val uninstallPrezto = ShellCustomizationUtils.getPreztoUninstallCommand()
    val uninstallStarship = ShellCustomizationUtils.getStarshipUninstallCommand()

    // Handle NONE option - uninstall all existing customizations
    if (selections.shellCustomization == ShellCustomizationChoice.NONE) {
        if (installed.starship) {
            userCommands.add(uninstallStarship)
        }
        if (installed.ohMyZsh) {
            userCommands.add(uninstallOhMyZsh)
        }
        if (installed.prezto) {
            userCommands.add(uninstallPrezto)
        }
    } else if (selections.shellCustomization != ShellCustomizationChoice.KEEP_EXISTING) {
        val customInstalled = when (selections.shellCustomization) {
            ShellCustomizationChoice.STARSHIP -> installed.starship
            ShellCustomizationChoice.OH_MY_ZSH -> installed.ohMyZsh
            ShellCustomizationChoice.PREZTO -> installed.prezto
            else -> true
        }

        if (!customInstalled) {
            when (selections.shellCustomization) {
                ShellCustomizationChoice.STARSHIP -> {
                    // Uninstall Oh My Zsh and Prezto first (they conflict with Starship on Zsh)
                    if (installed.ohMyZsh) {
                        userCommands.add(uninstallOhMyZsh)
                    }
                    if (installed.prezto) {
                        userCommands.add(uninstallPrezto)
                    }
                    // Starship install script + shell config
                    userCommands.add("curl -sS https://starship.rs/install.sh | sh -s -- -y")
                    postInstallCommands.add(
                        "SHELL_NAME=\$(basename \"\$SHELL\") && " +
                        "if [ \"\$SHELL_NAME\" = \"zsh\" ]; then " +
                        "  grep -q 'starship init zsh' ~/.zshrc 2>/dev/null || echo 'eval \"\$(starship init zsh)\"' >> ~/.zshrc; " +
                        "elif [ \"\$SHELL_NAME\" = \"bash\" ]; then " +
                        "  grep -q 'starship init bash' ~/.bashrc 2>/dev/null || echo 'eval \"\$(starship init bash)\"' >> ~/.bashrc; " +
                        "elif [ \"\$SHELL_NAME\" = \"fish\" ]; then " +
                        "  mkdir -p ~/.config/fish && grep -q 'starship init fish' ~/.config/fish/config.fish 2>/dev/null || echo 'starship init fish | source' >> ~/.config/fish/config.fish; " +
                        "fi && echo '✓ Starship configured'"
                    )
                }
                ShellCustomizationChoice.OH_MY_ZSH -> {
                    // Uninstall Prezto and Starship first
                    if (installed.prezto) {
                        userCommands.add(uninstallPrezto)
                    }
                    if (installed.starship) {
                        userCommands.add(uninstallStarship)
                    }
                    userCommands.add("sh -c \"\$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\" \"\" --unattended")
                }
                ShellCustomizationChoice.PREZTO -> {
                    // Uninstall Oh My Zsh and Starship first
                    if (installed.ohMyZsh) {
                        userCommands.add(uninstallOhMyZsh)
                    }
                    if (installed.starship) {
                        userCommands.add(uninstallStarship)
                    }
                    userCommands.add(
                        "git clone --recursive https://github.com/sorin-ionescu/prezto.git \"\${ZDOTDIR:-\$HOME}/.zprezto\" && " +
                        "setopt EXTENDED_GLOB 2>/dev/null; " +
                        "for rcfile in \"\${ZDOTDIR:-\$HOME}\"/.zprezto/runcoms/^README.md(.N); do " +
                        "  ln -sf \"\$rcfile\" \"\${ZDOTDIR:-\$HOME}/.\${rcfile:t}\" 2>/dev/null; " +
                        "done && echo '✓ Prezto installed'"
                    )
                }
                else -> {}
            }
        }
    }

    // Git tools
    if (selections.installGit && !installed.git) {
        when {
            isMac -> sudoCommands.add("brew install git")
            isWindows -> userCommands.add("winget install Git.Git --accept-source-agreements --accept-package-agreements")
            else -> sudoCommands.add(getLinuxInstall("git"))
        }
    }
    if (selections.installGitHubCLI && !installed.gh) {
        when {
            isMac -> sudoCommands.add("brew install gh")
            isWindows -> userCommands.add("winget install GitHub.cli --accept-source-agreements --accept-package-agreements")
            else -> sudoCommands.add(getLinuxInstall("gh"))
        }
    }

    // AI Assistants (npm-based with node check)
    val aiToInstall = mutableListOf<Pair<String, String>>() // id to npm package
    selections.aiAssistants.forEach { id ->
        val aiInstalled = when (id) {
            AIAssistantIds.CLAUDE_CODE -> installed.claudeCode
            AIAssistantIds.GEMINI_CLI -> installed.gemini
            AIAssistantIds.CODEX -> installed.codex
            AIAssistantIds.OPENCODE -> installed.opencode
            else -> true
        }
        if (!aiInstalled) {
            val npmPkg = when (id) {
                AIAssistantIds.CLAUDE_CODE -> "@anthropic-ai/claude-code"
                AIAssistantIds.GEMINI_CLI -> "@google/gemini-cli"
                AIAssistantIds.CODEX -> "@openai/codex"
                AIAssistantIds.OPENCODE -> "opencode-ai"
                else -> null
            }
            if (npmPkg != null) {
                aiToInstall.add(id to npmPkg)
            }
        }
    }

    if (aiToInstall.isNotEmpty()) {
        val npmPackages = aiToInstall.joinToString(" ") { it.second }
        val nodeCheckAndInstall = when {
            isMac -> {
                "{ command -v npm >/dev/null 2>&1 || { echo 'Installing Node.js via Homebrew...' && brew install node; }; } && " +
                "npm install -g $npmPackages"
            }
            isWindows -> {
                "powershell -Command \"if (!(Get-Command npm -ErrorAction SilentlyContinue)) { " +
                "winget install OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements } ; " +
                "npm install -g $npmPackages\""
            }
            else -> {
                "{ command -v npm >/dev/null 2>&1 || { " +
                "echo 'Installing Node.js via nvm...' && " +
                "curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/master/install.sh | bash && " +
                "export NVM_DIR=\"\$HOME/.nvm\" && " +
                "[ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\" && " +
                "nvm install --lts; }; } && " +
                "export NVM_DIR=\"\$HOME/.nvm\" && [ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\" && " +
                "npm install -g $npmPackages"
            }
        }
        userCommands.add(nodeCheckAndInstall)
    }

    // Build final command
    val allCommands = mutableListOf<String>()

    // Group sudo commands (only for non-Windows)
    if (sudoCommands.isNotEmpty() && !isWindows) {
        allCommands.add("echo '🔐 Administrator password required for installation...'")
        allCommands.add("sudo -v")  // Request sudo upfront
        allCommands.addAll(sudoCommands)
    } else if (sudoCommands.isNotEmpty()) {
        allCommands.addAll(sudoCommands)
    }

    // Add user commands
    allCommands.addAll(userCommands)

    // Add post-install commands
    allCommands.addAll(postInstallCommands)

    // Add completion message
    allCommands.add("echo '' && echo '✓ Installation complete!'")

    return allCommands.joinToString(" && ")
}
