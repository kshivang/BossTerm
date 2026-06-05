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

    /** True if a working `tailscale` CLI/app is present. Blocking — call off the UI thread. */
    fun isInstalled(): Boolean = bin() != null

    // Common Homebrew locations (Apple-silicon, Intel, then PATH).
    private val brewCandidates = listOf("/opt/homebrew/bin/brew", "/usr/local/bin/brew", "brew")
    private fun brewBin(): String? = brewCandidates.firstOrNull { runCmd(listOf(it, "--version"), 5) != null }

    /** True if Homebrew is available to auto-install Tailscale. Blocking. */
    fun brewAvailable(): Boolean = brewBin() != null

    /**
     * Install Tailscale via `brew install tailscale`. Blocking and slow (downloads) —
     * call off the UI thread. Returns true on success (or if it's already installed).
     */
    fun brewInstall(): Boolean {
        val brew = brewBin() ?: run { log.warn("Homebrew not found; cannot install Tailscale"); return false }
        log.info("Installing Tailscale: {} install tailscale …", brew)
        val ok = runCmd(listOf(brew, "install", "tailscale"), 600) != null
        log.info("`brew install tailscale` {}", if (ok) "succeeded" else "failed")
        return ok || isInstalled()
    }

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

    private fun runCmd(cmd: List<String>, timeoutSec: Long): String? {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            // Close the child's stdin so a CLI that prompts for input (e.g. `tailscale
            // serve` asking to enable a feature) gets EOF instead of waiting forever.
            runCatching { p.outputStream.close() }
            // Drain stdout on a daemon thread. The previous code read to EOF *before*
            // waitFor(), so a process that never closes its output (a hung `tailscale
            // serve`) blocked the read indefinitely and the timeout never fired — that
            // is what froze the UI thread for minutes. Reading off-thread lets waitFor()
            // own the deadline and destroy the process if it overruns.
            val sb = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') } }
            }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            reader.join(500)
            if (p.exitValue() == 0) sb.toString() else null
        } catch (e: Exception) {
            null
        }
    }
}
