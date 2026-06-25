package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Installs/uninstalls a per-OS "start at login" service that launches the BossTerm daemon, so it's
 * always available — even before the GUI is first opened or after a reboot. Runs the SAME command
 * as on-demand spawn ([DaemonLauncher.buildCommand]) with absolute paths baked at install time.
 *
 * Per OS: macOS LaunchAgent plist (`launchctl`), Linux systemd user unit (`systemctl --user`, with
 * an XDG-autostart fallback), Windows `HKCU\…\Run`. Per-profile artifacts are de-collided with
 * [BossTermPaths.profileTag] so a `-Dbossterm.settings.dir` profile gets its own service.
 *
 * Content generation is split into pure functions (unit-tested); install/uninstall shell out and
 * are best-effort (they return [Result]). [isInstalled] reflects the durable artifact (the file /
 * registry value), which is what actually makes the daemon start at login.
 */
object LoginServiceManager {
    private val log = LoggerFactory.getLogger(LoginServiceManager::class.java)

    private val isDefaultProfile: Boolean
        get() = System.getProperty(BossTermPaths.SETTINGS_DIR_PROPERTY).isNullOrBlank()

    /** Base id, suffixed with the profile tag for non-default settings dirs. */
    private fun serviceId(): String =
        "ai.rever.bossterm.daemon" + if (isDefaultProfile) "" else ".${BossTermPaths.profileTag()}"

    // ---- public API ----

    fun isInstalled(): Boolean = runCatching {
        when {
            ShellCustomizationUtils.isMacOS() -> macPlistFile().exists()
            ShellCustomizationUtils.isLinux() -> systemdUnitFile().exists() || xdgAutostartFile().exists()
            ShellCustomizationUtils.isWindows() -> queryWindowsRunValue() != null
            else -> false
        }
    }.getOrDefault(false)

    fun install(): Result<Unit> = runCatching {
        val command = DaemonLauncher.buildCommand()
            ?: error("Could not resolve the daemon launch command (JRE/classpath unavailable)")
        when {
            ShellCustomizationUtils.isMacOS() -> installMac(command)
            ShellCustomizationUtils.isLinux() -> installLinux(command)
            ShellCustomizationUtils.isWindows() -> installWindows(command)
            else -> error("Start-at-login is not supported on this platform")
        }
    }.onFailure { log.warn("Login service install failed: {}", it.message) }

    fun uninstall(): Result<Unit> = runCatching {
        when {
            ShellCustomizationUtils.isMacOS() -> uninstallMac()
            ShellCustomizationUtils.isLinux() -> uninstallLinux()
            ShellCustomizationUtils.isWindows() -> uninstallWindows()
            else -> {}
        }
    }.onFailure { log.warn("Login service uninstall failed: {}", it.message) }

    // ---- macOS ----

    private fun macPlistFile() = File(System.getProperty("user.home"), "Library/LaunchAgents/${serviceId()}.plist")

    private fun installMac(command: List<String>) {
        val file = macPlistFile()
        file.parentFile?.mkdirs()
        file.writeText(macPlist(serviceId(), command, BossTermPaths.daemonLogFile().absolutePath))
        // Immediate load (best-effort; RunAtLoad makes it start next login regardless).
        run("launchctl", "unload", file.absolutePath)
        run("launchctl", "load", "-w", file.absolutePath)
    }

    private fun uninstallMac() {
        val file = macPlistFile()
        run("launchctl", "unload", "-w", file.absolutePath)
        file.delete()
    }

    // ---- Linux ----

    private fun systemdUnitFile() = File(System.getProperty("user.home"), ".config/systemd/user/${systemdUnitName()}")
    private fun systemdUnitName() = "bossterm-daemon" + (if (isDefaultProfile) "" else "-${BossTermPaths.profileTag()}") + ".service"
    private fun xdgAutostartFile() = File(System.getProperty("user.home"), ".config/autostart/${serviceId()}.desktop")

    private fun hasSystemd(): Boolean = runCatching { File("/run/systemd/system").exists() }.getOrDefault(false)

    private fun installLinux(command: List<String>) {
        if (hasSystemd()) {
            val file = systemdUnitFile()
            file.parentFile?.mkdirs()
            file.writeText(systemdUnit(command))
            run("systemctl", "--user", "daemon-reload")
            run("systemctl", "--user", "enable", "--now", systemdUnitName())
        } else {
            val file = xdgAutostartFile()
            file.parentFile?.mkdirs()
            file.writeText(xdgDesktop(command))
        }
    }

