package ai.rever.bossterm.compose.update

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Generates platform-specific update helper scripts.
 *
 * These scripts run AFTER the main app quits and handle:
 * - Waiting for app to fully terminate
 * - Performing the actual installation
 * - Launching the updated app
 * - Cleaning up the script itself
 */
object UpdateScriptGenerator {

    /**
     * Escape a string for safe use as a shell argument.
     */
    private fun escapeShellArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /**
     * Escape a string for safe use in Windows batch files.
     */
    private fun escapeWindowsArg(arg: String): String {
        val escapedQuotes = arg.replace("\"", "\"\"")
        val escapedPercent = escapedQuotes.replace("%", "%%")
        return "\"$escapedPercent\""
    }

    /**
     * Validate a path for security concerns.
     */
    private fun validatePath(path: String, description: String) {
        if (path.contains('\u0000')) {
            throw SecurityException("$description contains null byte - possible directory traversal attack")
        }
        if (path.contains('$') || path.contains('`')) {
            throw SecurityException("$description contains shell metacharacters - possible command injection")
        }
        if (path.contains('\n') || path.contains('\r')) {
            throw SecurityException("$description contains newline characters - possible script injection")
        }
        if (path.contains("..")) {
            throw SecurityException("$description contains path traversal sequence '..' - rejected for security")
        }
        if (path.contains(";") || path.contains("|") || path.contains("&")) {
            throw SecurityException("$description contains command separator characters - rejected for security")
        }
        if (path.contains("%") || path.contains("^") || path.contains("!")) {
            throw SecurityException("$description contains Windows batch metacharacters - rejected for security")
        }
    }

    /**
     * Generate macOS update script.
     */
    fun generateMacOSUpdateScript(
        dmgPath: String,
        targetAppPath: String,
        appPid: Long
    ): File {
        validatePath(dmgPath, "DMG path")
        validatePath(targetAppPath, "Target app path")

        val escapedDmgPath = escapeShellArg(dmgPath)
        val escapedTargetAppPath = escapeShellArg(targetAppPath)

        println("🔒 Security: Validated and escaped update script parameters")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updater")
        tempDir.mkdirs()

        val scriptFile = File(tempDir, "update_bossterm_${System.currentTimeMillis()}.sh")

        val script = """
            #!/bin/bash

            # BossTerm Update Helper Script

            echo "BossTerm Update Helper started"
            echo "Waiting for BossTerm to quit (PID: $appPid)..."

            WAIT_COUNT=0
            MAX_WAIT=30
            while kill -0 $appPid 2>/dev/null; do
                sleep 1
                WAIT_COUNT=${'$'}((WAIT_COUNT + 1))
                if [ ${'$'}WAIT_COUNT -ge ${'$'}MAX_WAIT ]; then
                    echo "Timeout waiting for app to quit"
                    exit 1
                fi
            done

            echo "BossTerm has quit. Starting installation..."
            sleep 2

            echo "Mounting DMG: $escapedDmgPath"
            hdiutil attach $escapedDmgPath -nobrowse -quiet
            if [ ${'$'}? -ne 0 ]; then
                echo "Failed to mount DMG"
                open $escapedDmgPath
                exit 1
            fi

            VOLUME=${'$'}(ls -d /Volumes/BossTerm* 2>/dev/null | head -n 1)
            if [ -z "${'$'}VOLUME" ]; then
                echo "Could not find mounted BossTerm volume"
                open $escapedDmgPath
                exit 1
            fi

            echo "Found mounted volume: ${'$'}VOLUME"

            APP_BUNDLE=${'$'}(find "${'$'}VOLUME" -name "*.app" -maxdepth 1 | grep -i bossterm | head -n 1)
            if [ -z "${'$'}APP_BUNDLE" ]; then
                echo "Could not find BossTerm.app in volume"
                hdiutil detach "${'$'}VOLUME" -quiet
                open $escapedDmgPath
                exit 1
            fi

            echo "Found app bundle: ${'$'}APP_BUNDLE"

            echo "Removing old BossTerm: $escapedTargetAppPath"
            if [ -d $escapedTargetAppPath ]; then
                rm -rf $escapedTargetAppPath
                if [ ${'$'}? -ne 0 ]; then
                    echo "Failed to remove old app"
                    hdiutil detach "${'$'}VOLUME" -quiet
                    exit 1
                fi
            fi

            echo "Installing new BossTerm..."
            cp -R "${'$'}APP_BUNDLE" $escapedTargetAppPath
            if [ ${'$'}? -ne 0 ]; then
                echo "Failed to copy new app"
                hdiutil detach "${'$'}VOLUME" -quiet
                exit 1
            fi

            echo "Installation successful!"
            hdiutil detach "${'$'}VOLUME" -quiet

            echo "Launching new BossTerm..."
            open $escapedTargetAppPath

            sleep 2
            rm -f "${'$'}0"
            exit 0
        """.trimIndent()

        scriptFile.writeText(script)
        makeExecutable(scriptFile)

        println("Generated macOS update script: ${scriptFile.absolutePath}")
        return scriptFile
    }

