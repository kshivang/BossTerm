package ai.rever.bossterm.compose.ai

import kotlinx.serialization.Serializable

/**
 * Category of tool definition.
 */
enum class ToolCategory {
    AI_ASSISTANT,        // AI coding assistants (claude, gemini, codex, etc.)
    VERSION_CONTROL,     // Version control tools (git, gh)
    SHELL_CUSTOMIZATION, // Shell customization tools (starship, etc.)
    PACKAGE_MANAGER,     // Package managers (brew, winget, etc.)
    CONTAINER_TOOLS,     // Container and orchestration tools (docker, kubectl, k3d)
    LANGUAGE_RUNTIME,    // Language runtimes and toolchains (rust, cargo, go)
    CLI_UTILITIES        // CLI utilities (tmux, fzf, ripgrep, etc.)
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

    // Package Managers
    const val BREW = "brew"

    // Container Tools
    const val DOCKER = "docker"
    const val KUBECTL = "kubectl"
    const val K3D = "k3d"

    // Language Runtimes
    const val RUST = "rust"
    const val CARGO = "cargo"

    // CLI Utilities
    const val TMUX = "tmux"
    const val FZF = "fzf"

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

    /**
     * All package manager IDs.
     */
    val ALL_PACKAGE_MANAGERS = setOf(BREW)

    /**
     * All container tool IDs.
     */
    val ALL_CONTAINER_TOOLS = setOf(DOCKER, KUBECTL, K3D)

    /**
     * All language runtime IDs.
     */
    val ALL_LANGUAGE_RUNTIMES = setOf(RUST, CARGO)

    /**
     * All CLI utility IDs.
     */
    val ALL_CLI_UTILITIES = setOf(TMUX, FZF)
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
            installCommand = ToolCommandProvider.getGhInstallCommand(),
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
            installCommand = ToolCommandProvider.getGitInstallCommand(),
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
            installCommand = ToolCommandProvider.getStarshipInstallCommand(),
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
            installCommand = ToolCommandProvider.getOhMyZshInstallCommand(),
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
            installCommand = ToolCommandProvider.getZshInstallCommand(),
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
            installCommand = ToolCommandProvider.getBashInstallCommand(),
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
            installCommand = ToolCommandProvider.getFishInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://fishshell.com/",
            description = "Friendly Interactive Shell"
        ),
        // Package Managers
        AIAssistantDefinition(
            id = AIAssistantIds.BREW,
            displayName = "Homebrew",
            command = "brew",
            category = ToolCategory.PACKAGE_MANAGER,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = ToolCommandProvider.getBrewInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://brew.sh/",
            description = "The missing package manager for macOS and Linux"
        ),
        // Container Tools
        AIAssistantDefinition(
            id = AIAssistantIds.DOCKER,
            displayName = "Docker",
            command = "docker",
            category = ToolCategory.CONTAINER_TOOLS,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = ToolCommandProvider.getDockerInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://www.docker.com/",
            description = "Container platform for building and running applications"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.KUBECTL,
            displayName = "kubectl",
            command = "kubectl",
            category = ToolCategory.CONTAINER_TOOLS,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = ToolCommandProvider.getKubectlInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://kubernetes.io/docs/tasks/tools/",
            description = "Kubernetes command-line tool"
        ),
        // Language Runtimes
        AIAssistantDefinition(
            id = AIAssistantIds.RUST,
            displayName = "Rust",
            command = "rustc",
            category = ToolCategory.LANGUAGE_RUNTIME,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = ToolCommandProvider.getRustInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://www.rust-lang.org/",
            description = "Rust programming language compiler"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.CARGO,
            displayName = "Cargo",
            command = "cargo",
            category = ToolCategory.LANGUAGE_RUNTIME,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = ToolCommandProvider.getRustInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://doc.rust-lang.org/cargo/",
            description = "Rust package manager and build tool"
        ),
        // CLI Utilities
        AIAssistantDefinition(
            id = AIAssistantIds.TMUX,
            displayName = "tmux",
            command = "tmux",
            category = ToolCategory.CLI_UTILITIES,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = ToolCommandProvider.getTmuxInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://github.com/tmux/tmux",
            description = "Terminal multiplexer for managing multiple terminal sessions"
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.FZF,
            displayName = "fzf",
            command = "fzf",
            category = ToolCategory.CLI_UTILITIES,
            yoloFlag = "",
            yoloLabel = "",
            installCommand = ToolCommandProvider.getFzfInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://github.com/junegunn/fzf",
            description = "Fuzzy finder for command-line"
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

    /**
     * Only package managers (brew, etc.)
     */
    val PACKAGE_MANAGERS: List<AIAssistantDefinition>
        get() = ALL.filter { it.category == ToolCategory.PACKAGE_MANAGER }

    /**
     * Only container tools (docker, kubectl, k3d)
     */
    val CONTAINER_TOOLS: List<AIAssistantDefinition>
        get() = ALL.filter { it.category == ToolCategory.CONTAINER_TOOLS }

    /**
     * Only language runtimes (rust, cargo, etc.)
     */
    val LANGUAGE_RUNTIMES: List<AIAssistantDefinition>
        get() = ALL.filter { it.category == ToolCategory.LANGUAGE_RUNTIME }

    /**
     * Only CLI utilities (tmux, fzf, etc.)
     */
    val CLI_UTILITIES: List<AIAssistantDefinition>
        get() = ALL.filter { it.category == ToolCategory.CLI_UTILITIES }

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
