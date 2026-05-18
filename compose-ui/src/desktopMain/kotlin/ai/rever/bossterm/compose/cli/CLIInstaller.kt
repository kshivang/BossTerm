package ai.rever.bossterm.compose.cli

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.io.File
import java.io.FileOutputStream

/**
 * Handles installation of the `bossterm` command line tool.
 */
object CLIInstaller {
    private const val CLI_NAME = "bossterm"

    private val isWindows = ShellCustomizationUtils.isWindows()
    private val isMacOS = ShellCustomizationUtils.isMacOS()
    private val isLinux = ShellCustomizationUtils.isLinux()

    /**
     * Get the install path based on platform.
     * Windows: %LOCALAPPDATA%\BossTerm\bossterm.cmd (no admin needed)
     * macOS/Linux: /usr/local/bin/bossterm
     */
    private fun getInstallPath(): String {
        return if (isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            "$localAppData\\BossTerm\\bossterm.cmd"
        } else {
            "/usr/local/bin/bossterm"
        }
    }

    /**
     * Get the install directory based on platform.
     */
    private fun getInstallDir(): String {
        return if (isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            "$localAppData\\BossTerm"
        } else {
            "/usr/local/bin"
        }
    }

    /**
     * Check if CLI is already installed
     */
    fun isInstalled(): Boolean {
        val file = File(getInstallPath())
        return file.exists() && (isWindows || file.canExecute())
    }

