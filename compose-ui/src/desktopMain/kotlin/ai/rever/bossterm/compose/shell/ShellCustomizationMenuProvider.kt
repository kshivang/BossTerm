package ai.rever.bossterm.compose.shell

import ai.rever.bossterm.compose.ContextMenuElement
import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSection
import ai.rever.bossterm.compose.ContextMenuSubmenu
import ai.rever.bossterm.compose.ai.AIAssistantLauncher
import ai.rever.bossterm.compose.util.UrlOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Provides context menu items for shell customization tools (Starship, etc.).
 *
 * Detects if starship is installed and shows appropriate menu items:
 * - If installed: Shows submenu with configuration and preset options
 * - If not installed: Shows install option with link to documentation
 */
class ShellCustomizationMenuProvider {

    /**
     * Cached installation status to avoid repeated checks.
     */
    private var starshipInstalled: Boolean? = null
    private var ohmyzshInstalled: Boolean? = null
    private var preztoInstalled: Boolean? = null
    private var zshInstalled: Boolean? = null
    private var bashInstalled: Boolean? = null
    private var fishInstalled: Boolean? = null

    /**
     * Get the user's default shell from $SHELL environment variable.
     */
    private fun getDefaultShell(): String {
        return System.getenv("SHELL")?.substringAfterLast("/") ?: "bash"
    }

    /**
     * Check if Starship is configured for Zsh (has init line in .zshrc).
     */
    private fun isStarshipConfiguredForZsh(): Boolean {
        val home = System.getProperty("user.home") ?: return false
        val zshrc = File(home, ".zshrc")
        return try {
            zshrc.exists() && zshrc.readText().contains("starship init zsh")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Refresh installation status for shell customization tools.
     */
    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        starshipInstalled = ShellCustomizationUtils.isStarshipInstalled()
        ohmyzshInstalled = ShellCustomizationUtils.isOhMyZshInstalled()
        preztoInstalled = ShellCustomizationUtils.isPreztoInstalled()
        zshInstalled = ShellCustomizationUtils.isCommandInstalled("zsh")
        bashInstalled = ShellCustomizationUtils.isCommandInstalled("bash")
        fishInstalled = ShellCustomizationUtils.isCommandInstalled("fish")
    }

    /**
     * Get cached installation status for Starship.
     */
    fun getStatus(): Boolean? = starshipInstalled

    /**
     * Get cached installation status for Oh My Zsh.
     */
    fun getOhMyZshStatus(): Boolean? = ohmyzshInstalled

    /**
     * Get cached installation status for Prezto.
     */
    fun getPreztoStatus(): Boolean? = preztoInstalled

    /**
     * Get cached installation status for Zsh.
     */
    fun getZshStatus(): Boolean? = zshInstalled

    /**
     * Get cached installation status for Bash.
     */
    fun getBashStatus(): Boolean? = bashInstalled

    /**
     * Get cached installation status for Fish.
     */
    fun getFishStatus(): Boolean? = fishInstalled

    // ===== Uninstall Commands (delegated to ShellCustomizationUtils) =====

    /**
     * Build install command that first uninstalls conflicting tools.
     */
    private fun buildInstallWithUninstall(
        installCmd: String,
        uninstallStarship: Boolean = false,
        uninstallOhMyZsh: Boolean = false,
        uninstallPrezto: Boolean = false
    ): String {
        return ShellCustomizationUtils.buildInstallWithUninstall(
            installCmd = installCmd,
            uninstallStarship = uninstallStarship,
            uninstallOhMyZsh = uninstallOhMyZsh,
            uninstallPrezto = uninstallPrezto,
            checkStarshipInstalled = starshipInstalled,
            checkOhMyZshInstalled = ohmyzshInstalled,
            checkPreztoInstalled = preztoInstalled
        )
    }

    /**
     * Get context menu items for shell customization.
     * Only shows installed shell customization tools and the user's default shell.
     *
     * @param terminalWriter Function to write commands to terminal
     * @param onInstallRequest Callback for install requests (toolId, command, npmCommand)
     * @param statusOverride Override for installation status (for testing)
     * @return List of context menu elements
     */
    fun getMenuItems(
        terminalWriter: (String) -> Unit,
        onInstallRequest: ((String, String, String?) -> Unit)? = null,
        statusOverride: Map<String, Boolean>? = null
    ): List<ContextMenuElement> {
        val isStarshipInstalled = statusOverride?.get("starship")
            ?: (starshipInstalled ?: ShellCustomizationUtils.isStarshipInstalled())
        val isOhMyZshInstalled = statusOverride?.get("oh-my-zsh")
            ?: (ohmyzshInstalled ?: ShellCustomizationUtils.isOhMyZshInstalled())
        val isPreztoInstalled = statusOverride?.get("prezto")
            ?: (preztoInstalled ?: ShellCustomizationUtils.isPreztoInstalled())

        val shellItems = mutableListOf<ContextMenuElement>()

        // Only show installed shell customization tools
        if (isStarshipInstalled) {
            shellItems.add(buildStarshipMenu(terminalWriter, onInstallRequest))
        }

        if (isOhMyZshInstalled) {
            shellItems.add(buildOhMyZshMenu(terminalWriter, onInstallRequest))
        }

        if (isPreztoInstalled) {
            shellItems.add(buildPreztoMenu(terminalWriter, onInstallRequest))
        }

        // Add default shell menu (just the current user's default shell)
        val defaultShell = getDefaultShell()
        shellItems.add(ContextMenuSection(id = "default_shell_section", label = "Default Shell"))
        when (defaultShell) {
            "zsh" -> shellItems.add(buildZshMenu(terminalWriter))
            "bash" -> shellItems.add(buildBashMenu(terminalWriter))
            "fish" -> shellItems.add(buildFishMenu(terminalWriter))
            else -> shellItems.add(buildBashMenu(terminalWriter)) // Fallback to bash
        }

        return if (shellItems.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ContextMenuSubmenu(
                    id = "shell_submenu",
                    label = "Shell",
                    items = shellItems
                )
            )
        }
    }

