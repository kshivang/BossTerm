package ai.rever.bossterm.compose.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Detects which AI CLIs already carry a registration for this embedder's MCP
 * server by reading their config files directly (read-only).
 *
 * Why: the persisted attached-targets set ([McpTerminalRegistry.attachedTargets])
 * can drift out of sync with reality — historically a single failed quiet
 * reattach dropped the target from settings, after which startup auto-reattach
 * never ran again and the CLI's registered endpoint froze on whatever port it
 * last saw. The CLI config file is the canonical record; scanning it lets the
 * manager re-adopt those registrations on startup so the port they point at is
 * rewritten to the actually-bound one with zero user interaction.
 *
 * Matching is deliberately conservative: an entry counts only when it has the
 * exact server name AND a loopback URL — a user's hand-written entry pointing
 * at a remote host is never adopted (we must not rewrite it to 127.0.0.1).
 */
internal object McpRegistrationScanner {

    private val log = LoggerFactory.getLogger(McpRegistrationScanner::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    /** All targets whose config registers a loopback server named [serverName]. */
    fun scan(
        serverName: String,
        home: File = File(System.getProperty("user.home"))
    ): Set<McpAttachTarget> {
        val found = mutableSetOf<McpAttachTarget>()
        for (target in McpAttachTarget.entries) {
            val registered = try {
                when (target) {
                    McpAttachTarget.CLAUDE_CODE ->
                        jsonHasLoopbackEntry(File(home, ".claude.json"), "mcpServers", serverName)
                    McpAttachTarget.GEMINI ->
                        jsonHasLoopbackEntry(File(home, ".gemini/settings.json"), "mcpServers", serverName)
                    McpAttachTarget.OPENCODE ->
                        jsonHasLoopbackEntry(File(home, ".config/opencode/opencode.json"), "mcp", serverName)
                    McpAttachTarget.CODEX ->
                        codexTomlHasLoopbackEntry(File(home, ".codex/config.toml"), serverName)
                }
            } catch (t: Throwable) {
                // A malformed config file must never break startup; just skip it.
                log.warn("Could not scan {} config: {}", target.displayName, t.message)
                false
            }
            if (registered) found += target
        }
        return found
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
