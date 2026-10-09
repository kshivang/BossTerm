package ai.rever.bossterm.compose.shell

import ai.rever.bossterm.compose.settings.SettingsManager
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Injects BossTerm shell integration into shell processes.
 *
 * This uses the same approach as iTerm2:
 * - For Zsh: Hijack ZDOTDIR to point to our integration directory
 * - For Bash: Use --rcfile with our loader, replaying login startup files when requested
 * - For Fish: Prepend our vendor_conf.d to XDG_DATA_DIRS
 *
 * The integration scripts send OSC 133 sequences to track command execution,
 * enabling features like command completion notifications.
 */
object ShellIntegrationInjector {

    private val LOG = LoggerFactory.getLogger(ShellIntegrationInjector::class.java)

    // Resource paths for shell integration scripts
    private val SHELL_INTEGRATION_RESOURCES = listOf(
        ".zshenv",
        "bossterm_shell_integration.zsh",
        "bash-loader",
        "bossterm_shell_integration.bash",
        "fish/vendor_conf.d/bossterm_shell_integration.fish",
        "bin/boss-open"
    )

    // Lazy-initialized integration directory
    private val integrationDir: File by lazy {
        File(System.getProperty("user.home"), ".bossterm/shell-integration").also { dir ->
            extractAllResources(dir)
            installOpenShims(dir)
        }
    }

    /**
     * Inject shell integration environment variables for the given shell.
     *
     * @param shell The shell command/path (e.g., "/bin/zsh", "bash", etc.)
     * @param env The environment map to modify
     * @param enabled Whether shell integration is enabled (from settings)
     */
    fun injectForShell(shell: String, env: MutableMap<String, String>, enabled: Boolean = true) {
        if (!enabled) {
            LOG.debug("Shell integration disabled, skipping injection")
            return
        }

        val shellName = File(shell).name

        // If the command is 'login' (macOS uses /usr/bin/login as wrapper),
        // detect the actual shell from SHELL environment variable
        val effectiveShellName = if (shellName == "login") {
            val userShell = env["SHELL"] ?: System.getenv("SHELL") ?: ""
            File(userShell).name
        } else {
            shellName
        }

        LOG.debug("Injecting shell integration for: $effectiveShellName")

        // Optional shell customizations (Phase 7), honored by the integration
        // scripts. Reading settings here avoids threading flags through every
        // PTY-spawn call site.
        val customization = SettingsManager.instance.settings.value
        if (customization.shellViMode) env["BOSSTERM_VI_MODE"] = "1"
        if (customization.shellAutosuggestions) env["BOSSTERM_AUTOSUGGEST"] = "1"

        when {
            effectiveShellName == "zsh" || effectiveShellName.endsWith("zsh") -> injectZsh(env)
            effectiveShellName == "bash" || effectiveShellName.endsWith("bash") -> injectBash(env)
            effectiveShellName == "fish" || effectiveShellName.endsWith("fish") -> injectFish(env)
            else -> LOG.debug("Unknown shell '$effectiveShellName', shell integration not injected")
        }
    }

    /**
     * Inject for Zsh using ZDOTDIR hijacking.
     *
     * How it works:
     * 1. Save original ZDOTDIR (if any) to BOSSTERM_ORIG_ZDOTDIR
     * 2. Set ZDOTDIR to our integration directory
     * 3. Zsh reads .zshenv from our directory first
     * 4. Our .zshenv restores original ZDOTDIR and sources user's config
     * 5. Then loads shell integration for interactive shells
     */
    private fun injectZsh(env: MutableMap<String, String>) {
        // Save original ZDOTDIR if it exists
        env["ZDOTDIR"]?.let { original ->
            env["BOSSTERM_ORIG_ZDOTDIR"] = original
        }

        // Point ZDOTDIR to our integration directory
        env["ZDOTDIR"] = integrationDir.absolutePath
        env["BOSSTERM_INJECT_INTEGRATION"] = "1"

        LOG.debug("Injected Zsh integration via ZDOTDIR=${integrationDir.absolutePath}")
    }

    /**
     * Mark Bash for integration; argumentsForShell supplies its --rcfile startup hook.
     */
    private fun injectBash(env: MutableMap<String, String>) {
        env["BOSSTERM_INJECT_INTEGRATION"] = "1"
    }