    /**
     * Generate Windows update script.
     */
    fun generateWindowsUpdateScript(
        msiPath: String,
        appPid: Long
    ): File {
        validatePath(msiPath, "MSI path")
        val escapedMsiPath = escapeWindowsArg(msiPath)

        println("🔒 Security: Validated and escaped Windows update script parameters")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updater")
        tempDir.mkdirs()

        val scriptFile = File(tempDir, "update_bossterm_${System.currentTimeMillis()}.bat")

        val script = """
            @echo off
            REM BossTerm Update Helper Script

            echo BossTerm Update Helper started
            echo Waiting for BossTerm to quit (PID: $appPid)...

            :waitloop
            tasklist /FI "PID eq $appPid" 2>NUL | find /I /N "$appPid">NUL
            if "%ERRORLEVEL%"=="0" (
                timeout /t 1 /nobreak >NUL
                goto waitloop
            )

            echo BossTerm has quit. Starting installation...
            timeout /t 2 /nobreak >NUL

            echo Installing update...
            msiexec /i $escapedMsiPath /quiet /norestart

            if %ERRORLEVEL% NEQ 0 (
                echo Installation failed. Opening installer manually...
                start "" $escapedMsiPath
            ) else (
                echo Installation successful!
            )

            timeout /t 2 /nobreak >NUL
            del "%~f0"
        """.trimIndent()

        scriptFile.writeText(script)

        println("Generated Windows update script: ${scriptFile.absolutePath}")
        return scriptFile
    }

