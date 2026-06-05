package ai.rever.bossterm.compose.share

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The public URL cloudflared prints for a quick tunnel. */
private val URL_RE = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")

/** cloudflared logs this once an edge connection is live → the tunnel is actually routable. */
private val READY_RE = Regex("Registered tunnel connection")

/**
 * Remote-reach via **Cloudflare Quick Tunnel** (issue #276) — the zero-config public
 * sharing option, an alternative to Tailscale. Shells out to the `cloudflared` CLI:
 *
 *     cloudflared tunnel --no-autoupdate --url http://127.0.0.1:<port>
 *
 * This opens an *outbound* connection to Cloudflare's edge (so it traverses NAT with
 * no port-forwarding) and Cloudflare hands back a public `https://<random>.trycloudflare.com`
 * URL with **no account, no DNS, no config** and TLS included. We operate nothing.
 *
 * Unlike `tailscale serve` (which configures the daemon and exits), `cloudflared` is a
 * **long-lived process**: the tunnel lives only as long as the process. So [start]
 * returns the [Process] for the caller to hold and kill on teardown, and [awaitUrl]
 * parses the assigned URL from its output.
 *
 * Caveats (inherent to quick tunnels): the URL is ephemeral (new one each run) and
 * best-effort (no uptime SLA — Cloudflare positions it for testing/dev).
 */
object CloudflaredExposer {

    private val log = LoggerFactory.getLogger(CloudflaredExposer::class.java)

    // "cloudflared" first (honors PATH), then typical Homebrew locations.
    private val candidates = listOf("cloudflared", "/opt/homebrew/bin/cloudflared", "/usr/local/bin/cloudflared")
    private fun bin(): String? = candidates.firstOrNull { runCmd(listOf(it, "--version"), 5) != null }

    /** True if a working `cloudflared` CLI is present. Blocking — call off the UI thread. */
    fun isInstalled(): Boolean = bin() != null

    // Common Homebrew locations (Apple-silicon, Intel, then PATH).
    private val brewCandidates = listOf("/opt/homebrew/bin/brew", "/usr/local/bin/brew", "brew")
    private fun brewBin(): String? = brewCandidates.firstOrNull { runCmd(listOf(it, "--version"), 5) != null }

    /** True if Homebrew is available to auto-install cloudflared. Blocking. */
    fun brewAvailable(): Boolean = brewBin() != null

    /**
     * Install cloudflared via `brew install cloudflared`. Blocking and slow (downloads) —
     * call off the UI thread. Returns true on success (or if it's already installed).
     */
    fun brewInstall(): Boolean {
        val brew = brewBin() ?: run { log.warn("Homebrew not found; cannot install cloudflared"); return false }
        log.info("Installing cloudflared: {} install cloudflared …", brew)
        val ok = runCmd(listOf(brew, "install", "cloudflared"), 600) != null
        log.info("`brew install cloudflared` {}", if (ok) "succeeded" else "failed")
        return ok || isInstalled()
    }

    /**
     * Start a quick tunnel to `127.0.0.1:`[port]. Returns a [QuickTunnel] handle wrapping
     * the **long-lived** process (caller keeps it and [QuickTunnel.destroy]s it on teardown),
     * or null if the CLI is missing / failed to spawn.
     */
    fun start(port: Int): QuickTunnel? {
        val b = bin() ?: run { log.warn("cloudflared not found; cannot start quick tunnel"); return null }
        return try {
            // `--no-autoupdate` is a GLOBAL flag — it must precede the `tunnel` subcommand,
            // or cloudflared rejects/ignores it (and an auto-update mid-session would restart
            // the process and drop the tunnel).
            val proc = ProcessBuilder(b, "--no-autoupdate", "tunnel", "--url", "http://127.0.0.1:$port")
                .redirectErrorStream(true)
                .start()
                .also { runCatching { it.outputStream.close() } } // no stdin needed
            QuickTunnel(proc)
        } catch (e: Exception) {
            log.warn("failed to start cloudflared: {}", e.message)
            null
        }
    }

    /**
     * A running quick tunnel. One daemon thread drains cloudflared's output once, completing
     * [awaitUrl] when the public URL is printed and [awaitReady] when an edge connection
     * registers — the point at which the URL actually routes to us instead of serving a
     * Cloudflare "tunnel error" page. Draining continues so the process never stalls on a
     * full pipe.
     */
    class QuickTunnel internal constructor(val process: Process) {
        private val urlFuture = CompletableFuture<String?>()
        private val readyFuture = CompletableFuture<Boolean>()

        init {
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!urlFuture.isDone) URL_RE.find(line)?.value?.let { urlFuture.complete(it) }
                        if (!readyFuture.isDone && READY_RE.containsMatchIn(line)) readyFuture.complete(true)
                    }
                }
                urlFuture.complete(null)    // EOF without a URL
                readyFuture.complete(false) // EOF without registering a connection
            }.apply { isDaemon = true; name = "cloudflared-reader"; start() }
        }

        /** The assigned `*.trycloudflare.com` URL, or null on timeout / early exit. */
        fun awaitUrl(timeoutMs: Long = 30_000): String? =
            runCatching { urlFuture.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

        /**
         * True once cloudflared registers an edge connection (the tunnel is routable).
         * Read from cloudflared's own output, so it works even when this host can't resolve
         * `*.trycloudflare.com` itself (e.g. Tailscale MagicDNS returns NXDOMAIN) — probing
         * the public URL from the host would falsely fail in that case.
         */
        fun awaitReady(timeoutMs: Long = 20_000): Boolean =
            runCatching { readyFuture.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

        /** Kill the tunnel (ends the public URL). */
        fun destroy() { runCatching { process.destroyForcibly() } }
    }

    /** Run a short-lived command; return stdout on exit 0, else null. Bounded by [timeoutSec]. */
    private fun runCmd(cmd: List<String>, timeoutSec: Long): String? {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            runCatching { p.outputStream.close() }
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
