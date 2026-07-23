package ai.rever.bossterm.compose.update

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val APP_TRANSLOCATION_PATH_SEGMENT = "/AppTranslocation/"
private const val MACOS_APP_BUNDLE_SUFFIX = ".app"
private const val MACOS_APPLICATIONS_DIRECTORY = "/Applications"
private const val SPOTLIGHT_LOOKUP_TIMEOUT_SECONDS = 5L

/** Return the outermost complete `.app` path segment in [path]. */
internal fun macOSAppBundlePathIn(path: String): String? {
    val pathSegments = path.split('/')
    val bundleIndex = pathSegments.indexOfFirst {
        it.length > MACOS_APP_BUNDLE_SUFFIX.length &&
            it.endsWith(MACOS_APP_BUNDLE_SUFFIX)
    }
    if (bundleIndex < 0) return null

    return pathSegments.take(bundleIndex + 1).joinToString("/")
}

/** Extract the first app bundle represented in `java.library.path`. */
internal fun macOSAppBundlePathFromLibraryPath(libraryPath: String): String? {
    return libraryPath
        .split(File.pathSeparatorChar)
        .firstNotNullOfOrNull(::macOSAppBundlePathIn)
}

/**
 * Resolve a macOS app bundle path without coupling the decision logic to the
 * filesystem or Spotlight.
 */
internal fun realAppPathFor(
    path: String,
    appExists: (String) -> Boolean,
    installedAppLookup: () -> String?
): String {
    if (!path.contains(APP_TRANSLOCATION_PATH_SEGMENT)) return path

    val bundleName = macOSAppBundlePathIn(
        path.substringAfter(APP_TRANSLOCATION_PATH_SEGMENT)
    )
        ?.substringAfterLast('/')
        ?: return path

    val applicationsPath = "$MACOS_APPLICATIONS_DIRECTORY/$bundleName"
    if (appExists(applicationsPath)) return applicationsPath

    return installedAppLookup()?.takeIf(appExists) ?: path
}

/**
 * Platform-specific update installation logic.
 *
 * Handles the actual installation of updates for different platforms.
 * For macOS, it uses a helper script pattern to safely install updates after the app quits.
 */
object UpdateInstaller {

    /**
     * Validate download file for security concerns.
     */
    private fun validateDownloadFile(downloadFile: File, expectedExtension: String) {
        if (!downloadFile.exists()) {
            throw SecurityException("Download file does not exist: ${downloadFile.absolutePath}")
        }
        if (!downloadFile.name.endsWith(expectedExtension, ignoreCase = true)) {
            throw SecurityException("Invalid file extension. Expected $expectedExtension but got: ${downloadFile.name}")
        }
        val canonicalPath = try {
            downloadFile.canonicalPath
        } catch (e: Exception) {
            throw SecurityException("Failed to canonicalize path: ${downloadFile.absolutePath}")
        }

        val expectedTempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updates").canonicalPath
        if (!canonicalPath.startsWith(expectedTempDir)) {
            println("⚠️ Security Warning: Download file outside expected directory")
        }

        val filename = downloadFile.name
        if (filename.contains('\u0000') || filename.contains('\n') || filename.contains('\r')) {
            throw SecurityException("Filename contains invalid characters: $filename")
        }
    }

    /**
     * Extract version from update file name.
     */
    private fun extractVersionFromFilename(file: File): Version? {
        return try {
            val filename = file.name
            val versionStr = when {
                // macOS: BossTerm-1.0.0.dmg
                filename.endsWith(".dmg") -> filename
                    .removePrefix("BossTerm-")
                    .removeSuffix(".dmg")
                // Windows: BossTerm-1.0.0.msi
                filename.endsWith(".msi") -> filename
                    .removePrefix("BossTerm-")
                    .removeSuffix(".msi")
                // Linux deb: bossterm_1.0.0_amd64.deb or bossterm_1.0.0_arm64.deb
                filename.endsWith(".deb") -> filename
                    .removePrefix("bossterm_")
                    .removeSuffix("_amd64.deb")
                    .removeSuffix("_arm64.deb")
                // Linux rpm: bossterm-1.0.0.x86_64.rpm or bossterm-1.0.0.aarch64.rpm
                filename.endsWith(".rpm") -> filename
                    .removePrefix("bossterm-")
                    .removeSuffix(".x86_64.rpm")
                    .removeSuffix(".aarch64.rpm")
                // JAR: bossterm-1.0.0.jar
                filename.endsWith(".jar") -> filename
                    .removePrefix("bossterm-")
                    .removePrefix("BossTerm-")
                    .removeSuffix(".jar")
                else -> filename
            }

            Version.parse(versionStr)
        } catch (e: Exception) {
            println("Failed to extract version from filename: ${e.message}")
            null
        }
    }