    /**
     * Build Starship submenu with configuration options.
     */
    private fun buildStarshipMenu(
        terminalWriter: (String) -> Unit,
        onInstallRequest: ((String, String, String?) -> Unit)?
    ): ContextMenuSubmenu {
        return ContextMenuSubmenu(
            id = "starship_submenu",
            label = "Starship",
            items = listOf(
                // Configuration section
                ContextMenuSection(id = "starship_config_section", label = "Configuration"),
                ContextMenuItem(
                    id = "starship_config_edit",
                    label = "Edit Config",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.config/starship.toml\n") }
                ),
                ContextMenuItem(
                    id = "starship_config_init",
                    label = "Create Default Config",
                    action = { terminalWriter("mkdir -p ~/.config && starship preset -o ~/.config/starship.toml\n") }
                ),

                // Presets section
                ContextMenuSection(id = "starship_presets_section", label = "Apply Preset"),
                ContextMenuSubmenu(
                    id = "starship_presets_submenu",
                    label = "Presets",
                    items = listOf(
                        ContextMenuItem(
                            id = "starship_preset_nerd",
                            label = "Nerd Font Symbols",
                            action = { terminalWriter("starship preset nerd-font-symbols -o ~/.config/starship.toml\n") }
                        ),
                        ContextMenuItem(
                            id = "starship_preset_plain",
                            label = "Plain Text",
                            action = { terminalWriter("starship preset plain-text-symbols -o ~/.config/starship.toml\n") }
                        ),
                        ContextMenuItem(
                            id = "starship_preset_nonerd",
                            label = "No Nerd Font",
                            action = { terminalWriter("starship preset no-nerd-font -o ~/.config/starship.toml\n") }
                        ),
                        ContextMenuItem(
                            id = "starship_preset_pastel",
                            label = "Pastel Powerline",
                            action = { terminalWriter("starship preset pastel-powerline -o ~/.config/starship.toml\n") }
                        ),
                        ContextMenuItem(
                            id = "starship_preset_bracketed",
                            label = "Bracketed Segments",
                            action = { terminalWriter("starship preset bracketed-segments -o ~/.config/starship.toml\n") }
                        ),
                        ContextMenuItem(
                            id = "starship_preset_gruvbox",
                            label = "Gruvbox Rainbow",
                            action = { terminalWriter("starship preset gruvbox-rainbow -o ~/.config/starship.toml\n") }
                        ),
                        ContextMenuItem(
                            id = "starship_preset_tokyo",
                            label = "Tokyo Night",
                            action = { terminalWriter("starship preset tokyo-night -o ~/.config/starship.toml\n") }
                        )
                    )
                ),

                // Shell Setup section
                ContextMenuSection(id = "starship_setup_section", label = "Shell Setup"),
                ContextMenuItem(
                    id = "starship_setup_bash",
                    label = "Setup for Bash",
                    action = {
                        terminalWriter("echo 'eval \"\$(starship init bash)\"' >> ~/.bashrc && echo '✓ Added to ~/.bashrc' && source ~/.bashrc\n")
                    }
                ),
                ContextMenuItem(
                    id = "starship_setup_zsh",
                    label = "Setup for Zsh",
                    action = {
                        terminalWriter("echo 'eval \"\$(starship init zsh)\"' >> ~/.zshrc && echo '✓ Added to ~/.zshrc' && source ~/.zshrc\n")
                    }
                ),
                ContextMenuItem(
                    id = "starship_setup_fish",
                    label = "Setup for Fish",
                    action = {
                        terminalWriter("echo 'starship init fish | source' >> ~/.config/fish/config.fish && echo '✓ Added to config.fish' && source ~/.config/fish/config.fish\n")
                    }
                ),

                // Help section
                ContextMenuSection(id = "starship_help_section"),
                ContextMenuItem(
                    id = "starship_help",
                    label = "Help",
                    action = { terminalWriter("starship --help\n") }
                ),
                ContextMenuItem(
                    id = "starship_docs",
                    label = "Documentation",
                    action = { UrlOpener.open("https://starship.rs/config/") }
                ),

                // Uninstall section
                ContextMenuSection(id = "starship_uninstall_section"),
                ContextMenuItem(
                    id = "starship_uninstall",
                    label = "Uninstall",
                    action = {
                        val uninstallCmd = ShellCustomizationUtils.getStarshipUninstallCommand() +
                            " && echo 'Restarting shell...' && exec \$SHELL -l"
                        if (onInstallRequest != null) {
                            onInstallRequest("starship-uninstall", uninstallCmd, null)
                        } else {
                            terminalWriter("$uninstallCmd\n")
                        }
                    }
                )
            )
        )
    }