    /**
     * Ordinary Bash ignores ENV, and login Bash ignores --rcfile. For normal interactive
     * startup, use --rcfile and let the loader replay login startup files once. This retains
     * interactive/login-profile behavior, but Bash's readonly login_shell option is false.
     * Explicit command/script or startup-control arguments stay authoritative and untouched.
     */
    fun argumentsForShell(
        shell: String,
        arguments: List<String>,
        env: MutableMap<String, String>,
        enabled: Boolean = true,
    ): List<String> {
        if (!enabled || File(shell).name != "bash") return arguments
        val supported = setOf("-l", "--login", "-i", "-il", "-li")
        if (arguments.any { it !in supported }) return arguments
        val login = arguments.any { it == "--login" || 'l' in it }
        if (login) env["BOSSTERM_BASH_LOGIN"] = "1" else env.remove("BOSSTERM_BASH_LOGIN")
        val interactive = arguments.any { it != "--login" && 'i' in it }
        return listOf("--rcfile", File(integrationDir, "bash-loader").absolutePath) +
            if (interactive) listOf("-i") else emptyList()
    }

    /**
     * Inject for Fish using XDG_DATA_DIRS.
     *
     * How it works:
     * 1. Prepend our fish directory to XDG_DATA_DIRS
     * 2. Fish discovers vendor_conf.d/bossterm_shell_integration.fish
     * 3. Fish autoloads it during initialization
     */
    private fun injectFish(env: MutableMap<String, String>) {
        val fishDir = File(integrationDir, "fish").absolutePath
        val existingDirs = env["XDG_DATA_DIRS"] ?: "/usr/local/share:/usr/share"

        // Prepend our fish directory
        env["XDG_DATA_DIRS"] = "$fishDir:$existingDirs"
        env["BOSSTERM_INJECT_INTEGRATION"] = "1"

        LOG.debug("Injected Fish integration via XDG_DATA_DIRS")
    }

    /**
     * Extract all shell integration resources to the integration directory.
     */
    private fun extractAllResources(targetDir: File) {
        // Create target directory
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for (resource in SHELL_INTEGRATION_RESOURCES) {
            val targetFile = File(targetDir, resource)

            // Create parent directories if needed
            targetFile.parentFile?.mkdirs()

            // Extract resource (always overwrite to ensure latest version)
            try {
                val resourcePath = "shell-integration/$resource"
                // Use anonymous object pattern for more reliable classloader access (same as FontUtils)
                val inputStream = object {}.javaClass.classLoader?.getResourceAsStream(resourcePath)
                    ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
                    ?: ShellIntegrationInjector::class.java.classLoader?.getResourceAsStream(resourcePath)

                if (inputStream != null) {
                    inputStream.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Make scripts executable
                    if (resource.endsWith(".bash") || resource.endsWith(".zsh") ||
                        resource.endsWith(".fish") || resource == "bash-loader" ||
                        resource == ".zshenv" || resource.startsWith("bin/")) {
                        targetFile.setExecutable(true)
                    }
                } else {
                    System.err.println("[ShellIntegration] Resource not found: $resourcePath")
                }
            } catch (e: Exception) {
                System.err.println("[ShellIntegration] Error extracting $resource: ${e.message}")
            }
        }
    }

    /**
     * Install the open-request shim under the names CLI tools actually spawn.
     * The integration scripts prepend bin/ to PATH, so plain `open <url>` /
     * `xdg-open <url>` calls from CLI commands are forwarded to the terminal
     * as OSC 1341;OpenTarget instead of going straight to the OS default.
     */
    private fun installOpenShims(dir: File) {
        val shim = File(dir, "bin/boss-open")
        if (!shim.isFile) return
        val names = buildList {
            add("xdg-open")
            if (ai.rever.bossterm.compose.shell.ShellCustomizationUtils.isMacOS()) add("open")
        }
        for (name in names) {
            try {
                val target = File(dir, "bin/$name")
                shim.copyTo(target, overwrite = true)
                target.setExecutable(true)
            } catch (e: Exception) {
                LOG.warn("Error installing open shim '$name'", e)
            }
        }
    }
}
