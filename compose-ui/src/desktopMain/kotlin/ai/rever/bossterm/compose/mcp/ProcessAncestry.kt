package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Resolves which registered [TabbedTerminalState] (window) owns the MCP
 * client process connected on a given loopback TCP source port.
 *
 * Tools that default to "the primary window" (e.g. `run_command`,
 * `run_in_panel`, `get_active_tab` called with no `tab_id`) want to target
 * the window the calling client is running INSIDE, not whichever window
 * happens to be first in the registry. This object answers that.
 *
 * Algorithm:
 *  1. Resolve the PID owning the loopback TCP socket on the given port.
 *  2. Walk the parent process tree.
 *  3. First ancestor PID that matches a tracked pane's shell PID identifies
 *     the client's pane → that pane's state is the answer.
 *  4. If no ancestor matches (client running outside any BossTerm pane —
 *     Claude Desktop, an external Inspector, a CI script…), return null
 *     and the caller falls back to first-registered.
 *
 * Platform support: macOS via `lsof` + `ps`, Linux via `/proc/net/tcp{,6}` +
 * `/proc/<pid>/status` (both IPv4 and IPv6 loopback). Windows and unknown
 * platforms return null silently.
 *
 * Cost: a fresh resolution shells out twice per parent hop on macOS (lsof
 * once, then `ps -o ppid=` per ancestor). Real pane→Claude depth is 2-4
 * hops, so budget ~50-150ms. Linux is faster (pure /proc reads).
 *
 * Failures are logged at DEBUG and return null — never crash a tool call
 * on the strength of a parent-walk hiccup.
 */
internal object ProcessAncestry {

    private val log = LoggerFactory.getLogger(ProcessAncestry::class.java)

    /**
     * Max ancestors to walk before giving up. Real pane→Claude depth is
     * typically 2-4 (shell → maybe tmux → Claude Code's node launcher →
     * claude itself). The cap protects against PID cycles or pathologies.
     */
    private const val MAX_PARENT_WALK = 16

    /** Per-subprocess wait cap — these shell-outs should be near-instant. */
    private const val SUBPROCESS_TIMEOUT_S = 2L

    /**
     * @param remotePort the client-side ephemeral port — i.e. what Ktor
     *   reports as `call.request.local.remotePort`.
     * @param registry source of truth for tracked panes' shell PIDs.
     * @return the [TabbedTerminalState] containing the pane that owns the
     *   client process, or null if no ancestor matches.
     */
    fun resolveClientWindow(
        remotePort: Int,
        registry: McpTerminalRegistry
    ): TabbedTerminalState? {
        if (remotePort <= 0) return null
        val statesByShellPid = collectStateByShellPid(registry)
        if (statesByShellPid.isEmpty()) return null

        val clientPid = findClientPid(remotePort) ?: return null
        var pid = clientPid
        var hops = 0
        while (hops < MAX_PARENT_WALK) {
            statesByShellPid[pid]?.let { return it }
            val parent = parentPid(pid)
            if (parent == null || parent <= 1L || parent == pid) return null
            pid = parent
            hops++
        }
        return null
    }

