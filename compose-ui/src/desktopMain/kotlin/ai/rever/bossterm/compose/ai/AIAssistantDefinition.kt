package ai.rever.bossterm.compose.ai

import kotlinx.serialization.Serializable

/**
 * OpenCode's auto-approve, as a shell env prefix — its TUI has no auto-approve flag, so this is the
 * equivalent of the other CLIs' `--dangerously-skip-permissions`.
 *
 * Every permission key set to "allow". The bare-string form the config file documents
 * (`"permission": "allow"`) is NOT usable here: config.ts does
 * `mergeDeep(result.permission ?? {}, JSON.parse(OPENCODE_PERMISSION))`, and deep-merging a string
 * into an object spreads it per character — `OPENCODE_PERMISSION='"allow"'` resolves to
 * `{"0":"a","1":"l","2":"l","3":"o","4":"w"}`, which looks configured and approves nothing.
 * Confirmed both ways with `opencode debug config` against opencode 1.15.0; the explicit object
 * below resolves to all 13 keys = "allow".
 *
 * Single-quoted so the JSON's double quotes survive zsh/bash/fish alike.
 */
private const val OPENCODE_YOLO_ENV = "OPENCODE_PERMISSION='" +
    """{"read":"allow","edit":"allow","glob":"allow","grep":"allow","bash":"allow",""" +
    """"task":"allow","skill":"allow","lsp":"allow","question":"allow","webfetch":"allow",""" +
    """"websearch":"allow","external_directory":"allow","doom_loop":"allow"}""" +
    "'"

/**
 * Category of tool definition.
 */
enum class ToolCategory {
    AI_ASSISTANT,        // AI coding assistants (claude, gemini, codex, etc.)
    LOCAL_MODEL_RUNTIME, // Local model servers that other assistants can run against (ollama)
    VERSION_CONTROL,     // Version control tools (git, gh)
    SHELL_CUSTOMIZATION, // Shell customization tools (starship, etc.)
    PACKAGE_MANAGER,     // Package managers (brew, winget, etc.)
    CONTAINER_TOOLS,     // Container and orchestration tools (docker, kubectl, k3d)
    LANGUAGE_RUNTIME,    // Language runtimes and toolchains (rust, cargo, go)
    CLI_UTILITIES        // CLI utilities (tmux, fzf, ripgrep, etc.)
}

/**
 * How open a CLI's whole stack is — the client AND the models it can drive.
 *
 * License alone doesn't separate these: Codex and Gemini CLI are Apache-2.0 too, and only Claude
 * Code has a closed client. What differs is whether you can run the CLI end-to-end on open weights
 * without a proprietary vendor account. Menus and settings order by this so the fully-open stack
 * leads. Derived from [AIAssistantDefinition.openSource] + [AIAssistantDefinition.localModels] —
 * never stored, so the two flags can't drift out of sync with the tier.
 */
enum class OpennessTier {
    /** OSS client that can run against local/open-weight models (OpenCode, Kimi Code, Hermes, Ollama). */
    OPEN_STACK,

    /** OSS client, but the models need a vendor account (Grok Build, Codex, Gemini CLI). */
    OPEN_CLIENT,

    /** Closed client and closed models (Claude Code). */
    PROPRIETARY
}

/**
 * What kind of thing [AIAssistantDefinition.installCommand] is.
 *
 * Declared rather than sniffed. Onboarding used to classify by
 * `installCommand.startsWith("npm install -g ")` and treat everything else as runnable as-is, which
 * silently emitted `curl … | bash` into a Windows PowerShell chain — no `bash` there, and `|` is an
 * object pipeline, so the whole `&&`-joined chain died including git/gh/starship.
 */
enum class InstallKind {
    /**
     * A POSIX shell installer (`curl … | bash`). Unix-only: on Windows the entry must fall back to
     * [AIAssistantDefinition.npmInstallCommand], and is uninstallable there without one.
     */
    SHELL_SCRIPT,

    /** `npm install -g <pkg>`. Works anywhere npm does, Windows included, and batches with others. */
    NPM,

    /**
     * Already resolved for the running OS by [ToolCommandProvider] (brew / winget / apt / …), so it
     * runs as-is on every platform. The default, because that's how every non-AI tool is defined.
     */
    PLATFORM_NATIVE
}

/**
 * How a tool should be installed on the platform being targeted — the resolved form of
 * [AIAssistantDefinition.installKind] plus the Windows fallback.
 */