    /**
     * Generate Linux Deb update script.
     */
    fun generateLinuxDebUpdateScript(
        debPath: String,
        appPid: Long
    ): File {
        validatePath(debPath, "Deb path")
        val escapedDebPath = escapeShellArg(debPath)

        println("🔒 Security: Validated and escaped Linux Deb update script parameters")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updater")
        tempDir.mkdirs()

        val scriptFile = File(tempDir, "update_bossterm_${System.currentTimeMillis()}.sh")

        val script = """
            #!/bin/bash

            # BossTerm Update Helper Script (Debian/Ubuntu)
            LOG_FILE="/tmp/bossterm-update-debug-${'$'}(date +%s).log"

            # Log everything
            exec > >(tee -a "${'$'}LOG_FILE") 2>&1

            echo "=== BossTerm Update Script Started ==="
            echo "Timestamp: ${'$'}(date)"
            echo "Script PID: ${'$'}${'$'}"
            echo "User: ${'$'}(whoami)"
            echo "DISPLAY: ${'$'}DISPLAY"
            echo "XAUTHORITY: ${'$'}XAUTHORITY"
            echo "Package: $escapedDebPath"
            echo "Target PID: $appPid"
            echo ""

            echo "[1/5] Waiting for BossTerm to quit (PID: $appPid)..."
            WAIT_COUNT=0
            MAX_WAIT=30
            while kill -0 $appPid 2>/dev/null; do
                sleep 1
                WAIT_COUNT=${'$'}((WAIT_COUNT + 1))
                if [ ${'$'}WAIT_COUNT -ge ${'$'}MAX_WAIT ]; then
                    echo "❌ ERROR: Timeout waiting for app to quit after ${'$'}MAX_WAIT seconds"
                    exit 1
                fi
            done
            echo "✅ App quit detected after ${'$'}WAIT_COUNT seconds"
            sleep 2

            echo ""
            echo "[2/5] Installing Deb package: $escapedDebPath"

            # Try pkexec first (works well in normal desktop sessions)
            if command -v pkexec &> /dev/null && [ -n "${'$'}DISPLAY" ]; then
                echo "Trying pkexec for installation..."
                timeout 10 pkexec dpkg -i $escapedDebPath &
                PKEXEC_PID=${'$'}!
                sleep 2

                # Check if pkexec is still running (waiting for auth)
                if kill -0 ${'$'}PKEXEC_PID 2>/dev/null; then
                    echo "✅ pkexec authentication dialog should be visible"
                    wait ${'$'}PKEXEC_PID
                    INSTALL_RESULT=${'$'}?
                else
                    echo "⚠️ pkexec exited immediately, trying sudo with graphical prompt..."
                    INSTALL_RESULT=1
                fi
            else
                echo "ℹ️ pkexec not available or no DISPLAY, will use sudo"
                INSTALL_RESULT=1
            fi

            # Fallback to sudo with graphical askpass if pkexec failed
            if [ ${'$'}INSTALL_RESULT -ne 0 ]; then
                ASKPASS_SCRIPT="/tmp/bossterm-askpass-${'$'}${'$'}.sh"
                if command -v zenity &> /dev/null && [ -n "${'$'}DISPLAY" ]; then
                    echo "Using sudo with zenity for graphical authentication..."
                    cat > "${'$'}ASKPASS_SCRIPT" << 'ASKPASS_EOF'
#!/bin/bash
zenity --password --title="BossTerm Update Authentication" --text="Enter your password to install the BossTerm update:"
ASKPASS_EOF
                    chmod +x "${'$'}ASKPASS_SCRIPT"
                    export SUDO_ASKPASS="${'$'}ASKPASS_SCRIPT"
                    sudo -A dpkg -i $escapedDebPath
                    INSTALL_RESULT=${'$'}?
                    rm -f "${'$'}ASKPASS_SCRIPT"
                elif command -v kdialog &> /dev/null && [ -n "${'$'}DISPLAY" ]; then
                    echo "Using sudo with kdialog for graphical authentication..."
                    cat > "${'$'}ASKPASS_SCRIPT" << 'ASKPASS_EOF'
#!/bin/bash
kdialog --password "Enter your password to install the BossTerm update:"
ASKPASS_EOF
                    chmod +x "${'$'}ASKPASS_SCRIPT"
                    export SUDO_ASKPASS="${'$'}ASKPASS_SCRIPT"
                    sudo -A dpkg -i $escapedDebPath
                    INSTALL_RESULT=${'$'}?
                    rm -f "${'$'}ASKPASS_SCRIPT"
                elif command -v sudo &> /dev/null; then
                    echo "Using sudo for installation..."
                    sudo dpkg -i $escapedDebPath
                    INSTALL_RESULT=${'$'}?
                else
                    echo "❌ ERROR: No elevation method available"
                    exit 1
                fi
            fi

            echo "Installation result: exit code ${'$'}INSTALL_RESULT"

            if [ ${'$'}INSTALL_RESULT -ne 0 ]; then
                echo "⚠️ Installation failed, attempting to fix dependencies..."
                if command -v sudo &> /dev/null; then
                    sudo apt-get install -f -y
                    INSTALL_RESULT=${'$'}?
                    echo "Dependency fix result: exit code ${'$'}INSTALL_RESULT"
                fi
            fi

            # Only proceed with post-installation steps if installation succeeded
            if [ ${'$'}INSTALL_RESULT -eq 0 ]; then
                echo "✅ Installation successful"

                echo ""
                echo "[3/5] Fixing StartupWMClass in desktop file..."
                DESKTOP_FILE="/usr/share/applications/bossterm-BossTerm.desktop"
                if [ -f "${'$'}DESKTOP_FILE" ] && ! grep -q "StartupWMClass" "${'$'}DESKTOP_FILE"; then
                    if command -v sudo &> /dev/null; then
                        echo "StartupWMClass=bossterm" | sudo tee -a "${'$'}DESKTOP_FILE" > /dev/null
                    fi
                    echo "✅ Added StartupWMClass to desktop file"
                else
                    echo "ℹ️ StartupWMClass already present or desktop file not found"
                fi

                echo ""
                echo "[4/5] Refreshing desktop database..."
                if command -v update-desktop-database &> /dev/null; then
                    update-desktop-database /usr/share/applications 2>/dev/null || true
                    echo "✅ Desktop database refreshed"
                else
                    echo "ℹ️ update-desktop-database not available, skipping"
                fi

                echo ""
                echo "[5/5] Launching BossTerm..."
                if [ -x /opt/bossterm/bin/BossTerm ]; then
                    nohup /opt/bossterm/bin/BossTerm > /dev/null 2>&1 &
                    echo "✅ Launched from /opt/bossterm/bin/BossTerm"
                elif [ -x /usr/bin/bossterm ]; then
                    nohup /usr/bin/bossterm > /dev/null 2>&1 &
                    echo "✅ Launched from /usr/bin/bossterm"
                else
                    echo "⚠️ WARNING: Could not find BossTerm executable"
                fi

                sleep 2
                echo ""
                echo "=== Update Script Completed Successfully ==="
                echo "Log file: ${'$'}LOG_FILE"
                rm -f "${'$'}0"
                exit 0
            else
                echo "❌ ERROR: Installation failed with exit code ${'$'}INSTALL_RESULT"
                echo "Log file: ${'$'}LOG_FILE"
                echo "You can manually install with: sudo dpkg -i $escapedDebPath"
                exit 1
            fi
        """.trimIndent()

        scriptFile.writeText(script)
        makeExecutable(scriptFile)

        println("Generated Linux Deb update script: ${scriptFile.absolutePath}")
        return scriptFile
    }

