package ai.rever.bossterm.compose.ai

import ai.rever.bossterm.compose.settings.AIAssistantConfigData
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.util.UrlOpener

/**
 * Generates commands for launching and installing AI assistants.
 * Handles platform-specific installation with automatic Node.js installation when needed.
 */
class AIAssistantLauncher {

    /**
     * Get the command to launch an AI assistant with YOLO mode support.
     *
     * @param assistant The assistant definition
     * @param config The assistant's configuration (for YOLO mode settings)
     */
    fun getLaunchCommand(
        assistant: AIAssistantDefinition,
        config: AIAssistantConfigData? = null
    ): String {
        return "${buildLaunchCommand(assistant, config)}\n"
    }

    /**
     * Build the launch command with YOLO flag if enabled.
     */
    private fun buildLaunchCommand(assistant: AIAssistantDefinition, config: AIAssistantConfigData?): String {
        val baseCommand = config?.customCommand?.takeIf { it.isNotBlank() } ?: assistant.command
        val yoloFlag = config?.customYoloFlag?.takeIf { it.isNotBlank() } ?: assistant.yoloFlag
        val yoloEnabled = config?.yoloEnabled ?: true  // Default to YOLO mode enabled

        return if (yoloEnabled && yoloFlag.isNotBlank()) {
            "$baseCommand $yoloFlag"
        } else {
            baseCommand
        }
    }

    /**
     * Get the recommended installation command for an assistant.
     * Uses native script when available, otherwise npm with auto Node.js installation.
     */
    fun getInstallCommand(assistant: AIAssistantDefinition): String {
        // If native script is available (curl | bash), use it
        if (assistant.installCommand.startsWith("curl")) {
            return "${assistant.installCommand}\n"
        }
        // If no npm package defined (VCS tools like git/gh), use installCommand directly
        if (assistant.npmInstallCommand == null) {
            return "${assistant.installCommand}\n"
        }
        // Otherwise use npm with Node.js auto-installation (AI assistants)
        return "${getNpmInstallCommandWithNodeCheck(assistant)}\n"
    }

    /**
     * Get npm installation command (with Node.js auto-install if needed).
     */
    fun getNpmInstallCommand(assistant: AIAssistantDefinition): String {
        return "${getNpmInstallCommandWithNodeCheck(assistant)}\n"
    }

    /**
     * Get npm installation command with automatic Node.js installation if npm is not available.
     * - macOS: Uses Homebrew to install Node.js
     * - Linux: Uses nvm (Node Version Manager)
     * - Windows: Uses winget
     */
    private fun getNpmInstallCommandWithNodeCheck(assistant: AIAssistantDefinition): String {
        val npmPackage = assistant.npmInstallCommand?.removePrefix("npm install -g ")
            ?: assistant.installCommand.removePrefix("npm install -g ")
        val command = assistant.command

        return when {
            ShellCustomizationUtils.isWindows() -> {
                // Windows: check if npm exists, if not install Node.js via winget
                "powershell -Command \"" +
                    "if (!(Get-Command npm -ErrorAction SilentlyContinue)) { " +
                    "Write-Host 'Installing Node.js via winget...' ; " +
                    "winget install OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements ; " +
                    "\$env:Path = [System.Environment]::GetEnvironmentVariable('Path','Machine') + ';' + " +
                    "[System.Environment]::GetEnvironmentVariable('Path','User') " +
                    "} ; " +
                    "npm install -g $npmPackage ; " +
                    "Write-Host '' ; Write-Host '✓ Installation complete! Run ''$command'' to start.'\""
            }
            ShellCustomizationUtils.isMacOS() -> {
                // macOS: use Homebrew to install Node.js if npm not available
                "{ command -v npm >/dev/null 2>&1 || { echo 'Installing Node.js via Homebrew...' && brew install node; }; } && " +
                    "npm install -g $npmPackage && " +
                    "hash -r 2>/dev/null; " +
                    "echo '' && echo '✓ Installation complete! Run \"$command\" to start.'"
            }
            else -> {
                // Linux: use nvm to install Node.js if npm not available
                "{ command -v npm >/dev/null 2>&1 || { " +
                    "echo 'Installing Node.js via nvm...' && " +
                    "curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/master/install.sh | bash && " +
                    "export NVM_DIR=\"\$HOME/.nvm\" && " +
                    "[ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\" && " +
                    "nvm install --lts; " +
                    "}; } && " +
                    "npm install -g $npmPackage && " +
                    "export NVM_DIR=\"\$HOME/.nvm\" && [ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\" && " +
                    "hash -r 2>/dev/null; " +
                    "echo '' && echo '✓ Installation complete! Run \"$command\" to start.'"
            }
        }
    }

    /**
     * Result of resolving install commands for an assistant.
     *
     * @property command The primary install command to run
     * @property npmFallback Optional npm fallback command (null if useNpm was true or no npm available)
     */
    data class ResolvedInstallCommands(
        val command: String,
        val npmFallback: String?
    )

