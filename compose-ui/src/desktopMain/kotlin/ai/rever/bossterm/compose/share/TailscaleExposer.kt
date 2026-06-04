package ai.rever.bossterm.compose.share

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Remote-reach via Tailscale (issue #276, Phase 3) — modeled on OpenClaw's
 * `server-tailscale.ts`. Shells out to the `tailscale` CLI to expose the local
 * share-server port over the user's own tailnet:
 *  - **serve**  → reachable by tailnet devices only (private, TLS via Tailscale).
 *  - **funnel** → reachable from the public internet via Tailscale's edge (TLS).
 *
 * No relay we operate; the user's existing Tailscale install does the work. All
 * calls are best-effort with short timeouts — a missing CLI or a failed command
 * logs and returns null, and the caller falls back to the LAN/loopback URL.
 */
object TailscaleExposer {

    private val log = LoggerFactory.getLogger(TailscaleExposer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Common locations; "tailscale" first (honors PATH), then typical macOS/brew paths.
    private val candidates = listOf(
        "tailscale",
        "/usr/local/bin/tailscale",
        "/opt/homebrew/bin/tailscale",
        "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
    )

    /** Resolve a working `tailscale` binary path, or null if none responds. */
    private fun bin(): String? = candidates.firstOrNull { runCmd(listOf(it, "version"), 3) != null }

    /**
     * Expose [port] via `tailscale serve|funnel --bg`. Returns the published base URL
     * (`https://<magic-dns-name>`) on success, or null if the CLI is missing/fails.
     */
    fun enable(mode: String, port: Int): String? {
        val b = bin() ?: run { log.warn("tailscale CLI not found; cannot enable $mode"); return null }
        val sub = if (mode == "funnel") "funnel" else "serve"
        if (runCmd(listOf(b, sub, "--bg", port.toString()), 20) == null) {
            log.warn("`tailscale $sub --bg $port` failed")
            return null
        }
        val dns = magicDnsName(b) ?: run { log.warn("could not resolve tailscale MagicDNS name"); return null }
        val url = "https://$dns"
        log.info("Tailscale {} active for share port {} → {}", sub, port, url)
        return url
    }

    /** Tear down the serve/funnel mapping for the share port. Best-effort. */
    fun disable(mode: String, port: Int) {
        val b = bin() ?: return
        val sub = if (mode == "funnel") "funnel" else "serve"
        runCmd(listOf(b, sub, "--https=443", "off"), 10)
    }

    private fun magicDnsName(b: String): String? {
        val out = runCmd(listOf(b, "status", "--json"), 5) ?: return null
        return runCatching {
            json.parseToJsonElement(out).jsonObject["Self"]
                ?.jsonObject?.get("DNSName")?.jsonPrimitive?.content?.trimEnd('.')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun runCmd(cmd: List<String>, timeoutSec: Long): String? = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else if (p.exitValue() == 0) out else null
    } catch (e: Exception) {
        null
    }
}