    /**
     * Generate Linux RPM update script.
     */
    fun generateLinuxRpmUpdateScript(
        rpmPath: String,
        appPid: Long
    ): File {
        validatePath(rpmPath, "RPM path")
        val escapedRpmPath = escapeShellArg(rpmPath)

        println("🔒 Security: Validated and escaped Linux RPM update script parameters")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updater")
        tempDir.mkdirs()

        val scriptFile = File(tempDir, "update_bossterm_${System.currentTimeMillis()}.sh")

        val script = """
            #!/bin/bash

            # BossTerm Update Helper Script (Fedora/RHEL)
            LOG_FILE="/tmp/bossterm-update-debug-${'$'}(date +%s).log"

            # Log everything
            exec > >(tee -a "${'$'}LOG_FILE") 2>&1

            echo "=== BossTerm Update Script Started ==="
            echo "Timestamp: ${'$'}(date)"
            echo "Script PID: ${'$'}${'$'}"
            echo "User: ${'$'}(whoami)"
            echo "DISPLAY: ${'$'}DISPLAY"
            echo "XAUTHORITY: ${'$'}XAUTHORITY"
            echo "Package: $escapedRpmPath"
            echo "Target PID: $appPid"
            echo ""

            echo "[1/5] Waiting for BossTerm to quit (PID: $appPid)..."
            WAIT_COUNT=0
            MAX_WAIT=30
            while kill -0 $appPid 2>/dev/null; do
                sleep 1
                WAIT_COUNT=${'$'}((WAIT_COUNT + 1))
                if [ ${'$'}WAIT_COUNT -ge ${'$'}MAX_WAIT ]; then
                    echo "❌ ERROR: Timeout waiting for app to quit after ${'$'}MAX_WAIT seconds"
                    exit 1
                fi
            done
            echo "✅ App quit detected after ${'$'}WAIT_COUNT seconds"
            sleep 2

            echo ""
            echo "[2/5] Installing RPM package: $escapedRpmPath"

            # Try pkexec first (works well in normal desktop sessions)
            if command -v pkexec &> /dev/null && [ -n "${'$'}DISPLAY" ]; then
                echo "Trying pkexec for installation..."
                timeout 10 pkexec rpm -U $escapedRpmPath &
                PKEXEC_PID=${'$'}!
                sleep 2

                # Check if pkexec is still running (waiting for auth)
                if kill -0 ${'$'}PKEXEC_PID 2>/dev/null; then
                    echo "✅ pkexec authentication dialog should be visible"
                    wait ${'$'}PKEXEC_PID
                    INSTALL_RESULT=${'$'}?
                else
                    echo "⚠️ pkexec exited immediately, trying sudo with graphical prompt..."
                    INSTALL_RESULT=1
                fi
            else
                echo "ℹ️ pkexec not available or no DISPLAY, will use sudo"
                INSTALL_RESULT=1
            fi

            # Fallback to sudo with graphical askpass if pkexec failed
            if [ ${'$'}INSTALL_RESULT -ne 0 ]; then
                ASKPASS_SCRIPT="/tmp/bossterm-askpass-${'$'}${'$'}.sh"
                if command -v zenity &> /dev/null && [ -n "${'$'}DISPLAY" ]; then
                    echo "Using sudo with zenity for graphical authentication..."
                    cat > "${'$'}ASKPASS_SCRIPT" << 'ASKPASS_EOF'
#!/bin/bash
zenity --password --title="BossTerm Update Authentication" --text="Enter your password to install the BossTerm update:"
ASKPASS_EOF
                    chmod +x "${'$'}ASKPASS_SCRIPT"
                    export SUDO_ASKPASS="${'$'}ASKPASS_SCRIPT"
                    sudo -A rpm -U $escapedRpmPath
                    INSTALL_RESULT=${'$'}?
                    rm -f "${'$'}ASKPASS_SCRIPT"
                elif command -v kdialog &> /dev/null && [ -n "${'$'}DISPLAY" ]; then
                    echo "Using sudo with kdialog for graphical authentication..."
                    cat > "${'$'}ASKPASS_SCRIPT" << 'ASKPASS_EOF'
#!/bin/bash
kdialog --password "Enter your password to install the BossTerm update:"
ASKPASS_EOF
                    chmod +x "${'$'}ASKPASS_SCRIPT"
                    export SUDO_ASKPASS="${'$'}ASKPASS_SCRIPT"
                    sudo -A rpm -U $escapedRpmPath
                    INSTALL_RESULT=${'$'}?
                    rm -f "${'$'}ASKPASS_SCRIPT"
                elif command -v sudo &> /dev/null; then
                    echo "Using sudo for installation..."
                    sudo rpm -U $escapedRpmPath
                    INSTALL_RESULT=${'$'}?
                else
                    echo "❌ ERROR: No elevation method available"
                    exit 1
                fi
            fi

            echo "Installation result: exit code ${'$'}INSTALL_RESULT"

            # Only proceed with post-installation steps if installation succeeded
            if [ ${'$'}INSTALL_RESULT -eq 0 ]; then
                echo "✅ Installation successful"

                echo ""
                echo "[3/5] Fixing StartupWMClass in desktop file..."
                DESKTOP_FILE="/usr/share/applications/bossterm-BossTerm.desktop"
                if [ -f "${'$'}DESKTOP_FILE" ] && ! grep -q "StartupWMClass" "${'$'}DESKTOP_FILE"; then
                    if command -v sudo &> /dev/null; then
                        echo "StartupWMClass=bossterm" | sudo tee -a "${'$'}DESKTOP_FILE" > /dev/null
                    fi
                    echo "✅ Added StartupWMClass to desktop file"
                else
                    echo "ℹ️ StartupWMClass already present or desktop file not found"
                fi

                echo ""
                echo "[4/5] Refreshing desktop database..."
                if command -v update-desktop-database &> /dev/null; then
                    update-desktop-database /usr/share/applications 2>/dev/null || true
                    echo "✅ Desktop database refreshed"
                else
                    echo "ℹ️ update-desktop-database not available, skipping"
                fi

                echo ""
                echo "[5/5] Launching BossTerm..."
                if [ -x /opt/bossterm/bin/BossTerm ]; then
                    nohup /opt/bossterm/bin/BossTerm > /dev/null 2>&1 &
                    echo "✅ Launched from /opt/bossterm/bin/BossTerm"
                elif [ -x /usr/bin/bossterm ]; then
                    nohup /usr/bin/bossterm > /dev/null 2>&1 &
                    echo "✅ Launched from /usr/bin/bossterm"
                else
                    echo "⚠️ WARNING: Could not find BossTerm executable"
                fi

                sleep 2
                echo ""
                echo "=== Update Script Completed Successfully ==="
                echo "Log file: ${'$'}LOG_FILE"
                rm -f "${'$'}0"
                exit 0
            else
                echo "❌ ERROR: RPM installation failed with exit code ${'$'}INSTALL_RESULT"
                echo "Log file: ${'$'}LOG_FILE"
                echo "You can manually install with: sudo rpm -U $escapedRpmPath"
                exit 1
            fi
        """.trimIndent()

        scriptFile.writeText(script)
        makeExecutable(scriptFile)

        println("Generated Linux RPM update script: ${scriptFile.absolutePath}")
        return scriptFile
    }

