package ai.rever.bossterm.compose.ai

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Detects which AI coding assistants (and the other registered CLI tools) are installed.
 *
 * Detection is performed on-demand when the context menu opens, not via polling.
 *
 * Two passes, in order:
 *  1. **Filesystem only, no subprocesses** — each tool's [AIAssistantDefinition.extraDetectPaths],
 *     then the inherited `PATH`, then a short list of common install dirs, then nvm's versioned
 *     node bin dirs.
 *  2. **One** login-shell lookup for everything pass 1 missed, so tools installed behind a PATH
 *     export in the user's profile (nvm, asdf, custom prefixes) are still found.
 *
 * Pass 2 is deliberately a single batched spawn. The previous implementation ran `which <cmd>` and
 * then `$SHELL -l -c 'which <cmd>'` *per tool*: with the registry at 20+ tools that was up to ~40
 * processes per sweep, each with its own 5s timeout, all on [Dispatchers.IO] — the dispatcher the
 * terminal's shell sessions also compete for. Adding the open-source CLIs made that fan-out worse,
 * so the shell-outs are collapsed into one `command -v` loop.
 */
class AIAssistantDetector {

    private val log = LoggerFactory.getLogger(AIAssistantDetector::class.java)

    private val _installationStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * Reactive state of installation status for each assistant.
     * Key is the assistant ID, value is true if installed.
     */
    val installationStatus: StateFlow<Map<String, Boolean>> = _installationStatus.asStateFlow()

    /**
     * Check if an assistant is installed (uses cached status from last detectAll).
     *
     * @param assistantId The assistant ID to check
     * @return true if installed, false or unknown if not in cache
     */
    fun isInstalled(assistantId: String): Boolean {
        return installationStatus.value[assistantId] == true
    }

    /**
     * Check if an assistant is confirmed NOT installed.
     * Returns true only if detection has run AND explicitly found the assistant is not installed.
     * Use this for command interception to avoid false positives when detection hasn't run yet.
     *
     * @param assistantId The assistant ID to check
     * @return true only if detection confirmed assistant is not installed, false otherwise
     */
    fun isConfirmedNotInstalled(assistantId: String): Boolean {
        return installationStatus.value[assistantId] == false
    }

    private val home = System.getProperty("user.home").orEmpty()

    /**
     * Detect installation status for all registered tools.
     *
     * @return Map of assistant ID to installation status
     */
    suspend fun detectAll(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val definitions = AIAssistants.ALL
        val foundOnDisk = definitions.associate { it.id to existsOnDisk(it) }

        // Only the misses need the (single) shell probe.
        val unresolved = definitions
            .filter { foundOnDisk[it.id] != true }
            .map { it.command }
            .distinct()
        val onShellPath = lookupInLoginShell(unresolved)

        val results = definitions.associate { definition ->
            definition.id to (foundOnDisk[definition.id] == true || definition.command in onShellPath)
        }
        _installationStatus.value = results
        results
    }

    /**
     * Detect if a single tool is installed. Does not publish to [installationStatus] — callers that
     * want the cached map should use [detectAll].
     *
     * @param assistant The assistant to check
     * @return true if installed, false otherwise
     */
    suspend fun detectSingle(assistant: AIAssistantDefinition): Boolean = withContext(Dispatchers.IO) {
        existsOnDisk(assistant) || assistant.command in lookupInLoginShell(listOf(assistant.command))
    }