    /**
     * Flatten every tracked tab's shell PID across every registered state
     * into a single lookup map. Cheap and rebuilt per resolution so pane
     * creation / closure is reflected immediately without a refresh hook.
     */
    private fun collectStateByShellPid(
        registry: McpTerminalRegistry
    ): Map<Long, TabbedTerminalState> {
        val out = HashMap<Long, TabbedTerminalState>()
        for (state in registry.allStates()) {
            for (tab in state.tabs) {
                val pid = tab.processHandle.value?.getPid() ?: continue
                out[pid] = state
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Client PID resolution: socket → owning process.
    // ---------------------------------------------------------------------

    private fun findClientPid(remotePort: Int): Long? = when {
        ShellCustomizationUtils.isMacOS() -> findClientPidMacOS(remotePort)
        ShellCustomizationUtils.isLinux() -> findClientPidLinux(remotePort)
        else -> null
    }

    /**
     * `lsof -nP -iTCP:<port> -sTCP:ESTABLISHED -F p` lists every process with
     * an established TCP endpoint on that port number — both ends of the
     * connection match (the client's local port and our server's remote port).
     * Filtering by port alone rather than `-iTCP@127.0.0.1:<port>` means an
     * IPv6 loopback client (`::1`, e.g. when the OS resolves `localhost` to
     * IPv6) is matched too. The ephemeral client port is unique to this
     * connection, so port-only matching stays precise. Each end is one line
     * `p<PID>`; our own PID is the server side, the *other* is the client.
     */
    private fun findClientPidMacOS(remotePort: Int): Long? {
        val ourPid = ProcessHandle.current().pid()
        return try {
            val process = ProcessBuilder(
                "lsof", "-nP",
                "-iTCP:$remotePort",
                "-sTCP:ESTABLISHED",
                "-F", "p"
            ).redirectErrorStream(true).start()
            // waitFor BEFORE reading: on timeout the child is killed instead of
            // leaked, and we never parse a half-written line. Safe to read after
            // exit because lsof's per-port output is far below the pipe buffer.
            if (!process.waitFor(SUBPROCESS_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            process.inputStream.bufferedReader().readLines().asSequence()
                .filter { it.startsWith("p") }
                .mapNotNull { it.drop(1).toLongOrNull() }
                .firstOrNull { it != ourPid }
        } catch (e: Throwable) {
            log.debug("macOS lsof PID lookup failed for port {}: {}", remotePort, e.message)
            null
        }
    }

    /**
     * Linux: scan `/proc/net/tcp` AND `/proc/net/tcp6` for ESTABLISHED rows
     * whose local or remote address ends with `:<portHex>`. Reading tcp6 too
     * covers an IPv6 loopback client (`::1`, e.g. when `localhost` resolves to
     * IPv6) — both files share the same column layout, only the address width
     * differs, and the `:<portHex>` suffix match is layout-agnostic. Each row
     * carries the socket inode; then scan `/proc/<pid>/fd/N` symlinks for
     * `socket:[<inode>]` targets to find the owning PID.
     *
     * Pure file reads — no shell-out, so this is fast (~5-10ms).
     */
    private fun findClientPidLinux(remotePort: Int): Long? {
        val portHex = remotePort.toString(16).uppercase().padStart(4, '0')
        return try {
            val inodes = sequenceOf("/proc/net/tcp", "/proc/net/tcp6")
                .flatMap { path ->
                    runCatching { File(path).readText().lineSequence().drop(1).toList() }
                        .getOrDefault(emptyList())
                        .asSequence()
                }
                .mapNotNull { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size < 10) return@mapNotNull null
                    val local = fields[1]
                    val remote = fields[2]
                    val state = fields[3]
                    if (state != "01") return@mapNotNull null // 01 = TCP_ESTABLISHED
                    if (local.endsWith(":$portHex") || remote.endsWith(":$portHex")) {
                        fields[9].toLongOrNull()
                    } else null
                }
                .toSet()
            if (inodes.isEmpty()) return null

            val ourPid = ProcessHandle.current().pid()
            File("/proc")
                .listFiles { f -> f.isDirectory && f.name.toLongOrNull() != null }
                ?.asSequence()
                ?.mapNotNull { pidDir ->
                    val pid = pidDir.name.toLongOrNull() ?: return@mapNotNull null
                    if (pid == ourPid) return@mapNotNull null
                    val fdDir = File(pidDir, "fd")
                    val fds = fdDir.listFiles() ?: return@mapNotNull null
                    val match = fds.any { fd ->
                        val target = try {
                            Files.readSymbolicLink(fd.toPath()).toString()
                        } catch (_: Throwable) {
                            return@any false
                        }
                        if (!target.startsWith("socket:[") || !target.endsWith("]")) {
                            return@any false
                        }
                        val inode = target.substring("socket:[".length, target.length - 1)
                            .toLongOrNull() ?: return@any false
                        inode in inodes
                    }
                    if (match) pid else null
                }
                ?.firstOrNull()
        } catch (e: Throwable) {
            log.debug("Linux /proc PID lookup failed for port {}: {}", remotePort, e.message)
            null
        }
    }

    // ---------------------------------------------------------------------
    // Parent PID walking.
    // ---------------------------------------------------------------------

    private fun parentPid(pid: Long): Long? = when {
        ShellCustomizationUtils.isMacOS() -> parentPidMacOS(pid)
        ShellCustomizationUtils.isLinux() -> parentPidLinux(pid)
        else -> null
    }

    private fun parentPidMacOS(pid: Long): Long? = try {
        val process = ProcessBuilder("ps", "-o", "ppid=", "-p", pid.toString())
            .redirectErrorStream(true).start()
        if (!process.waitFor(SUBPROCESS_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().readText().trim().toLongOrNull()
        }
    } catch (e: Throwable) {
        log.debug("macOS ps ppid lookup failed for pid {}: {}", pid, e.message)
        null
    }

    private fun parentPidLinux(pid: Long): Long? = try {
        File("/proc/$pid/status").readText()
            .lineSequence()
            .firstOrNull { it.startsWith("PPid:") }
            ?.substringAfter("PPid:")?.trim()?.toLongOrNull()
    } catch (e: Throwable) {
        log.debug("Linux /proc/{}/status PPid read failed: {}", pid, e.message)
        null
    }
}
