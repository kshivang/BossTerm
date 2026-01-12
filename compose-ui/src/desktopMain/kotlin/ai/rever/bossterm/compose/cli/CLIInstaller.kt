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
     * Get current CLI version from embedded resource
     */
    fun getCurrentVersion(): String {
        return "1.0.0" // TODO: Read from build config
    }

    /**
     * Install the CLI tool. Returns result message.
     */
    fun install(): InstallResult {
        return try {
            // Extract CLI script from resources
            val scriptContent = getCLIScript()

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
            try {
                FileOutputStream(targetFile).use { out ->
                    out.write(scriptContent.toByteArray())
                }
                targetFile.setExecutable(true, false)
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
    private fun getCLIScript(): String {
        // Determine platform-specific resource path
        val resourcePath = when {
            isWindows -> "windows/bossterm.cmd"
            isMacOS -> "macos/bossterm"
            isLinux -> "linux/bossterm"
            else -> "macos/bossterm" // fallback
        }

        // Try to load from resources first
        val resourceStream = CLIInstaller::class.java.classLoader?.getResourceAsStream(resourcePath)
        if (resourceStream != null) {
            return resourceStream.bufferedReader().readText()
        }

        // Fallback: for Windows, return embedded Windows script
        if (isWindows) {
            return EMBEDDED_WINDOWS_CLI_SCRIPT
        }

        // Fallback to embedded script (macOS version)
        return EMBEDDED_CLI_SCRIPT
    }

    sealed class InstallResult {
        object Success : InstallResult()
        object Cancelled : InstallResult()
        data class SuccessWithWarning(val message: String) : InstallResult()
        data class Error(val message: String) : InstallResult()
    }

    // Embedded CLI script (fallback if resource not found)
    private val EMBEDDED_CLI_SCRIPT = """
#!/usr/bin/env bash
#
# BossTerm CLI Launcher Script
# Version: 1.0.0
#

APP_PATH="/Applications/BossTerm.app"
APP_NAME="BossTerm"
VERSION="1.0.0"

check_app() {
    if [ ! -d "${'$'}APP_PATH" ]; then
        echo "Error: BossTerm.app not found at ${'$'}APP_PATH"
        exit 1
    fi
}

open_bossterm() {
    open -a "${'$'}APP_NAME" "${'$'}@"
}

expand_path() {
    local path="${'$'}1"
    path="${'$'}{path/#\~/${'$'}HOME}"
    if [[ ! "${'$'}path" =~ ^/ ]]; then
        path="${'$'}(cd "${'$'}path" 2>/dev/null && pwd || echo "${'$'}(pwd)/${'$'}path")"
    fi
    echo "${'$'}{path}"
}

show_help() {
    cat <<EOF
BossTerm - Modern Terminal Emulator
Version: ${'$'}VERSION

Usage:
  bossterm                      Open BossTerm
  bossterm <path>               Open BossTerm in directory
  bossterm -d <path>            Open BossTerm in specified directory
  bossterm -c <command>         Execute command (coming soon)
  bossterm --new-window         Open a new window

Options:
  -d, --directory <path>   Start in specified directory
  -c, --command <cmd>      Execute command after opening
  -n, --new-window         Force open a new window
  -v, --version            Show version information
  -h, --help               Show this help message

EOF
}

main() {
    check_app

    if [ ${'$'}# -eq 0 ]; then
        open_bossterm
        exit 0
    fi

    case "${'$'}1" in
        -h|--help|help)
            show_help
            exit 0
            ;;
        -v|--version|version)
            echo "BossTerm CLI version ${'$'}VERSION"
            exit 0
            ;;
        -n|--new-window)
            open_bossterm -n
            exit 0
            ;;
        -d|--directory)
            if [ -z "${'$'}2" ]; then
                echo "Error: Directory path required"
                exit 1
            fi
            dir_path=${'$'}(expand_path "${'$'}2")
            if [ ! -d "${'$'}dir_path" ]; then
                echo "Error: Directory not found: ${'$'}dir_path"
                exit 1
            fi
            BOSSTERM_CWD="${'$'}dir_path" open_bossterm
            exit 0
            ;;
        -c|--command)
            if [ -z "${'$'}2" ]; then
                echo "Error: Command required"
                exit 1
            fi
            echo "Note: Command execution coming soon"
            open_bossterm
            exit 0
            ;;
        -*)
            echo "Error: Unknown option: ${'$'}1"
            echo "Run 'bossterm --help' for usage"
            exit 1
            ;;
        *)
            path=${'$'}(expand_path "${'$'}1")
            if [ -d "${'$'}path" ]; then
                BOSSTERM_CWD="${'$'}path" open_bossterm
                exit 0
            elif [ -f "${'$'}path" ]; then
                parent_dir=${'$'}(dirname "${'$'}path")
                BOSSTERM_CWD="${'$'}parent_dir" open_bossterm
                exit 0
            else
                echo "Error: Path not found: ${'$'}1"
                exit 1
            fi
            ;;
    esac
}

main "${'$'}@"
    """.trimIndent()

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