sealed interface ResolvedInstall {
    /** Install via npm. Callers batch these into one `npm install -g a b c` with a single node check. */
    data class Npm(val packageName: String) : ResolvedInstall

    /** Run this command as-is. */
    data class Command(val command: String) : ResolvedInstall
}

/**
 * Definition of a CLI tool that can be integrated with BossTerm.
 *
 * @property id Unique identifier for the tool (e.g., "claude-code")
 * @property displayName Human-readable name shown in menus (e.g., "Claude Code")
 * @property command The CLI command to launch the tool (e.g., "claude"). This is also what gets
 *   detected on `PATH`, so it must stay a bare binary name — subcommands go in [launchArgs].
 * @property launchArgs Subcommand/arguments always appended to [command] when launching, regardless
 *   of auto mode (e.g. `tui` for OpenClaw, whose bare command prints help instead of starting an
 *   interactive session). Blank for CLIs whose bare command is the agent.
 * @property category Category of the tool (AI_ASSISTANT or VERSION_CONTROL)
 * @property yoloFlag The flag to enable auto/YOLO mode (e.g., "--dangerously-skip-permissions")
 * @property yoloEnv Shell env-var prefix that enables auto mode for CLIs that have no flag for it,
 *   e.g. `OPENCODE_PERMISSION='{…}'`. Prepended to the command when auto mode is on, so the launch
 *   line becomes `VAR='value' cmd`. Must be shell-safe as written (quote it here). Most CLIs leave
 *   this blank and use [yoloFlag]; a CLI may set either, and OpenCode is the case that needs this
 *   one because its TUI accepts no auto-approve argument at all.
 * @property yoloLabel Label for YOLO mode (e.g., "Auto Mode")
 * @property installCommand Command to install the tool
 * @property installKind What kind of command [installCommand] is — see [InstallKind]. Consumers
 *   must branch on this rather than inspecting the command string.
 * @property npmInstallCommand Alternative npm install command. For a [InstallKind.SHELL_SCRIPT]
 *   entry this is also the Windows install path, since the script can't run there.
 * @property websiteUrl URL for "Learn more" action
 * @property description Brief description of the tool
 * @property isBuiltIn Whether this is a built-in tool (vs custom)
 * @property license SPDX id of the CLI's own license, "" when proprietary or unknown. Rendered as a
 *   badge for AI assistants and local model runtimes; ignored for other categories.
 * @property sourceUrl Source repository, "" when there isn't a public one.
 * @property openSource Whether the CLI itself is open source.
 * @property localModels Whether the CLI can be pointed at local/open-weight models (Ollama, a
 *   self-hosted OpenAI-compatible endpoint, …) rather than requiring a specific vendor's API.
 * @property extraDetectPaths Install locations to probe before falling back to `PATH` lookups.
 *   A leading `~/` is expanded to the user's home directory.
 */
