package ai.rever.bossterm.compose.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Reads the AI CLIs' own config files (read-only) to report whether each one
 * currently carries a registration for this embedder's MCP server.
 *
 * Why: the persisted attached-targets set ([McpTerminalRegistry.attachedTargets])
 * can drift out of sync with reality in both directions — historically a
 * single failed quiet reattach dropped the target from settings (freezing the
 * CLI's registered endpoint on a stale port), and conversely a user who ran
 * `claude mcp remove` by hand kept getting the entry resurrected by the next
 * bind's auto-reattach. The CLI config file is the canonical record; the
 * manager reconciles the persisted set against this scan on startup — adopting
 * entries we're missing, pruning ones the user removed.
 *
 * Matching is deliberately conservative: an entry counts as PRESENT only when
 * it has the exact server name AND a loopback URL. A same-named entry pointing
 * at a remote host reports ABSENT — we neither adopt it nor keep auto-rewriting
 * it to 127.0.0.1; it belongs to the user.
 */
internal object McpRegistrationScanner {

    private val log = LoggerFactory.getLogger(McpRegistrationScanner::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Per-CLI registration state. UNKNOWN (config unreadable/malformed right
     * now) is distinct from ABSENT on purpose: the manager prunes persisted
     * targets only on a clean ABSENT — pruning on a transient read failure
     * would silently disable auto-reattach, the exact bug this file exists
     * to prevent.
     */
    enum class Presence { PRESENT, ABSENT, UNKNOWN }

    /** Registration state of a loopback server named [serverName], per CLI. */
    fun scan(
        serverName: String,
        home: File = File(System.getProperty("user.home"))
    ): Map<McpAttachTarget, Presence> =
        McpAttachTarget.entries.associateWith { target ->
            try {
                val registered = when (target) {
                    McpAttachTarget.CLAUDE_CODE ->
                        jsonHasLoopbackEntry(File(home, ".claude.json"), "mcpServers", serverName)
                    McpAttachTarget.GEMINI ->
                        jsonHasLoopbackEntry(File(home, ".gemini/settings.json"), "mcpServers", serverName)
                    McpAttachTarget.OPENCODE ->
                        jsonHasLoopbackEntry(File(home, ".config/opencode/opencode.json"), "mcp", serverName)
                    McpAttachTarget.CODEX ->
                        codexTomlHasLoopbackEntry(File(home, ".codex/config.toml"), serverName)
                }
                if (registered) Presence.PRESENT else Presence.ABSENT
            } catch (t: Throwable) {
                log.warn("Could not scan {} config: {}", target.displayName, t.message)
                Presence.UNKNOWN
            }
        }

    /**
     * True when [file] parses as JSON and `<containerKey>.<serverName>` exists
     * with a loopback `url`/`httpUrl`. Gemini writes `httpUrl`, the others
     * `url`; checking both keeps one code path for all three JSON configs.
     */
    internal fun jsonHasLoopbackEntry(file: File, containerKey: String, serverName: String): Boolean {
        if (!file.isFile) return false
        val text = file.readText()
        if (text.isBlank()) return false
        val root = json.parseToJsonElement(text).jsonObject
        val entry = root[containerKey]?.jsonObject?.get(serverName)?.jsonObject ?: return false
        val url = (entry["url"] ?: entry["httpUrl"])?.jsonPrimitive?.content ?: return false
        return isLoopbackUrl(url)
    }

    /**
     * Minimal TOML scan for `[mcp_servers.<serverName>]` followed by a
     * loopback `url = "…"` before the next section header. Codex's config is
     * machine-written with this exact shape; a full TOML parser isn't worth
     * the dependency.
     */
    internal fun codexTomlHasLoopbackEntry(file: File, serverName: String): Boolean {
        if (!file.isFile) return false
        var inSection = false
        for (rawLine in file.readLines()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inSection = line == "[mcp_servers.$serverName]" ||
                    line == "[mcp_servers.\"$serverName\"]"
                continue
            }
            if (inSection && line.substringBefore('=').trim() == "url") {
                val raw = line.substringAfter('=').trim()
                // Quoted values end at the closing quote (tolerates trailing
                // inline comments); unquoted ones are cut at any '#'.
                val url = if (raw.startsWith("\"")) {
                    raw.drop(1).substringBefore('"')
                } else {
                    raw.substringBefore('#').trim()
                }
                return isLoopbackUrl(url)
            }
        }
        return false
    }

    internal fun isLoopbackUrl(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)
    }
}