    /**
     * Build Oh My Zsh submenu with configuration options.
     */
    private fun buildOhMyZshMenu(
        terminalWriter: (String) -> Unit,
        onInstallRequest: ((String, String, String?) -> Unit)?
    ): ContextMenuSubmenu {
        return ContextMenuSubmenu(
            id = "ohmyzsh_submenu",
            label = "Oh My Zsh",
            items = listOf(
                // Themes section
                ContextMenuSection(id = "ohmyzsh_themes_section", label = "Themes"),
                ContextMenuItem(
                    id = "ohmyzsh_current_theme",
                    label = "Show Current Theme",
                    action = { terminalWriter("echo \"Current theme: \$ZSH_THEME\"\n") }
                ),
                ContextMenuSubmenu(
                    id = "ohmyzsh_themes_submenu",
                    label = "Change Theme",
                    items = listOf(
                        ContextMenuItem(
                            id = "ohmyzsh_theme_robbyrussell",
                            label = "robbyrussell (default)",
                            action = { terminalWriter("sed -i 's/^ZSH_THEME=.*/ZSH_THEME=\"robbyrussell\"/' ~/.zshrc && echo '✓ Theme changed to robbyrussell - run: source ~/.zshrc'\n") }
                        ),
                        ContextMenuItem(
                            id = "ohmyzsh_theme_agnoster",
                            label = "agnoster",
                            action = { terminalWriter("sed -i 's/^ZSH_THEME=.*/ZSH_THEME=\"agnoster\"/' ~/.zshrc && echo '✓ Theme changed to agnoster - run: source ~/.zshrc'\n") }
                        ),
                        ContextMenuItem(
                            id = "ohmyzsh_theme_avit",
                            label = "avit",
                            action = { terminalWriter("sed -i 's/^ZSH_THEME=.*/ZSH_THEME=\"avit\"/' ~/.zshrc && echo '✓ Theme changed to avit - run: source ~/.zshrc'\n") }
                        ),
                        ContextMenuItem(
                            id = "ohmyzsh_theme_bira",
                            label = "bira",
                            action = { terminalWriter("sed -i 's/^ZSH_THEME=.*/ZSH_THEME=\"bira\"/' ~/.zshrc && echo '✓ Theme changed to bira - run: source ~/.zshrc'\n") }
                        ),
                        ContextMenuItem(
                            id = "ohmyzsh_theme_candy",
                            label = "candy",
                            action = { terminalWriter("sed -i 's/^ZSH_THEME=.*/ZSH_THEME=\"candy\"/' ~/.zshrc && echo '✓ Theme changed to candy - run: source ~/.zshrc'\n") }
                        ),
                        ContextMenuItem(
                            id = "ohmyzsh_theme_dst",
                            label = "dst",
                            action = { terminalWriter("sed -i 's/^ZSH_THEME=.*/ZSH_THEME=\"dst\"/' ~/.zshrc && echo '✓ Theme changed to dst - run: source ~/.zshrc'\n") }
                        ),
                        ContextMenuItem(
                            id = "ohmyzsh_theme_list",
                            label = "List All Themes",
                            action = { terminalWriter("ls ~/.oh-my-zsh/themes/\n") }
                        )
                    )
                ),

                // Plugins section
                ContextMenuSection(id = "ohmyzsh_plugins_section", label = "Plugins"),
                ContextMenuItem(
                    id = "ohmyzsh_show_plugins",
                    label = "Show Active Plugins",
                    action = { terminalWriter("grep '^plugins=' ~/.zshrc\n") }
                ),
                ContextMenuItem(
                    id = "ohmyzsh_list_plugins",
                    label = "List Available Plugins",
                    action = { terminalWriter("ls ~/.oh-my-zsh/plugins/\n") }
                ),
                ContextMenuItem(
                    id = "ohmyzsh_edit_plugins",
                    label = "Edit Plugins",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.zshrc\n") }
                ),

                // Maintenance section
                ContextMenuSection(id = "ohmyzsh_maintenance_section", label = "Maintenance"),
                ContextMenuItem(
                    id = "ohmyzsh_update",
                    label = "Update Oh My Zsh",
                    action = { terminalWriter("omz update\n") }
                ),
                ContextMenuItem(
                    id = "ohmyzsh_reload",
                    label = "Reload Config",
                    action = { terminalWriter("source ~/.zshrc\n") }
                ),
                ContextMenuItem(
                    id = "ohmyzsh_edit_zshrc",
                    label = "Edit .zshrc",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.zshrc\n") }
                ),

                // Help section
                ContextMenuSection(id = "ohmyzsh_help_section"),
                ContextMenuItem(
                    id = "ohmyzsh_help",
                    label = "Help",
                    action = { terminalWriter("omz help\n") }
                ),
                ContextMenuItem(
                    id = "ohmyzsh_docs",
                    label = "Documentation",
                    action = { UrlOpener.open("https://github.com/ohmyzsh/ohmyzsh/wiki") }
                ),

                // Uninstall section
                ContextMenuSection(id = "ohmyzsh_uninstall_section"),
                ContextMenuItem(
                    id = "ohmyzsh_uninstall",
                    label = "Uninstall",
                    action = {
                        val uninstallCmd = "sh ~/.oh-my-zsh/tools/uninstall.sh"
                        if (onInstallRequest != null) {
                            onInstallRequest("oh-my-zsh-uninstall", uninstallCmd, null)
                        } else {
                            terminalWriter("$uninstallCmd\n")
                        }
                    }
                )
            )
        )
    }

