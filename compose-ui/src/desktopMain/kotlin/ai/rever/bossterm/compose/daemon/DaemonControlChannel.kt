package ai.rever.bossterm.compose.daemon

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The daemon's loopback control socket — how the GUI discovers, authenticates to, and
 * issues lifecycle commands to a running daemon. Modeled on the single-instance deep-link
 * forwarder: a `ServerSocket` bound to loopback on an ephemeral port, gated by a per-launch
 * secret written (owner-only) to [BossTermPaths.daemonPortFile].
 *
 * Wire protocol (one request per connection, newline-framed UTF-8):
 *   client →  `<secret> <VERB> [arg]\n`
 *   server →  `<response line>\n`  (then the connection closes)
 *
 * High-bandwidth terminal I/O does NOT ride this socket — later phases mint share tokens and
 * stream over the existing share WebSocket. This channel stays small: handshake + lifecycle.
 *
 * `HELLO` and `PING` are answered here (the channel knows version + pid); every other verb is
 * delegated to [onRequest]. Unknown/!auth'd requests are dropped.
 */
class DaemonControlChannel(
    private val version: String,
    private val protocolVersion: Int,
    /** Handles verbs beyond HELLO/PING; returns the single response line. */
    private val onRequest: (verb: String, arg: String) -> String,
) {
    private val log = LoggerFactory.getLogger(DaemonControlChannel::class.java)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var secret: String = ""
    @Volatile private var running = false

    // Bounded worker pool for per-connection request handling — caps concurrent threads so a local
    // actor can't spawn unbounded short-lived threads ahead of the bad-secret rejection. SynchronousQueue
    // + the default AbortPolicy: past the max, execute() throws and we close the socket (shed the flood).
    private val requestPool = java.util.concurrent.ThreadPoolExecutor(
        2, 16, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
    ) { r -> Thread(r, "bossterm-daemon-control-req").apply { isDaemon = true } }

    /** Bound port, or -1 before [start]. */
    val port: Int get() = serverSocket?.localPort ?: -1

    /** The per-launch secret (also written to daemon.port). Shared with the attach WS for auth. */
    val secretValue: String get() = secret

    /**
     * Bind the loopback socket, write [BossTermPaths.daemonPortFile], and start accepting.
     * Idempotent-ish: throws if already started. Returns the bound port.
     */
    fun start(): Int {
        check(serverSocket == null) { "DaemonControlChannel already started" }
        secret = newSecret()
        val socket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        serverSocket = socket
        running = true
        writePortFile(socket.localPort, secret)
        thread(name = "bossterm-daemon-control", isDaemon = true) { acceptLoop(socket) }
        log.info("Daemon control channel on 127.0.0.1:{} (v{} proto{})", socket.localPort, version, protocolVersion)
        return socket.localPort
    }

    /** Stop accepting and remove the port file (only if it's still ours). Safe to call more than once. */
    fun stop() {
        running = false
        runCatching { requestPool.shutdownNow() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Only delete daemon.port if it still belongs to THIS instance. Under a shutdown/start race,
        // another daemon may already have overwritten it with its own port+secret — deleting that would
        // strand a live daemon with no discovery file (and the GUI would spawn yet another).
        val mySecret = secret
        runCatching {
            val ep = readEndpoint()
            if (ep == null || ep.secret == mySecret) BossTermPaths.daemonPortFile().delete()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) log.debug("control accept ended: {}", e.message)
                break
            }
            // One short-lived request per connection; handle on a bounded worker pool so a slow/hung
            // client never blocks the accept loop and a flood can't spawn unbounded threads.
            try {
                requestPool.execute { handle(client) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                if (running) log.warn("control: request pool saturated; rejecting connection")
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 2000
            val line = try {
                BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                    .readLine()
                    ?.take(MAX_REQUEST_CHARS)
                    ?: return
            } catch (e: Exception) {
                return
            }
            val sp = line.indexOf(' ')
            if (sp <= 0) return
            val presented = line.substring(0, sp)
            if (!secretMatches(presented)) {
                log.warn("Daemon control: rejected request with bad secret")
                return
            }
            val rest = line.substring(sp + 1).trim()
            val vsp = rest.indexOf(' ')
            val verb = (if (vsp < 0) rest else rest.substring(0, vsp)).uppercase()
            val arg = if (vsp < 0) "" else rest.substring(vsp + 1).trim()
            val response = try {
                dispatch(verb, arg)
            } catch (e: Exception) {
                log.warn("Daemon control: verb {} failed: {}", verb, e.message)
                "ERR ${e.message}"
            }
            runCatching {
                OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8).apply {
                    write(response)
                    write("\n")
                    flush()
                }
            }
        }
    }

    private fun dispatch(verb: String, arg: String): String = when (verb) {
        "HELLO" -> "OK $version $protocolVersion ${currentPid()}"
        "PING" -> "PONG"
        else -> onRequest(verb, arg)
    }

    private fun secretMatches(presented: String): Boolean =
        MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            secret.toByteArray(StandardCharsets.UTF_8),
        )

    private fun writePortFile(port: Int, secret: String) {
        val file = BossTermPaths.daemonPortFile()
        // Write to a temp file made owner-only BEFORE the secret touches it, then atomically rename
        // in: closes both the "secret briefly world-readable on a fresh file" window and the
        // "reader sees a half-written file" race (readEndpoint is polled every 50–400ms by the GUI).
        // Unique temp name (not a fixed ".daemon.port.tmp") so two daemons racing on shutdown/start —
        // which this code explicitly anticipates — can't collide on it before the atomic rename.
        val tmp = java.io.File.createTempFile(".daemon.port", ".tmp", file.parentFile)
        restrictToOwner(tmp)
        tmp.writeText("$port\n$secret\n$version $protocolVersion\n", StandardCharsets.UTF_8)
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure {
            // Filesystems without atomic rename (rare): fall back to in-place, still owner-first.
            restrictToOwner(file)
            file.writeText("$port\n$secret\n$version $protocolVersion\n", StandardCharsets.UTF_8)
            restrictToOwner(file)
            runCatching { tmp.delete() }
        }
        restrictToOwner(file)
        file.deleteOnExit()
        runCatching {
            BossTermPaths.daemonPidFile().apply {
                writeText(currentPid().toString(), StandardCharsets.UTF_8)
                deleteOnExit()
            }
        }
    }

    companion object {
        /**
         * Control-protocol version — the ONLY cross-update compatibility signal between GUI and daemon
         * (the app version is informational; see [DaemonClient.isCompatibleLiveDaemon]). MUST be bumped
         * on any incompatible change to the verb set / framing OR to daemon behavior the GUI depends on,
         * so the HELLO handshake refuses a stale daemon left running across an app update instead of
         * attaching to old code. Phase 0: 1.
         */
        const val PROTOCOL_VERSION = 1

        /** Cap a control request so a misbehaving peer can't stream unbounded input. */
        private const val MAX_REQUEST_CHARS = 8192

        private val rng = SecureRandom()

        private fun newSecret(): String {
            val bytes = ByteArray(32)
            rng.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun currentPid(): Long = ProcessHandle.current().pid()

        /** chmod 600 on POSIX; no-op (best effort) where unsupported — token never group/world readable. */
        private fun restrictToOwner(file: java.io.File) = BossTermPaths.restrictToOwner(file)

        /**
         * Parsed contents of [BossTermPaths.daemonPortFile]. Null when absent or malformed.
         * Used by the GUI-side client to discover and authenticate to a running daemon.
         */
        data class Endpoint(val port: Int, val secret: String, val version: String, val protocolVersion: Int)

        fun readEndpoint(): Endpoint? {
            val file = BossTermPaths.daemonPortFile()
            if (!file.exists()) return null
            return runCatching {
                val lines = file.readText(StandardCharsets.UTF_8).lines()
                val port = lines.getOrNull(0)?.trim()?.toInt() ?: return null
                val secret = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val verParts = lines.getOrNull(2)?.trim()?.split(' ') ?: emptyList()
                val ver = verParts.getOrNull(0) ?: "0.0.0"
                val proto = verParts.getOrNull(1)?.toIntOrNull() ?: 0
                Endpoint(port, secret, ver, proto)
            }.getOrNull()
        }
    }
}