    /**
     * Check if CLI needs update (compare versions)
     */
    fun needsUpdate(): Boolean {
        if (!isInstalled()) return false

        // Windows .cmd doesn't support --version, skip update check
        if (isWindows) return false

        // Read installed version
        val installedVersion = try {
            val process = ProcessBuilder(getInstallPath(), "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            // Parse "BossTerm CLI version X.X.X"
            output.substringAfter("version ").substringBefore("\n").trim()
        } catch (e: Exception) {
            "0.0.0"
        }

        return installedVersion != getCurrentVersion()
    }

    /**
     * Current CLI version. Set by the build via `-Dbossterm.version=<ver>`
     * (see `bossterm-app/build.gradle.kts`); falls back to "dev" when running
     * from an IDE / unpackaged.
     */
    fun getCurrentVersion(): String {
        return System.getProperty("bossterm.version") ?: "dev"
    }

    /**
     * Install the CLI tool. Returns result message.
     */
    fun install(): InstallResult {
        return try {
            // Extract CLI script from resources.
            val scriptContent = getCLIScript()
                ?: return InstallResult.Error(
                    "Couldn't find the canonical bossterm script in the app bundle. " +
                            "Run install.sh from the repo instead, or reinstall BossTerm."
                )

            // Windows: Install to AppData (no admin needed)
            if (isWindows) {
                return installWindows(scriptContent)
            }

            // macOS/Linux: Check if /usr/local/bin exists
            val installDir = File(getInstallDir())
            if (!installDir.exists()) {
                return InstallResult.Error("Directory ${getInstallDir()} does not exist")
            }

            // Try to write directly (might work if user has permissions)
            val targetFile = File(getInstallPath())
            val mcpHelperContent = getMcpHelperScript()  // may be null on Windows-only setups
            val manPageContent = getManPageContent()     // may be null on Windows
            try {
                FileOutputStream(targetFile).use { out ->
                    out.write(scriptContent.toByteArray())
                }
                targetFile.setExecutable(true, false)
                // Best-effort install of the MCP helper next to the script
                // so `bossterm run` / `send` / `logs` work. The bash script
                // discovers the helper via $SCRIPT_DIR/bossterm-mcp.py.
                if (mcpHelperContent != null) {
                    try {
                        val helper = File(installDir, "bossterm-mcp.py")
                        FileOutputStream(helper).use { out ->
                            out.write(mcpHelperContent.toByteArray())
                        }
                        helper.setExecutable(true, false)
                    } catch (_: Exception) {
                        // Non-fatal: MCP-backed subcommands won't work
                        // until the user installs it themselves, but the
                        // launcher itself is in place.
                    }
                }
                // Best-effort install of the man page. Lands in the
                // user-scope `~/.local/share/man/man1` dir to avoid needing
                // sudo for this single side-installer; install.sh handles
                // the /usr/local/share/man path when run with sudo.
                if (manPageContent != null) {
                    try {
                        installManPage(manPageContent)
                    } catch (_: Exception) {
                        // Non-fatal: `man bossterm` won't work but the
                        // script does. User can re-run install.sh for the
                        // system-wide path.
                    }
                }
                InstallResult.Success
            } catch (e: SecurityException) {
                // Need sudo - use AppleScript to request admin privileges
                installWithAdminPrivileges(scriptContent)
            } catch (e: Exception) {
                installWithAdminPrivileges(scriptContent)
            }
        } catch (e: Exception) {
            InstallResult.Error("Failed to install: ${e.message}")
        }
    }

    /**
     * Install CLI on Windows (no admin needed for AppData).
     */
    private fun installWindows(scriptContent: String): InstallResult {
        return try {
            val installPath = getInstallPath()
            val installDir = File(getInstallDir())

            // Create directory if needed
            if (!installDir.exists()) {
                if (!installDir.mkdirs()) {
                    return InstallResult.Error("Failed to create directory: $installDir")
                }
            }

            // Write script
            val targetFile = File(installPath)
            FileOutputStream(targetFile).use { out ->
                out.write(scriptContent.toByteArray())
            }

            // Add to user PATH
            val pathResult = addToWindowsPath(installDir.absolutePath)
            if (pathResult is InstallResult.Error) {
                // Script installed but PATH update failed - warn user
                return InstallResult.SuccessWithWarning(
                    "CLI installed to $installPath but could not update PATH. " +
                    "Add ${installDir.absolutePath} to your PATH manually, or restart your terminal."
                )
            }

            InstallResult.Success
        } catch (e: Exception) {
            InstallResult.Error("Failed to install: ${e.message}")
        }
    }

    /**
     * Add directory to Windows user PATH via registry.
     */
    private fun addToWindowsPath(installDir: String): InstallResult {
        return try {
            // Read current user PATH from registry
            val queryProcess = ProcessBuilder(
                "reg", "query", "HKCU\\Environment", "/v", "Path"
            ).redirectErrorStream(true).start()

            val output = queryProcess.inputStream.bufferedReader().readText()
            queryProcess.waitFor()

            // Extract current PATH value
            val pathMatch = Regex("Path\\s+REG_(?:EXPAND_)?SZ\\s+(.*)").find(output)
            val currentPath = pathMatch?.groupValues?.get(1)?.trim() ?: ""

            // Check if already in PATH
            if (currentPath.contains(installDir, ignoreCase = true)) {
                return InstallResult.Success // Already in PATH
            }

            // Add to PATH
            val newPath = if (currentPath.isEmpty()) installDir else "$currentPath;$installDir"

            val addProcess = ProcessBuilder(
                "reg", "add", "HKCU\\Environment", "/v", "Path", "/t", "REG_EXPAND_SZ", "/d", newPath, "/f"
            ).redirectErrorStream(true).start()

            val addExitCode = addProcess.waitFor()
            if (addExitCode != 0) {
                val error = addProcess.inputStream.bufferedReader().readText()
                return InstallResult.Error("Failed to update PATH: $error")
            }

            // Note: WM_SETTINGCHANGE broadcast would require Win32 API (JNA).
            // New terminal sessions will pick up PATH changes automatically.
            InstallResult.Success
        } catch (e: Exception) {
            InstallResult.Error("Failed to update PATH: ${e.message}")
        }
    }

    /**
     * Uninstall the CLI tool
     */
    fun uninstall(): InstallResult {
        return try {
            val targetFile = File(getInstallPath())
            if (!targetFile.exists()) {
                return InstallResult.Success
            }

            // Windows: Just delete the file (no admin needed)
            if (isWindows) {
                return uninstallWindows()
            }

            try {
                targetFile.delete()
                InstallResult.Success
            } catch (e: Exception) {
                uninstallWithAdminPrivileges()
            }
        } catch (e: Exception) {
            InstallResult.Error("Failed to uninstall: ${e.message}")
        }
    }

    /**
     * Uninstall CLI on Windows.
     */
    private fun uninstallWindows(): InstallResult {
        return try {
            val targetFile = File(getInstallPath())
            if (targetFile.exists()) {
                if (!targetFile.delete()) {
                    return InstallResult.Error("Failed to delete ${getInstallPath()}")
                }
            }

            // Optionally remove from PATH (leave it for now - doesn't hurt)
            // removeFromWindowsPath(getInstallDir())

            InstallResult.Success
        } catch (e: Exception) {
            InstallResult.Error("Failed to uninstall: ${e.message}")
        }
    }

    private fun installWithAdminPrivileges(scriptContent: String): InstallResult {
        return try {
            // Create temp file with script content
            val tempFile = File.createTempFile("bossterm_cli", ".sh")
            tempFile.writeText(scriptContent)
            tempFile.setExecutable(true)

            val installPath = getInstallPath()
            val process = if (isMacOS) {
                // macOS: Use osascript to run with admin privileges
                val script = """
                    do shell script "cp '${tempFile.absolutePath}' '$installPath' && chmod +x '$installPath'" with administrator privileges
                """.trimIndent()
                ProcessBuilder("osascript", "-e", script)
            } else if (isLinux) {
                // Linux: Use pkexec (PolicyKit) for GUI privilege escalation
                ProcessBuilder("pkexec", "sh", "-c", "cp '${tempFile.absolutePath}' '$installPath' && chmod +x '$installPath'")
            } else {
                tempFile.delete()
                return InstallResult.Error("Unsupported platform. Please manually copy the script to $installPath")
            }

            process.redirectErrorStream(true)
            val proc = process.start()
            val exitCode = proc.waitFor()
            tempFile.delete()

            if (exitCode == 0) {
                InstallResult.Success
            } else {
                val error = proc.inputStream.bufferedReader().readText()
                if (error.contains("User canceled") || error.contains("cancelled") || error.contains("dismissed") || exitCode == 126) {
                    InstallResult.Cancelled
                } else {
                    InstallResult.Error("Installation failed: $error")
                }
            }
        } catch (e: Exception) {
            InstallResult.Error("Failed to install with admin privileges: ${e.message}")
        }
    }

    private fun uninstallWithAdminPrivileges(): InstallResult {
        return try {
            val installPath = getInstallPath()
            val process = if (isMacOS) {
                // macOS: Use osascript
                val script = """
                    do shell script "rm -f '$installPath'" with administrator privileges
                """.trimIndent()
                ProcessBuilder("osascript", "-e", script)
            } else if (isLinux) {
                // Linux: Use pkexec
                ProcessBuilder("pkexec", "rm", "-f", installPath)
            } else {
                return InstallResult.Error("Unsupported platform. Please manually remove $installPath")
            }

            process.redirectErrorStream(true)
            val proc = process.start()
            val exitCode = proc.waitFor()

            if (exitCode == 0) {
                InstallResult.Success
            } else {
                val error = proc.inputStream.bufferedReader().readText()
                if (error.contains("User canceled") || error.contains("cancelled") || error.contains("dismissed") || exitCode == 126) {
                    InstallResult.Cancelled
                } else {
                    InstallResult.Error("Uninstall failed: $error")
                }
            }
        } catch (e: Exception) {
            InstallResult.Error("Failed to uninstall: ${e.message}")
        }
    }

    /**
     * Get the CLI script content for the current platform
     */
    /**
     * Locate the canonical `bossterm` script content for the current
     * platform. Returns null if no source is available — callers must
     * propagate that as an error rather than installing a stale stub.
     *
     * Lookup order (mac/Linux):
     *   1. `compose.application.resources.dir` / `bossterm` — Compose
     *      Desktop's runtime resources dir inside a packaged
     *      .app/.deb/.rpm. The right answer at runtime.
     *   2. Classpath resource `bossterm` — for hosts that route the file
     *      through the standard resources path.
     *   3. Repo checkout — walk up from `user.dir` looking for a VERSION
     *      sentinel + `cli-resources/bossterm`. Lets a developer running
     *      from an IDE / `./gradlew :bossterm-app:run` test the in-app
     *      installer against the working tree.
     *   4. null — caller surfaces a useful error.
     *
     * Windows keeps its own embedded `.cmd` because the canonical bash
     * script doesn't cover it; that's tracked separately.
     */
    private fun getCLIScript(): String? {
        if (isWindows) return EMBEDDED_WINDOWS_CLI_SCRIPT
        return findCliResource("bossterm")
    }

    /**
     * Companion Python helper for MCP-backed CLI subcommands. Same lookup
     * order as [getCLIScript]. Returns null if not bundled — callers can
     * still install the bash script, but MCP-backed subcommands won't work
     * until the user installs the helper separately (e.g. via install.sh).
     */
    private fun getMcpHelperScript(): String? {
        if (isWindows) return null  // Helper is mac/Linux only.
        return findCliResource("bossterm-mcp.py")
    }

    /**
     * Troff man page source. Installed to `~/.local/share/man/man1`
     * (user-scope; no sudo) so `man bossterm` works on Linux after a
     * Help → Install CLI flow, matching install.sh's user-scope path.
     * macOS doesn't use man-db, but the file still lands in `MANPATH` for
     * users who configured one. install.sh handles the system-wide path
     * `/usr/local/share/man/man1` when run with sudo.
     */
    private fun getManPageContent(): String? {
        if (isWindows) return null
        return findCliResource("man/man1/bossterm.1")
    }

    private fun installManPage(content: String) {
        val home = System.getProperty("user.home") ?: return
        val dir = File("$home/.local/share/man/man1")
        if (!dir.exists() && !dir.mkdirs()) return
        val target = File(dir, "bossterm.1")
        FileOutputStream(target).use { out ->
            out.write(content.toByteArray())
        }
    }

    /**
     * Shared lookup for any file under `cli-resources/`. See [getCLIScript]
     * for the lookup order; this helper just centralizes the three paths
     * so a future addition (e.g. shell completion scripts) doesn't drift
     * from the existing two.
     */
    private fun findCliResource(relativePath: String): String? {
        // 1. Compose Desktop's runtime resources dir.
        System.getProperty("compose.application.resources.dir")?.let { dir ->
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
        }
        // 2. Classpath fallback. Replace path separators just in case the
        //    OS we're running on rejects forward slashes; the JAR side
        //    accepts the / form universally.
        CLIInstaller::class.java.classLoader?.getResourceAsStream(relativePath)?.use {
            return it.bufferedReader().readText()
        }
        // 3. Dev / IDE fallback: walk up from the JVM's working directory
        //    looking for the repo root (VERSION sentinel + cli-resources/).
        //    This lets a developer running `./gradlew :bossterm-app:run`
        //    exercise the in-app installer without a packaged .app.
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val cur = dir ?: return@repeat
            if (File(cur, "VERSION").isFile && File(cur, "cli-resources").isDirectory) {
                val candidate = File(cur, "cli-resources/$relativePath")
                if (candidate.isFile) return candidate.readText()
                return null
            }
            dir = cur.parentFile
        }
        return null
    }