    /**
     * Build Prezto submenu with configuration options.
     */
    private fun buildPreztoMenu(
        terminalWriter: (String) -> Unit,
        onInstallRequest: ((String, String, String?) -> Unit)?
    ): ContextMenuSubmenu {
        return ContextMenuSubmenu(
            id = "prezto_submenu",
            label = "Prezto",
            items = listOf(
                // Modules section
                ContextMenuSection(id = "prezto_modules_section", label = "Modules"),
                ContextMenuItem(
                    id = "prezto_show_modules",
                    label = "Show Loaded Modules",
                    action = { terminalWriter("grep '^\\s*zmodule' ~/.zpreztorc 2>/dev/null || grep \"'\" ~/.zpreztorc | head -20\n") }
                ),
                ContextMenuItem(
                    id = "prezto_edit_modules",
                    label = "Edit Modules",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.zpreztorc\n") }
                ),

                // Theme section
                ContextMenuSection(id = "prezto_theme_section", label = "Theme"),
                ContextMenuItem(
                    id = "prezto_show_theme",
                    label = "Show Current Theme",
                    action = { terminalWriter("grep 'zstyle.*theme' ~/.zpreztorc\n") }
                ),
                ContextMenuItem(
                    id = "prezto_list_themes",
                    label = "List Themes",
                    action = { terminalWriter("ls ~/.zprezto/modules/prompt/functions/ | grep prompt_ | sed 's/prompt_//'\n") }
                ),

                // Maintenance section
                ContextMenuSection(id = "prezto_maintenance_section", label = "Maintenance"),
                ContextMenuItem(
                    id = "prezto_update",
                    label = "Update Prezto",
                    action = { terminalWriter("cd ~/.zprezto && git pull && git submodule update --init --recursive && cd -\n") }
                ),
                ContextMenuItem(
                    id = "prezto_reload",
                    label = "Reload Config",
                    action = { terminalWriter("source ~/.zshrc\n") }
                ),
                ContextMenuItem(
                    id = "prezto_edit_zshrc",
                    label = "Edit .zshrc",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.zshrc\n") }
                ),

                // Help section
                ContextMenuSection(id = "prezto_help_section"),
                ContextMenuItem(
                    id = "prezto_docs",
                    label = "Documentation",
                    action = { UrlOpener.open("https://github.com/sorin-ionescu/prezto") }
                ),

                // Uninstall section
                ContextMenuSection(id = "prezto_uninstall_section"),
                ContextMenuItem(
                    id = "prezto_uninstall",
                    label = "Uninstall",
                    action = {
                        val uninstallCmd = "rm -rf ~/.zprezto ~/.zshrc ~/.zlogin ~/.zlogout ~/.zpreztorc ~/.zprofile ~/.zshenv && " +
                            "echo '✓ Prezto uninstalled. Run: exec \$SHELL to restart with default zsh config'"
                        if (onInstallRequest != null) {
                            onInstallRequest("prezto-uninstall", uninstallCmd, null)
                        } else {
                            terminalWriter("$uninstallCmd\n")
                        }
                    }
                )
            )
        )
    }

