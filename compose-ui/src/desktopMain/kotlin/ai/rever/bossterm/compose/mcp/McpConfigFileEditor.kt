package ai.rever.bossterm.compose.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    /**
     * Write [root] to [file] via a same-directory temp file + atomic rename, so a crash mid-write
     * can't truncate someone's config.
     *
     * Preserves the existing file's permissions. `createTempFile` makes an owner-only (0600) file,
     * and moving that over the target would silently tighten the mode of a config the user may have
     * deliberately made group-readable — a surprising side effect of "add an MCP server".
     */
    private fun write(file: File, root: JsonObject): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val existingPermissions = runCatching {
                if (file.isFile) Files.getPosixFilePermissions(file.toPath()) else null
            }.getOrNull()

            val temp = File.createTempFile("mcp-config", ".json", file.parentFile)
            try {
                temp.writeText(json.encodeToString(JsonObject.serializer(), root) + "\n")
                existingPermissions?.let {
                    runCatching { Files.setPosixFilePermissions(temp.toPath(), it) }
                }
                try {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    // Some filesystems (and Windows when the target is open) refuse an atomic move.
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    /**
     * A remote server entry. The CLIs agree on `url` and disagree on everything else, so the caller
     * spells out the shape its CLI's parser actually accepts:
     *  - Kimi Code wants `transport: "sse"` (its zod schema is a discriminated union on `transport`,
     *    and a bare `url` would default to `http`, not sse).
     *  - OpenCode wants `type: "remote"` plus `enabled: true`.
     */
    fun remoteEntry(
        url: String,
        transport: String? = null,
        type: String? = null,
        enabled: Boolean? = null
    ): JsonObject = buildJsonObject {
        if (type != null) put("type", type)
        put("url", url)
        if (transport != null) put("transport", transport)
        if (enabled != null) put("enabled", enabled)
    }
}
