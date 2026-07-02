package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Spawns the headless BossTerm daemon as a detached child process, reusing the SAME bundled
 * JRE and classpath as the running GUI — so there is no second bundle to ship and a freshly
 * spawned daemon is, at spawn time, code-identical to the GUI that launched it.
 *
 * NOTE on "version locking": that identity only holds for a daemon THIS GUI spawns. A daemon left
 * running across an app update keeps its old code, and the GUI deliberately does NOT compare app
 * versions to retire it (a routine patch release bumps the version without changing the wire protocol,
 * and refusing every such daemon would needlessly kill the user's sessions). Cross-update
 * compatibility is gated on [DaemonControlChannel.PROTOCOL_VERSION] instead — bump it on any
 * incompatible daemon change so [DaemonClient] refuses a stale daemon rather than attaching to it.
 *
 * Two launch recipes, tried in order:
 *
 * 1. `java -cp …` (dev / `gradlew run`, or any runtime that ships a launcher binary):
 *   - JRE: `<java.home>/bin/java` (mac/Linux), `<java.home>/bin/javaw.exe` (Windows, no console).
 *   - Classpath: `System.getProperty("java.class.path")` verbatim.
 *   - Props propagated: UIElement + version + settings-dir + bundled-resources dir, so the daemon
 *     resolves the same files ([BossTermPaths]) and finds bundled binaries (cloudflared, CLI).
 *   - The daemon does NOT need the GUI's AWT `--add-opens` flags (it runs headless).
 *
 * 2. The app's own native launcher with [DAEMON_ARG] (packaged `.app`/`.exe`/`.deb`): jpackage
 *    runtimes ship NO `bin/java` at all (the app boots through its native launcher + libjli), so
 *    recipe 1 is impossible there. Instead we relaunch the native launcher itself; the GUI facade
 *    (`MainKt`) sees `--daemon` as its first arg and dispatches straight to the daemon entry point
 *    before any AWT/Compose init. Runtime-set props travel as [PROP_ARG_PREFIX] args (a native
 *    launcher can't take `-D` flags), and the launcher's own cfg re-applies the packaged
 *    java-options for free. `apple.awt.UIElement` is set programmatically by the dispatch.
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

    /** First program arg that makes the GUI facade (`MainKt`) run the daemon instead of the app. */
    const val DAEMON_ARG = "--daemon"

    /** Prefix for `--prop:key=value` program args carrying system props into a native-launcher daemon. */
    const val PROP_ARG_PREFIX = "--prop:"

    /** Props the daemon must agree with the GUI on; forwarded by both launch recipes when set. */
    private val PASSTHROUGH_PROPS = listOf(
        BossTermPaths.SETTINGS_DIR_PROPERTY, "bossterm.version", "compose.application.resources.dir",
    )

    /**
     * Apply every [PROP_ARG_PREFIX] arg as a system property and return the remaining args.
     * Called by the GUI facade's `--daemon` dispatch BEFORE the daemon entry point runs, so path
     * and version resolution ([BossTermPaths] et al.) see the forwarded values. Explicit forwards
     * intentionally override same-named props the packaged launcher cfg already set at JVM boot
     * (the runtime-set value — e.g. a custom settings-dir profile — is the authoritative one).
     */
    fun applyPropArgs(args: Array<String>): Array<String> {
        val rest = ArrayList<String>(args.size)
        for (arg in args) {
            if (!arg.startsWith(PROP_ARG_PREFIX)) { rest.add(arg); continue }
            val kv = arg.removePrefix(PROP_ARG_PREFIX)
            val eq = kv.indexOf('=')
            if (eq > 0) System.setProperty(kv.take(eq), kv.substring(eq + 1))
            else log.warn("Ignoring malformed prop arg: {}", arg)
        }
        return rest.toTypedArray()
    }

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
        val javaBin = resolveJavaBinary()
        if (javaBin == null) {
            // Packaged bundles ship no java binary — relaunch the app's native launcher as the
            // daemon instead. Only valid for the real daemon entry (the facade dispatch is
            // hardwired to it), so a custom mainClass (tests) still requires a java binary.
            if (mainClass == DEFAULT_DAEMON_MAIN_CLASS) {
                nativeLauncherCommand(extraArgs)?.let { return it }
            }
            log.error(
                "DaemonLauncher: no java binary under java.home={} and no packaged launcher found",
                System.getProperty("java.home"),
            )
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
            PASSTHROUGH_PROPS.forEach { key -> passthroughProp(key)?.let { add(it) } }
            add("-cp")
            add(classpath)
            add(mainClass)
            addAll(extraArgs)
        }
    }

    /**
     * `<native launcher> --daemon [--prop:key=value …]`, or null when not running from a packaged
     * install. The GUI facade dispatches on [DAEMON_ARG] before any AWT init, and the launcher's
     * cfg re-applies the packaged java-options; only runtime-resolved props need forwarding.
     */
    private fun nativeLauncherCommand(extraArgs: List<String>): List<String>? {
        val launcher = packagedLauncherBinary() ?: return null
        log.info("DaemonLauncher: no bundled java binary; using native launcher {}", launcher.absolutePath)
        return buildList {
            add(launcher.absolutePath)
            add(DAEMON_ARG)
            PASSTHROUGH_PROPS.forEach { key ->
                System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { add("$PROP_ARG_PREFIX$key=$it") }
            }
            addAll(extraArgs)
        }
    }

    /**
     * The packaged app's native launcher binary, derived from `java.home` per jpackage layout, or
     * null in dev / unrecognized layouts. The per-OS derivations are pure functions of [javaHome]
     * (no current-OS checks inside) so ALL of them are unit-tested regardless of the CI platform.
     */
    internal fun packagedLauncherBinary(
        javaHome: String? = System.getProperty("java.home"),
    ): File? {
        val jh = javaHome?.takeIf { it.isNotBlank() } ?: return null
        return when {
            ShellCustomizationUtils.isMacOS() -> macLauncher(jh)
            ShellCustomizationUtils.isWindows() -> windowsLauncher(jh)
            ShellCustomizationUtils.isLinux() -> linuxLauncher(jh)
            else -> null
        }
    }

    /** macOS: `<Bundle>.app/Contents/runtime/Contents/Home` → `<Bundle>.app/Contents/MacOS/<Bundle>`. */
    internal fun macLauncher(javaHome: String): File? {
        val bundle = macBundleFile(javaHome) ?: return null
        return pickLauncher(File(bundle, "Contents/MacOS"), bundle.name.removeSuffix(".app")) { it.canExecute() }
    }

    /** Windows: `<install>\runtime` → `<install>\<App>.exe` (launcher named after the install dir). */
    internal fun windowsLauncher(javaHome: String): File? {
        val home = File(javaHome)
        if (!home.name.equals("runtime", ignoreCase = true)) return null
        val install = home.parentFile ?: return null
        return pickLauncher(install, install.name) { it.extension.equals("exe", ignoreCase = true) }
    }

    /** Linux: `<install>/lib/runtime` → `<install>/bin/<App>` (deb/rpm install dirs are lowercased). */
    internal fun linuxLauncher(javaHome: String): File? {
        val home = File(javaHome)
        if (home.name != "runtime" || home.parentFile?.name != "lib") return null
        val install = home.parentFile.parentFile ?: return null
        return pickLauncher(File(install, "bin"), install.name) { it.canExecute() }
    }

    /**
     * Pick the launcher from [dir]: the [executable] named [preferredName] (case-insensitive,
     * extension ignored — jpackage names the main launcher after the app/install dir), else the
     * directory's SOLE executable. With several candidates and no name match, refuse loudly —
     * exec'ing a guess (an add-launcher, an uninstaller) is worse than failing.
     */
    private fun pickLauncher(dir: File, preferredName: String, executable: (File) -> Boolean): File? {
        val candidates = dir.listFiles()?.filter { it.isFile && executable(it) }.orEmpty()
        candidates.firstOrNull { it.nameWithoutExtension.equals(preferredName, ignoreCase = true) }?.let { return it }
        candidates.singleOrNull()?.let { return it }
        if (candidates.size > 1) {
            log.warn(
                "DaemonLauncher: multiple launcher candidates in {} and none named '{}': {}",
                dir, preferredName, candidates.joinToString { it.name },
            )
        }
        return null
    }

    fun spawn(
        mainClass: String = DEFAULT_DAEMON_MAIN_CLASS,
        extraArgs: List<String> = emptyList(),
    ): Process? {
        val command = buildCommand(mainClass, extraArgs) ?: return null
        val logFile = prepareLogFile()
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
        val logFile = prepareLogFile()
        runCatching {
            ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        }.onSuccess { log.info("openGui: spawned GUI pid={}", it.pid()) }
            .onFailure { log.warn("openGui: spawn failed: {}", it.message) }
    }

    /** The daemon log, created owner-only up front (no world-readable window) — it captures daemon +
     *  GUI stdout/stderr (cwd/title metadata). Rotated once at spawn if oversized, so an always-on
     *  daemon's append-only log can't grow without bound across restarts (a single `.old` is kept). */
    private fun prepareLogFile(): File {
        val logFile = BossTermPaths.daemonLogFile()
        runCatching {
            if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                val old = File(logFile.parentFile, logFile.name + ".old")
                runCatching { old.delete() }
                logFile.renameTo(old)
            }
        }
        BossTermPaths.createOwnerOnly(logFile)
        return logFile
    }

    /** Rotate the daemon log at spawn once it exceeds this size (bounds disk use across restarts). */
    private const val MAX_LOG_BYTES = 10L * 1024 * 1024

    /**
     * Bring a process to the foreground by OS pid (macOS only). A background app can't self-activate
     * on modern macOS, but the daemon activating *another* process via System Events works — may
     * prompt for Accessibility permission the first time. No-op off macOS / on failure.
     */
    fun activatePid(pid: Long) {
        if (!ShellCustomizationUtils.isMacOS()) return
        // The pid is supplied by the (authenticated, loopback) attach client. Best-effort sanity check
        // that it looks like a BossTerm process before foregrounding it — a heuristic, not a hard
        // boundary (see isBossTermProcess). Low risk regardless: post-auth, loopback, and the pid is a
        // Long so the osascript can't be injected.
        if (!isBossTermProcess(pid)) {
            log.debug("activatePid({}) skipped: doesn't look like a BossTerm process", pid)
            return
        }
        runCatching {
            ProcessBuilder(
                "osascript", "-e",
                "tell application \"System Events\" to set frontmost of (first process whose unix id is $pid) to true",
            ).start()
        }.onFailure { log.debug("activatePid({}) failed: {}", pid, it.message) }
    }

    /**
     * Heuristic: does [pid]'s command line look like a BossTerm GUI? Substring match on the GUI main
     * class (dev) or the `.app` bundle (packaged) — tighter than a bare "bossterm" match, but NOT a
     * real identity check: a user-owned process whose argv merely contains one of those strings would
     * also pass. Sufficient as a best-effort guard for [activatePid] (post-auth, loopback, no injection).
     */
    private fun isBossTermProcess(pid: Long): Boolean = runCatching {
        // -ww: don't truncate the command (the GUI's classpath/main-class can be long).
        val proc = ProcessBuilder("ps", "-ww", "-p", pid.toString(), "-o", "command=")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        out.contains(GUI_MAIN_CLASS) || out.contains("BossTerm.app")
    }.getOrDefault(false)

    /** The packaged .app bundle path, derived from java.home, or null in dev / non-bundle runs. */
    private fun macAppBundlePath(): String? {
        if (!ShellCustomizationUtils.isMacOS()) return null
        val jh = System.getProperty("java.home")?.takeIf { it.isNotBlank() } ?: return null
        return macBundleFile(jh)?.path
    }

    /** `<Bundle>.app` containing [path] (e.g. a bundled java.home), or null when not inside a bundle. */
    private fun macBundleFile(path: String): File? {
        // Normalize separators so the derivation stays a pure function of its input: real macOS
        // paths always use '/', but the cross-OS unit tests feed platform-native paths (Windows
        // CI passes '\'). File() converts back to the native separator on construction.
        val p = path.replace('\\', '/')
        return if (p.contains(".app/Contents/")) File(p.substringBefore(".app/Contents/") + ".app") else null
    }

    /** Launch the GUI in a normal (non-headless, non-agent) JVM via the same JRE + classpath. */
    private fun buildGuiCommand(): List<String>? {
        val javaBin = resolveJavaBinary()
        if (javaBin == null) {
            // Packaged Windows/Linux: no bin/java (the same gap the daemon spawn has) — relaunch
            // the native launcher as a plain GUI; its cfg re-applies the packaged java-options and
            // --add-opens. Like the macOS `open <bundle>` path above, a runtime-set settings-dir
            // profile is not forwarded (the GUI facade only parses --prop: args after --daemon).
            return packagedLauncherBinary()?.let { listOf(it.absolutePath) }
        }
        val classpath = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() } ?: return null
        return buildList {
            add(javaBin.absolutePath)
            // GUI is a normal foreground app: do NOT pass headless or UIElement.
            PASSTHROUGH_PROPS.forEach { key -> passthroughProp(key)?.let { add(it) } }
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
