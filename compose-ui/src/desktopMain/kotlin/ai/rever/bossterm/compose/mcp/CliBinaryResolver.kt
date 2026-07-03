package ai.rever.bossterm.compose.mcp

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves an AI-CLI binary name (`claude`, `codex`, `gemini`, `node`, …) to
 * an absolute path that [ProcessBuilder] can spawn even when the JVM's
 * inherited `PATH` is the bare launchd default.
 *
 * Why this exists: a packaged desktop app launched from Finder/the Dock
 * inherits `PATH=/usr/bin:/bin:/usr/sbin:/sbin` — none of the places these
 * CLIs actually live (`~/.local/bin`, `/opt/homebrew/bin`, …). Every
 * `claude mcp add` shell-out then dies with "CLI not found", the startup
 * auto-reattach silently fails, and the CLI's registered endpoint is left
 * pointing at a stale port until the user manually re-runs the attach from a
 * real terminal. Resolving to an absolute path here makes attach work the
 * same from a packaged app as from `gradlew run`.
 *
 * Resolution order (first executable match wins, result cached per name):
 *  1. The inherited `PATH`, exactly what ProcessBuilder would search.
 *  2. A short list of well-known install dirs (Homebrew, npm/bun/volta
 *     globals, the Claude Code native installer's `~/.local/bin`, …).
 *  3. A best-effort `$SHELL -l -c 'command -v <name>'` login-shell probe,
 *     which picks up PATH exports from the user's shell profile (nvm, asdf,
 *     custom prefixes). Bounded by a 5s timeout.
 *
 * If nothing is found the original name is returned unchanged so the caller
 * hits the existing IOException → clipboard-fallback path.
 *
 * Windows note: GUI apps there inherit the full user PATH from the registry,
 * and PATHEXT handling makes manual probing subtly wrong — so on Windows the
 * name is returned as-is and ProcessBuilder's own lookup applies.
 */
internal object CliBinaryResolver {

    private val log = LoggerFactory.getLogger(CliBinaryResolver::class.java)

    /** Positive results only — a CLI installed mid-session is found on the next call. */
    private val cache = ConcurrentHashMap<String, String>()

    /** Resolve [binary] using the real process environment. Cached. */
    fun resolve(binary: String): String {
        cache[binary]?.let { return it }
        val resolved = resolveUncached(
            binary = binary,
            pathValue = System.getenv("PATH").orEmpty(),
            extraDirs = wellKnownDirs(System.getProperty("user.home").orEmpty()),
            isWindows = System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true),
            loginShellProbe = ::probeLoginShell
        )
        if (resolved != binary) {
            cache[binary] = resolved
            log.info("Resolved CLI '{}' -> {}", binary, resolved)
        }
        return resolved
    }

    /**
     * Pure resolution core, parameterized for tests. Returns [binary]
     * unchanged when no executable is found (or when resolution doesn't
     * apply: absolute/relative paths, Windows).
     */
    internal fun resolveUncached(
        binary: String,
        pathValue: String,
        extraDirs: List<File>,
        isWindows: Boolean,
        loginShellProbe: (String) -> String? = { null }
    ): String {
        // Embedders may already pass an explicit path — honor it untouched.
        if (binary.contains('/') || binary.contains('\\')) return binary
        if (isWindows) return binary

        val pathDirs = pathValue.split(':').filter { it.isNotBlank() }.map(::File)
        for (dir in pathDirs + extraDirs) {
            val candidate = File(dir, binary)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }

        val fromShell = loginShellProbe(binary)
        if (fromShell != null) {
            val candidate = File(fromShell)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        return binary
    }

    internal fun wellKnownDirs(home: String): List<File> = listOf(
        // Claude Code's native installer target; also a common pipx/user-local bin.
        "$home/.local/bin",
        // Homebrew (Apple Silicon, then Intel/Linuxbrew).
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/home/linuxbrew/.linuxbrew/bin",
        // Claude Code's legacy local-install shim.
        "$home/.claude/local",
        // JS toolchain globals (node for the OpenCode config script, npm-installed CLIs).
        "$home/.bun/bin",
        "$home/.npm-global/bin",
        "$home/.volta/bin",
        "$home/.asdf/shims",
        "$home/bin"
    ).map(::File)

    /**
     * Ask the user's login shell where [binary] lives. Login (not interactive)
     * mode: profile files carry the PATH exports we need without the prompt/rc
     * machinery that can hang a headless spawn. Best-effort — any failure or
     * timeout returns null.
     */
    private fun probeLoginShell(binary: String): String? {
        // The names we probe come from the fixed McpAttachTarget command
        // tables, but quote defensively anyway.
        if (!binary.all { it.isLetterOrDigit() || it in "._-" }) return null
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        return try {
            val process = ProcessBuilder(shell, "-l", "-c", "command -v $binary")
                .redirectErrorStream(false)
                .start()
            process.outputStream.close()
            if (!process.waitFor(LOGIN_SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                log.warn("Login-shell probe for '{}' timed out", binary)
                return null
            }
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().readText().lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("/") }
        } catch (t: Throwable) {
            log.debug("Login-shell probe for '{}' failed: {}", binary, t.message)
            null
        }
    }

    private const val LOGIN_SHELL_TIMEOUT_SECONDS = 5L
}
