package ai.rever.bossterm.compose.shell

import java.io.File

/**
 * Shared utility functions for shell customization operations.
 * Used by both context menu (ShellCustomizationMenuProvider) and onboarding wizard.
 */
object ShellCustomizationUtils {

    // ===== Shell Resolution =====

    /**
     * Get a valid shell command, with fallback if $SHELL is not available.
     *
     * Checks in order:
     * 1. $SHELL environment variable (if the file exists and is executable)
     * 2. /bin/bash (common default)
     * 3. /bin/sh (POSIX fallback, always available)
     *
     * @return Path to a valid shell executable
     */
    fun getValidShell(): String {
        // Try $SHELL first
        val envShell = System.getenv("SHELL")
        if (!envShell.isNullOrBlank()) {
            val shellFile = File(envShell)
            if (shellFile.exists() && shellFile.canExecute()) {
                return envShell
            }
        }

        // Fallback to /bin/bash
        val bash = File("/bin/bash")
        if (bash.exists() && bash.canExecute()) {
            return "/bin/bash"
        }

        // Ultimate fallback to /bin/sh (POSIX, always available)
        return "/bin/sh"
    }

    // ===== Detection Functions =====

    /**
     * Check if Starship is installed.
     */
    fun isStarshipInstalled(): Boolean = isCommandInstalled("starship")

    /**
     * Check if Oh My Zsh is installed (by checking ~/.oh-my-zsh directory).
     */
    fun isOhMyZshInstalled(): Boolean {
        val home = System.getProperty("user.home") ?: return false
        return File(home, ".oh-my-zsh").isDirectory
    }

    /**
     * Check if Prezto is installed (by checking ~/.zprezto directory).
     */
    fun isPreztoInstalled(): Boolean {
        val home = System.getProperty("user.home") ?: return false
        return File(home, ".zprezto").isDirectory
    }

    /**
     * Check if a command is available in PATH.
     */
    fun isCommandInstalled(command: String): Boolean {
        var process: Process? = null
        return try {
            process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        } finally {
            process?.inputStream?.close()
            process?.errorStream?.close()
            process?.outputStream?.close()
        }
    }

    // ===== Uninstall Commands =====

    /**
     * Get command to uninstall Starship.
     * Removes binary and config lines from all shell rc files.
     */
    fun getStarshipUninstallCommand(): String {
        return "{ " +
            "command -v starship >/dev/null 2>&1 && { " +
            "  rm -f \"\$(command -v starship)\" 2>/dev/null || sudo rm -f \"\$(command -v starship)\" 2>/dev/null; " +
            "  rm -rf ~/.config/starship.toml ~/.cache/starship 2>/dev/null; " +
            "}; " +
            "[ -f ~/.zshrc ] && { sed -i.bak '/starship init/d' ~/.zshrc 2>/dev/null || sed -i '' '/starship init/d' ~/.zshrc 2>/dev/null; }; " +
            "[ -f ~/.bashrc ] && { sed -i.bak '/starship init/d' ~/.bashrc 2>/dev/null || sed -i '' '/starship init/d' ~/.bashrc 2>/dev/null; }; " +
            "[ -f ~/.config/fish/config.fish ] && { sed -i.bak '/starship init/d' ~/.config/fish/config.fish 2>/dev/null || sed -i '' '/starship init/d' ~/.config/fish/config.fish 2>/dev/null; }; " +
            "echo '✓ Starship removed'; } || true"
    }

    /**
     * Get command to uninstall Oh My Zsh.
     * Uses the official uninstaller if available, otherwise manual cleanup.
     */
    fun getOhMyZshUninstallCommand(): String {
        return "[ -d ~/.oh-my-zsh ] && { " +
            "rm -rf ~/.oh-my-zsh && " +
            "if [ -f ~/.zshrc.pre-oh-my-zsh ]; then " +
            "  mv ~/.zshrc.pre-oh-my-zsh ~/.zshrc; " +
            "else " +
            "  sed -i.bak '/oh-my-zsh/d' ~/.zshrc 2>/dev/null || sed -i '' '/oh-my-zsh/d' ~/.zshrc 2>/dev/null; " +
            "fi && " +
            "[ -f ~/.zshrc ] && { " +
            "  sed -i.bak '/^source.*oh-my-zsh/d' ~/.zshrc 2>/dev/null || sed -i '' '/^source.*oh-my-zsh/d' ~/.zshrc 2>/dev/null; " +
            "}; " +
            "echo '✓ Oh My Zsh removed'; } || true"
    }

    /**
     * Get command to uninstall Prezto.
     * Removes the .zprezto directory and related config files.
     */
    fun getPreztoUninstallCommand(): String {
        return "[ -d ~/.zprezto ] && { rm -rf ~/.zprezto ~/.zpreztorc && echo '✓ Prezto removed'; } || true"
    }

    // ===== Install Commands =====

