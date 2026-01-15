package ai.rever.bossterm.compose.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
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
    object Password : OnboardingStep(1, "Password")  // Admin password for installations
    object Prerequisites : OnboardingStep(2, "Prerequisites")  // Windows and macOS
    object ShellSelection : OnboardingStep(3, "Shell")
    object ShellCustomization : OnboardingStep(4, "Customization")
    object VersionControl : OnboardingStep(5, "Git")
    object AIAssistants : OnboardingStep(6, "AI Assistants")
    object Review : OnboardingStep(7, "Review")
    object Installing : OnboardingStep(8, "Installing")
    object GhAuth : OnboardingStep(9, "GitHub Auth")
    object Complete : OnboardingStep(10, "Complete")

    companion object {
        // All steps including Password and Prerequisites (Windows and macOS)
        val allStepsWithPrerequisites: List<OnboardingStep> by lazy {
            listOf(
                Welcome, Password, Prerequisites, ShellSelection, ShellCustomization,
                VersionControl, AIAssistants, Review, Installing, GhAuth, Complete
            )
        }
        // Steps for Linux (has Password for sudo, but no Prerequisites/package manager step)
        val allStepsLinux: List<OnboardingStep> by lazy {
            listOf(
                Welcome, Password, ShellSelection, ShellCustomization,
                VersionControl, AIAssistants, Review, Installing, GhAuth, Complete
            )
        }
        // Visible steps with Password and Prerequisites (Windows and macOS)
        val visibleStepsWithPrerequisites: List<OnboardingStep> by lazy {
            listOf(
                Welcome, Password, Prerequisites, ShellSelection, ShellCustomization,
                VersionControl, AIAssistants, Review
            )
        }
        // Visible steps for Linux (has Password, but no Prerequisites)
        val visibleStepsLinux: List<OnboardingStep> by lazy {
            listOf(
                Welcome, Password, ShellSelection, ShellCustomization,
                VersionControl, AIAssistants, Review
            )
        }

        // Check if platform needs Prerequisites step (Windows and macOS)
        fun needsPrerequisites(): Boolean =
            ShellCustomizationUtils.isWindows() || ShellCustomizationUtils.isMacOS()

        fun allSteps(needsPrerequisites: Boolean) = if (needsPrerequisites) allStepsWithPrerequisites else allStepsLinux
        fun visibleSteps(needsPrerequisites: Boolean) = if (needsPrerequisites) visibleStepsWithPrerequisites else visibleStepsLinux
    }
}

/**
 * Shell choice options.
 */
enum class ShellChoice(
    val id: String,
    val displayName: String,
    val description: String,
    val command: String,
    val isWindows: Boolean = false
) {
    // Unix shells
    ZSH("zsh", "Zsh", "Modern shell with powerful features, tab completion, and plugin support", "zsh"),
    BASH("bash", "Bash", "Classic Unix shell, widely compatible across systems", "bash"),
    FISH("fish", "Fish", "User-friendly shell with autosuggestions and syntax highlighting", "fish"),
    // Windows shells
    POWERSHELL("powershell", "PowerShell", "Modern Windows shell with scripting and automation", "powershell.exe", true),
    CMD("cmd", "Command Prompt", "Classic Windows command line interpreter", "cmd.exe", true),
    // Keep current
    KEEP_CURRENT("keep", "Keep Current", "Use your current default shell", "")
}

/**
 * Shell customization options.
 */
