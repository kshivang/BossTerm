package ai.rever.bossterm.compose.update.source

import ai.rever.bossterm.compose.update.GitHubAsset
import ai.rever.bossterm.compose.update.GitHubRelease
import ai.rever.bossterm.compose.update.UpdateSourceConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Primary [UpdateSource] backed by the Supabase `app_releases` table (queried over
 * PostgREST). Binaries live in Supabase Storage; the row's asset URLs point at them.
 * Plain Ktor REST keeps this independent of any shared Supabase client lifecycle.
 */
class SupabaseUpdateSource(
    private val appId: String = UpdateSourceConfig.appId,
    private val restBaseUrl: String = UpdateSourceConfig.restBaseUrl,
    private val anonKey: String = UpdateSourceConfig.anonKey,
    private val apiClient: HttpClient = defaultApiClient()
) : UpdateSource {

    override val name: String = "supabase"

    companion object {
        private const val MAX_RELEASES = 50

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
        if (anonKey.isBlank()) {
            throw UpdateSourceException("Supabase anon key not configured")
        }
        val url = "$restBaseUrl/app_releases?app=eq.$appId&order=published_at.desc&limit=$MAX_RELEASES&select=*"
        val response = apiClient.get(url) {
            headers {
                append("apikey", anonKey)
                append(HttpHeaders.Authorization, "Bearer $anonKey")
                append(HttpHeaders.Accept, "application/json")
            }
        }
        if (response.status.value !in 200..299) {
            throw UpdateSourceException("Supabase app_releases request failed (HTTP ${response.status.value})")
        }
        val rows: List<AppReleaseRow> = response.body()
        return rows.map { it.toGitHubRelease() }
    }
}

/** One row of the `app_releases` table. Field names match the PostgREST JSON. */
@Serializable
internal data class AppReleaseRow(
    val app: String,
    val version: String,
    val channel: String = "stable",
    val prerelease: Boolean = false,
    @SerialName("release_notes") val releaseNotes: String = "",
    val assets: List<AppReleaseAsset> = emptyList(),
    @SerialName("published_at") val publishedAt: String = ""
) {
    fun toGitHubRelease(): GitHubRelease = GitHubRelease(
        tag_name = "v$version",
        name = version,
        body = releaseNotes,
        draft = false,
        prerelease = prerelease,
        published_at = publishedAt,
        assets = assets.map { asset ->
            GitHubAsset(
                name = asset.name,
                browser_download_url = asset.url,
                size = asset.size,
                content_type = "",
                sha256 = asset.sha256
            )
        }
    )
}

@Serializable
internal data class AppReleaseAsset(
    val name: String,
    val url: String,
    val size: Long = 0,
    val sha256: String? = null
)
