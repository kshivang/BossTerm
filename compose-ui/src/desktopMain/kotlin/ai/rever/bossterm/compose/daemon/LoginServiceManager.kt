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
        // Prefer the modern `bootstrap`/`bootout` (load/unload are deprecated and unreliable on
        // recent macOS); fall back to load/unload on older systems or if bootstrap fails. RunAtLoad
        // makes it start next login regardless. Re-bootstrapping refreshes the baked command.
        val uid = uid()
        if (uid != null) {
            run("launchctl", "bootout", "gui/$uid", file.absolutePath) // clear any prior reg (ok to fail)
            val (code, _) = runCapture("launchctl", "bootstrap", "gui/$uid", file.absolutePath)
            if (code == 0) return
        }
        run("launchctl", "unload", file.absolutePath)
        run("launchctl", "load", "-w", file.absolutePath)
    }

    private fun uninstallMac() {
        val file = macPlistFile()
        uid()?.let { run("launchctl", "bootout", "gui/$it", file.absolutePath) }
        run("launchctl", "unload", "-w", file.absolutePath) // older-OS fallback
        file.delete()
    }

    /** Current user's numeric uid for `launchctl … gui/<uid>`, or null if it can't be read. */
    private fun uid(): String? {
        val (code, out) = runCapture("id", "-u")
        return if (code == 0) out.trim().takeIf { it.isNotEmpty() } else null
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
            // `enable --now` is what actually makes the daemon start at login; the unit file alone does
            // NOT autostart. If it fails (no user D-Bus session, linger not enabled, …), remove the
            // orphan unit file and surface the failure — otherwise install() reports success and
            // isInstalled() reads true while start-at-login silently never happens.
            val (code, out) = runCapture("systemctl", "--user", "enable", "--now", systemdUnitName())
            if (code != 0) {
                runCatching { file.delete() }
                run("systemctl", "--user", "daemon-reload")
                error("systemctl --user enable --now failed (exit $code): ${out.trim().take(300)}")
            }
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
        // Store via a `.reg import` of a verbatim file (like the plist/systemd generators) rather than
        // `reg add /v … /d <value>`. Passing the command line as a /d arg goes through ProcessBuilder,
        // which on Windows re-quotes any arg containing spaces — so the already-inner-quoted value
        // would be stored DOUBLE-escaped and fail to launch at login. A .reg file isn't re-quoted.
        val regFile = File(BossTermPaths.dir(), ".bossterm-login.reg")
        runCatching {
            writeRegFileUtf16(regFile, windowsRegFile(command))
            run("reg", "import", regFile.absolutePath)
        }.onFailure { log.warn("Windows login-service install failed: {}", it.message) }
        runCatching { regFile.delete() }
    }

    private fun uninstallWindows() {
        // `reg delete` only carries the value NAME (no spaces), so ProcessBuilder quoting is harmless here.
        run("reg", "delete", WIN_RUN_KEY, "/v", winValueName(), "/f")
    }

    /**
     * The stored Run-key command line, or null if absent. Extracts the actual REG_SZ data (not just
     * the value-name presence) so a corrupt round-trip — e.g. a double-quoted value that won't launch —
     * is observable rather than reading as "installed".
     */
    private fun queryWindowsRunValue(): String? {
        val (code, out) = runCapture("reg", "query", WIN_RUN_KEY, "/v", winValueName())
        if (code != 0) return null
        // `reg query` prints: "    <name>    REG_SZ    <data>". Pull the data after the type token.
        val line = out.lineSequence().firstOrNull { it.contains(winValueName()) && it.contains("REG_SZ") } ?: return null
        return line.substringAfter("REG_SZ").trim().ifEmpty { null }
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

    /** Full registry key path for .reg files (`reg add` accepts HKCU; .reg requires the long form). */
    private val WIN_RUN_KEY_FULL = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /**
     * A Windows .reg file (Version 5.00) setting the Run value to [windowsRunValue]. The value text is
     * .reg-escaped (backslashes doubled, quotes as `\"`) and stored verbatim by `reg import`, so the
     * inner quoting around space-containing paths survives — unlike a `/d` arg mangled by ProcessBuilder.
     */
    internal fun windowsRegFile(command: List<String>): String {
        val escaped = windowsRunValue(command).replace("\\", "\\\\").replace("\"", "\\\"")
        return "Windows Registry Editor Version 5.00\r\n\r\n" +
            "[$WIN_RUN_KEY_FULL]\r\n" +
            "\"${winValueName()}\"=\"$escaped\"\r\n"
    }

    /** Write [content] as UTF-16 LE with a BOM — the encoding `reg import` expects for a Version 5.00 .reg. */
    private fun writeRegFileUtf16(file: File, content: String) {
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + content.toByteArray(Charsets.UTF_16LE))
    }

    internal fun systemdUnit(command: List<String>): String = """
        [Unit]
        Description=BossTerm session daemon

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
        Exec=${command.joinToString(" ") { shQuote(it) }.replace("%", "%%")}
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
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            // Drain the merged stream before waitFor() so a command that emits more than the OS pipe
            // buffer can't deadlock (the same hazard runCapture avoids); we don't need the output here.
            p.inputStream.bufferedReader().readText()
            p.waitFor()
        }.onFailure { log.debug("{} failed: {}", cmd.firstOrNull(), it.message) }
    }

    private fun runCapture(vararg cmd: String): Pair<Int, String> = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() to out
    }.getOrDefault(-1 to "")
}