    /**
     * Verify update is not a downgrade.
     */
    private fun verifyNoDowngrade(downloadFile: File): Boolean {
        val downloadedVersion = extractVersionFromFilename(downloadFile)

        if (downloadedVersion == null) {
            println("⚠️ Cannot verify update version - version extraction failed")
            return true
        }

        val currentVersion = Version.CURRENT

        println("Version check:")
        println("  Current: $currentVersion")
        println("  Download: $downloadedVersion")

        if (downloadedVersion < currentVersion) {
            println("❌ DOWNGRADE DETECTED!")
            return false
        }

        if (downloadedVersion == currentVersion) {
            println("⚠️ Same version detected ($downloadedVersion) - allowing reinstall")
        } else {
            println("✅ Update verified: $currentVersion → $downloadedVersion")
        }

        return true
    }

    /**
     * Install update for the current platform.
     */
    suspend fun installUpdate(downloadPath: String): InstallResult {
        return try {
            val downloadFile = File(downloadPath)
            if (!downloadFile.exists()) {
                return InstallResult.Error("Update file not found")
            }

            if (!verifyNoDowngrade(downloadFile)) {
                return InstallResult.Error("Cannot install older version")
            }

            when (getCurrentPlatform()) {
                "macOS" -> installMacOSUpdate(downloadFile)
                "Windows" -> installWindowsUpdate(downloadFile)
                "Linux-deb" -> installLinuxDebUpdate(downloadFile)
                "Linux-rpm" -> installLinuxRpmUpdate(downloadFile)
                else -> installJarUpdate(downloadFile)
            }
        } catch (e: Exception) {
            println("Error installing update: ${e.message}")
            InstallResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Install macOS update using helper script pattern.
     */
    private suspend fun installMacOSUpdate(downloadFile: File): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                println("Starting macOS update installation...")
                validateDownloadFile(downloadFile, ".dmg")

                val currentAppPath = getCurrentApplicationPath()
                if (currentAppPath == null) {
                    println("⚠️ Could not determine current application path")
                    return@withContext openDMGForManualInstallation(downloadFile)
                }

                println("🎯 Target application path: $currentAppPath")

                // Verify DMG
                println("📦 Mounting DMG for verification...")
                val mountTest = ProcessBuilder(
                    "hdiutil", "attach", downloadFile.absolutePath,
                    "-nobrowse", "-quiet", "-verify"
                ).start()
                mountTest.waitFor()

                if (mountTest.exitValue() != 0) {
                    return@withContext InstallResult.Error("Failed to mount DMG for verification")
                }

                val mountedVolume = findMountedBossTermVolume()
                if (mountedVolume == null) {
                    cleanupDMG(null)
                    return@withContext InstallResult.Error("Could not locate mounted DMG volume")
                }

                try {
                    val appBundle = findAppBundleInVolume(mountedVolume)
                        ?: throw IllegalStateException("Could not find BossTerm.app in mounted DMG")
                    println("✅ DMG verified successfully (found: ${appBundle.name})")
                } finally {
                    cleanupDMG(mountedVolume)
                }

                val currentPid = ProcessHandle.current().pid()
                println("📝 Generating update script (PID: $currentPid)")

                val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
                    dmgPath = downloadFile.absolutePath,
                    targetAppPath = currentAppPath,
                    appPid = currentPid
                )

                println("🚀 Launching update script")
                UpdateScriptGenerator.launchScript(scriptFile)

                InstallResult.RequiresRestart("Update is ready to install. The app will quit and install the update.")
            } catch (e: Exception) {
                println("❌ Error during update preparation: ${e.message}")
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Install Windows update using helper script pattern.
     */
    private suspend fun installWindowsUpdate(downloadFile: File): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                println("Starting Windows update installation...")
                validateDownloadFile(downloadFile, ".msi")

                val currentPid = ProcessHandle.current().pid()
                val scriptFile = UpdateScriptGenerator.generateWindowsUpdateScript(
                    msiPath = downloadFile.absolutePath,
                    appPid = currentPid
                )

                UpdateScriptGenerator.launchScript(scriptFile)

                InstallResult.RequiresRestart("Update is ready to install. The app will quit and install the update.")
            } catch (e: Exception) {
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Install JAR update.
     */
    private suspend fun installJarUpdate(downloadFile: File): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                validateDownloadFile(downloadFile, ".jar")

                val currentJar = getCurrentJarPath()
                if (currentJar == null) {
                    return@withContext InstallResult.Error("Could not locate current JAR")
                }

                val backupJar = File(currentJar.parentFile, "${currentJar.name}.backup")
                currentJar.copyTo(backupJar, overwrite = true)

                downloadFile.copyTo(currentJar, overwrite = true)

                InstallResult.Success("Update installed. Restart the app to use the new version.")
            } catch (e: Exception) {
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Validates Linux update environment (DISPLAY, pkexec/sudo availability).
     * Returns null if validation passes, or an InstallResult.Error if validation fails.
     */
    private fun validateLinuxUpdateEnvironment(): InstallResult.Error? {
        // Pre-flight validation: Check environment
        val display = System.getenv("DISPLAY")
        if (display.isNullOrBlank()) {
            println("⚠️ WARNING: No DISPLAY set - pkexec may not be able to show authentication dialog")
            return InstallResult.Error("No DISPLAY environment variable set. Cannot show authentication dialog.")
        }
        println("✅ DISPLAY is set: $display")

        // Check if pkexec or sudo is available
        val hasPkexec = try {
            ProcessBuilder("which", "pkexec").start().waitFor() == 0
        } catch (e: Exception) { false }

        val hasSudo = try {
            ProcessBuilder("which", "sudo").start().waitFor() == 0
        } catch (e: Exception) { false }

        if (!hasPkexec && !hasSudo) {
            println("❌ ERROR: Neither pkexec nor sudo available")
            return InstallResult.Error("Neither pkexec nor sudo is available for installation. Please install polkit or sudo.")
        }

        if (hasPkexec) {
            println("✅ pkexec is available for authentication")
        } else if (hasSudo) {
            println("✅ sudo is available for authentication")
        }

        return null
    }

    /**
     * Install Linux Deb update using helper script pattern.
     */
    private suspend fun installLinuxDebUpdate(downloadFile: File): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                println("Starting Linux Deb update installation...")
                validateDownloadFile(downloadFile, ".deb")

                // Validate environment
                validateLinuxUpdateEnvironment()?.let { return@withContext it }

                val currentPid = ProcessHandle.current().pid()
                val scriptFile = UpdateScriptGenerator.generateLinuxDebUpdateScript(
                    debPath = downloadFile.absolutePath,
                    appPid = currentPid
                )

                UpdateScriptGenerator.launchScript(scriptFile)

                InstallResult.RequiresRestart("Update is ready to install. The app will quit and install the update.")
            } catch (e: Exception) {
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Install Linux RPM update using helper script pattern.
     */
    private suspend fun installLinuxRpmUpdate(downloadFile: File): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                println("Starting Linux RPM update installation...")
                validateDownloadFile(downloadFile, ".rpm")

                // Validate environment
                validateLinuxUpdateEnvironment()?.let { return@withContext it }

                val currentPid = ProcessHandle.current().pid()
                val scriptFile = UpdateScriptGenerator.generateLinuxRpmUpdateScript(
                    rpmPath = downloadFile.absolutePath,
                    appPid = currentPid
                )

                UpdateScriptGenerator.launchScript(scriptFile)

                InstallResult.RequiresRestart("Update is ready to install. The app will quit and install the update.")
            } catch (e: Exception) {
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Get current application path for macOS .app bundle.
     */
    fun getCurrentApplicationPath(): String? {
        return try {
            val libraryPath = System.getProperty("java.library.path")
            val bundlePath = libraryPath?.let(::macOSAppBundlePathFromLibraryPath)

            if (bundlePath != null && File(bundlePath).exists()) {
                return resolveRealAppPath(bundlePath)
            }

            val jarPath = UpdateInstaller::class.java.protectionDomain.codeSource.location.path
            var currentFile = File(jarPath)
            for (i in 0..5) {
                if (currentFile.name.endsWith(".app")) {
                    return resolveRealAppPath(currentFile.absolutePath)
                }
                currentFile = currentFile.parentFile ?: break
            }

            val applicationsPath = "$MACOS_APPLICATIONS_DIRECTORY/$BOSSTERM_MACOS_APP_BUNDLE_NAME"
            if (File(applicationsPath).exists()) {
                return applicationsPath
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolve a Gatekeeper App Translocation path back to the writable installed
     * bundle before generating the in-place update helper script.
     */
    private fun resolveRealAppPath(path: String): String {
        if (!path.contains(APP_TRANSLOCATION_PATH_SEGMENT)) return path

        println("⚠️ BossTerm is running translocated by Gatekeeper; resolving the installed app path")

        val resolvedPath = realAppPathFor(
            path = path,
            appExists = { File(it).exists() },
            installedAppLookup = ::findInstalledAppViaSpotlight
        )

        if (resolvedPath != path) {
            println("✅ Resolved installed BossTerm app path: $resolvedPath")
            return resolvedPath
        }

        println("⚠️ Could not resolve the installed app path; keeping translocated path: $path")
        return path
    }

    /** Locate the installed BossTerm app via Spotlight without blocking on a full output pipe. */
    private fun findInstalledAppViaSpotlight(): String? {
        return try {
            val process = ProcessBuilder(
                "mdfind", "kMDItemCFBundleIdentifier == '$BOSSTERM_MACOS_BUNDLE_ID'"
            )
                .redirectErrorStream(true)
                .start()

            val outputFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }

            if (!process.waitFor(SPOTLIGHT_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                outputFuture.cancel(true)
                println("⚠️ mdfind lookup timed out after $SPOTLIGHT_LOOKUP_TIMEOUT_SECONDS seconds")
                return null
            }

            val output = outputFuture.get(1, TimeUnit.SECONDS)
            if (process.exitValue() != 0) {
                println("⚠️ mdfind lookup failed with exit code ${process.exitValue()}")
                return null
            }

            output.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(MACOS_APP_BUNDLE_SUFFIX) }
                .filterNot { it.contains(APP_TRANSLOCATION_PATH_SEGMENT) }
                .filterNot { it.contains("/Frameworks/") || it.contains("/Helpers/") }
                .firstOrNull { File(it).exists() }
        } catch (e: Exception) {
            println("⚠️ mdfind lookup for installed BossTerm failed: ${e.message}")
            null
        }
    }

    private fun findMountedBossTermVolume(): File? {
        val volumesDir = File("/Volumes")
        return volumesDir.listFiles()?.find {
            it.name.contains("BossTerm", ignoreCase = true) && it.isDirectory
        }
    }

    fun findAppBundleInVolume(mountedVolume: File): File? {
        return mountedVolume.listFiles()?.find {
            it.name.endsWith(".app") && it.name.contains("BossTerm", ignoreCase = true)
        }
    }

    private fun openDMGForManualInstallation(downloadFile: File): InstallResult {
        return try {
            ProcessBuilder("open", downloadFile.absolutePath).start().waitFor()
            InstallResult.Success("DMG opened for manual installation")
        } catch (e: Exception) {
            InstallResult.Error(e.message ?: "Failed to open DMG")
        }
    }

    private fun cleanupDMG(mountedVolume: File?) {
        try {
            val volume = mountedVolume ?: findMountedBossTermVolume()
            if (volume != null) {
                ProcessBuilder("hdiutil", "detach", volume.absolutePath, "-quiet")
                    .start()
                    .waitFor()
            }
        } catch (e: Exception) {
            println("Warning: Could not unmount DMG: ${e.message}")
        }
    }

    private fun getCurrentJarPath(): File? {
        return try {
            val jarPath = UpdateInstaller::class.java.protectionDomain.codeSource.location.toURI().path
            val jarFile = File(jarPath)
            if (jarFile.exists() && jarFile.name.endsWith(".jar")) jarFile else null
        } catch (e: Exception) {
            null
        }
    }

    fun getCurrentPlatform(): String {
        return when {
            ShellCustomizationUtils.isMacOS() -> "macOS"
            ShellCustomizationUtils.isWindows() -> "Windows"
            ShellCustomizationUtils.isLinux() -> detectLinuxDistroType()
            else -> "Unknown"
        }
    }

    /**
     * Detect Linux distribution type (deb-based or rpm-based).
     */
    private fun detectLinuxDistroType(): String {
        return try {
            // Check for dpkg (Debian/Ubuntu)
            val dpkgCheck = ProcessBuilder("which", "dpkg").start()
            dpkgCheck.waitFor()
            if (dpkgCheck.exitValue() == 0) {
                return "Linux-deb"
            }

            // Check for rpm (Fedora/RHEL/CentOS)
            val rpmCheck = ProcessBuilder("which", "rpm").start()
            rpmCheck.waitFor()
            if (rpmCheck.exitValue() == 0) {
                return "Linux-rpm"
            }

            // Check /etc/os-release for more info
            val osRelease = File("/etc/os-release")
            if (osRelease.exists()) {
                val content = osRelease.readText().lowercase()
                return when {
                    content.contains("debian") || content.contains("ubuntu") ||
                    content.contains("mint") || content.contains("pop") -> "Linux-deb"
                    content.contains("fedora") || content.contains("rhel") ||
                    content.contains("centos") || content.contains("rocky") ||
                    content.contains("alma") -> "Linux-rpm"
                    else -> "Linux"
                }
            }

            "Linux"
        } catch (e: Exception) {
            println("Could not detect Linux distro type: ${e.message}")
            "Linux"
        }
    }
}
