package ai.rever.bossterm.compose.update.source

import ai.rever.bossterm.compose.update.GitHubConfig
import ai.rever.bossterm.compose.update.GitHubRelease
import ai.rever.bossterm.compose.update.Version
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Backup [UpdateSource] backed by the GitHub Releases API (kshivang/BossTerm).
 * The relocated single-GET fetch from DesktopUpdateService; non-2xx responses are
 * surfaced as [UpdateSourceException] so [FallbackUpdateSource] can react.
 */
class GitHubUpdateSource(
    private val apiClient: HttpClient = defaultApiClient()
) : UpdateSource {

    override val name: String = "github"

    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val RELEASES_REPO = "kshivang/BossTerm"
        private const val RELEASES_ENDPOINT = "$GITHUB_API_BASE/repos/$RELEASES_REPO/releases"

        private fun defaultApiClient(): HttpClient = HttpClient(CIO) {
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
    }

    override suspend fun listReleases(): List<GitHubRelease> {
        val response = apiClient.get(RELEASES_ENDPOINT) {
            headers {
                append("Accept", "application/vnd.github.v3+json")
                append("User-Agent", "BossTerm-Desktop-${Version.CURRENT}")
                GitHubConfig.token?.let { append("Authorization", "Bearer $it") }
            }
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            val detail = if (body.contains("rate limit", ignoreCase = true)) " - rate limit exceeded" else ""
            throw UpdateSourceException("GitHub releases request failed (HTTP ${response.status.value})$detail")
        }
        return response.body()
    }
}
