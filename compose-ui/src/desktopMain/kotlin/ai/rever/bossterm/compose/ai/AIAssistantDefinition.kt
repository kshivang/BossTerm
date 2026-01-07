package ai.rever.bossterm.compose.ai

import kotlinx.serialization.Serializable

/**
 * Category of tool definition.
 */
enum class ToolCategory {
    AI_ASSISTANT,        // AI coding assistants (claude, gemini, codex, etc.)
    VERSION_CONTROL,     // Version control tools (git, gh)
    SHELL_CUSTOMIZATION  // Shell customization tools (starship, etc.)
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
 * Constants for AI assistant IDs to avoid magic strings.
 */
object AIAssistantIds {
    // AI Coding Assistants
    const val CLAUDE_CODE = "claude-code"
    const val CODEX = "codex"
    const val GEMINI_CLI = "gemini-cli"
    const val OPENCODE = "opencode"

    // Version Control Tools
    const val GIT = "git"
    const val GH = "gh"

    // Shell Customization Tools
    const val STARSHIP = "starship"
    const val OH_MY_ZSH = "oh-my-zsh"
    const val PREZTO = "prezto"
    const val ZSH = "zsh"
    const val BASH = "bash"
    const val FISH = "fish"

    /**
     * All AI assistant IDs (coding assistants only).
     */
    val ALL_AI_ASSISTANTS = setOf(CLAUDE_CODE, GEMINI_CLI, CODEX, OPENCODE)

    /**
     * All version control tool IDs.
     */
    val ALL_VCS_TOOLS = setOf(GIT, GH)

    /**
     * All shell customization tool IDs.
     */
    val ALL_SHELL_TOOLS = setOf(STARSHIP, OH_MY_ZSH, PREZTO, ZSH, BASH, FISH)
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
            id = AIAssistantIds.CLAUDE_CODE,
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
            id = AIAssistantIds.CODEX,
            displayName = "Codex (OpenAI)",
            command = "codex",
            yoloFlag = "--full-auto",
            yoloLabel = "Full Auto",
            installCommand = "npm install -g @openai/codex",
            npmInstallCommand = "npm install -g @openai/codex",
            websiteUrl = "https://github.com/openai/codex",
            description = "OpenAI's coding assistant"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.GEMINI_CLI,
            displayName = "Gemini CLI",
            command = "gemini",
            yoloFlag = "-y",
            yoloLabel = "Auto",
            installCommand = "npm install -g @google/gemini-cli",
            npmInstallCommand = "npm install -g @google/gemini-cli",
            websiteUrl = "https://github.com/google-gemini/gemini-cli",
            description = "Google's AI coding assistant"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.OPENCODE,
            displayName = "OpenCode",
            command = "opencode",
            yoloFlag = "--auto-approve",
            yoloLabel = "Auto",
            installCommand = "curl -fsSL https://opencode.ai/install | bash",
            npmInstallCommand = "npm install -g opencode-ai",
            websiteUrl = "https://github.com/anomalyco/opencode",
            description = "Open-source AI coding assistant"
        ),
        // Version Control Tools (platform-aware install commands)
        AIAssistantDefinition(
            id = AIAssistantIds.GH,
            displayName = "GitHub CLI",
            command = "gh",
            category = ToolCategory.VERSION_CONTROL,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = AIAssistantLauncher.getGhInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://cli.github.com/",
            description = "GitHub's official CLI"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.GIT,
            displayName = "Git",
            command = "git",
            category = ToolCategory.VERSION_CONTROL,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = AIAssistantLauncher.getGitInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://git-scm.com/downloads",
            description = "Distributed version control system"
        ),
        // Shell Customization Tools
        AIAssistantDefinition(
            id = AIAssistantIds.STARSHIP,
            displayName = "Starship",
            command = "starship",
            category = ToolCategory.SHELL_CUSTOMIZATION,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = AIAssistantLauncher.getStarshipInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://starship.rs/",
            description = "Cross-shell prompt customization"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.OH_MY_ZSH,
            displayName = "Oh My Zsh",
            command = "omz",
            category = ToolCategory.SHELL_CUSTOMIZATION,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = AIAssistantLauncher.getOhMyZshInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://ohmyz.sh/",
            description = "Zsh framework with plugins and themes"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.PREZTO,
            displayName = "Prezto",
            command = "zprezto",
            category = ToolCategory.SHELL_CUSTOMIZATION,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = "",  // Complex install handled in ShellCustomizationMenuProvider
            npmInstallCommand = null,
            websiteUrl = "https://github.com/sorin-ionescu/prezto",
            description = "Zsh configuration framework"
        ),
        // Shell installations
        AIAssistantDefinition(
            id = AIAssistantIds.ZSH,
            displayName = "Zsh",
            command = "zsh",
            category = ToolCategory.SHELL_CUSTOMIZATION,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = AIAssistantLauncher.getZshInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://www.zsh.org/",
            description = "Z shell - powerful command interpreter"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.BASH,
            displayName = "Bash",
            command = "bash",
            category = ToolCategory.SHELL_CUSTOMIZATION,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = AIAssistantLauncher.getBashInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://www.gnu.org/software/bash/",
            description = "Bourne Again Shell"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.FISH,
            displayName = "Fish",
            command = "fish",
            category = ToolCategory.SHELL_CUSTOMIZATION,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = AIAssistantLauncher.getFishInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://fishshell.com/",
            description = "Friendly Interactive Shell"
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
