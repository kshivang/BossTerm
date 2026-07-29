package ai.rever.bossterm.compose.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Adds/removes an MCP server entry in a CLI's JSON config, in this process.
 *
 * Why this exists: some CLIs only expose an interactive `mcp add` (a TUI prompt), so the attach
 * helper has to edit their config file itself. OpenCode's variant of this problem is solved with a
 * `node -e` one-liner, which is fine *there* because OpenCode ships as an npm package — node is
 * guaranteed wherever `opencode` is. Kimi Code CLI is the opposite case: a single self-contained
 * binary whose whole pitch is "no Node.js setup", so shelling out to node would fail exactly on the
 * machines that installed it the recommended way. Doing the merge on the JVM has no such dependency.
 *
 * Everything unknown in the file is preserved: the config is parsed to a [JsonObject], only the
 * `<containerKey>.<serverName>` path is touched, and the rest is written back as-is. Writes go
 * through a temp file in the same directory and then a rename, so a crash mid-write can't leave the
 * user with a truncated config.
 */
internal object McpConfigFileEditor {

    private val log = LoggerFactory.getLogger(McpConfigFileEditor::class.java)

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
    }

    /**
     * Set `<containerKey>.<serverName>` in [file] to [entry], creating the file and its parent
     * directory when missing. Returns false when the file exists but doesn't parse — a
     * hand-corrupted config is the user's to fix, and overwriting it would lose their other servers.
     */
    fun putServer(
        file: File,
        containerKey: String,
        serverName: String,
        entry: JsonObject
    ): Boolean {
        val root = readRoot(file) ?: return false
        val container = root[containerKey]?.let { existing ->
            runCatching { existing.jsonObject }.getOrElse {
                log.warn("'{}' in {} is not an object — refusing to overwrite", containerKey, file)
                return false
            }
        } ?: JsonObject(emptyMap())

        val updatedContainer = JsonObject(container.toMutableMap().apply { put(serverName, entry) })
        val updatedRoot = JsonObject(root.toMutableMap().apply { put(containerKey, updatedContainer) })
        return write(file, updatedRoot)
    }

    /**
     * Remove `<containerKey>.<serverName>` from [file]. Returns true when the file ends up without
     * that entry — including when it was already absent, so this is idempotent like the CLI
     * `mcp remove` subcommands the other targets use.
     */
    fun removeServer(file: File, containerKey: String, serverName: String): Boolean {
        if (!file.isFile) return true
        val root = readRoot(file) ?: return false
        val container = root[containerKey]?.let { existing ->
            runCatching { existing.jsonObject }.getOrNull()
        } ?: return true
        if (!container.containsKey(serverName)) return true

        val updatedContainer = JsonObject(container.toMutableMap().apply { remove(serverName) })
        val updatedRoot = JsonObject(root.toMutableMap().apply { put(containerKey, updatedContainer) })
        return write(file, updatedRoot)
    }

    /** Parsed contents of [file], an empty object when it's missing/blank, or null when malformed. */
    private fun readRoot(file: File): JsonObject? {
        if (!file.isFile) return JsonObject(emptyMap())
        val text = try {
            file.readText()
        } catch (t: Throwable) {
            log.warn("Could not read {}: {}", file, t.message)
            return null
        }
        if (text.isBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (t: Throwable) {
            log.warn("Could not parse {}: {}", file, t.message)
            null
        }
    }

    /** Write [root] to [file] via a same-directory temp file + rename. */
    private fun write(file: File, root: JsonObject): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val temp = File.createTempFile("mcp-config", ".json", file.parentFile)
            try {
                temp.writeText(json.encodeToString(JsonObject.serializer(), root) + "\n")
                if (!temp.renameTo(file)) {
                    // Cross-device or an existing target Windows won't clobber: fall back to a
                    // plain copy, which is still better than leaving the registration unwritten.
                    temp.copyTo(file, overwrite = true)
                }
                log.info("Wrote MCP registration to {}", file)
                true
            } finally {
                temp.delete()
            }
        } catch (t: Throwable) {
            log.warn("Could not write {}: {}", file, t.message)
            false
        }
    }

    /** A remote (HTTP/SSE) server entry: `{"url": …, "transport": "sse"}` when [transport] is set. */
    fun remoteEntry(url: String, transport: String? = null): JsonObject = buildJsonObject {
        put("url", url)
        if (transport != null) put("transport", transport)
    }
}
