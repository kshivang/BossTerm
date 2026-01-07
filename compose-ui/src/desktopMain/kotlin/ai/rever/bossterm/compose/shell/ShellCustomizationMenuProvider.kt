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
import java.util.concurrent.TimeUnit

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

    /**
     * Detect if a command is installed by checking `which`.
     */
    private fun isCommandInstalled(command: String): Boolean {
        return try {
            val process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(2, TimeUnit.SECONDS)
            completed && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Detect if Oh My Zsh is installed by checking ~/.oh-my-zsh directory.
     */
    private fun isOhMyZshInstalled(): Boolean {
        val home = System.getProperty("user.home") ?: return false
        return File(home, ".oh-my-zsh").isDirectory
    }

    /**
     * Refresh installation status for shell customization tools.
     */
    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        starshipInstalled = isCommandInstalled("starship")
        ohmyzshInstalled = isOhMyZshInstalled()
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
     * Get context menu items for shell customization.
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
            ?: (starshipInstalled ?: isCommandInstalled("starship"))
        val isOhMyZshInstalled = statusOverride?.get("oh-my-zsh")
            ?: (ohmyzshInstalled ?: isOhMyZshInstalled())

        val shellItems = mutableListOf<ContextMenuElement>()

        // Starship menu
        if (!isStarshipInstalled) {
            // Not installed: Install + Learn More submenu
            shellItems.add(
                ContextMenuSubmenu(
                    id = "starship_submenu",
                    label = "Starship",
                    items = listOf(
                        ContextMenuItem(
                            id = "starship_install",
                            label = "Install",
                            action = {
                                if (onInstallRequest != null) {
                                    onInstallRequest("starship", AIAssistantLauncher.getStarshipInstallCommand(), null)
                                } else {
                                    UrlOpener.open("https://starship.rs/")
                                }
                            }
                        ),
                        ContextMenuItem(
                            id = "starship_learnmore",
                            label = "Learn More",
                            action = { UrlOpener.open("https://starship.rs/") }
                        )
                    )
                )
            )
        } else {
            // Installed: Configuration submenu
            shellItems.add(buildStarshipMenu(terminalWriter))
        }

        // Oh My Zsh menu
        if (!isOhMyZshInstalled) {
            // Not installed: Install + Learn More submenu
            shellItems.add(
                ContextMenuSubmenu(
                    id = "ohmyzsh_submenu",
                    label = "Oh My Zsh",
                    items = listOf(
                        ContextMenuItem(
                            id = "ohmyzsh_install",
                            label = "Install",
                            action = {
                                if (onInstallRequest != null) {
                                    onInstallRequest("oh-my-zsh", AIAssistantLauncher.getOhMyZshInstallCommand(), null)
                                } else {
                                    UrlOpener.open("https://ohmyz.sh/")
                                }
                            }
                        ),
                        ContextMenuItem(
                            id = "ohmyzsh_learnmore",
                            label = "Learn More",
                            action = { UrlOpener.open("https://ohmyz.sh/") }
                        )
                    )
                )
            )
        } else {
            // Installed: Configuration submenu
            shellItems.add(buildOhMyZshMenu(terminalWriter))
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
    private fun buildStarshipMenu(terminalWriter: (String) -> Unit): ContextMenuSubmenu {
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
                        terminalWriter("echo 'eval \"\$(starship init bash)\"' >> ~/.bashrc && echo '✓ Added to ~/.bashrc - restart shell or run: source ~/.bashrc'\n")
                    }
                ),
                ContextMenuItem(
                    id = "starship_setup_zsh",
                    label = "Setup for Zsh",
                    action = {
                        terminalWriter("echo 'eval \"\$(starship init zsh)\"' >> ~/.zshrc && echo '✓ Added to ~/.zshrc - restart shell or run: source ~/.zshrc'\n")
                    }
                ),
                ContextMenuItem(
                    id = "starship_setup_fish",
                    label = "Setup for Fish",
                    action = {
                        terminalWriter("echo 'starship init fish | source' >> ~/.config/fish/config.fish && echo '✓ Added to config.fish - restart shell'\n")
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
                )
            )
        )
    }

    /**
     * Build Oh My Zsh submenu with configuration options.
     */
    private fun buildOhMyZshMenu(terminalWriter: (String) -> Unit): ContextMenuSubmenu {
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
                )
            )
        )
    }
}
