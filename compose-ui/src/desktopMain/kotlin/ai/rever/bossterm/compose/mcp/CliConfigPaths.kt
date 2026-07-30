package ai.rever.bossterm.compose.mcp

import java.io.File

/**
 * Where each AI CLI keeps the config file that carries its MCP server declarations.
 *
 * One place for these paths so the writer ([McpAttachTarget.attachInProcess]) and the reader
 * ([McpRegistrationScanner]) can't disagree — a mismatch there looks like "attach succeeded but
 * the status pill says it isn't attached", which is a confusing bug to chase.
 *
 * Each CLI supports an env var that relocates its whole config directory. Those are honored, but
 * **only when [home] is the real user home**: tests pass a temp directory, and a developer with
 * `KIMI_CODE_HOME` exported would otherwise have their actual config rewritten by a test run.
 */
internal object CliConfigPaths {

    /** Kimi Code CLI — `~/.kimi-code/mcp.json`, or `$KIMI_CODE_HOME/mcp.json`. */
    fun kimiMcpJson(home: File): File = resolve(home, "KIMI_CODE_HOME", ".kimi-code", "mcp.json")

    /** Grok Build — `~/.grok/config.toml`, or `$GROK_HOME/config.toml`. */
    fun grokConfigToml(home: File): File = resolve(home, "GROK_HOME", ".grok", "config.toml")

    /** Hermes Agent — `~/.hermes/config.yaml`, or `$HERMES_HOME/config.yaml`. */
    fun hermesConfigYaml(home: File): File = resolve(home, "HERMES_HOME", ".hermes", "config.yaml")

    /** OpenCode — `~/.config/opencode/opencode.json`, or `$OPENCODE_CONFIG` (the file itself). */
    fun opencodeConfigJson(home: File): File {
        if (isRealUserHome(home)) {
            System.getenv("OPENCODE_CONFIG")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        }
        return File(File(home, ".config/opencode"), "opencode.json")
    }

    /**
     * OpenClaw — `~/.openclaw/openclaw.json` (path confirmed via `openclaw config file`), or
     * `$OPENCLAW_CONFIG_PATH`, which points at the file itself rather than a directory.
     */
    fun openclawConfigJson(home: File): File {
        if (isRealUserHome(home)) {
            System.getenv("OPENCLAW_CONFIG_PATH")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        }
        return File(File(home, ".openclaw"), "openclaw.json")
    }

    private fun resolve(home: File, envVar: String, dirName: String, fileName: String): File {
        if (isRealUserHome(home)) {
            System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { return File(it, fileName) }
        }
        return File(File(home, dirName), fileName)
    }

    private fun isRealUserHome(home: File): Boolean {
        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return false
        return runCatching { File(userHome).canonicalFile == home.canonicalFile }.getOrDefault(false)
    }
}