enum class ShellCustomizationChoice(
    val id: String,
    val displayName: String,
    val description: String,
    val requiresZsh: Boolean = false,
    val isWindowsOnly: Boolean = false
) {
    // Cross-platform
    STARSHIP("starship", "Starship", "Fast, minimal, customizable prompt for any shell", false, false),
    // Unix only (requires Zsh)
    OH_MY_ZSH("oh-my-zsh", "Oh My Zsh", "Framework with 300+ plugins and 150+ themes", true, false),
    PREZTO("prezto", "Prezto", "Lightweight Zsh configuration framework", true, false),
    // Windows only
    OH_MY_POSH("oh-my-posh", "Oh My Posh", "Prompt theme engine for PowerShell and CMD", false, true),
    // Common
    NONE("none", "None", "Keep the default shell prompt", false, false),
    KEEP_EXISTING("keep", "Keep Existing", "You already have customization installed", false, false)
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
    // Unix shells
    val zsh: Boolean = false,
    val bash: Boolean = false,
    val fish: Boolean = false,
    // Windows shells
    val powershell: Boolean = false,
    val cmd: Boolean = false,
    // Windows package managers
    val winget: Boolean = false,
    val chocolatey: Boolean = false,
    // macOS package managers
    val homebrew: Boolean = false,
    // Customization tools
    val starship: Boolean = false,
    val ohMyZsh: Boolean = false,
    val prezto: Boolean = false,
    val ohMyPosh: Boolean = false,
    // Version control
    val git: Boolean = false,
    val gh: Boolean = false,
    // AI assistants
    val claudeCode: Boolean = false,
    val gemini: Boolean = false,
    val codex: Boolean = false,
    val opencode: Boolean = false
) {
    val hasAnyShellCustomization: Boolean
        get() = starship || ohMyZsh || prezto || ohMyPosh

    val hasWindowsPackageManager: Boolean
        get() = winget || chocolatey

    val hasMacPackageManager: Boolean
        get() = homebrew

    val hasPackageManager: Boolean
        get() = winget || chocolatey || homebrew
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
    var adminPassword by remember { mutableStateOf("") }  // Admin password for sudo commands

    // Focus requester for primary action button (Get Started / Next)
    val primaryButtonFocusRequester = remember { FocusRequester() }

    // Platform detection
    val isWindows = remember { ShellCustomizationUtils.isWindows() }
    val isMac = remember { ShellCustomizationUtils.isMacOS() }
    val needsPrerequisites = remember { isWindows || isMac }

    val scope = rememberCoroutineScope()

    // Detect installed tools on launch
    LaunchedEffect(Unit) {
        installedTools = detectInstalledTools()
        isDetecting = false
    }

    // Request focus on primary button when step changes
    LaunchedEffect(currentStep) {
        // Focus the primary button for steps that have one
        if (currentStep !is OnboardingStep.Installing &&
            currentStep !is OnboardingStep.GhAuth &&
            currentStep !is OnboardingStep.Complete) {
            try {
                primaryButtonFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Ignore if focus request fails
            }
        }
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
                        steps = OnboardingStep.visibleSteps(needsPrerequisites)
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
                                OnboardingStep.Prerequisites -> PrerequisitesStep(
                                    installedTools = installedTools,
                                    adminPassword = adminPassword,
                                    onRefreshTools = {
                                        scope.launch {
                                            isDetecting = true
                                            installedTools = detectInstalledTools()
                                            isDetecting = false
                                        }
                                    }
                                )
                                OnboardingStep.Password -> PasswordStep(
                                    password = adminPassword,
                                    onPasswordChange = { adminPassword = it }
                                )
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
                                    adminPassword = adminPassword,
                                    onComplete = { success ->
                                        if (success) {
                                            installationComplete = true
                                            // Go to GhAuth step if gh was installed during wizard
                                            if (ghInstalledDuringWizard) {
                                                currentStep = OnboardingStep.GhAuth
                                            } else {
                                                currentStep = OnboardingStep.Complete
                                            }
                                        }
                                        // On failure, stay on Installing step - user can see error message
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
                    val allSteps = OnboardingStep.allSteps(needsPrerequisites)
                    val currentIndex = allSteps.indexOf(currentStep)
                    if (currentIndex > 0 &&
                        currentStep !is OnboardingStep.Installing &&
                        currentStep !is OnboardingStep.GhAuth &&
                        currentStep !is OnboardingStep.Complete
                    ) {
                        OutlinedButton(
                            onClick = {
                                currentStep = allSteps[currentIndex - 1]
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
                                    onClick = {
                                        // All platforms go to Password (needed for sudo on all platforms)
                                        currentStep = OnboardingStep.Password
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentColor,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .focusRequester(primaryButtonFocusRequester)
                                        .focusable()
                                        .onKeyEvent { event ->
                                            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                                currentStep = OnboardingStep.Password
                                                true
                                            } else false
                                        }
                                ) {
                                    Text("Get Started")
                                }
                            }
                            OnboardingStep.Review -> {
                                val hasSelections = hasAnySelection(selections, installedTools)
                                val doInstallOrFinish = {
                                    if (hasSelections) {
                                        // Track if gh will be installed
                                        ghInstalledDuringWizard = selections.installGitHubCLI && !installedTools.gh

                                        // Build script content and write to file
                                        val scriptContent = buildInstallCommand(selections, installedTools)
                                        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
                                        if (isWindows) {
                                            // Windows: run command directly
                                            installCommand = scriptContent
                                        } else {
                                            // Unix: write script to file and run it
                                            val scriptFile = File("/tmp/bossterm_install.sh")
                                            scriptFile.writeText(scriptContent)
                                            scriptFile.setExecutable(true)
                                            installCommand = "/tmp/bossterm_install.sh"
                                        }
                                        currentStep = OnboardingStep.Installing
                                    } else {
                                        markCompleted()
                                        onComplete()
                                    }
                                }
                                Button(
                                    onClick = { doInstallOrFinish() },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentColor,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .focusRequester(primaryButtonFocusRequester)
                                        .focusable()
                                        .onKeyEvent { event ->
                                            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                                doInstallOrFinish()
                                                true
                                            } else false
                                        }
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
                            OnboardingStep.Prerequisites -> {
                                // Prerequisites step on Windows and macOS - use normal navigation
                                val goToNextFromPrereqs = {
                                    val nextIndex = allSteps.indexOf(currentStep) + 1
                                    if (nextIndex < allSteps.size) {
                                        currentStep = allSteps[nextIndex]
                                    }
                                }
                                Button(
                                    onClick = { goToNextFromPrereqs() },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentColor,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .focusRequester(primaryButtonFocusRequester)
                                        .focusable()
                                        .onKeyEvent { event ->
                                            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                                goToNextFromPrereqs()
                                                true
                                            } else false
                                        }
                                ) {
                                    Text("Next")
                                }
                            }
                            else -> {
                                // Disable Next on Password step if password is empty
                                val canProceed = when (currentStep) {
                                    is OnboardingStep.Password -> adminPassword.isNotEmpty()
                                    else -> true
                                }
                                val goToNextStep = {
                                    if (canProceed) {
                                        val nextIndex = allSteps.indexOf(currentStep) + 1
                                        if (nextIndex < allSteps.size) {
                                            currentStep = allSteps[nextIndex]
                                        }
                                    }
                                }
                                Button(
                                    onClick = { goToNextStep() },
                                    enabled = canProceed,
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentColor,
                                        contentColor = Color.White,
                                        disabledBackgroundColor = AccentColor.copy(alpha = 0.5f),
                                        disabledContentColor = Color.White.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .focusRequester(primaryButtonFocusRequester)
                                        .focusable()
                                        .onKeyEvent { event ->
                                            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                                goToNextStep()
                                                true
                                            } else false
                                        }
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
            ShellChoice.POWERSHELL -> installed.powershell
            ShellChoice.CMD -> installed.cmd
            ShellChoice.KEEP_CURRENT -> true
        }
        if (!shellInstalled) return true
    }
    // Check if NONE is selected and there are existing customizations to uninstall
    if (selections.shellCustomization == ShellCustomizationChoice.NONE) {
        if (installed.starship || installed.ohMyZsh || installed.prezto || installed.ohMyPosh) return true
    } else if (selections.shellCustomization != ShellCustomizationChoice.KEEP_EXISTING) {
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
    val isWindows = ShellCustomizationUtils.isWindows()
    val isMac = ShellCustomizationUtils.isMacOS()

    InstalledTools(
        // Unix shells
        zsh = if (!isWindows) isCommandInstalled("zsh") else false,
        bash = if (!isWindows) isCommandInstalled("bash") else false,
        fish = if (!isWindows) isCommandInstalled("fish") else false,
        // Windows shells (always available on Windows)
        powershell = if (isWindows) isCommandInstalled("powershell") else false,
        cmd = isWindows, // cmd is always available on Windows
        // Windows package managers
        winget = if (isWindows) isCommandInstalled("winget") else false,
        chocolatey = if (isWindows) isCommandInstalled("choco") else false,
        // macOS package managers - check file directly for more reliable detection
        homebrew = if (isMac) isHomebrewInstalled() else false,
        // Customization tools
        starship = ShellCustomizationUtils.isStarshipInstalled() || isCommandInstalled("starship"),
        ohMyZsh = if (!isWindows) ShellCustomizationUtils.isOhMyZshInstalled() else false,
        prezto = if (!isWindows) ShellCustomizationUtils.isPreztoInstalled() else false,
        ohMyPosh = if (isWindows) isCommandInstalled("oh-my-posh") else false,
        // Version control
        git = isCommandInstalled("git"),
        gh = isCommandInstalled("gh"),
        // AI assistants
        claudeCode = isCommandInstalled("claude"),
        gemini = isCommandInstalled("gemini"),
        codex = isCommandInstalled("codex"),
        opencode = isCommandInstalled("opencode")
    )
}

/**
 * Check if Homebrew is installed by directly checking known installation paths.
 * More reliable than using login shell detection.
 */
private fun isHomebrewInstalled(): Boolean {
    // Apple Silicon Macs
    if (File("/opt/homebrew/bin/brew").exists()) return true
    // Intel Macs
    if (File("/usr/local/bin/brew").exists()) return true
    return false
}

/**
 * Check if a command is installed.
 * Uses platform-appropriate detection:
 * - Windows: Uses 'where' command
 * - Unix/macOS: Uses login shell with 'command -v'
 */
private fun isCommandInstalled(command: String): Boolean {
    val isWindows = ShellCustomizationUtils.isWindows()
    var process: Process? = null

    return try {
        if (isWindows) {
            // Windows: Use 'where' command
            process = ProcessBuilder("where", command)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } else {
            // Unix/macOS: Use login shell to source user's profile and get full PATH
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

    // Helper to get Windows install command with winget/chocolatey fallback
    // wingetId: winget package ID, chocoName: chocolatey package name
    fun getWindowsInstall(wingetId: String, chocoName: String): String {
        return when {
            installed.winget -> "winget install $wingetId --accept-source-agreements --accept-package-agreements"
            installed.chocolatey -> "choco install $chocoName -y"
            else -> "echo 'No package manager available. Please install winget or Chocolatey first.' && exit 1"
        }
    }

    // Helper for Windows uninstall with winget/chocolatey fallback
    fun getWindowsUninstall(wingetId: String, chocoName: String): String {
        return when {
            installed.winget -> "winget uninstall $wingetId --silent"
            installed.chocolatey -> "choco uninstall $chocoName -y"
            else -> "echo 'Skipping uninstall - no package manager available'"
        }
    }

    // Shell installation and set as default
    if (selections.shell != ShellChoice.KEEP_CURRENT) {
        val shellInstalled = when (selections.shell) {
            ShellChoice.ZSH -> installed.zsh
            ShellChoice.BASH -> installed.bash
            ShellChoice.FISH -> installed.fish
            ShellChoice.POWERSHELL -> installed.powershell
            ShellChoice.CMD -> installed.cmd
            ShellChoice.KEEP_CURRENT -> true
        }
        val shellCmd = selections.shell.command
        if (!shellInstalled && !isWindows) {
            // Only install shells on Unix (Windows shells are built-in)
            when {
                isMac -> userCommands.add("brew install $shellCmd")
                else -> sudoCommands.add(getLinuxInstall(shellCmd))
            }
        }
        // Set selected shell as default using chsh (Unix only, not needed on Windows)
        if (!isWindows && !shellInstalled) {
            sudoCommands.add("sudo chsh -s \$(which $shellCmd) \$USER && echo '✓ Default shell changed to $shellCmd'")
        }
    }

    // Shell customization (with conflict removal)
    // Use shared uninstall commands from ShellCustomizationUtils (Unix only)
    val uninstallOhMyZsh = ShellCustomizationUtils.getOhMyZshUninstallCommand()
    val uninstallPrezto = ShellCustomizationUtils.getPreztoUninstallCommand()
    val uninstallStarship = ShellCustomizationUtils.getStarshipUninstallCommand()

    // Handle NONE option - uninstall all existing customizations
    if (selections.shellCustomization == ShellCustomizationChoice.NONE) {
        if (installed.starship) {
            if (isWindows) {
                userCommands.add(getWindowsUninstall("Starship.Starship", "starship"))
            } else {
                userCommands.add(uninstallStarship)
            }
        }
        if (installed.ohMyZsh && !isWindows) {
            userCommands.add(uninstallOhMyZsh)
        }
        if (installed.prezto && !isWindows) {
            userCommands.add(uninstallPrezto)
        }
        if (installed.ohMyPosh && isWindows) {
            userCommands.add(getWindowsUninstall("JanDeDobbeleer.OhMyPosh", "oh-my-posh"))
        }
    } else if (selections.shellCustomization != ShellCustomizationChoice.KEEP_EXISTING) {
        val customInstalled = when (selections.shellCustomization) {
            ShellCustomizationChoice.STARSHIP -> installed.starship
            ShellCustomizationChoice.OH_MY_ZSH -> installed.ohMyZsh
            ShellCustomizationChoice.PREZTO -> installed.prezto
            ShellCustomizationChoice.OH_MY_POSH -> installed.ohMyPosh
            else -> true
        }

        if (!customInstalled) {
            when (selections.shellCustomization) {
                ShellCustomizationChoice.STARSHIP -> {
                    if (isWindows) {
                        // Windows: Install Starship and configure PowerShell profile
                        if (installed.ohMyPosh) {
                            userCommands.add(getWindowsUninstall("JanDeDobbeleer.OhMyPosh", "oh-my-posh"))
                        }
                        userCommands.add(getWindowsInstall("Starship.Starship", "starship"))
                        // Configure PowerShell profile
                        postInstallCommands.add(
                            "powershell -Command \"" +
                            "\$profilePath = \\\"\$env:USERPROFILE\\\\Documents\\\\PowerShell\\\\Microsoft.PowerShell_profile.ps1\\\"; " +
                            "if (!(Test-Path (Split-Path \\\$profilePath))) { New-Item -ItemType Directory -Path (Split-Path \\\$profilePath) -Force | Out-Null }; " +
                            "if (!(Test-Path \\\$profilePath)) { New-Item -ItemType File -Path \\\$profilePath -Force | Out-Null }; " +
                            "if (!(Select-String -Path \\\$profilePath -Pattern 'starship init' -Quiet -ErrorAction SilentlyContinue)) { " +
                            "Add-Content -Path \\\$profilePath -Value 'Invoke-Expression (&starship init powershell)' }; " +
                            "Write-Host 'Starship configured for PowerShell'\""
                        )
                    } else {
                        // Unix: Uninstall Oh My Zsh and Prezto first (they conflict with Starship on Zsh)
                        if (installed.ohMyZsh) {
                            userCommands.add(uninstallOhMyZsh)
                        }
                        if (installed.prezto) {
                            userCommands.add(uninstallPrezto)
                        }
                        // Starship install script + shell config + PATH setup
                        userCommands.add("curl -sS https://starship.rs/install.sh | sh -s -- -y")
                        postInstallCommands.add(
                            "SHELL_NAME=\$(basename \"\$SHELL\") && " +
                            "if [ \"\$SHELL_NAME\" = \"zsh\" ]; then " +
                            "  grep -q '/usr/local/bin' ~/.zshrc 2>/dev/null || grep -q '/usr/local/bin' ~/.zprofile 2>/dev/null || echo 'export PATH=\"/usr/local/bin:\$PATH\"' >> ~/.zprofile; " +
                            "  grep -q 'starship init zsh' ~/.zshrc 2>/dev/null || echo 'eval \"\$(starship init zsh)\"' >> ~/.zshrc; " +
                            "elif [ \"\$SHELL_NAME\" = \"bash\" ]; then " +
                            "  grep -q '/usr/local/bin' ~/.bashrc 2>/dev/null || grep -q '/usr/local/bin' ~/.bash_profile 2>/dev/null || echo 'export PATH=\"/usr/local/bin:\$PATH\"' >> ~/.bash_profile; " +
                            "  grep -q 'starship init bash' ~/.bashrc 2>/dev/null || echo 'eval \"\$(starship init bash)\"' >> ~/.bashrc; " +
                            "elif [ \"\$SHELL_NAME\" = \"fish\" ]; then " +
                            "  fish -c 'contains /usr/local/bin \$fish_user_paths' 2>/dev/null || fish -c 'set -U fish_user_paths /usr/local/bin \$fish_user_paths' 2>/dev/null; " +
                            "  mkdir -p ~/.config/fish && grep -q 'starship init fish' ~/.config/fish/config.fish 2>/dev/null || echo 'starship init fish | source' >> ~/.config/fish/config.fish; " +
                            "fi && echo '✓ Starship installed and PATH configured'"
                        )
                    }
                }
                ShellCustomizationChoice.OH_MY_POSH -> {
                    // Windows only: Install Oh My Posh
                    if (installed.starship) {
                        userCommands.add(getWindowsUninstall("Starship.Starship", "starship"))
                    }
                    userCommands.add(getWindowsInstall("JanDeDobbeleer.OhMyPosh", "oh-my-posh"))
                    // Configure PowerShell profile
                    postInstallCommands.add(
                        "powershell -Command \"" +
                        "\$profilePath = \\\"\$env:USERPROFILE\\\\Documents\\\\PowerShell\\\\Microsoft.PowerShell_profile.ps1\\\"; " +
                        "if (!(Test-Path (Split-Path \\\$profilePath))) { New-Item -ItemType Directory -Path (Split-Path \\\$profilePath) -Force | Out-Null }; " +
                        "if (!(Test-Path \\\$profilePath)) { New-Item -ItemType File -Path \\\$profilePath -Force | Out-Null }; " +
                        "if (!(Select-String -Path \\\$profilePath -Pattern 'oh-my-posh' -Quiet -ErrorAction SilentlyContinue)) { " +
                        "Add-Content -Path \\\$profilePath -Value 'oh-my-posh init pwsh | Invoke-Expression' }; " +
                        "Write-Host 'Oh My Posh configured for PowerShell'\""
                    )
                }
                ShellCustomizationChoice.OH_MY_ZSH -> {
                    // Unix only: Uninstall Prezto and Starship first
                    if (installed.prezto) {
                        userCommands.add(uninstallPrezto)
                    }
                    if (installed.starship) {
                        userCommands.add(uninstallStarship)
                    }
                    userCommands.add("sh -c \"\$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\" \"\" --unattended")
                }
                ShellCustomizationChoice.PREZTO -> {
                    // Unix only: Uninstall Oh My Zsh and Starship first
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
            isMac -> userCommands.add("brew install git")
            isWindows -> userCommands.add(getWindowsInstall("Git.Git", "git"))
            else -> sudoCommands.add(getLinuxInstall("git"))
        }
    }
    if (selections.installGitHubCLI && !installed.gh) {
        when {
            isMac -> userCommands.add("brew install gh")
            isWindows -> userCommands.add(getWindowsInstall("GitHub.cli", "gh"))
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
        // Build cleanup command to remove any corrupted/partial npm installations (fixes ENOTEMPTY error)
        val packagesToClean = aiToInstall.map { it.second }
        val unixCleanup = packagesToClean.joinToString("; ") { pkg ->
            "rm -rf \"\$(npm prefix -g 2>/dev/null)/lib/node_modules/$pkg\" 2>/dev/null || true"
        }
        val nodeCheckAndInstall = when {
            isMac -> {
                "{ command -v npm >/dev/null 2>&1 || { echo 'Installing Node.js via Homebrew...' && brew install node; }; } && " +
                "echo 'Cleaning up any partial installations...' && $unixCleanup && " +
                "npm install -g $npmPackages"
            }
            isWindows -> {
                // Use winget or Chocolatey to install Node.js if npm is not available
                val nodeInstallCmd = when {
                    installed.winget -> "winget install OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements"
                    installed.chocolatey -> "choco install nodejs-lts -y"
                    else -> "echo 'No package manager available. Please install Node.js manually.' && exit 1"
                }
                // Windows cleanup: remove partial npm installations
                val windowsCleanup = packagesToClean.joinToString("; ") { pkg ->
                    "Remove-Item -Recurse -Force \"\$(npm prefix -g)/node_modules/$pkg\" -ErrorAction SilentlyContinue"
                }
                "powershell -Command \"if (!(Get-Command npm -ErrorAction SilentlyContinue)) { $nodeInstallCmd } ; " +
                "Write-Host 'Cleaning up any partial installations...'; $windowsCleanup; " +
                "npm install -g $npmPackages\""
            }
            else -> {
                // Linux: use nvm to install Node.js and npm if not available
                // If npm is missing (even with node installed), reinstall node to get npm back
                "export NVM_DIR=\"\$HOME/.nvm\" && " +
                "if [ ! -s \"\$NVM_DIR/nvm.sh\" ]; then " +
                "echo 'Installing nvm...' && " +
                "curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/master/install.sh | bash; " +
                "fi && " +
                ". \"\$NVM_DIR/nvm.sh\" && " +
                "if ! command -v npm >/dev/null 2>&1; then " +
                "echo 'Installing Node.js...' && " +
                "NODE_VER=\$(nvm current 2>/dev/null) && " +
                "if [ \"\$NODE_VER\" != \"none\" ] && [ \"\$NODE_VER\" != \"system\" ]; then nvm uninstall \"\$NODE_VER\" 2>/dev/null; fi && " +
                "nvm install --lts && nvm alias default node; " +
                "fi && " +
                "nvm use default && " +
                "echo 'Cleaning up any partial installations...' && $unixCleanup && " +
                "npm install -g $npmPackages"
            }
        }
        userCommands.add(nodeCheckAndInstall)

        // Add npm global bin to PATH if not already present
        if (!isWindows) {
            postInstallCommands.add(
                "NPM_BIN=\$(npm bin -g 2>/dev/null) && " +
                "if [ -n \"\$NPM_BIN\" ] && [ -d \"\$NPM_BIN\" ]; then " +
                "  SHELL_NAME=\$(basename \"\$SHELL\") && " +
                "  if [ \"\$SHELL_NAME\" = \"zsh\" ]; then " +
                "    grep -q \"\$NPM_BIN\" ~/.zshrc 2>/dev/null || grep -q \"\$NPM_BIN\" ~/.zprofile 2>/dev/null || " +
                "    echo \"export PATH=\\\"\$NPM_BIN:\\\$PATH\\\"\" >> ~/.zprofile; " +
                "  elif [ \"\$SHELL_NAME\" = \"bash\" ]; then " +
                "    grep -q \"\$NPM_BIN\" ~/.bashrc 2>/dev/null || grep -q \"\$NPM_BIN\" ~/.bash_profile 2>/dev/null || " +
                "    echo \"export PATH=\\\"\$NPM_BIN:\\\$PATH\\\"\" >> ~/.bash_profile; " +
                "  elif [ \"\$SHELL_NAME\" = \"fish\" ]; then " +
                "    fish -c \"contains \$NPM_BIN \\\$fish_user_paths\" 2>/dev/null || " +
                "    fish -c \"set -U fish_user_paths \$NPM_BIN \\\$fish_user_paths\" 2>/dev/null; " +
                "  fi && " +
                "  echo '✓ npm global bin added to PATH'; " +
                "fi"
            )
        }
    }

    // Build final command list
    val allCommands = mutableListOf<String>()

    // Authenticate sudo upfront for Unix (Starship and other tools internally use sudo)
    val needsSudo = !isWindows && (sudoCommands.isNotEmpty() || userCommands.any { it.contains("starship") })
    if (needsSudo) {
        allCommands.add("echo '🔐 Authenticating administrator access...'")
        // Use sudo -S to read password from stdin (provided via env var)
        allCommands.add("echo \"\$BOSSTERM_SUDO_PWD\" | sudo -S -v 2>/dev/null")
        // Keep sudo credentials alive in background
        allCommands.add("(while true; do sudo -n true; sleep 50; kill -0 \"\$\$\" 2>/dev/null || exit; done) &")
        allCommands.add("SUDO_KEEPALIVE_PID=\$!")
    }

    // Add sudo commands
    allCommands.addAll(sudoCommands)

    // Add user commands (includes Starship install which internally uses sudo)
    allCommands.addAll(userCommands)

    // Add post-install commands
    allCommands.addAll(postInstallCommands)

    // Kill sudo keepalive at the end (after ALL commands)
    if (needsSudo) {
        allCommands.add("kill \$SUDO_KEEPALIVE_PID 2>/dev/null || true")
    }

    // Add completion message
    allCommands.add("echo ''")
    allCommands.add("echo '✓ Installation complete!'")

    // For Windows, join with && (PowerShell handles long commands better)
    // For Unix, return bash script content (caller will write to file)
    return if (isWindows) {
        allCommands.joinToString(" && ")
    } else {
        // Return script content - caller will write to file and run it
        buildString {
            appendLine("#!/bin/bash")
            appendLine("set -e")  // Exit on first error
            allCommands.forEach { cmd ->
                appendLine(cmd)
            }
        }
    }
}