    /**
     * Filesystem-only lookup for [assistant]'s command. No subprocesses, so this is safe to run for
     * every registered tool on every sweep.
     */
    private fun existsOnDisk(assistant: AIAssistantDefinition): Boolean {
        // 1. Locations the definition declares for itself.
        for (path in assistant.resolvedDetectPaths(home)) {
            if (isExecutableFile(File(path))) return true
        }

        // 2. The inherited PATH — what a plain spawn would search anyway. Cheaper and more
        //    reliable than shelling out to `which`, and it works on Windows where `which` doesn't.
        val pathDirs = System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
        for (dir in pathDirs) {
            if (foundIn(File(dir), assistant.command)) return true
        }

        // 3. Common install dirs a GUI-launched app's bare PATH misses.
        val commonDirs = listOf(
            "/usr/local/bin",
            "/usr/bin",
            "/opt/homebrew/bin",
            "$home/.local/bin",
            "$home/.npm-global/bin"
        )
        for (dir in commonDirs) {
            if (foundIn(File(dir), assistant.command)) return true
        }

        // 4. nvm keeps one bin dir per installed node version.
        val nvmBase = File("$home/.nvm/versions/node")
        if (nvmBase.isDirectory) {
            nvmBase.listFiles()?.forEach { versionDir ->
                if (versionDir.isDirectory && foundIn(File(versionDir, "bin"), assistant.command)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * True when [command] resolves to an executable inside [dir]. On Windows the bare name is
     * almost never the real file, so the usual PATHEXT suffixes are tried too.
     */
    private fun foundIn(dir: File, command: String): Boolean {
        if (isExecutableFile(File(dir, command))) return true
        if (!ShellCustomizationUtils.isWindows()) return false
        return WINDOWS_EXECUTABLE_SUFFIXES.any { isExecutableFile(File(dir, command + it)) }
    }

    private fun isExecutableFile(file: File): Boolean = file.isFile && file.canExecute()

    /**
     * Ask the user's login shell which of [commands] it can resolve, in a single spawn.
     *
     * Login (not interactive) mode: profile files carry the PATH exports we need without the
     * prompt/rc machinery that can hang a headless spawn. Returns the subset that resolved; any
     * failure or timeout returns an empty set, so detection degrades to the filesystem passes.
     */
    private fun lookupInLoginShell(commands: List<String>): Set<String> {
        if (commands.isEmpty()) return emptySet()
        // GUI apps on Windows inherit the full user PATH from the registry, so pass 1 already
        // covers it — and there is no login shell to ask.
        if (ShellCustomizationUtils.isWindows()) return emptySet()

        // The names come from the registry, but a custom assistant can contribute one, so refuse
        // anything that isn't a plain command name rather than interpolating it into a script.
        val safe = commands.filter { name ->
            name.isNotEmpty() && name.all { it.isLetterOrDigit() || it in "._-" }
        }
        if (safe.isEmpty()) return emptySet()

        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        // Echo the name of each command the shell can resolve, nothing for the rest. `|| true`
        // keeps a miss from tripping any `set -e` inherited from the profile.
        val script = safe.joinToString("; ") { name ->
            "command -v $name >/dev/null 2>&1 && echo $name || true"
        }
        return try {
            val process = ProcessBuilder(shell, "-l", "-c", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.outputStream.close()
            // Drain stdout on a daemon thread while waiting: a profile that echoes an MOTD larger
            // than the OS pipe buffer would otherwise block the child on write until the timeout.
            val output = AtomicReference("")
            val drainer = Thread {
                try {
                    output.set(process.inputStream.bufferedReader().readText())
                } catch (_: Throwable) {
                    // ignore: output stays empty
                }
            }.apply {
                isDaemon = true
                name = "ai-detector-probe-drain"
                start()
            }
            if (!process.waitFor(LOGIN_SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                log.warn("Login-shell tool probe timed out after {}s", LOGIN_SHELL_TIMEOUT_SECONDS)
                return emptySet()
            }
            drainer.join(1_000)
            val resolved = safe.toSet()
            output.get().lineSequence().map { it.trim() }.filter { it in resolved }.toSet()
        } catch (t: Throwable) {
            log.debug("Login-shell tool probe failed: {}", t.message)
            emptySet()
        }
    }

    private companion object {
        /**
         * 10s for the whole batch. The old code allowed 5s *per tool* across two spawns each; one
         * login shell that has already paid its profile cost resolves the rest almost free.
         */
        const val LOGIN_SHELL_TIMEOUT_SECONDS = 10L

        val WINDOWS_EXECUTABLE_SUFFIXES = listOf(".exe", ".cmd", ".bat", ".com", ".ps1")
    }
}