@Serializable
data class AIAssistantDefinition(
    val id: String,
    val displayName: String,
    val command: String,
    val launchArgs: String = "",
    val category: ToolCategory = ToolCategory.AI_ASSISTANT,
    val yoloFlag: String = "",
    val yoloEnv: String = "",
    val yoloLabel: String = "Auto",
    val installCommand: String = "",
    val installKind: InstallKind = InstallKind.PLATFORM_NATIVE,
    val npmInstallCommand: String? = null,
    val websiteUrl: String = "",
    val description: String = "",
    val isBuiltIn: Boolean = true,
    val license: String = "",
    val sourceUrl: String = "",
    val openSource: Boolean = false,
    val localModels: Boolean = false,
    val extraDetectPaths: List<String> = emptyList()
) {
    /** See [OpennessTier]. Derived, so it can never contradict [openSource] / [localModels]. */
    val openness: OpennessTier
        get() = when {
            !openSource -> OpennessTier.PROPRIETARY
            localModels -> OpennessTier.OPEN_STACK
            else -> OpennessTier.OPEN_CLIENT
        }

    /** [extraDetectPaths] with `~/` expanded against [home]. */
    fun resolvedDetectPaths(home: String): List<String> =
        extraDetectPaths.map { if (it.startsWith("~/")) "$home/${it.removePrefix("~/")}" else it }

    /** The npm package this entry installs, from either install field. Null when it isn't on npm. */
    val npmPackage: String?
        get() = listOfNotNull(npmInstallCommand, installCommand)
            .firstOrNull { it.startsWith(NPM_GLOBAL_PREFIX) }
            ?.removePrefix(NPM_GLOBAL_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * How to install this tool on the target platform, or null when it can't be installed there.
     *
     * A [InstallKind.SHELL_SCRIPT] entry falls back to npm on Windows; if it has no npm package
     * either — Hermes, which upstream ships for Linux/macOS/WSL2 only — the answer is null, and the
     * caller is expected to report that rather than emit an unrunnable command.
     */
    fun resolveInstall(isWindows: Boolean): ResolvedInstall? {
        val asCommand = installCommand.takeIf { it.isNotBlank() }?.let(ResolvedInstall::Command)
        return when (installKind) {
            InstallKind.NPM -> npmPackage?.let(ResolvedInstall::Npm) ?: asCommand
            InstallKind.PLATFORM_NATIVE -> asCommand
            InstallKind.SHELL_SCRIPT ->
                if (isWindows) npmPackage?.let(ResolvedInstall::Npm) else asCommand
        }
    }

    private companion object {
        const val NPM_GLOBAL_PREFIX = "npm install -g "
    }
}

// NOTE: an `AIAssistantConfig` @Serializable type used to live here, with its own getCommand /
// getYoloFlag / buildFullCommand. It had no callers anywhere: the type settings actually persists is
// AIAssistantConfigData, and the launch line is built by ToolCommandProvider.launchCommandText. It
// was a third copy of the auto-mode rules kept in step only by a comment — the same shape of
// duplication that let OpenCode's invalid `--auto-approve` survive. Removed along with
// AIAssistants.defaultConfigs(), its only reference.

/**
 * Constants for AI assistant IDs to avoid magic strings.
 */
object AIAssistantIds {
    // AI Coding Assistants
    const val CLAUDE_CODE = "claude-code"
    const val CODEX = "codex"
    const val GEMINI_CLI = "gemini-cli"
    const val OPENCODE = "opencode"
    const val KIMI_CODE = "kimi-code"
    const val GROK_BUILD = "grok-build"
    const val HERMES = "hermes"
    const val OPENCLAW = "openclaw"

    // Local Model Runtimes
    const val OLLAMA = "ollama"

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
    val ALL_AI_ASSISTANTS = setOf(
        CLAUDE_CODE, GEMINI_CLI, CODEX, OPENCODE, KIMI_CODE, GROK_BUILD, HERMES, OPENCLAW
    )

    /**
     * All local model runtime IDs.
     */
    val ALL_LOCAL_MODEL_RUNTIMES = setOf(OLLAMA)

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
    // AI coding assistants, declared open-source-first (see OpennessTier). Menus, settings, and
    // onboarding render AI_ASSISTANTS_OSS_FIRST, which re-sorts by tier regardless of this order —
    // but keeping the declaration in the same order means a call site that misses the sorted
    // accessor still shows the open stack first.
    val BUILTIN: List<AIAssistantDefinition> = listOf(
        // --- Tier 1: open-source client, runs on local/open-weight models ---
        AIAssistantDefinition(
            id = AIAssistantIds.OPENCODE,
            displayName = "OpenCode",
            command = "opencode",
            // Auto mode comes from an env var, not a flag — opencode's TUI accepts NO
            // auto-approve argument. Verified against opencode 1.15.0 by probing the installed
            // binary; the TUI validates its arguments strictly, so all three candidates print the
            // usage banner and never start:
            //   --auto-approve                 was our value here; not a flag in any form
            //   --dangerously-skip-permissions valid ONLY for `opencode run` (headless one-shot)
            //   --auto                         claimed by opencode.ai/docs/permissions, but
            //                                  rejected by 1.15.0 — the docs are ahead of or
            //                                  diverged from the shipped CLI
            // A known-valid flag (--pure) starts the TUI and blocks instead of printing the
            // banner, which is how those three were confirmed rejected rather than accepted.
            // OPENCODE_PERMISSION is the documented equivalent — see OPENCODE_YOLO_ENV.
            yoloFlag = "",
            yoloEnv = OPENCODE_YOLO_ENV,
            yoloLabel = "Auto",
            installCommand = "curl -fsSL https://opencode.ai/install | bash",
            installKind = InstallKind.SHELL_SCRIPT,
            npmInstallCommand = "npm install -g opencode-ai",
            websiteUrl = "https://github.com/anomalyco/opencode",
            description = "Open-source AI coding assistant",
            license = "MIT",
            sourceUrl = "https://github.com/anomalyco/opencode",
            openSource = true,
            localModels = true,
            extraDetectPaths = listOf(
                "~/.opencode/bin/opencode",
                "~/.local/bin/opencode",
                "/usr/local/bin/opencode"
            )
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.KIMI_CODE,
            displayName = "Kimi Code CLI",
            command = "kimi",
            // Verified against a live kimi 0.30.0 (`kimi --help`): "-y, --yolo  Auto-approve regular
            // tool calls; the agent may still ask questions." (`--auto` is the stronger fully
            // autonomous mode.) There is no `kimi mcp` subcommand at all, which is why this target
            // writes its config in-process — see McpAttachTarget.KIMI_CODE.
            //
            // On the two-projects-one-binary hazard: the installer resolves it upstream. A
            // pre-existing Python `kimi-cli` shim on PATH is renamed to `kimi-legacy`, so `kimi` is
            // unambiguously kimi-code after install.
            yoloFlag = "--yolo",
            yoloLabel = "YOLO",
            // Single self-contained binary — no Node required, unlike the npm-shipped CLIs.
            // Installs to ~/.kimi-code/bin/kimi (confirmed) and prepends it to PATH.
            installCommand = "curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash",
            installKind = InstallKind.SHELL_SCRIPT,
            npmInstallCommand = "npm install -g @moonshot-ai/kimi-code",
            websiteUrl = "https://github.com/MoonshotAI/kimi-code",
            description = "Moonshot AI's open-source terminal coding agent",
            license = "MIT",
            sourceUrl = "https://github.com/MoonshotAI/kimi-code",
            openSource = true,
            localModels = true,
            extraDetectPaths = listOf(
                "~/.kimi-code/bin/kimi",
                "~/.local/bin/kimi",
                "/usr/local/bin/kimi"
            )
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.OPENCLAW,
            displayName = "OpenClaw",
            command = "openclaw",
            // Bare `openclaw` prints help — `tui` is the interactive terminal session (verified
            // against 2026.3.13). It connects to OpenClaw's Gateway rather than running the agent
            // in-process, so the gateway has to be up; the CLI says so when it isn't.
            launchArgs = "tui",
            // OpenClaw's YOLO is normally PERSISTENT config (`exec-policy preset yolo` rewrites
            // tools.exec + the approvals defaults), which would loosen the user's exec policy
            // everywhere, not just in this terminal. This uses the documented SESSION-scoped
            // override delivered through `tui --message` instead, so auto mode behaves like the
            // other CLIs' per-session flags and ~/.openclaw/openclaw.json is left alone.
            // NOTE: docs-only — unverifiable on this machine, whose install (2026.3.13) predates
            // both `mcp` and `exec-policy` (npm latest is 2026.7.1-2). Needs a live-gateway check.
            yoloFlag = "--message '/exec security=full ask=off'",
            yoloLabel = "Auto",
            installCommand = "curl -fsSL https://openclaw.ai/install.sh | bash",
            installKind = InstallKind.SHELL_SCRIPT,
            npmInstallCommand = "npm install -g openclaw@latest",
            websiteUrl = "https://github.com/openclaw/openclaw",
            description = "Personal assistant agent - gateway-based, runs on local models too",
            // Standard 24-line MIT; GitHub reports NOASSERTION only because the file ends with a
            // pointer to THIRD_PARTY_NOTICES.md.
            license = "MIT",
            sourceUrl = "https://github.com/openclaw/openclaw",
            openSource = true,
            localModels = true,
            extraDetectPaths = listOf(
                "/opt/homebrew/bin/openclaw",
                "~/.local/bin/openclaw",
                "/usr/local/bin/openclaw"
            )
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.HERMES,
            displayName = "Hermes Agent",
            command = "hermes",
            yoloFlag = "--yolo",
            yoloLabel = "YOLO",
            installCommand = "curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash",
            // No npm package upstream, and the installer targets Linux/macOS/WSL2 — so this entry
            // resolves to "can't install" on native Windows rather than to a broken command.
            installKind = InstallKind.SHELL_SCRIPT,
            npmInstallCommand = null,
            websiteUrl = "https://github.com/NousResearch/hermes-agent",
            description = "Nous Research's agent - 20+ providers, local models included",
            license = "MIT",
            sourceUrl = "https://github.com/NousResearch/hermes-agent",
            openSource = true,
            localModels = true,
            extraDetectPaths = listOf(
                "~/.local/bin/hermes",
                "~/.hermes/bin/hermes",
                "/usr/local/bin/hermes"
            )
        ),
        // --- Tier 2: open-source client, vendor-hosted models ---
        AIAssistantDefinition(
            id = AIAssistantIds.GROK_BUILD,
            displayName = "Grok Build",
            command = "grok",
            // Verified against grok 0.2.3 --help: "--always-approve  Auto-approve all tool executions".
            yoloFlag = "--always-approve",
            yoloLabel = "Auto Approve",
            installCommand = "curl -fsSL https://x.ai/cli/install.sh | bash",
            installKind = InstallKind.SHELL_SCRIPT,
            npmInstallCommand = "npm install -g @xai-official/grok",
            websiteUrl = "https://github.com/xai-org/grok-build",
            description = "xAI's coding agent - Apache-2.0 client, xAI models",
            license = "Apache-2.0",
            sourceUrl = "https://github.com/xai-org/grok-build",
            openSource = true,
            localModels = false,
            extraDetectPaths = listOf(
                "~/.grok/bin/grok",
                "~/.local/bin/grok",
                "/usr/local/bin/grok"
            )
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.CODEX,
            displayName = "Codex (OpenAI)",
            command = "codex",
            // Auto mode should let Codex operate across the full filesystem without sandbox restrictions.
            yoloFlag = "--sandbox danger-full-access",
            yoloLabel = "Full Auto",
            installCommand = "npm install -g @openai/codex",
            installKind = InstallKind.NPM,
            npmInstallCommand = "npm install -g @openai/codex",
            websiteUrl = "https://github.com/openai/codex",
            description = "OpenAI's coding assistant",
            license = "Apache-2.0",
            sourceUrl = "https://github.com/openai/codex",
            openSource = true,
            localModels = false,
            extraDetectPaths = listOf(
                "~/.local/bin/codex",
                "/usr/local/bin/codex"
            )
        ),
        AIAssistantDefinition(
            id = AIAssistantIds.GEMINI_CLI,
            displayName = "Gemini CLI",
            command = "gemini",
            yoloFlag = "-y",
            yoloLabel = "Auto",
            installCommand = "npm install -g @google/gemini-cli",
            installKind = InstallKind.NPM,
            npmInstallCommand = "npm install -g @google/gemini-cli",
            websiteUrl = "https://github.com/google-gemini/gemini-cli",
            description = "Google's AI coding assistant",
            license = "Apache-2.0",
            sourceUrl = "https://github.com/google-gemini/gemini-cli",
            openSource = true,
            localModels = false,
            extraDetectPaths = listOf(
                "~/.local/bin/gemini",
                "/usr/local/bin/gemini"
            )
        ),
        // --- Tier 3: proprietary client and models ---
        AIAssistantDefinition(
            id = AIAssistantIds.CLAUDE_CODE,
            displayName = "Claude Code",
            command = "claude",
            yoloFlag = "--dangerously-skip-permissions",
            yoloLabel = "Auto Mode",
            installCommand = "curl -fsSL https://claude.ai/install.sh | bash",
            // Windows resolves to the npm package, which is how onboarding installed Claude Code on
            // every platform before the registry took over — no Windows regression.
            installKind = InstallKind.SHELL_SCRIPT,
            npmInstallCommand = "npm install -g @anthropic-ai/claude-code",
            websiteUrl = "https://docs.anthropic.com/en/docs/claude-code",
            description = "Anthropic's AI coding assistant",
            license = "",
            sourceUrl = "",
            openSource = false,
            localModels = false,
            extraDetectPaths = listOf(
                "~/.claude/local/claude",
                "~/.local/bin/claude",
                "/usr/local/bin/claude"
            )
        ),
        // --- Local model runtimes ---
        AIAssistantDefinition(
            id = AIAssistantIds.OLLAMA,
            displayName = "Ollama",
            command = "ollama",
            category = ToolCategory.LOCAL_MODEL_RUNTIME,
            // Not an agent — there is nothing to auto-approve. Launching is handled by the
            // Ollama submenu (run a model / start the server), not the generic launch path.
            yoloFlag = "",
            yoloLabel = "",
            installCommand = ToolCommandProvider.getOllamaInstallCommand(),
            npmInstallCommand = null,
            websiteUrl = "https://github.com/ollama/ollama",
            description = "Run open-weight models locally - the offline backend for the agents above",
            license = "MIT",
            sourceUrl = "https://github.com/ollama/ollama",
            openSource = true,
            localModels = true,
            extraDetectPaths = listOf(
                "/usr/local/bin/ollama",
                "/opt/homebrew/bin/ollama",
                "/Applications/Ollama.app/Contents/Resources/ollama"
            )
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
            description = "GitHub's official CLI",
            extraDetectPaths = listOf(
                "/usr/bin/gh",
                "/usr/local/bin/gh",
                "/opt/homebrew/bin/gh",
                "~/.local/bin/gh"
            )
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
            description = "Distributed version control system",
            extraDetectPaths = listOf(
                "/usr/bin/git",
                "/usr/local/bin/git",
                "/opt/homebrew/bin/git"
            )
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
            description = "Cross-shell prompt customization",
            extraDetectPaths = listOf(
                "/usr/bin/starship",
                "/usr/local/bin/starship",
                "/opt/homebrew/bin/starship",
                "~/.cargo/bin/starship"  // Cargo install location
            )
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
            description = "The missing package manager for macOS and Linux",
            extraDetectPaths = listOf(
                "/opt/homebrew/bin/brew",              // M1/M2 macOS
                "/usr/local/bin/brew",                 // Intel macOS
                "/home/linuxbrew/.linuxbrew/bin/brew"  // Linux
            )
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
            description = "Container platform for building and running applications",
            extraDetectPaths = listOf(
                "/usr/local/bin/docker",
                "/usr/bin/docker",
                "/Applications/Docker.app/Contents/Resources/bin/docker"  // macOS Docker Desktop
            )
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
            description = "Kubernetes command-line tool",
            extraDetectPaths = listOf(
                "/usr/local/bin/kubectl",
                "/usr/bin/kubectl",
                "~/.local/bin/kubectl"
            )
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
            description = "Rust programming language compiler",
            // The old path table keyed these off "rustc"/"cargo", but this entry's id is "rust",
            // so rustc never got its specific path. Per-definition paths fix that by construction.
            extraDetectPaths = listOf("~/.cargo/bin/rustc")
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
            description = "Rust package manager and build tool",
            extraDetectPaths = listOf("~/.cargo/bin/cargo")
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
            description = "Terminal multiplexer for managing multiple terminal sessions",
            extraDetectPaths = listOf(
                "/usr/local/bin/tmux",
                "/usr/bin/tmux",
                "/opt/homebrew/bin/tmux"
            )
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
            description = "Fuzzy finder for command-line",
            extraDetectPaths = listOf(
                "/usr/local/bin/fzf",
                "/usr/bin/fzf",
                "/opt/homebrew/bin/fzf",
                "~/.local/bin/fzf",
                "~/.fzf/bin/fzf"
            )
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
     * AI coding assistants ordered open-source-first: fully-open stacks, then open clients on
     * vendor models, then proprietary; alphabetical within a tier. This is what the AI menu,
     * the settings list, and the onboarding step render.
     *
     * Custom assistants are unranked (no license metadata to rank them by), so they sort into the
     * PROPRIETARY tier by default — a custom entry can opt into a better tier by declaring
     * [AIAssistantDefinition.openSource].
     */
    val AI_ASSISTANTS_OSS_FIRST: List<AIAssistantDefinition>
        get() = AI_ASSISTANTS.sortedWith(
            compareBy({ it.openness.ordinal }, { it.displayName })
        )

    /**
     * Only local model runtimes (ollama). Kept out of [AI_ASSISTANTS] because they serve models
     * rather than acting as agents — they have no auto-approve mode and nothing to attach MCP to.
     */
    val LOCAL_MODEL_RUNTIMES: List<AIAssistantDefinition>
        get() = ALL.filter { it.category == ToolCategory.LOCAL_MODEL_RUNTIME }

    /**
     * What the onboarding wizard ticks by default: the fully-open stack ([OpennessTier.OPEN_STACK])
     * only. Selecting every registered assistant would install seven CLIs on a first run, and
     * defaulting to the vendor-account ones would contradict the open-source-first ordering the
     * same screen presents.
     */
    val DEFAULT_ONBOARDING_SELECTION: Set<String>
        get() = AI_ASSISTANTS.filter { it.openness == OpennessTier.OPEN_STACK }
            .map { it.id }
            .toSet()

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
}
