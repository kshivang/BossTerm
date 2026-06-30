package ai.rever.bossterm.compose.update

import ai.rever.bossterm.compose.update.source.FallbackUpdateSource
import ai.rever.bossterm.compose.update.source.GitHubUpdateSource
import ai.rever.bossterm.compose.update.source.SupabaseUpdateSource
import ai.rever.bossterm.compose.update.source.UpdateSource
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Desktop update service. Release metadata comes from the configured [UpdateSource]
 * — Supabase primary (Realtime-fed `app_releases` catalog), GitHub Releases backup —
 * while binaries are streamed from whichever URL the chosen source provides.
 */
class DesktopUpdateService {

    private val downloadClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 900_000  // 15 minutes for large downloads
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    /** Supabase primary, GitHub backup (overridable via BOSSTERM_UPDATE_PRIMARY_SOURCE). */
    private val source: UpdateSource = buildSource()

    /** Dedicated GitHub source used only to recover a download if the primary URL fails. */
    private val gitHubSource = GitHubUpdateSource()

    private fun buildSource(): UpdateSource {
        val src = when {
            UpdateSourceConfig.primarySource == "github" || !UpdateSourceConfig.supabaseEnabled -> GitHubUpdateSource()
            UpdateSourceConfig.primarySource == "supabase-only" -> SupabaseUpdateSource()
            else -> FallbackUpdateSource(primary = SupabaseUpdateSource(), backup = GitHubUpdateSource())
        }
        println("[update] source configured: ${src.name}")
        return src
    }

    private fun upToDate(): UpdateInfo = UpdateInfo(
        available = false,
        currentVersion = Version.CURRENT,
        latestVersion = Version.CURRENT,
        releaseNotes = ""
    )

