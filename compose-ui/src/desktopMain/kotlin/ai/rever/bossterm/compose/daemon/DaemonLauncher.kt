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

    /**
     * Fully-qualified main class of the daemon (in bossterm-app; referenced by name only).
     * NOTE the `Kt` suffix: the daemon entry point is a Kotlin file-level `fun main` in
     * DaemonMain.kt, which compiles to the facade class `DaemonMainKt`. Without the suffix
     * `java -cp … ai.rever.bossterm.app.DaemonMain` fails with ClassNotFoundException and the
     * daemon never starts. (Caught by the out-of-process smoke test, not the in-process unit tests.)
     */
    const val DEFAULT_DAEMON_MAIN_CLASS = "ai.rever.bossterm.app.DaemonMainKt"

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
            // NOT headless: the daemon shows a menu-bar/tray icon (DaemonTray) so a background
            // process isn't invisible. On a no-display host the JVM auto-detects headless and the
            // tray simply no-ops. On macOS run as a UIElement agent so there's a menu-bar item but
            // NO Dock icon / app menu (the daemon isn't a foreground app).
            if (ShellCustomizationUtils.isMacOS()) add("-Dapple.awt.UIElement=true")
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

    /** Main class of the GUI app (Kotlin file-level main in Main.kt → MainKt facade). */
    const val GUI_MAIN_CLASS = "ai.rever.bossterm.app.MainKt"

    /**
     * Show the BossTerm GUI — invoked from the daemon's menu-bar item. Packaged macOS: `open` the
     * .app, which focuses it if already running and launches it otherwise (single-instance focus).
     * Dev / other platforms: spawn a fresh GUI instance via the same JRE + classpath (it attaches
     * to the daemon and renders its sessions). Best-effort; logs on failure.
     */
    fun openGui() {
        macAppBundlePath()?.let { app ->
            runCatching { ProcessBuilder("open", app).start() }
                .onSuccess { log.info("openGui: focused/launched {}", app) }
                .onFailure { log.warn("openGui: `open {}` failed: {}", app, it.message) }
            return
        }
        val cmd = buildGuiCommand() ?: run { log.warn("openGui: cannot build GUI launch command"); return }
        runCatching {
            ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(BossTermPaths.daemonLogFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(BossTermPaths.daemonLogFile()))
                .start()
        }.onSuccess { log.info("openGui: spawned GUI pid={}", it.pid()) }
            .onFailure { log.warn("openGui: spawn failed: {}", it.message) }
    }

    /**
     * Bring a process to the foreground by OS pid (macOS only). A background app can't self-activate
     * on modern macOS, but the daemon activating *another* process via System Events works — may
     * prompt for Accessibility permission the first time. No-op off macOS / on failure.
     */
    fun activatePid(pid: Long) {
        if (!ShellCustomizationUtils.isMacOS()) return
        runCatching {
            ProcessBuilder(
                "osascript", "-e",
                "tell application \"System Events\" to set frontmost of (first process whose unix id is $pid) to true",
            ).start()
        }.onFailure { log.debug("activatePid({}) failed: {}", pid, it.message) }
    }

    /** The packaged .app bundle path, derived from java.home, or null in dev / non-bundle runs. */
    private fun macAppBundlePath(): String? {
        if (!ShellCustomizationUtils.isMacOS()) return null
        val jh = System.getProperty("java.home")?.takeIf { it.isNotBlank() } ?: return null
        return if (jh.contains(".app/Contents/")) jh.substringBefore(".app/Contents/") + ".app" else null
    }

    /** Launch the GUI in a normal (non-headless, non-agent) JVM via the same JRE + classpath. */
    private fun buildGuiCommand(): List<String>? {
        val javaBin = resolveJavaBinary() ?: return null
        val classpath = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() } ?: return null
        return buildList {
            add(javaBin.absolutePath)
            // GUI is a normal foreground app: do NOT pass headless or UIElement.
            passthroughProp(BossTermPaths.SETTINGS_DIR_PROPERTY)?.let { add(it) }
            passthroughProp("bossterm.version")?.let { add(it) }
            passthroughProp("compose.application.resources.dir")?.let { add(it) }
            // Mirror the GUI launcher's AWT --add-opens (global hotkeys / window integration).
            add("--add-opens"); add("java.desktop/java.awt=ALL-UNNAMED")
            if (ShellCustomizationUtils.isMacOS()) { add("--add-opens"); add("java.desktop/sun.lwawt.macosx=ALL-UNNAMED") }
            if (ShellCustomizationUtils.isLinux()) { add("--add-opens"); add("java.desktop/sun.awt.X11=ALL-UNNAMED") }
            add("-cp")
            add(classpath)
            add(GUI_MAIN_CLASS)
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
        // Prefer an executable candidate, but fall back to mere existence: some packaging /
        // mounts under-report the exec bit, and ProcessBuilder.start() will surface the real
        // failure rather than us silently no-op'ing the whole daemon.
        return candidates.firstOrNull { it.canExecute() } ?: candidates.firstOrNull { it.exists() }
    }
}
