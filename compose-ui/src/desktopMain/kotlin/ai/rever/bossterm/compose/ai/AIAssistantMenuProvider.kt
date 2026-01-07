package ai.rever.bossterm.compose.ai

import ai.rever.bossterm.compose.ContextMenuElement
import ai.rever.bossterm.compose.ContextMenuItem
import ai.rever.bossterm.compose.ContextMenuSubmenu
import ai.rever.bossterm.compose.settings.AIAssistantConfigData
import ai.rever.bossterm.compose.settings.TerminalSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Generates context menu items for AI coding assistants.
 *
 * This class creates dynamic context menu entries based on the current
 * installation status of AI assistants. Installed assistants show a
 * "Launch" option, while uninstalled ones show "Install" and "Learn More"
 * options in a submenu.
 *
 * @param detector The detector to query for installation status
 * @param launcher The launcher for generating commands
 */
class AIAssistantMenuProvider(
    private val detector: AIAssistantDetector,
    private val launcher: AIAssistantLauncher
) {

    /**
     * Generate context menu items for AI assistants.
     *
     * @param terminalWriter Function to write commands to the terminal (for launching)
     * @param onInstallRequest Callback to trigger installation dialog (assistant, installCommand, npmCommand?)
     * @param workingDirectory Current working directory for launching assistants
     * @param configs Per-assistant configuration from settings
     * @param statusOverride Optional pre-computed installation status (bypasses StateFlow for immediate updates)
     * @return List of context menu elements for the AI Assistants section
     */
    fun getMenuItems(
        terminalWriter: (String) -> Unit,
        onInstallRequest: (AIAssistantDefinition, String, String?) -> Unit,
        workingDirectory: String? = null,
        configs: Map<String, AIAssistantConfigData> = emptyMap(),
        statusOverride: Map<String, Boolean>? = null
    ): List<ContextMenuElement> {
        // Use override if provided (for immediate updates after detection), otherwise read from StateFlow
        val status = statusOverride ?: detector.installationStatus.value
        val assistantItems = mutableListOf<ContextMenuElement>()

        // Add items for each assistant
        for (assistant in AIAssistants.AI_ASSISTANTS) {
            val config = configs[assistant.id] ?: AIAssistantConfigData()

            // Skip if assistant is disabled in config
            if (!config.enabled) continue

            val isInstalled = status[assistant.id] ?: false

            if (isInstalled) {
                // Build menu label with YOLO mode indicator
                val label = buildMenuLabel(assistant, config)

                // Installed: Direct menu item to launch
                assistantItems.add(
                    ContextMenuItem(
                        id = "ai_launch_${assistant.id}",
                        label = label,
                        action = {
                            terminalWriter(launcher.getLaunchCommand(assistant, config, workingDirectory))
                        }
                    )
                )
            } else {
                // Not installed: Show submenu with Install options and Learn More
                val installItems = mutableListOf<ContextMenuElement>()

                // Add primary install option
                val scriptCommand = launcher.getInstallCommand(assistant).trim()
                val npmCommand = if (assistant.npmInstallCommand != null) {
                    launcher.getNpmInstallCommand(assistant).trim()
                } else null

                if (npmCommand != null) {
                    // Has both script and npm - show both options
                    // Script option passes npm as fallback
                    installItems.add(
                        ContextMenuItem(
                            id = "ai_install_script_${assistant.id}",
                            label = "Install (Script)",
                            action = {
                                onInstallRequest(assistant, scriptCommand, npmCommand)
                            }
                        )
                    )
                    // npm option has no fallback
                    installItems.add(
                        ContextMenuItem(
                            id = "ai_install_npm_${assistant.id}",
                            label = "Install (npm)",
                            action = {
                                onInstallRequest(assistant, npmCommand, null)
                            }
                        )
                    )
                } else {
                    // Only one install method (no npm fallback)
                    installItems.add(
                        ContextMenuItem(
                            id = "ai_install_${assistant.id}",
                            label = "Install",
                            action = {
                                onInstallRequest(assistant, scriptCommand, null)
                            }
                        )
                    )
                }

                // Add Learn More
                installItems.add(
                    ContextMenuItem(
                        id = "ai_learnmore_${assistant.id}",
                        label = "Learn More",
                        action = {
                            launcher.openWebsite(assistant)
                        }
                    )
                )

                assistantItems.add(
                    ContextMenuSubmenu(
                        id = "ai_submenu_${assistant.id}",
                        label = assistant.displayName,
                        items = installItems
                    )
                )
            }
        }

        // Wrap all assistants under a single "AI Assistants" submenu
        return listOf(
            ContextMenuSubmenu(
                id = "ai_assistants_menu",
                label = "AI Assistants",
                items = assistantItems
            )
        )
    }

    /**
     * Build the menu label with YOLO mode indicator if enabled.
     */
    private fun buildMenuLabel(assistant: AIAssistantDefinition, config: AIAssistantConfigData): String {
        val yoloEnabled = config.yoloEnabled
        val yoloLabel = assistant.yoloLabel

        return if (yoloEnabled && yoloLabel.isNotBlank()) {
            "${assistant.displayName} ($yoloLabel)"
        } else {
            assistant.displayName
        }
    }

    /**
     * Check if any AI assistant is currently installed.
     *
     * @return true if at least one assistant is installed
     */
    fun hasInstalledAssistants(): Boolean {
        return detector.installationStatus.value.values.any { it }
    }

    /**
     * Get the count of installed AI assistants.
     *
     * @return Number of installed assistants
     */
    fun getInstalledCount(): Int {
        return detector.installationStatus.value.values.count { it }
    }
}

/**
 * State holder for AI assistant integration.
 * Encapsulates detector, launcher, and menu provider.
 */
class AIAssistantState(
    val detector: AIAssistantDetector,
    val launcher: AIAssistantLauncher,
    val menuProvider: AIAssistantMenuProvider
)

/**
 * Remember AI assistant state with automatic lifecycle management.
 * Detection is performed on-demand when context menu opens, not via polling.
 *
 * @param settings Terminal settings containing AI assistant configuration
 * @return AIAssistantState with initialized components
 */
@Composable
fun rememberAIAssistantState(settings: TerminalSettings): AIAssistantState {
    val detector = remember { AIAssistantDetector() }
    val launcher = remember { AIAssistantLauncher() }
    val menuProvider = remember(detector, launcher) {
        AIAssistantMenuProvider(detector, launcher)
    }

    return remember(detector, launcher, menuProvider) {
        AIAssistantState(detector, launcher, menuProvider)
    }
}