    /**
     * Get command to install and configure Starship for the given shell.
     */
    fun getStarshipInstallCommand(shell: String = "zsh"): String {
        val configCommand = when (shell.lowercase()) {
            "zsh" -> "grep -q 'starship init zsh' ~/.zshrc 2>/dev/null || echo 'eval \"\$(starship init zsh)\"' >> ~/.zshrc"
            "bash" -> "grep -q 'starship init bash' ~/.bashrc 2>/dev/null || echo 'eval \"\$(starship init bash)\"' >> ~/.bashrc"
            "fish" -> "mkdir -p ~/.config/fish && grep -q 'starship init fish' ~/.config/fish/config.fish 2>/dev/null || echo 'starship init fish | source' >> ~/.config/fish/config.fish"
            else -> "grep -q 'starship init zsh' ~/.zshrc 2>/dev/null || echo 'eval \"\$(starship init zsh)\"' >> ~/.zshrc"
        }
        return "curl -sS https://starship.rs/install.sh | sh -s -- -y && $configCommand && echo '✓ Starship configured'"
    }

    /**
     * Get multi-shell Starship configuration command.
     * Configures Starship for the selected shell type.
     */
    fun getStarshipShellConfigCommand(selectedShell: String): String {
        return "if [ \"\$SHELL\" = \"*/zsh\" ] || [ -n \"\$ZSH_VERSION\" ]; then " +
            "  grep -q 'starship init zsh' ~/.zshrc 2>/dev/null || echo 'eval \"\$(starship init zsh)\"' >> ~/.zshrc; " +
            "elif [ \"\$SHELL\" = \"*/bash\" ] || [ -n \"\$BASH_VERSION\" ]; then " +
            "  grep -q 'starship init bash' ~/.bashrc 2>/dev/null || echo 'eval \"\$(starship init bash)\"' >> ~/.bashrc; " +
            "elif [ \"\$SHELL\" = \"*/fish\" ]; then " +
            "  mkdir -p ~/.config/fish && grep -q 'starship init fish' ~/.config/fish/config.fish 2>/dev/null || echo 'starship init fish | source' >> ~/.config/fish/config.fish; " +
            "fi && echo '✓ Starship configured'"
    }

    /**
     * Get command to install Oh My Zsh.
     */
    fun getOhMyZshInstallCommand(): String {
        return "sh -c \"\$(curl -fsSL https://raw.github.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\" \"\" --unattended && echo '✓ Oh My Zsh installed'"
    }

    /**
     * Get command to install Prezto.
     */
    fun getPreztoInstallCommand(): String {
        return "git clone --recursive https://github.com/sorin-ionescu/prezto.git \"\${ZDOTDIR:-\$HOME}/.zprezto\" && " +
            "setopt EXTENDED_GLOB 2>/dev/null || true && " +
            "for rcfile in \"\${ZDOTDIR:-\$HOME}\"/.zprezto/runcoms/^README.md(.N); do " +
            "  ln -sf \"\$rcfile\" \"\${ZDOTDIR:-\$HOME}/.\${rcfile:t}\"; " +
            "done && echo '✓ Prezto installed'"
    }

    // ===== Combined Operations =====

    /**
     * Build install command that first uninstalls conflicting tools.
     *
     * @param installCmd The install command to run
     * @param uninstallStarship Whether to uninstall Starship first
     * @param uninstallOhMyZsh Whether to uninstall Oh My Zsh first
     * @param uninstallPrezto Whether to uninstall Prezto first
     * @param checkStarshipInstalled Current Starship installation status (null to check)
     * @param checkOhMyZshInstalled Current Oh My Zsh installation status (null to check)
     * @param checkPreztoInstalled Current Prezto installation status (null to check)
     */
    fun buildInstallWithUninstall(
        installCmd: String,
        uninstallStarship: Boolean = false,
        uninstallOhMyZsh: Boolean = false,
        uninstallPrezto: Boolean = false,
        checkStarshipInstalled: Boolean? = null,
        checkOhMyZshInstalled: Boolean? = null,
        checkPreztoInstalled: Boolean? = null
    ): String {
        val parts = mutableListOf<String>()

        val starshipInstalled = checkStarshipInstalled ?: isStarshipInstalled()
        val ohMyZshInstalled = checkOhMyZshInstalled ?: isOhMyZshInstalled()
        val preztoInstalled = checkPreztoInstalled ?: isPreztoInstalled()

        if (uninstallStarship && starshipInstalled) {
            parts.add(getStarshipUninstallCommand())
        }
        if (uninstallOhMyZsh && ohMyZshInstalled) {
            parts.add(getOhMyZshUninstallCommand())
        }
        if (uninstallPrezto && preztoInstalled) {
            parts.add(getPreztoUninstallCommand())
        }
        parts.add(installCmd)

        return parts.joinToString(" && ")
    }
}