    /**
     * Resolve the install commands for an AI assistant.
     * This handles the logic of choosing between script and npm installation methods.
     *
     * @param assistant The assistant to install
     * @param useNpm If true, use npm as primary method; if false, use script with npm as fallback
     * @return Resolved commands ready to use for installation
     */
    fun resolveInstallCommands(assistant: AIAssistantDefinition, useNpm: Boolean = false): ResolvedInstallCommands {
        val scriptCommand = getInstallCommand(assistant).trim()
        val npmCommand = if (assistant.npmInstallCommand != null) {
            getNpmInstallCommand(assistant).trim()
        } else null

        val command = if (useNpm && npmCommand != null) npmCommand else scriptCommand
        val fallbackNpm = if (useNpm) null else npmCommand

        return ResolvedInstallCommands(command, fallbackNpm)
    }

    /**
     * Open the assistant's website in the default browser.
     */
    fun openWebsite(assistant: AIAssistantDefinition): Boolean {
        return UrlOpener.open(assistant.websiteUrl)
    }

    companion object {
        /**
         * Get platform-aware install command for Git.
         * Supports macOS (brew), Windows (winget), and Linux (apt/dnf/pacman).
         */
        fun getGitInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install git"
                ShellCustomizationUtils.isWindows() -> "winget install Git.Git --accept-source-agreements --accept-package-agreements"
                else -> getLinuxInstallCommand("git", "git", "git")
            }
        }

        /**
         * Get platform-aware install command for GitHub CLI.
         * Supports macOS (brew), Windows (winget), and Linux (apt/dnf/pacman).
         */
        fun getGhInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install gh"
                ShellCustomizationUtils.isWindows() -> "winget install GitHub.cli --accept-source-agreements --accept-package-agreements"
                else -> getLinuxInstallCommand("gh", "gh", "github-cli")
            }
        }

        /**
         * Get install command for Starship prompt.
         * Uses universal curl script that works on macOS, Linux, and WSL.
         * Also adds init line to shell config based on $SHELL and restarts shell.
         */
        fun getStarshipInstallCommand(): String {
            // Install starship, configure for current shell, restart shell to activate
            return """curl -sS https://starship.rs/install.sh | sh && """ +
                """SHELL_NAME=$(basename "${"$"}SHELL") && """ +
                """if [ "${"$"}SHELL_NAME" = "zsh" ]; then """ +
                """  grep -q 'starship init zsh' ~/.zshrc 2>/dev/null || echo 'eval "$(starship init zsh)"' >> ~/.zshrc; """ +
                """  echo '✓ Starship configured for Zsh. Restarting shell...'; """ +
                """elif [ "${"$"}SHELL_NAME" = "bash" ]; then """ +
                """  grep -q 'starship init bash' ~/.bashrc 2>/dev/null || echo 'eval "$(starship init bash)"' >> ~/.bashrc; """ +
                """  echo '✓ Starship configured for Bash. Restarting shell...'; """ +
                """elif [ "${"$"}SHELL_NAME" = "fish" ]; then """ +
                """  mkdir -p ~/.config/fish && grep -q 'starship init fish' ~/.config/fish/config.fish 2>/dev/null || echo 'starship init fish | source' >> ~/.config/fish/config.fish; """ +
                """  echo '✓ Starship configured for Fish. Restarting shell...'; """ +
                """fi && exec ${"$"}SHELL -l"""
        }

        /**
         * Get install command for Oh My Zsh.
         * Uses official install script from ohmyz.sh.
         */
        fun getOhMyZshInstallCommand(): String {
            return "sh -c \"\$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\""
        }

        /**
         * Get platform-aware install command for Zsh.
         */
        fun getZshInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install zsh"
                ShellCustomizationUtils.isWindows() -> "echo 'Zsh is not natively supported on Windows. Consider using WSL.'"
                else -> getLinuxInstallCommand("zsh", "zsh", "zsh")
            }
        }

        /**
         * Get platform-aware install command for Bash.
         */
        fun getBashInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install bash"
                ShellCustomizationUtils.isWindows() -> "echo 'Bash is available through Git Bash or WSL on Windows.'"
                else -> getLinuxInstallCommand("bash", "bash", "bash")
            }
        }

        /**
         * Get platform-aware install command for Fish.
         */
        fun getFishInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install fish"
                ShellCustomizationUtils.isWindows() -> "echo 'Fish is available through WSL on Windows. Visit https://fishshell.com for more info.'"
                else -> getLinuxInstallCommand("fish", "fish", "fish")
            }
        }

        /**
         * Get Linux install command with package manager detection.
         * Tries apt, dnf, then pacman in order.
         * Uses BOSSTERM_SUDO_PWD environment variable for password if available.
         */
        private fun getLinuxInstallCommand(aptPkg: String, dnfPkg: String, pacmanPkg: String): String {
            // Validate sudo credentials using password from env var (same pattern as OnboardingWizard)
            return "echo \"\$BOSSTERM_SUDO_PWD\" | sudo -S -v 2>/dev/null && " +
                   "{ command -v apt >/dev/null 2>&1 && sudo apt install -y $aptPkg; } || " +
                   "{ command -v dnf >/dev/null 2>&1 && sudo dnf install -y $dnfPkg; } || " +
                   "{ command -v pacman >/dev/null 2>&1 && sudo pacman -S --noconfirm $pacmanPkg; } || " +
                   "{ echo 'No supported package manager found (apt/dnf/pacman)'; exit 1; }"
        }
    }
}