    sealed class InstallResult {
        object Success : InstallResult()
        object Cancelled : InstallResult()
        data class SuccessWithWarning(val message: String) : InstallResult()
        data class Error(val message: String) : InstallResult()
    }


    // Embedded Windows CLI script (fallback if resource not found)
    private val EMBEDDED_WINDOWS_CLI_SCRIPT = """
@echo off
setlocal enabledelayedexpansion

:: BossTerm CLI Launcher for Windows
:: Usage: bossterm [path] [-d directory] [-n]

set "TARGET_DIR="

:: Parse arguments
:parse_args
if "%~1"=="" goto find_app
if /i "%~1"=="-d" (
    set "TARGET_DIR=%~2"
    shift
    shift
    goto parse_args
)
if /i "%~1"=="-n" (
    shift
    goto parse_args
)
if /i "%~1"=="--help" goto show_help
if /i "%~1"=="-h" goto show_help
set "TARGET_DIR=%~1"
shift
goto parse_args

:find_app
set "APP_PATH="
if exist "%LOCALAPPDATA%\BossTerm\BossTerm.exe" (
    set "APP_PATH=%LOCALAPPDATA%\BossTerm\BossTerm.exe"
    goto found_app
)
if exist "%ProgramFiles%\BossTerm\BossTerm.exe" (
    set "APP_PATH=%ProgramFiles%\BossTerm\BossTerm.exe"
    goto found_app
)
if exist "%ProgramFiles(x86)%\BossTerm\BossTerm.exe" (
    set "APP_PATH=%ProgramFiles(x86)%\BossTerm\BossTerm.exe"
    goto found_app
)
echo BossTerm not found.
echo Please install BossTerm from https://bossterm.dev
exit /b 1

:found_app
if not defined TARGET_DIR set "TARGET_DIR=%CD%"
pushd "%TARGET_DIR%" 2>nul
if errorlevel 1 (
    echo Error: Directory not found: %TARGET_DIR%
    exit /b 1
)
set "BOSSTERM_CWD=!CD!"
popd
set "BOSSTERM_CWD=%BOSSTERM_CWD%"
start "" "%APP_PATH%"
exit /b 0

:show_help
echo BossTerm - Terminal Emulator
echo.
echo Usage: bossterm [path] [-d directory] [-n]
echo.
echo Options:
echo   path         Open BossTerm in the specified directory
echo   -d DIR       Open BossTerm in the specified directory
echo   -n           Open in new window (default behavior)
echo   -h, --help   Show this help message
exit /b 0
    """.trimIndent()
}