    /**
     * Get install command for Prezto.
     */
    private fun getPreztoInstallCommand(): String {
        return "git clone --recursive https://github.com/sorin-ionescu/prezto.git \"\${ZDOTDIR:-\$HOME}/.zprezto\" && " +
            "setopt EXTENDED_GLOB && " +
            "for rcfile in \"\${ZDOTDIR:-\$HOME}\"/.zprezto/runcoms/^README.md(.N); do ln -s \"\$rcfile\" \"\${ZDOTDIR:-\$HOME}/.\${rcfile:t}\"; done && " +
            "echo '✓ Prezto installed. Restart shell or run: exec zsh'"
    }

    /**
     * Build Zsh submenu with configuration options.
     */
    private fun buildZshMenu(terminalWriter: (String) -> Unit): ContextMenuSubmenu {
        return ContextMenuSubmenu(
            id = "zsh_submenu",
            label = "Zsh",
            items = listOf(
                ContextMenuItem(
                    id = "zsh_version",
                    label = "Show Version",
                    action = { terminalWriter("zsh --version\n") }
                ),
                ContextMenuItem(
                    id = "zsh_set_default",
                    label = "Set as Default Shell",
                    action = { terminalWriter("chsh -s \$(which zsh) && echo '✓ Default shell changed to Zsh. Log out and log back in for new tabs to use Zsh.' && exec zsh -l\n") }
                ),
                ContextMenuItem(
                    id = "zsh_edit_zshrc",
                    label = "Edit .zshrc",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.zshrc\n") }
                ),
                ContextMenuItem(
                    id = "zsh_reload",
                    label = "Reload Config",
                    action = { terminalWriter("source ~/.zshrc\n") }
                )
            )
        )
    }

