package ai.rever.bossterm.compose.update

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of update service using GitHub Releases API.
 */
class DesktopUpdateService {

    private val apiClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    private val downloadClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 900_000  // 15 minutes for large downloads
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val RELEASES_REPO = "kshivang/BossTerm"
        private const val RELEASES_ENDPOINT = "$GITHUB_API_BASE/repos/$RELEASES_REPO/releases"
    }

    /**
     * Fetch all available releases from GitHub.
     * Used for version selection in About section.
     */
    suspend fun getAllReleases(includePreReleases: Boolean = false): Result<List<GitHubRelease>> {
        return try {
            val response = apiClient.get(RELEASES_ENDPOINT) {
                headers {
                    append("Accept", "application/vnd.github.v3+json")
                    append("User-Agent", "BossTerm-Desktop-${Version.CURRENT}")
                    GitHubConfig.token?.let { append("Authorization", "Bearer $it") }
                }
            }

            if (response.status.value !in 200..299) {
                val errorBody = response.bodyAsText()
                val errorMessage = when {
                    errorBody.contains("rate limit", ignoreCase = true) ->
                        "GitHub API rate limit exceeded. Please try again later."
                    else -> "Unable to fetch releases (HTTP ${response.status.value})"
                }
                return Result.failure(Exception(errorMessage))
            }

            val releases = response.body<List<GitHubRelease>>()
            val filteredReleases = releases
                .filter { !it.draft && (includePreReleases || !it.prerelease) }
                .sortedByDescending { Version.parse(it.tag_name) }

            Result.success(filteredReleases)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download a specific release version.
     * Returns the path to the downloaded file.
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
            if (GitHubConfig.hasToken) {
                println("✅ Using authenticated GitHub API (5,000 requests/hour)")
            } else {
                println("⚠️ Using unauthenticated GitHub API (60 requests/hour)")
            }

            val response = apiClient.get(RELEASES_ENDPOINT) {
                headers {
                    append("Accept", "application/vnd.github.v3+json")
                    append("User-Agent", "BossTerm-Desktop-${Version.CURRENT}")
                    GitHubConfig.token?.let { append("Authorization", "Bearer $it") }
                }
            }

            if (response.status.value !in 200..299) {
                val errorBody = response.bodyAsText()
                val errorMessage = when {
                    errorBody.contains("rate limit", ignoreCase = true) ->
                        "GitHub API rate limit exceeded. Please try again later."
                    else -> "Unable to check for updates (HTTP ${response.status.value})"
                }
                println("Update check failed: $errorMessage")
                return UpdateInfo(
                    available = false,
                    currentVersion = Version.CURRENT,
                    latestVersion = Version.CURRENT,
                    releaseNotes = ""
                )
            }

            val releases = response.body<List<GitHubRelease>>()

            val latestRelease = releases
                .filter { !it.draft && !it.prerelease }
                .mapNotNull { release ->
                    Version.parse(release.tag_name)?.let { version -> release to version }
                }
                .maxByOrNull { it.second }
                ?.first

            if (latestRelease == null) {
                return UpdateInfo(
                    available = false,
                    currentVersion = Version.CURRENT,
                    latestVersion = Version.CURRENT,
                    releaseNotes = ""
                )
            }

            val latestVersion = Version.parse(latestRelease.tag_name)
                ?: return UpdateInfo(
                    available = false,
                    currentVersion = Version.CURRENT,
                    latestVersion = Version.CURRENT,
                    releaseNotes = ""
                )

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
                assetName = asset?.name ?: ""
            )
        } catch (e: Exception) {
            println("Error checking for updates: ${e.message}")
            UpdateInfo(
                available = false,
                currentVersion = Version.CURRENT,
                latestVersion = Version.CURRENT,
                releaseNotes = ""
            )
        }
    }

    /**
     * Download an update.
     */
    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (progress: Float) -> Unit
    ): String? {
        return try {
            val downloadUrl = updateInfo.downloadUrl ?: return null

            println("Starting download from: $downloadUrl")

            val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updates")
            tempDir.mkdirs()

            val downloadFile = File(tempDir, updateInfo.assetName)
            if (downloadFile.exists()) {
                downloadFile.delete()
            }

            streamToFile(downloadUrl, updateInfo.assetSize, downloadFile, onProgress)

            if (downloadFile.exists() && downloadFile.length() > 0) {
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