    /**
     * Launch the update script in the background.
     */
    fun launchScript(scriptFile: File) {
        try {
            val logDir = File("/tmp/bossterm-updater")
            logDir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val logFile = File(logDir, "update-${timestamp}.log")

            val command = when {
                ShellCustomizationUtils.isMacOS() || ShellCustomizationUtils.isLinux() -> {
                    listOf("nohup", "bash", scriptFile.absolutePath)
                }
                ShellCustomizationUtils.isWindows() -> {
                    listOf("cmd", "/c", "start", "/b", scriptFile.absolutePath)
                }
                else -> {
                    listOf("bash", scriptFile.absolutePath)
                }
            }

            println("Launching update script: ${command.joinToString(" ")}")
            println("Log file: ${logFile.absolutePath}")

            val processBuilder = ProcessBuilder(command)

            // CRITICAL FIX: Redirect to log file instead of DISCARD
            // This allows pkexec to communicate with authentication agent
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile))

            val process = processBuilder.start()

            // Monitor process briefly
            Thread.sleep(500)
            if (!process.isAlive) {
                val exitCode = process.exitValue()
                println("WARNING: Update script exited immediately with code: $exitCode")
                println("Check log: ${logFile.absolutePath}")
            } else {
                println("✅ Update script launched successfully")
                println("💡 Monitor progress: tail -f ${logFile.absolutePath}")
            }
        } catch (e: Exception) {
            println("❌ Failed to launch update script: ${e.message}")
            throw e
        }
    }

    /**
     * Make a file executable (Unix-like systems).
     */
    fun makeExecutable(file: File) {
        try {
            val path = file.toPath()
            val permissions = mutableSetOf<PosixFilePermission>()
            permissions.add(PosixFilePermission.OWNER_READ)
            permissions.add(PosixFilePermission.OWNER_WRITE)
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            permissions.add(PosixFilePermission.GROUP_READ)
            permissions.add(PosixFilePermission.GROUP_EXECUTE)
            permissions.add(PosixFilePermission.OTHERS_READ)
            permissions.add(PosixFilePermission.OTHERS_EXECUTE)

            Files.setPosixFilePermissions(path, permissions)
        } catch (e: Exception) {
            // Not a POSIX system (Windows) - ignore
        }
    }
}