    /**
     * Build Bash submenu with configuration options.
     */
    private fun buildBashMenu(terminalWriter: (String) -> Unit): ContextMenuSubmenu {
        return ContextMenuSubmenu(
            id = "bash_submenu",
            label = "Bash",
            items = listOf(
                ContextMenuItem(
                    id = "bash_version",
                    label = "Show Version",
                    action = { terminalWriter("bash --version\n") }
                ),
                ContextMenuItem(
                    id = "bash_set_default",
                    label = "Set as Default Shell",
                    action = { terminalWriter("chsh -s \$(which bash) && echo '✓ Default shell changed to Bash. Log out and log back in for new tabs to use Bash.' && exec bash -l\n") }
                ),
                ContextMenuItem(
                    id = "bash_edit_bashrc",
                    label = "Edit .bashrc",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.bashrc\n") }
                ),
                ContextMenuItem(
                    id = "bash_edit_profile",
                    label = "Edit .bash_profile",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.bash_profile\n") }
                ),
                ContextMenuItem(
                    id = "bash_reload",
                    label = "Reload Config",
                    action = { terminalWriter("source ~/.bashrc\n") }
                )
            )
        )
    }

    /**
     * Get platform-aware install command for Zsh.
     */
    private fun getZshInstallCommand(): String {
        return when {
            System.getProperty("os.name").lowercase().contains("mac") ->
                "brew install zsh"
            System.getProperty("os.name").lowercase().contains("windows") ->
                "echo 'Zsh is not natively supported on Windows. Consider using WSL.'"
            else -> getLinuxInstallCommand("zsh", "zsh", "zsh")
        }
    }

    /**
     * Get platform-aware install command for Bash.
     */
    private fun getBashInstallCommand(): String {
        return when {
            System.getProperty("os.name").lowercase().contains("mac") ->
                "brew install bash"
            System.getProperty("os.name").lowercase().contains("windows") ->
                "echo 'Bash is available through Git Bash or WSL on Windows.'"
            else -> getLinuxInstallCommand("bash", "bash", "bash")
        }
    }

    /**
     * Get platform-aware install command for Fish.
     */
    private fun getFishInstallCommand(): String {
        return when {
            System.getProperty("os.name").lowercase().contains("mac") ->
                "brew install fish"
            System.getProperty("os.name").lowercase().contains("windows") ->
                "echo 'Fish is available through WSL on Windows. Visit https://fishshell.com for more info.'"
            else -> getLinuxInstallCommand("fish", "fish", "fish")
        }
    }

    /**
     * Build Fish submenu with configuration options.
     */
    private fun buildFishMenu(terminalWriter: (String) -> Unit): ContextMenuSubmenu {
        return ContextMenuSubmenu(
            id = "fish_submenu",
            label = "Fish",
            items = listOf(
                ContextMenuItem(
                    id = "fish_version",
                    label = "Show Version",
                    action = { terminalWriter("fish --version\n") }
                ),
                ContextMenuItem(
                    id = "fish_set_default",
                    label = "Set as Default Shell",
                    action = { terminalWriter("chsh -s \$(which fish) && echo '✓ Default shell changed to Fish. Log out and log back in for new tabs to use Fish.' && exec fish -l\n") }
                ),
                ContextMenuItem(
                    id = "fish_edit_config",
                    label = "Edit config.fish",
                    action = { terminalWriter("\${EDITOR:-nano} ~/.config/fish/config.fish\n") }
                ),
                ContextMenuItem(
                    id = "fish_reload",
                    label = "Reload Config",
                    action = { terminalWriter("source ~/.config/fish/config.fish\n") }
                ),
                ContextMenuItem(
                    id = "fish_web_config",
                    label = "Web Config (GUI)",
                    action = { terminalWriter("fish_config\n") }
                )
            )
        )
    }

    /**
     * Get Linux install command with package manager detection.
     */
    private fun getLinuxInstallCommand(aptPkg: String, dnfPkg: String, pacmanPkg: String): String {
        return "{ command -v apt >/dev/null 2>&1 && sudo apt install -y $aptPkg; } || " +
               "{ command -v dnf >/dev/null 2>&1 && sudo dnf install -y $dnfPkg; } || " +
               "{ command -v pacman >/dev/null 2>&1 && sudo pacman -S --noconfirm $pacmanPkg; } || " +
               "{ echo 'No supported package manager found (apt/dnf/pacman)'; exit 1; }"
    }
}