    private fun uninstallLinux() {
        if (systemdUnitFile().exists()) {
            run("systemctl", "--user", "disable", "--now", systemdUnitName())
            systemdUnitFile().delete()
            run("systemctl", "--user", "daemon-reload")
        }
        xdgAutostartFile().delete()
    }

    // ---- Windows ----

    private val WIN_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private fun winValueName() = "BossTermDaemon" + (if (isDefaultProfile) "" else "_${BossTermPaths.profileTag()}")

    private fun installWindows(command: List<String>) {
        run("reg", "add", WIN_RUN_KEY, "/v", winValueName(), "/t", "REG_SZ", "/d", windowsRunValue(command), "/f")
    }

    private fun uninstallWindows() {
        run("reg", "delete", WIN_RUN_KEY, "/v", winValueName(), "/f")
    }

    private fun queryWindowsRunValue(): String? {
        val (code, out) = runCapture("reg", "query", WIN_RUN_KEY, "/v", winValueName())
        return if (code == 0 && out.contains(winValueName())) out else null
    }

    // ---- pure content generators (unit-tested) ----

    /**
     * Quote a single arg for a Windows command-line value per the CommandLineToArgvW rules: wrap in
     * double quotes if it contains whitespace or a quote, escaping interior `"` as `\"` and doubling
     * any run of backslashes that immediately precedes the closing quote (so a path ending in `\`
     * doesn't escape the closing quote). Realistically jar paths don't hit these, but be correct.
     */
    internal fun winQuote(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) return arg
        val sb = StringBuilder("\"")
        var backslashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> backslashes++
                '"' -> { repeat(backslashes * 2 + 1) { sb.append('\\') }; backslashes = 0; sb.append('"') }
                else -> { repeat(backslashes) { sb.append('\\') }; backslashes = 0; sb.append(c) }
            }
        }
        repeat(backslashes * 2) { sb.append('\\') } // trailing backslashes before the closing quote
        sb.append('"')
        return sb.toString()
    }

    internal fun windowsRunValue(command: List<String>): String = command.joinToString(" ") { winQuote(it) }

    internal fun systemdUnit(command: List<String>): String = """
        [Unit]
        Description=BossTerm session daemon
        After=default.target

        [Service]
        Type=simple
        ExecStart=${command.joinToString(" ") { shQuote(it) }}
        Restart=on-failure
        RestartSec=2

        [Install]
        WantedBy=default.target
    """.trimIndent() + "\n"

    internal fun xdgDesktop(command: List<String>): String = """
        [Desktop Entry]
        Type=Application
        Name=BossTerm Daemon
        Exec=${command.joinToString(" ") { shQuote(it) }}
        X-GNOME-Autostart-enabled=true
        NoDisplay=true
    """.trimIndent() + "\n"

    internal fun macPlist(label: String, command: List<String>, logPath: String): String {
        // Built by explicit line concatenation (NOT trimIndent) — interpolating a multi-line
        // <string> block into an indented raw literal would skew trimIndent's common-indent.
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        sb.append("<plist version=\"1.0\">\n")
        sb.append("<dict>\n")
        sb.append("    <key>Label</key>\n")
        sb.append("    <string>${xmlEscape(label)}</string>\n")
        sb.append("    <key>ProgramArguments</key>\n")
        sb.append("    <array>\n")
        command.forEach { sb.append("        <string>${xmlEscape(it)}</string>\n") }
        sb.append("    </array>\n")
        sb.append("    <key>RunAtLoad</key>\n")
        sb.append("    <true/>\n")
        sb.append("    <key>KeepAlive</key>\n")
        sb.append("    <dict>\n")
        sb.append("        <key>SuccessfulExit</key>\n")
        sb.append("        <false/>\n")
        sb.append("    </dict>\n")
        sb.append("    <key>StandardOutPath</key>\n")
        sb.append("    <string>${xmlEscape(logPath)}</string>\n")
        sb.append("    <key>StandardErrorPath</key>\n")
        sb.append("    <string>${xmlEscape(logPath)}</string>\n")
        sb.append("</dict>\n")
        sb.append("</plist>\n")
        return sb.toString()
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Minimal POSIX single-arg quote for unit/desktop ExecStart (wrap in single quotes if needed). */
    private fun shQuote(arg: String): String =
        if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "-_./:=" }) arg
        else "'" + arg.replace("'", "'\\''") + "'"

    // ---- process helpers ----

    private fun run(vararg cmd: String) {
        runCatching {
            ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor()
        }.onFailure { log.debug("{} failed: {}", cmd.firstOrNull(), it.message) }
    }

    private fun runCapture(vararg cmd: String): Pair<Int, String> = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() to out
    }.getOrDefault(-1 to "")
}
