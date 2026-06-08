package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
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

    /**
     * BossTerm-managed cloudflared, dropped here by [linuxDirectInstall] so we never need
     * root or a package manager. Invoked by absolute path, so it needs no PATH entry.
     */
    private val managedBin: File = File(System.getProperty("user.home"), ".bossterm/bin/cloudflared")

    // "cloudflared" first (honors PATH), then our managed copy, then typical Homebrew locations.
    private fun candidates(): List<String> =
        listOf("cloudflared", managedBin.absolutePath, "/opt/homebrew/bin/cloudflared", "/usr/local/bin/cloudflared")
    private fun bin(): String? = candidates().firstOrNull { runCmd(listOf(it, "--version"), 5) != null }

    /** True if a working `cloudflared` CLI is present. Blocking — call off the UI thread. */
    fun isInstalled(): Boolean = bin() != null

    /**
     * True if we can install cloudflared without the user leaving the app. Blocking.
     * - macOS: via Homebrew if present, else by downloading the notarized binary directly
     *   (see [macDirectInstall]) — so users without Homebrew get the same one-click flow.
     * - Linux: by downloading the static binary directly (see [linuxDirectInstall]).
     * Either way it requires a CPU arch cloudflared ships a binary for.
     */
    fun canAutoInstall(): Boolean = when {
        ShellCustomizationUtils.isMacOS() -> brewAvailable() || macArch() != null
        ShellCustomizationUtils.isLinux() -> linuxArch() != null
        else -> false
    }

    /**
     * One-click install for the current platform. Blocking and slow (downloads) — call off
     * the UI thread. Returns true on success (or if it's already installed). macOS prefers
     * Homebrew when present (managed updates + on PATH); otherwise it downloads directly.
     */
    fun autoInstall(): Boolean = when {
        ShellCustomizationUtils.isMacOS() -> if (brewAvailable()) brewInstall() else macDirectInstall()
        ShellCustomizationUtils.isLinux() -> linuxDirectInstall()
        else -> false
    }

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
     * The cloudflared release we install directly, with per-asset SHA-256.
     *
     * cloudflared publishes no checksums/signatures on its GitHub releases, so transport TLS
     * only guarantees "the bytes the CDN served" — not that they're the bytes Cloudflare built.
     * We close that gap by pinning a known-good hash here at BUILD time (out-of-band from the
     * download channel) and refusing to `chmod +x` / run anything that doesn't match. The
     * hashes were verified against the published artifacts. Bump VERSION + the hashes together
     * when updating cloudflared (auto-update is disabled in [start], so a pinned build is fine).
     */
    private const val PINNED_VERSION = "2026.5.2"
    private val PINNED_SHA256 = mapOf(
        "cloudflared-linux-amd64"      to "5286698547f03df745adb2355f04c12dde52ef425491e81f433642d695521886",
        "cloudflared-linux-arm64"      to "5a4e8ce2701105271412059f44b6a0bf1ae4542b4d98ff3180c0c019443a5815",
        "cloudflared-linux-arm"        to "70a4c869a037bd69af6ce2ad0c4da4a7680d94fcfb8d4c70ecddae24d560762f",
        "cloudflared-linux-386"        to "ad82d1dbed8bbb9d702807cbd97df932cc774d29e9da5c109b7a3c7f7aee2065",
        "cloudflared-darwin-amd64.tgz" to "7240f709506bc2c1eb9da4d89cf2555499c60280ecb854b7d80e8f17d4b7903d",
        "cloudflared-darwin-arm64.tgz" to "ba94054c9fd4297645093d59d51442e5e546d07bb0516120e694a13d5b216d38",
    )
    private fun assetUrl(name: String) =
        "https://github.com/cloudflare/cloudflared/releases/download/$PINNED_VERSION/$name"

    /** cloudflared's Linux GitHub asset suffix for this CPU, or null if unsupported. */
    private fun linuxArch(): String? = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        "arm", "armv7l", "armv7", "armhf" -> "arm"
        "i386", "i486", "i586", "i686", "x86" -> "386"
        else -> null
    }

    /** cloudflared's macOS GitHub asset suffix for this CPU, or null if unsupported. */
    private fun macArch(): String? = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "amd64"
        else -> null
    }

    /**
     * Seamless Linux install: download cloudflared's single static binary straight from its
     * GitHub releases into [managedBin], `chmod +x`, and verify it runs. No sudo, no package
     * manager, no PATH changes — works on any distro. Blocking and slow (downloads ~tens of
     * MB) — call off the UI thread. Returns true on success (or if it's already installed).
     */
    fun linuxDirectInstall(): Boolean {
        if (isInstalled()) return true
        val arch = linuxArch() ?: run {
            log.warn("no cloudflared Linux binary for arch '{}'", System.getProperty("os.arch")); return false
        }
        val asset = "cloudflared-linux-$arch"
        val sha = PINNED_SHA256[asset] ?: run { log.warn("no pinned hash for {}", asset); return false }
        if (!downloadWithRetries(assetUrl(asset), managedBin, sha)) return false
        managedBin.setExecutable(true, false)
        val ok = isInstalled()
        log.info("cloudflared direct install {}", if (ok) "→ ${managedBin.absolutePath}" else "failed verification")
        return ok
    }

    /**
     * Seamless macOS install for users without Homebrew: download cloudflared's notarized
     * release tarball (`cloudflared-darwin-<arch>.tgz`), extract the single binary into
     * [managedBin] with the bundled `tar`, `chmod +x`, and verify. No sudo, no Homebrew, no
     * PATH changes. Blocking and slow (downloads) — call off the UI thread. Returns true on
     * success (or if it's already installed).
     */
    fun macDirectInstall(): Boolean {
        if (isInstalled()) return true
        val arch = macArch() ?: run {
            log.warn("no cloudflared macOS binary for arch '{}'", System.getProperty("os.arch")); return false
        }
        val asset = "cloudflared-darwin-$arch.tgz"
        val sha = PINNED_SHA256[asset] ?: run { log.warn("no pinned hash for {}", asset); return false }
        val tgz = File(managedBin.parentFile, "cloudflared.tgz")
        if (!downloadWithRetries(assetUrl(asset), tgz, sha)) return false
        return try {
            // The tarball holds a single `cloudflared` binary at its root → extracts to managedBin.
            val extracted = runCmd(
                listOf("tar", "-xzf", tgz.absolutePath, "-C", managedBin.parentFile.absolutePath), 120
            ) != null
            tgz.delete()
            if (!extracted || !managedBin.exists()) {
                log.warn("failed to extract cloudflared from tarball"); return false
            }
            managedBin.setExecutable(true, false)
            val ok = isInstalled()
            log.info("cloudflared direct install {}", if (ok) "→ ${managedBin.absolutePath}" else "failed verification")
            ok
        } catch (e: Exception) {
            log.warn("cloudflared macOS extract error: {}", e.message); tgz.delete(); false
        }
    }

    /**
     * Download [url] → [dest] (replacing any existing file) and verify it against
     * [expectedSha256] before committing it into place — so a tampered or corrupted artifact is
     * never moved to where we'll `chmod +x` and run it. Also tolerates the intermittent 504s /
     * timeouts GitHub's release-download path throws even when the API is healthy: a non-200, a
     * body below [minBytes] (e.g. a tiny HTML error page), or a hash mismatch each just fails
     * that attempt and retries with backoff. Blocking. Returns true only on a verified download.
     */
    private fun downloadWithRetries(
        url: String,
        dest: File,
        expectedSha256: String,
        minBytes: Long = 1_000_000L,
    ): Boolean {
        val dir = dest.parentFile
        if (!dir.exists() && !dir.mkdirs()) { log.warn("could not create {}", dir); return false }
        val tmp = File(dir, dest.name + ".download")

        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // github.com 302 → CDN (https→https)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

        val attempts = 3
        repeat(attempts) { i ->
            tmp.delete()
            try {
                log.info("Downloading (attempt {}/{}): {}", i + 1, attempts, url)
                val req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "BossTerm")
                    .GET()
                    .build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
                val size = if (tmp.exists()) tmp.length() else 0L
                if (resp.statusCode() != 200 || size < minBytes) {
                    log.warn("download attempt {} failed (HTTP {}, {} bytes)", i + 1, resp.statusCode(), size)
                } else {
                    val actual = sha256(tmp)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        log.warn("download attempt {} rejected: SHA-256 mismatch (expected {}, got {})",
                            i + 1, expectedSha256, actual)
                    } else {
                        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        return true
                    }
                }
            } catch (e: Exception) {
                log.warn("download attempt {} error: {}", i + 1, e.message)
            }
            if (i < attempts - 1) runCatching { Thread.sleep(2_000) }
        }
        tmp.delete()
        return false
    }

    /** Lowercase hex SHA-256 of [file]'s contents. */
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
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
