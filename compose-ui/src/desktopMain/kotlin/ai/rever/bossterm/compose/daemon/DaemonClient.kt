package ai.rever.bossterm.compose.daemon

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * GUI-side client for the BossTerm daemon: discover a running daemon (via [BossTermPaths.daemonPortFile]),
 * authenticate with its secret, spawn one if absent, and issue control verbs. Modeled on the
 * single-instance deep-link forwarder, extended with:
 *  - a HELLO/version handshake so the GUI never talks to an incompatible daemon,
 *  - stale-file fallback (a `kill -9`'d daemon leaves a dead port file),
 *  - a `FileChannel.tryLock` race guard so two GUIs cold-starting spawn exactly one daemon.
 *
 * Blocking; call [ensureConnected] off the UI thread.
 */
class DaemonClient {
    private val log = LoggerFactory.getLogger(DaemonClient::class.java)

    @Volatile private var endpoint: DaemonControlChannel.Companion.Endpoint? = null

    /** The live, compatible endpoint if [ensureConnected] has succeeded. */
    val current: DaemonControlChannel.Companion.Endpoint? get() = endpoint

    /**
     * Ensure a live, protocol-compatible daemon is reachable, spawning one if needed.
     * Returns the endpoint, or null if no compatible daemon could be reached (GUI then falls back
     * to its in-process path).
     */
    fun ensureConnected(spawnIfAbsent: Boolean = true): DaemonControlChannel.Companion.Endpoint? {
        probeExisting()?.let { endpoint = it; return it }
        if (!spawnIfAbsent) return null
        return spawnRaceGuarded()?.also { endpoint = it }
    }

    /** Send one control verb; returns the response line (`OK …`/`ERR …`) or null on failure. */
    fun request(verb: String, arg: String = ""): String? {
        val ep = endpoint ?: return null
        return rawRequest(ep, if (arg.isEmpty()) "${ep.secret} $verb" else "${ep.secret} $verb $arg")
    }

    // ---- discovery / handshake ----

    /** Read the port file and HELLO it; returns the endpoint only if live AND protocol-compatible. */
    private fun probeExisting(): DaemonControlChannel.Companion.Endpoint? {
        val ep = DaemonControlChannel.readEndpoint() ?: return null
        return if (isCompatibleLiveDaemon(ep)) ep else {
            // Dead or incompatible daemon left a port file behind.
            if (!ping(ep)) {
                log.info("Stale daemon.port (no live daemon); will respawn")
                // Only delete if the file STILL describes the daemon we just probed — under a
                // shutdown/start race a newer daemon may have rewritten it, and deleting that would
                // strand it (discovery-less) and trigger a spurious extra spawn. Same guard as stop().
                runCatching {
                    val cur = DaemonControlChannel.readEndpoint()
                    if (cur == null || cur.secret == ep.secret) BossTermPaths.daemonPortFile().delete()
                }
            } else {
                log.warn("Daemon proto {} != client proto {}; not attaching", ep.protocolVersion, DaemonControlChannel.PROTOCOL_VERSION)
            }
            null
        }
    }

    /**
     * Compatibility is gated on the **protocol** version, not the app version: the daemon shares the
     * GUI's classpath at spawn (so a freshly-spawned daemon always matches), and across a *routine* app
     * update the wire protocol is usually unchanged — refusing every patch-bumped-but-wire-compatible
     * daemon would needlessly kill the user's sessions. [DaemonControlChannel.PROTOCOL_VERSION] is the
     * incompatibility signal and MUST be bumped whenever a daemon code change alters the verb
     * set / framing / behavior the GUI depends on; that retires older daemons here. The HELLO version
     * string is surfaced ([Endpoint.version]) for diagnostics/logging only.
     */
    private fun isCompatibleLiveDaemon(ep: DaemonControlChannel.Companion.Endpoint): Boolean {
        val hello = rawRequest(ep, "${ep.secret} ${DaemonProtocol.HELLO}") ?: return false
        // "OK <version> <proto> <pid>". Guard the prefix so an "ERR …" reply isn't parsed as a
        // (coincidental) proto via the no-op removePrefix.
        if (!hello.startsWith("OK ")) return false
        val parts = hello.removePrefix("OK ").trim().split(' ')
        val proto = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return proto == DaemonControlChannel.PROTOCOL_VERSION
    }

    private fun ping(ep: DaemonControlChannel.Companion.Endpoint): Boolean =
        rawRequest(ep, "${ep.secret} ${DaemonProtocol.PING}")?.trim() == "PONG"

    // ---- spawn with single-spawn race guard ----

    private fun spawnRaceGuarded(): DaemonControlChannel.Companion.Endpoint? {
        val lockFile = BossTermPaths.daemonLockFile()
        var raf: RandomAccessFile? = null
        var channel: FileChannel? = null
        var lock: FileLock? = null
        try {
            // Create the lock file owner-only (0600) BEFORE opening it, so there's no create-then-chmod
            // window in which another local user could open()+tryLock() it and wedge the single-spawn
            // guard. Combined with the 0700 base dir (BossTermPaths.dir), this keeps the advisory lock
            // — the real single-spawn guarantee, keyed on the OS file handle — reachable only by us.
            BossTermPaths.createOwnerOnly(lockFile)
            raf = RandomAccessFile(lockFile, "rw")
            channel = raf.channel
            lock = runCatching { channel.tryLock() }.getOrNull()

            if (lock != null) {
                // We own the spawn. A daemon may have appeared between our probe and the lock.
                probeExisting()?.let { return it }
                log.info("Spawning daemon…")
                val proc = DaemonLauncher.spawn() ?: run {
                    log.error("Daemon spawn failed")
                    return null
                }
                val ep = awaitLiveDaemon()
                if (ep == null) {
                    // Spawned but never became reachable in time — terminate it rather than leave an
                    // orphan that could bind a moment later while we've already fallen back to
                    // in-process MCP (two daemons / a stale port file).
                    log.warn("Spawned daemon (pid={}) did not become reachable; terminating it", proc.pid())
                    runCatching { proc.destroy() } // SIGTERM (best-effort)
                    // A child wedged during startup can ignore SIGTERM and bind a moment later — exactly
                    // the orphan we're trying to avoid. Escalate to a forcible kill if it doesn't exit.
                    val exited = runCatching { proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
                    if (!exited) {
                        log.warn("daemon pid={} ignored SIGTERM; killing forcibly", proc.pid())
                        runCatching { proc.destroyForcibly().waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
                    }
                }
                return ep
            } else {
                // Another GUI is spawning; just wait for it to come up.
                log.info("Another process is spawning the daemon; waiting")
                return awaitLiveDaemon()
            }
        } catch (e: Exception) {
            log.error("Daemon spawn/race-guard failed: {}", e.message)
            return null
        } finally {
            runCatching { lock?.release() }
            runCatching { channel?.close() }
            runCatching { raf?.close() }
        }
    }

    /** Poll for a live, compatible daemon for up to ~[timeoutMs], with short backoff. */
    private fun awaitLiveDaemon(timeoutMs: Long = 12_000): DaemonControlChannel.Companion.Endpoint? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var sleep = 50L
        while (System.nanoTime() < deadline) {
            val ep = DaemonControlChannel.readEndpoint()
            if (ep != null && isCompatibleLiveDaemon(ep)) return ep
            try {
                Thread.sleep(sleep)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); return null
            }
            sleep = (sleep * 2).coerceAtMost(400)
        }
        log.error("Daemon did not become reachable within {}ms", timeoutMs)
        return null
    }

    private fun rawRequest(ep: DaemonControlChannel.Companion.Endpoint, line: String): String? = try {
        Socket(InetAddress.getLoopbackAddress(), ep.port).use { sock ->
            sock.soTimeout = 3000
            OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8).apply {
                write(line); write("\n"); flush()
            }
            BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)).readLine()
        }
    } catch (e: Exception) {
        null
    }
}
