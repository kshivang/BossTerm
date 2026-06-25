package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Spawns the headless BossTerm daemon as a detached child process, reusing the SAME bundled
 * JRE and classpath as the running GUI — so there is no second bundle to ship and the daemon
 * is inherently version-locked to the GUI.
 *
 * Recipe (works identically under `gradlew run` and a packaged `.app`/`.deb`/`.exe`):
 *   - JRE: `<java.home>/bin/java` (mac/Linux), `<java.home>/bin/javaw.exe` (Windows, no console).
 *   - Classpath: `System.getProperty("java.class.path")` verbatim — jpackage sets it to the full
 *     app jar list, which already includes the daemon's main class and all of compose-ui.
 *   - Props propagated: headless + version + settings-dir + bundled-resources dir, so the daemon
 *     resolves the same files ([BossTermPaths]) and finds bundled binaries (cloudflared, CLI).
 *   - The daemon does NOT need the GUI's AWT `--add-opens` flags (it runs headless).
 *
 * The child is detached (its stdout/stderr append to [BossTermPaths.daemonLogFile]); the JVM does
 * not kill child processes on exit, so the daemon outlives the GUI — exactly what we want.
 */
object DaemonLauncher {
    private val log = LoggerFactory.getLogger(DaemonLauncher::class.java)

    /** Fully-qualified main class of the daemon (in bossterm-app; referenced by name only). */
    const val DEFAULT_DAEMON_MAIN_CLASS = "ai.rever.bossterm.app.DaemonMain"

    /**
     * Launch the daemon. Returns the spawned [Process] (already running, detached), or null if
     * the JRE couldn't be located. Callers then poll [BossTermPaths.daemonPortFile] /
     * [DaemonControlChannel.readEndpoint] for readiness — process-start does not imply listening.
     */
    /**
     * The exact `java … DaemonMain` command used to launch the daemon, or null if the JRE/classpath
     * can't be resolved. Shared by [spawn] (on-demand) and the login service ([LoginServiceManager]),
     * so an at-login daemon is launched identically to an on-demand one. Paths are absolute.
     */
    fun buildCommand(
        mainClass: String = DEFAULT_DAEMON_MAIN_CLASS,
        extraArgs: List<String> = emptyList(),
    ): List<String>? {
        val javaBin = resolveJavaBinary() ?: run {
            log.error("DaemonLauncher: could not locate a java binary under java.home={}", System.getProperty("java.home"))
            return null
        }
        val classpath = System.getProperty("java.class.path")
        if (classpath.isNullOrBlank()) {
            log.error("DaemonLauncher: empty java.class.path; cannot build daemon command")
            return null
        }
        return buildList {
            add(javaBin.absolutePath)
            add("-Djava.awt.headless=true")
            // Propagate the props that affect path/resource/version resolution so the daemon
            // and GUI agree on settings dir, bundled-resource location, and reported version.
            passthroughProp(BossTermPaths.SETTINGS_DIR_PROPERTY)?.let { add(it) }
            passthroughProp("bossterm.version")?.let { add(it) }
            passthroughProp("compose.application.resources.dir")?.let { add(it) }
            add("-cp")
            add(classpath)
            add(mainClass)
            addAll(extraArgs)
        }
    }

    fun spawn(
        mainClass: String = DEFAULT_DAEMON_MAIN_CLASS,
        extraArgs: List<String> = emptyList(),
    ): Process? {
        val command = buildCommand(mainClass, extraArgs) ?: return null
        val logFile = BossTermPaths.daemonLogFile()
        return try {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
                .also { log.info("DaemonLauncher: spawned daemon pid={} (log: {})", it.pid(), logFile.absolutePath) }
        } catch (e: Exception) {
            log.error("DaemonLauncher: failed to spawn daemon: {}", e.message)
            null
        }
    }

    /** `-Dkey=value` for a currently-set system property, or null if unset/blank. */
    private fun passthroughProp(key: String): String? =
        System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { "-D$key=$it" }

    /** The bundled JRE's launcher binary, or null if not found. */
    private fun resolveJavaBinary(): File? {
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() } ?: return null
        val bin = File(javaHome, "bin")
        val candidates = if (ShellCustomizationUtils.isWindows()) {
            // javaw.exe avoids spawning a console window; java.exe is the fallback.
            listOf(File(bin, "javaw.exe"), File(bin, "java.exe"))
        } else {
            listOf(File(bin, "java"))
        }
        return candidates.firstOrNull { it.canExecute() }
    }
}
