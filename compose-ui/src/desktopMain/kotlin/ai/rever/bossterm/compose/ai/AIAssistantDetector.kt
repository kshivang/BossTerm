package ai.rever.bossterm.compose.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Detects which AI coding assistants are installed on the system.
 *
 * Detection is performed on-demand when the context menu opens, not via polling.
 *
 * Detection strategy (in order):
 * 1. Direct path check in common locations
 * 2. `which` command
 * 3. Shell-sourced `which` (to pick up PATH modifications from .bashrc/.zshrc)
 */
class AIAssistantDetector {

    private val _installationStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * Reactive state of installation status for each assistant.
     * Key is the assistant ID, value is true if installed.
     */
    val installationStatus: StateFlow<Map<String, Boolean>> = _installationStatus.asStateFlow()

    /**
     * Check if an assistant is installed (uses cached status from last detectAll).
     *
     * @param assistantId The assistant ID to check
     * @return true if installed, false or unknown if not in cache
     */
    fun isInstalled(assistantId: String): Boolean {
        return installationStatus.value[assistantId] == true
    }

    /**
     * Check if an assistant is confirmed NOT installed.
     * Returns true only if detection has run AND explicitly found the assistant is not installed.
     * Use this for command interception to avoid false positives when detection hasn't run yet.
     *
     * @param assistantId The assistant ID to check
     * @return true only if detection confirmed assistant is not installed, false otherwise
     */
    fun isConfirmedNotInstalled(assistantId: String): Boolean {
        return installationStatus.value[assistantId] == false
    }

    private val home = System.getProperty("user.home")

    /**
     * Detect installation status for all registered AI assistants.
     *
     * @return Map of assistant ID to installation status
     */
    suspend fun detectAll(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = AIAssistants.ALL.associate { assistant ->
            assistant.id to detectSingle(assistant)
        }
        _installationStatus.value = results
        results
    }

    /**
     * Detect if a single AI assistant is installed.
     *
     * @param assistant The assistant to check
     * @return true if installed, false otherwise
     */
    suspend fun detectSingle(assistant: AIAssistantDefinition): Boolean = withContext(Dispatchers.IO) {
        checkInstallation(assistant.id, assistant.command)
    }

    /**
     * Check if a command is installed using multiple detection strategies.
     *
     * @param assistantId The assistant ID for assistant-specific path checks
     * @param command The command name to check
     * @return true if the command is found, false otherwise
     */
    private fun checkInstallation(assistantId: String, command: String): Boolean {
        // Strategy 1: Check assistant-specific installation paths
        val specificPaths = getAssistantSpecificPaths(assistantId, command)
        for (path in specificPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return true
            }
        }

        // Strategy 2: Check common paths directly
        val commonPaths = listOf(
            "/usr/local/bin",
            "/usr/bin",
            "/opt/homebrew/bin",
            "$home/.local/bin",
            "$home/.npm-global/bin"
        )
        for (path in commonPaths) {
            val file = File(path, command)
            if (file.exists() && file.canExecute()) {
                return true
            }
        }

        // Strategy 3: Check npm paths with nvm glob pattern support
        val nvmBase = File("$home/.nvm/versions/node")
        if (nvmBase.exists() && nvmBase.isDirectory) {
            nvmBase.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val binPath = File(dir, "bin/$command")
                    if (binPath.exists() && binPath.canExecute()) {
                        return true
                    }
                }
            }
        }

        // Strategy 4: Use `which` command
        if (runCommand("which", command)) {
            return true
        }

        // Strategy 5: Shell-sourced which (picks up PATH from shell config)
        val shell = System.getenv("SHELL") ?: "/bin/bash"
        if (runCommand(shell, "-l", "-c", "which $command")) {
            return true
        }

        return false
    }

    /**
     * Get assistant-specific installation paths.
     */
    private fun getAssistantSpecificPaths(assistantId: String, command: String): List<String> {
        return when (assistantId) {
            "opencode" -> listOf(
                "$home/.opencode/bin/opencode",
                "$home/.local/bin/opencode",
                "/usr/local/bin/opencode"
            )
            "claude-code" -> listOf(
                "$home/.claude/local/claude",
                "$home/.local/bin/claude",
                "/usr/local/bin/claude"
            )
            "codex" -> listOf(
                "$home/.local/bin/codex",
                "/usr/local/bin/codex"
            )
            "gemini-cli" -> listOf(
                "$home/.local/bin/gemini",
                "/usr/local/bin/gemini"
            )
            "gh" -> listOf(
                "/usr/bin/gh",
                "/usr/local/bin/gh",
                "/opt/homebrew/bin/gh",
                "$home/.local/bin/gh"
            )
            "git" -> listOf(
                "/usr/bin/git",
                "/usr/local/bin/git",
                "/opt/homebrew/bin/git"
            )
            "starship" -> listOf(
                "/usr/bin/starship",
                "/usr/local/bin/starship",
                "/opt/homebrew/bin/starship",
                "$home/.cargo/bin/starship"  // Cargo install location
            )
            else -> emptyList()
        }
    }

    /**
     * Run a command and check if it exits successfully.
     *
     * @param args Command and arguments
     * @return true if command exits with code 0, false otherwise
     */
    private fun runCommand(vararg args: String): Boolean {
        val process: Process
        try {
            process = ProcessBuilder(*args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            return false
        }

        // Process started successfully - ensure cleanup in all cases
        return try {
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            completed && process.exitValue() == 0
        } catch (e: Exception) {
            false
        } finally {
            // Ensure process is terminated
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}
