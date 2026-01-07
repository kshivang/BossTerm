package ai.rever.bossterm.compose.ai

import ai.rever.bossterm.compose.settings.AIAssistantConfigData
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

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
     * @param workingDirectory Optional working directory to cd into first
     */
    fun getLaunchCommand(
        assistant: AIAssistantDefinition,
        config: AIAssistantConfigData? = null,
        workingDirectory: String? = null
    ): String {
        val command = buildLaunchCommand(assistant, config)
        return if (workingDirectory != null) {
            "cd ${escapeShellArg(workingDirectory)} && $command\n"
        } else {
            "$command\n"
        }
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
        // Otherwise use npm with Node.js auto-installation
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
            isWindows() -> {
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
            isMacOS() -> {
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
        return openUrl(assistant.websiteUrl)
    }

    /**
     * Open a URL in the default browser.
     */
    private fun openUrl(url: String): Boolean {
        val os = System.getProperty("os.name").lowercase()

        return try {
            when {
                os.contains("linux") -> openUrlOnLinux(url)
                os.contains("mac") -> {
                    ProcessBuilder("open", url).start()
                    true
                }
                os.contains("win") -> {
                    ProcessBuilder("cmd", "/c", "start", "", url).start()
                    true
                }
                else -> {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI(url))
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to open URL: $url - ${e.message}")
            false
        }
    }

    /**
     * Open URL on Linux using multiple fallback strategies.
     */
    private fun openUrlOnLinux(url: String): Boolean {
        val browserCommands = listOf(
            listOf("sensible-browser", url),
            listOf("x-www-browser", url),
            listOf("gnome-open", url),
            listOf("kde-open", url),
            listOf("firefox", url),
            listOf("google-chrome", url),
            listOf("chromium", url),
            listOf("chromium-browser", url),
            listOf("xdg-open", url)
        )

        for (command in browserCommands) {
            try {
                val whichProcess = ProcessBuilder("which", command[0])
                    .redirectErrorStream(true)
                    .start()
                val completed = whichProcess.waitFor(2, TimeUnit.SECONDS)
                val exists = completed && whichProcess.exitValue() == 0

                if (exists) {
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                    return true
                }
            } catch (e: Exception) {
                continue
            }
        }

        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun escapeShellArg(arg: String): String {
        return "'${arg.replace("'", "'\\''")}'"
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")

    private fun isMacOS(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")
}