    /**
     * Fetch all available releases (for version selection in About). Filtering and
     * sorting happen here so the underlying source stays format-only.
     */
    suspend fun getAllReleases(includePreReleases: Boolean = false): Result<List<GitHubRelease>> {
        return try {
            val filtered = source.listReleases()
                .filter { !it.draft && (includePreReleases || !it.prerelease) }
                .sortedByDescending { Version.parse(it.tag_name) }
            Result.success(filtered)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download a specific release version. Returns the path to the downloaded file.
     */
    suspend fun downloadRelease(
        release: GitHubRelease,
        onProgress: (progress: Float) -> Unit
    ): Result<String> {
        val version = Version.parse(release.tag_name)
            ?: return Result.failure(Exception("Invalid version: ${release.tag_name}"))

        val expectedAssetName = getExpectedAssetName(version)
        val asset = release.assets.find { it.name.equals(expectedAssetName, ignoreCase = true) }
            ?: return Result.failure(Exception("No asset available for ${getCurrentPlatform()}"))

        val downloadUrl = asset.browser_download_url
            ?: return Result.failure(Exception("No download URL for asset"))

        return try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updates")
            tempDir.mkdirs()

            val downloadFile = File(tempDir, asset.name)
            if (downloadFile.exists()) {
                downloadFile.delete()
            }

            streamToFile(downloadUrl, asset.size, downloadFile, onProgress)

            if (downloadFile.exists() && downloadFile.length() > 0) {
                if (!verifyChecksum(downloadFile, asset.sha256)) {
                    downloadFile.delete()
                    return Result.failure(Exception("Checksum verification failed for ${asset.name}"))
                }
                Result.success(downloadFile.absolutePath)
            } else {
                Result.failure(Exception("Download failed: file is empty"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check for available updates.
     */
    suspend fun checkForUpdates(): UpdateInfo {
        return try {
            val releases = source.listReleases()

            val latestRelease = releases
                .filter { !it.draft && !it.prerelease }
                .mapNotNull { release ->
                    Version.parse(release.tag_name)?.let { version -> release to version }
                }
                .maxByOrNull { it.second }
                ?.first
                ?: return upToDate()

            val latestVersion = Version.parse(latestRelease.tag_name) ?: return upToDate()
            val isUpdateAvailable = latestVersion.isNewerThan(Version.CURRENT)

            val expectedAssetName = getExpectedAssetName(latestVersion)
            println("Looking for asset: $expectedAssetName")
            println("Available assets: ${latestRelease.assets.map { it.name }}")

            val asset = latestRelease.assets.find {
                it.name.equals(expectedAssetName, ignoreCase = true)
            }

            // Only show update available if the asset exists for this platform
            // (handles library-only releases that have no platform binaries)
            if (asset == null && isUpdateAvailable) {
                println("⚠️ Update v$latestVersion exists but no asset for ${getCurrentPlatform()}")
            }

            UpdateInfo(
                available = isUpdateAvailable && asset != null,
                currentVersion = Version.CURRENT,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = asset?.browser_download_url,
                assetSize = asset?.size ?: 0,
                assetName = asset?.name ?: "",
                sha256 = asset?.sha256
            )
        } catch (e: Exception) {
            println("Error checking for updates: ${e.message}")
            upToDate()
        }
    }

    /**
     * Download an update. Tries the source-provided URL (Supabase Storage when Supabase
     * is primary); if that fails, recovers via the GitHub asset for the same version.
     */
    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (progress: Float) -> Unit
    ): String? {
        val primaryUrl = updateInfo.downloadUrl ?: return null

        downloadFrom(primaryUrl, updateInfo.assetName, updateInfo.assetSize, updateInfo.sha256, onProgress)
            ?.let { return it }

        // The fallback chain is metadata-only: once Supabase serves the catalog, the
        // download URL is a Storage URL with no recovery. If that fails (e.g. the bucket
        // isn't public/reachable) recover via the GitHub asset for the same version.
        val gitHubUrl = gitHubAssetUrlFor(updateInfo.latestVersion)
        if (gitHubUrl != null && gitHubUrl != primaryUrl) {
            println("[update] primary download failed; falling back to GitHub asset")
            return downloadFrom(gitHubUrl, updateInfo.assetName, updateInfo.assetSize, sha256 = null, onProgress)
        }
        return null
    }

    /** Download [url] to a temp file, verifying [sha256] when provided. Returns the path or null. */
    private suspend fun downloadFrom(
        url: String,
        assetName: String,
        assetSize: Long,
        sha256: String?,
        onProgress: (progress: Float) -> Unit
    ): String? {
        return try {
            println("Starting download from: $url")

            val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updates")
            tempDir.mkdirs()

            val downloadFile = File(tempDir, assetName)
            if (downloadFile.exists()) {
                downloadFile.delete()
            }

            streamToFile(url, assetSize, downloadFile, onProgress)

            if (downloadFile.exists() && downloadFile.length() > 0) {
                if (!verifyChecksum(downloadFile, sha256)) {
                    println("❌ Checksum mismatch; discarding download: $assetName")
                    downloadFile.delete()
                    return null
                }
                println("Update downloaded successfully: ${downloadFile.absolutePath}")
                downloadFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            println("Error downloading update: ${e.message}")
            null
        }
    }

    /** Resolve the GitHub Releases asset URL for [version] — the download-time backup. */
    private suspend fun gitHubAssetUrlFor(version: Version): String? = try {
        val expected = getExpectedAssetName(version)
        gitHubSource.listReleases()
            .firstOrNull { Version.parse(it.tag_name) == version }
            ?.assets?.firstOrNull { it.name.equals(expected, ignoreCase = true) }
            ?.browser_download_url
    } catch (e: Exception) {
        println("[update] could not resolve GitHub fallback asset: ${e.message}")
        null
    }

    /**
     * Verify [file] against [expectedSha] (lowercase hex). No-op when null/blank.
     *
     * Integrity check, not authenticity: the hash and the download URL come from the
     * same app_releases row, so this catches Storage/CDN corruption, not a compromised
     * catalog. Update authenticity still rests on OS code-signing.
     */
    private fun verifyChecksum(file: File, expectedSha: String?): Boolean {
        if (expectedSha.isNullOrBlank()) return true
        val actual = sha256Of(file)
        val ok = actual.equals(expectedSha, ignoreCase = true)
        if (ok) {
            println("✅ Checksum verified for ${file.name}")
        } else {
            println("❌ Checksum mismatch for ${file.name}: expected $expectedSha, got $actual")
        }
        return ok
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Stream a download to [destFile], reporting throttled progress.
     *
     * Uses Ktor's [prepareGet]/[execute] streaming API and reads the body channel
     * INSIDE the execute lambda, so progress reflects bytes arriving off the socket.
     * (The non-streaming `get()` would buffer the whole body into memory before
     * returning, making the bar jump straight to 100% at the end — see issue #265.)
     */
    private suspend fun streamToFile(
        url: String,
        expectedSize: Long,
        destFile: File,
        onProgress: (progress: Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        downloadClient.prepareGet(url).execute { response ->
            check(response.status.value in 200..299) {
                "Download failed (HTTP ${response.status.value})"
            }

            val totalSize = response.headers["Content-Length"]?.toLongOrNull() ?: expectedSize
            val channel = response.bodyAsChannel()

            destFile.outputStream().use { output ->
                var downloadedBytes = 0L
                val buffer = ByteArray(8192)
                var lastProgressUpdate = 0L

                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val shouldUpdateProgress = if (totalSize > 0) {
                            downloadedBytes - lastProgressUpdate >= 262144 ||
                                (downloadedBytes.toFloat() / totalSize - lastProgressUpdate.toFloat() / totalSize) >= 0.05f
                        } else {
                            downloadedBytes - lastProgressUpdate >= 131072
                        }

                        if (shouldUpdateProgress) {
                            val progress = if (totalSize > 0) {
                                (downloadedBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                            } else {
                                // Unknown total size: monotonic, asymptotic toward <1
                                // (never decreases; the explicit onProgress(1f) below finishes it).
                                val mb = downloadedBytes / 1_048_576f
                                (1f - 1f / (1f + mb / 8f)).coerceIn(0f, 0.95f)
                            }

                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                            lastProgressUpdate = downloadedBytes
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(1f)
            }
        }
    }

    /**
     * Install an update.
     */
    suspend fun installUpdate(downloadPath: String): Boolean {
        val result = UpdateInstaller.installUpdate(downloadPath)

        return when (result) {
            is InstallResult.Success -> {
                println("✅ ${result.message}")
                true
            }
            is InstallResult.RequiresRestart -> {
                println("🔄 ${result.message}")
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                GlobalScope.launch {
                    delay(1000)
                    quitForUpdate()
                }
                true
            }
            is InstallResult.Error -> {
                println("❌ ${result.message}")
                false
            }
        }
    }

    fun getCurrentPlatform(): String = UpdateInstaller.getCurrentPlatform()

    fun getExpectedAssetName(version: Version): String {
        return when (getCurrentPlatform()) {
            "macOS" -> "BossTerm-${version}.dmg"
            "Windows" -> "BossTerm-${version}.msi"
            "Linux-deb" -> "bossterm_${version}_${getLinuxArch()}.deb"
            "Linux-rpm" -> "bossterm-${version}.${getRpmArch()}.rpm"
            else -> "bossterm-${version}.jar"
        }
    }

    private fun getLinuxArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("amd64") || arch.contains("x86_64") -> "amd64"
            else -> "amd64"
        }
    }

    private fun getRpmArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
            else -> "x86_64"
        }
    }

    /**
     * Quit the application for update installation.
     */
    private fun quitForUpdate() {
        println("Quitting application for update...")
        System.exit(0)
    }
}
