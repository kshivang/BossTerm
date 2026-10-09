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
        get() = BossTermPaths.dir().canonicalFile == File(System.getProperty("user.home"), ".bossterm").canonicalFile

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

    @Synchronized
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

    @Synchronized
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
        BossTermPaths.createOwnerOnly(BossTermPaths.daemonLogFile())
        val content = macPlist(serviceId(), command, BossTermPaths.daemonLogFile().absolutePath)
        val uid = uid()
        if (uid != null) runChecked("launchctl", "enable", "gui/$uid/${serviceId()}")
        // Never bootout a service that's currently registered: bootout KILLS a running daemon (and
        // every session it owns), and with RunAtLoad the follow-up bootstrap starts a fresh daemon
        // immediately — the pair used to race the GUI's own spawn into two live daemons. When the
        // service is registered, writing the plist file is enough: launchd re-reads it at next
        // login, which is exactly when a refreshed baked command matters.
        val registered = uid != null &&
            runCapture("launchctl", "print", "gui/$uid/${serviceId()}").first == 0
        if (registered && runCatching { file.readText() == content }.getOrDefault(false)) return
        file.writeText(content)
        if (registered) return
        // Not registered — register and start it now. Prefer the modern `bootstrap` (load/unload are
        // deprecated and unreliable on recent macOS); fall back to load on older systems or if
        // bootstrap fails. RunAtLoad makes it start next login regardless.
        if (uid != null) {
            run("launchctl", "bootout", "gui/$uid", file.absolutePath) // clear any half-dead reg (ok to fail)
            val (code, _) = runCapture("launchctl", "bootstrap", "gui/$uid", file.absolutePath)
            if (code == 0) return
        }
        runChecked("launchctl", "load", "-w", file.absolutePath)
    }

    private fun uninstallMac() {
        val file = macPlistFile()
        // Disabling future starts does not kill the daemon (and every session it owns), unlike
        // bootout/unload. installMac re-enables the registration when the user opts back in.
        uid()?.let { runChecked("launchctl", "disable", "gui/$it/${serviceId()}") }
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
            // Drop any stale XDG autostart entry first — isInstalled() treats EITHER mechanism as
            // installed, so leaving both would schedule the daemon twice at login.
            runCatching { xdgAutostartFile().delete() }
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
            // No systemd now, but a unit may linger from a prior systemd-enabled state — disable +
            // remove it so we don't schedule the daemon twice (XDG here + the stale systemd unit).
            if (systemdUnitFile().exists()) {
                run("systemctl", "--user", "disable", systemdUnitName())
                runCatching { systemdUnitFile().delete() }
                run("systemctl", "--user", "daemon-reload")
            }
            val file = xdgAutostartFile()
            file.parentFile?.mkdirs()
            file.writeText(xdgDesktop(command))
        }
    }

    private fun uninstallLinux() {
        if (systemdUnitFile().exists()) {
            runChecked("systemctl", "--user", "disable", systemdUnitName())
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
        try {
            writeRegFileUtf16(regFile, windowsRegFile(command))
            runChecked("reg", "import", regFile.absolutePath)
            check(queryWindowsRunValue() == windowsRunValue(command)) { "Windows login command was not stored correctly" }
        } finally { regFile.delete() }
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
        ExecStart=${command.joinToString(" ") { unitQuote(it) }.replace("%", "%%").replace("$", "$$")}
        Restart=on-failure
        RestartSec=2

        [Install]
        WantedBy=default.target
    """.trimIndent() + "\n"

    internal fun xdgDesktop(command: List<String>): String = """
        [Desktop Entry]
        Type=Application
        Name=BossTerm Daemon
        Exec=${command.joinToString(" ") { desktopQuote(it) }.replace("%", "%%")}
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

    /**
     * Quote one arg for a systemd `ExecStart` / `.desktop` `Exec=` value. These are NOT shell-parsed, so
     * shell-style single-quoting (`'\''`) is taken literally and mangles a path containing a single
     * quote (e.g. `/home/o'brien/…`). Wrap in double quotes and escape backslash + double-quote instead
     * — valid C-style escaping in both formats, and a single quote inside double quotes is literal.
     * (`%` is doubled separately by the callers, since both formats expand %-specifiers.)
     */
    private fun unitQuote(arg: String): String =
        if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "-_./:=" }) arg
        else "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Desktop Exec has two escaping passes: the string value, then command-line quotes. */
    internal fun desktopQuote(arg: String): String {
        require(arg.none { it == '\n' || it == '\r' || it == '\u0000' })
        if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "-_./:=" }) return arg
        val escaped = buildString {
            arg.forEach { c ->
                if (c == '\\' || c == '"' || c == '$' || c == '`') append('\\')
                append(c)
            }
        }.replace("\\", "\\\\")
        return "\"$escaped\""
    }

    // ---- process helpers ----

    private fun runChecked(vararg cmd: String) {
        val (code, output) = runCapture(*cmd)
        check(code == 0) { "${cmd.firstOrNull()} failed (exit $code): ${output.trim().take(300)}" }
    }

    private fun run(vararg cmd: String) {
        val (code, output) = runCapture(*cmd)
        if (code != 0) log.debug("{} failed (exit {}): {}", cmd.firstOrNull(), code, output.take(300))
    }

    private fun runCapture(vararg cmd: String): Pair<Int, String> {
        var process: Process? = null
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start().also { process = it }
            p.outputStream.close()
            val output = StringBuilder()
            val reader = kotlin.concurrent.thread(isDaemon = true, name = "bossterm-login-command") {
                runCatching {
                    p.inputStream.bufferedReader().use { stream ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            synchronized(output) {
                                output.append(buffer, 0, count.coerceAtMost((64 * 1024 - output.length).coerceAtLeast(0)))
                            }
                        }
                    }
                }
            }
            val exited = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) p.destroyForcibly()
            reader.join(1000)
            if (!exited) runCatching { p.inputStream.close() }
            (if (exited) p.exitValue() else -1) to synchronized(output) { output.toString() }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            -1 to (e.message ?: "command failed")
        } finally {
            process?.takeIf { it.isAlive }?.let { runCatching { it.destroyForcibly() } }
        }
    }
}
