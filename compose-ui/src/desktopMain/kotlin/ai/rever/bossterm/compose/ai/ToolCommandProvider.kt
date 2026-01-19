package ai.rever.bossterm.compose.ai

import ai.rever.bossterm.compose.settings.AIAssistantConfigData
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.util.UrlOpener

/**
 * Provides commands for launching and installing tools.
 * Handles AI assistants, VCS tools, shells, and other development utilities.
 * Supports platform-specific installation with automatic dependency installation when needed.
 */
class ToolCommandProvider {

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
                    "npm install -g $npmPackage && " +
                    "hash -r 2>/dev/null && " +
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
         * Get platform-aware install command for Homebrew.
         * Uses password from BOSSTERM_SUDO_PWD env var for sudo authentication.
         * Same script as OnboardingSteps for consistency.
         */
        fun getBrewInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() || ShellCustomizationUtils.isLinux() -> """
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

    # Source brew for current session so it works immediately
    eval "${'$'}(${'$'}BREW_PATH shellenv)"

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
                else -> "echo 'Homebrew is not supported on Windows. Use winget or Chocolatey instead.'"
            }
        }

        /**
         * Get platform-aware install command for Docker.
         */
        fun getDockerInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install --cask docker"
                ShellCustomizationUtils.isWindows() -> "winget install Docker.DockerDesktop --accept-source-agreements --accept-package-agreements"
                else -> getLinuxInstallCommand("docker.io", "docker", "docker")
            }
        }

        /**
         * Get platform-aware install command for kubectl.
         */
        fun getKubectlInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install kubectl"
                ShellCustomizationUtils.isWindows() -> "winget install Kubernetes.kubectl --accept-source-agreements --accept-package-agreements"
                else -> """
#!/bin/bash
echo "Installing kubectl..."
curl -LO "https://dl.k8s.io/release/${"$"}(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl
mkdir -p ~/.local/bin
mv kubectl ~/.local/bin/
echo "✓ kubectl installed to ~/.local/bin/kubectl"
                """.trimIndent()
            }
        }

        /**
         * Get platform-aware install command for Rust and Cargo.
         */
        fun getRustInstallCommand(): String {
            return """
#!/bin/bash
echo "Installing Rust and Cargo..."
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "${"$"}HOME/.cargo/env"
echo ""
echo "✓ Rust and Cargo installed successfully!"
echo "Run 'source ${"$"}HOME/.cargo/env' to update your current shell"
            """.trimIndent()
        }

        /**
         * Get platform-aware install command for tmux.
         */
        fun getTmuxInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install tmux"
                ShellCustomizationUtils.isWindows() -> "echo 'tmux is not supported on Windows. Consider using WSL or Windows Terminal.'"
                else -> getLinuxInstallCommand("tmux", "tmux", "tmux")
            }
        }

        /**
         * Get platform-aware install command for fzf.
         */
        fun getFzfInstallCommand(): String {
            return when {
                ShellCustomizationUtils.isMacOS() -> "brew install fzf"
                ShellCustomizationUtils.isWindows() -> "winget install junegunn.fzf --accept-source-agreements --accept-package-agreements"
                else -> getLinuxInstallCommand("fzf", "fzf", "fzf")
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
