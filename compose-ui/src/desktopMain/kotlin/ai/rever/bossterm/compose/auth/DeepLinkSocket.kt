package ai.rever.bossterm.compose.auth

import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

/**
 * Single-instance forwarding for deep links on Windows/Linux, where the OS launches a
 * NEW process for a `bossterm://` URL (macOS routes the URL to the running app via
 * Apple Events instead). BossTerm intentionally allows multiple instances, so this is
 * scoped strictly to deep-link argv: the first instance listens on a loopback socket
 * advertised in `~/.bossterm/deeplink.port` (port + random secret, owner-only); a
 * newly launched instance with a deep-link argument forwards the URI there and exits
 * before any AWT/Compose init. A stale port file (dead listener) falls back to
 * handling the link locally.
 */
internal object DeepLinkSocket {

    private val log = LoggerFactory.getLogger(DeepLinkSocket::class.java)
    private val portFile = File(System.getProperty("user.home"), ".bossterm/deeplink.port")

    /** Try to hand [uri] to an already-running instance. True = forwarded, caller exits. */
    fun tryForward(uri: String): Boolean {
        val lines = runCatching { portFile.readLines() }.getOrNull() ?: return false
        val port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return false
        val secret = lines.getOrNull(1)?.trim().orEmpty()
        if (secret.isEmpty()) return false
        return runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 2000
                s.getOutputStream().write("$secret $uri\n".toByteArray(Charsets.UTF_8))
                s.getOutputStream().flush()
            }
            true
        }.getOrElse {
            // Listener gone — stale file. Remove it and let the caller handle the link itself.
            runCatching { portFile.delete() }
            false
        }
    }

    /**
     * Become the primary deep-link listener — but only if no live instance already is. Multiple
     * BossTerm instances are allowed; exactly ONE should own routing. Without this guard every
     * launch overwrote `deeplink.port` with its own port+secret (so the LAST launch won routing)
     * AND registered `deleteOnExit`, so that launch's exit also tore routing down for instances
     * still running. Probing first makes the FIRST live instance own routing for as long as it runs;
     * later launches detect it and leave it be. Best-effort.
     */
    fun startPrimaryListener(onUri: (String) -> Unit) {
        if (liveListenerPresent()) {
            log.info("Deep-link routing already owned by a running instance; not usurping it")
            return
        }
        runCatching {
            val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val secretBytes = secret.toByteArray(Charsets.UTF_8)
            portFile.parentFile?.mkdirs()
            portFile.writeText("${server.localPort}\n$secret\n")
            restrictToOwner(portFile)
            portFile.deleteOnExit()
            Thread {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { client ->
                            client.soTimeout = 2000
                            // Bound the read so a hostile local process can't stream an
                            // unbounded line at the daemon thread; a real "<secret> <uri>" is tiny.
                            val line = readBoundedLine(client.getInputStream(), 8192) ?: return@use
                            val sep = line.indexOf(' ')
                            if (sep <= 0) return@use
                            // Constant-time secret compare (it's a security boundary, even if loopback-only).
                            val presented = line.take(sep).toByteArray(Charsets.UTF_8)
                            if (!java.security.MessageDigest.isEqual(presented, secretBytes)) return@use
                            val uri = line.substring(sep + 1).trim()
                            if (uri.startsWith("bossterm://")) onUri(uri)
                        }
                    }
                }
            }.apply { isDaemon = true; name = "bossterm-deeplink"; start() }
        }.onFailure { log.warn("Deep-link listener unavailable: {}", it.message) }
    }

    /**
     * True if `deeplink.port` points at a still-listening socket. A successful TCP connect proves a
     * listener is bound (we send nothing — its accept loop sees an empty line and ignores it). A
     * refused connect means the file is stale (the owning instance died): delete it so we can claim
     * ownership. Sequential launches (A then B) therefore don't usurp A; only a genuine cold start
     * (no live owner) becomes primary.
     */
    private fun liveListenerPresent(): Boolean {
        val lines = runCatching { portFile.readLines() }.getOrNull() ?: return false
        val port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return false
        if (lines.getOrNull(1)?.trim().isNullOrEmpty()) return false
        return runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).close()
            true
        }.getOrElse {
            runCatching { portFile.delete() } // stale file from a dead instance — clear it
            false
        }
    }

    /** Read one line (up to [max] bytes), stopping at newline or cap — never reads unbounded. */
    private fun readBoundedLine(input: java.io.InputStream, max: Int): String? {
        val buf = StringBuilder()
        var count = 0
        while (count < max) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            buf.append(b.toChar())
            count++
        }
        return buf.toString().trimEnd('\r').ifEmpty { null }
    }

    private fun restrictToOwner(file: File) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
    }
}
