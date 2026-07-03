package ai.rever.bossterm.compose.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/**
 * Asks a loopback port whether a live Boss/BossTerm MCP server owns it, and
 * which one. Servers expose `GET /identity` (see BossTermMcpManager) returning
 * `{"serverName": "...", "pid": ...}`.
 *
 * Used by the polite auto-reattach: before an instance rewrites a CLI's
 * registered endpoint to its own port, it probes the port the registration
 * currently points at — a live sibling with the same server name keeps
 * ownership (first-owner-wins-while-alive) instead of the historical
 * last-writer-wins clobbering between e.g. a packaged app and a dev instance.
 *
 * Best-effort by design: any failure (nothing listening, a non-Boss service,
 * an older Boss build without /identity, timeout) returns null, which callers
 * treat as "not a live sibling — safe to claim".
 */
internal object McpInstanceProbe {

    private val log = LoggerFactory.getLogger(McpInstanceProbe::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    internal const val IDENTITY_PATH = "/identity"

    /** Server name of the live MCP instance on [port], or null. */
    fun liveServerName(port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): String? {
        return try {
            // NO_PROXY: a configured system/HTTP proxy must never sit between
            // us and a loopback port — it would answer for (or hang on) an
            // address only this machine can serve, skewing the probe result.
            val connection = URI("http://127.0.0.1:$port$IDENTITY_PATH").toURL()
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.requestMethod = "GET"
                if (connection.responseCode != 200) return null
                // Identity payloads are tiny. Read at most MAX_IDENTITY_CHARS
                // into a fixed buffer and stop — a misbehaving service on the
                // port can stream gigabytes inside the read timeout, and a
                // readText()-then-truncate would have materialized all of it.
                val body = connection.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(MAX_IDENTITY_CHARS)
                    var filled = 0
                    while (filled < buf.size) {
                        val n = reader.read(buf, filled, buf.size - filled)
                        if (n == -1) break
                        filled += n
                    }
                    String(buf, 0, filled)
                }
                json.parseToJsonElement(body).jsonObject["serverName"]
                    ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            log.debug("Identity probe of port {} failed: {}", port, t.message)
            null
        }
    }

    /**
     * Whether an instance bound to [ourPort] should rewrite a CLI's registered
     * entry, given the entry's current default port and the identity of
     * whatever is live there. Pure — the manager supplies the probe result.
     *
     *  - No parseable registered port → rewrite (nothing to be polite to).
     *  - Registered port is ours → rewrite (idempotent refresh).
     *  - A live server with OUR name owns the registered port → skip: that
     *    sibling instance keeps the default; our own terminals reach us via
     *    the injected port env var regardless.
     *  - Anything else (dead port, foreign service, older build without
     *    /identity) → rewrite and claim the default.
     */
    fun shouldRewrite(
        registeredDefaultPort: Int?,
        ourPort: Int,
        ourServerName: String,
        liveOwnerName: String?
    ): Boolean = when {
        registeredDefaultPort == null -> true
        registeredDefaultPort == ourPort -> true
        liveOwnerName == ourServerName -> false
        else -> true
    }

    private const val PROBE_TIMEOUT_MS = 750

    /** Hard cap on how much of the response is ever read into memory. */
    private const val MAX_IDENTITY_CHARS = 4096
}
