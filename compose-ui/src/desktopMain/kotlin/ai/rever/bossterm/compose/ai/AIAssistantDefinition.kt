package ai.rever.bossterm.compose.ai

import kotlinx.serialization.Serializable

/**
 * Category of tool definition.
 */
enum class ToolCategory {
    AI_ASSISTANT,  // AI coding assistants (claude, gemini, codex, etc.)
    VERSION_CONTROL  // Version control tools (git, gh)
}

/**
 * Definition of a CLI tool that can be integrated with BossTerm.
 *
 * @property id Unique identifier for the tool (e.g., "claude-code")
 * @property displayName Human-readable name shown in menus (e.g., "Claude Code")
 * @property command The CLI command to launch the tool (e.g., "claude")
 * @property category Category of the tool (AI_ASSISTANT or VERSION_CONTROL)
 * @property yoloFlag The flag to enable auto/YOLO mode (e.g., "--dangerously-skip-permissions")
 * @property yoloLabel Label for YOLO mode (e.g., "Auto Mode")
 * @property installCommand Command to install the tool
 * @property npmInstallCommand Alternative npm install command
 * @property websiteUrl URL for "Learn more" action
 * @property description Brief description of the tool
 * @property isBuiltIn Whether this is a built-in tool (vs custom)
 */
@Serializable
data class AIAssistantDefinition(
    val id: String,
    val displayName: String,
    val command: String,
    val category: ToolCategory = ToolCategory.AI_ASSISTANT,
    val yoloFlag: String = "",
    val yoloLabel: String = "Auto",
    val installCommand: String = "",
    val npmInstallCommand: String? = null,
    val websiteUrl: String = "",
    val description: String = "",
    val isBuiltIn: Boolean = true
)

/**
 * Per-assistant configuration stored in settings.
 */
@Serializable
data class AIAssistantConfig(
    val assistantId: String,
    val enabled: Boolean = true,
    val yoloEnabled: Boolean = true,
    val customCommand: String? = null,
    val customYoloFlag: String? = null
) {
    /**
     * Get the effective command to run.
     */
    fun getCommand(assistant: AIAssistantDefinition): String =
        customCommand?.takeIf { it.isNotBlank() } ?: assistant.command

    /**
     * Get the effective YOLO flag.
     */
    fun getYoloFlag(assistant: AIAssistantDefinition): String =
        customYoloFlag?.takeIf { it.isNotBlank() } ?: assistant.yoloFlag

    /**
     * Build the full command with YOLO mode if enabled.
     */
    fun buildFullCommand(assistant: AIAssistantDefinition): String {
        val baseCommand = getCommand(assistant)
        val flag = getYoloFlag(assistant)
        return if (yoloEnabled && flag.isNotBlank()) {
            "$baseCommand $flag"
        } else {
            baseCommand
        }
    }
}

/**
 * Registry of supported AI coding assistants.
 *
 * This object provides a centralized list of AI assistants that BossTerm can
 * detect and integrate with. Each assistant has detection, launch, and install
 * capabilities.
 */
object AIAssistants {
    /**
     * Built-in AI coding assistants.
     */
    val BUILTIN: List<AIAssistantDefinition> = listOf(
        AIAssistantDefinition(
            id = "claude-code",
            displayName = "Claude Code",
            command = "claude",
            yoloFlag = "--dangerously-skip-permissions",
            yoloLabel = "Auto Mode",
            installCommand = "curl -fsSL https://claude.ai/install.sh | bash",
            npmInstallCommand = "npm install -g @anthropic-ai/claude-code",
            websiteUrl = "https://docs.anthropic.com/en/docs/claude-code",
            description = "Anthropic's AI coding assistant"
        ),
        AIAssistantDefinition(
            id = "codex",
            displayName = "Codex (OpenAI)",
            command = "codex",
            yoloFlag = "--full-auto",
            yoloLabel = "Full Auto",
            installCommand = "npm install -g @openai/codex",
            npmInstallCommand = null,
            websiteUrl = "https://github.com/openai/codex",
            description = "OpenAI's coding assistant"
        ),
        AIAssistantDefinition(
            id = "gemini-cli",
            displayName = "Gemini CLI",
            command = "gemini",
            yoloFlag = "-y",
            yoloLabel = "Auto",
            installCommand = "npm install -g @google/gemini-cli",
            npmInstallCommand = null,
            websiteUrl = "https://github.com/google-gemini/gemini-cli",
            description = "Google's AI coding assistant"
        ),
        AIAssistantDefinition(
            id = "opencode",
            displayName = "OpenCode",
            command = "opencode",
            yoloFlag = "--auto-approve",
            yoloLabel = "Auto",
            installCommand = "curl -fsSL https://opencode.ai/install | bash",
            npmInstallCommand = "npm install -g opencode-ai",
            websiteUrl = "https://github.com/anomalyco/opencode",
            description = "Open-source AI coding assistant"
        ),
        // Version Control Tools
        AIAssistantDefinition(
            id = "gh",
            displayName = "GitHub CLI",
            command = "gh",
            category = ToolCategory.VERSION_CONTROL,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = "sudo apt install -y gh",
            npmInstallCommand = null,
            websiteUrl = "https://cli.github.com/",
            description = "GitHub's official CLI"
        ),
        AIAssistantDefinition(
            id = "git",
            displayName = "Git",
            command = "git",
            category = ToolCategory.VERSION_CONTROL,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = "sudo apt install -y git",
            npmInstallCommand = null,
            websiteUrl = "https://git-scm.com/downloads",
            description = "Distributed version control system"
        )
    )

    /**
     * All supported tools (built-in + custom).
     * Custom tools are loaded from settings at runtime.
     */
    val ALL: List<AIAssistantDefinition>
        get() = BUILTIN + _customAssistants

    /**
     * Only AI coding assistants (claude, gemini, codex, etc.)
     */
    val AI_ASSISTANTS: List<AIAssistantDefinition>
        get() = ALL.filter { it.category == ToolCategory.AI_ASSISTANT }

    /**
     * Only version control tools (git, gh)
     */
    val VCS_TOOLS: List<AIAssistantDefinition>
        get() = ALL.filter { it.category == ToolCategory.VERSION_CONTROL }

    private var _customAssistants: List<AIAssistantDefinition> = emptyList()

    /**
     * Set custom assistants from settings.
     */
    fun setCustomAssistants(assistants: List<AIAssistantDefinition>) {
        _customAssistants = assistants.map { it.copy(isBuiltIn = false) }
    }

    /**
     * Find an assistant by its ID.
     */
    fun findById(id: String): AIAssistantDefinition? = ALL.find { it.id == id }

    /**
     * Find an assistant by its command name.
     */
    fun findByCommand(command: String): AIAssistantDefinition? = ALL.find { it.command == command }

    /**
     * Create default configs for all assistants.
     */
    fun defaultConfigs(): Map<String, AIAssistantConfig> =
        ALL.associate { it.id to AIAssistantConfig(assistantId = it.id) }
}
