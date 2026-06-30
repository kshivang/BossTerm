package ai.rever.bossterm.compose.update.source

import ai.rever.bossterm.compose.update.GitHubRelease
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for BossTerm's update-source seam: Supabase `app_releases` row mapping and
 * the Supabase-primary / GitHub-backup fallback behavior.
 */
class UpdateSourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `app_releases row maps to GitHubRelease with assets and sha256`() {
        val raw = """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "app": "bossterm",
              "version": "1.3.0",
              "channel": "stable",
              "prerelease": false,
              "release_notes": "Faster rendering",
              "assets": [
                {"name": "BossTerm-1.3.0.dmg", "url": "https://cdn/bossterm/1.3.0/BossTerm-1.3.0.dmg", "size": 123456, "sha256": "cafebabe"}
              ],
              "published_at": "2026-07-01T00:00:00Z"
            }
        """.trimIndent()

        val release = json.decodeFromString<AppReleaseRow>(raw).toGitHubRelease()

        assertEquals("v1.3.0", release.tag_name)
        assertEquals("Faster rendering", release.body)
        assertFalse(release.prerelease)
        assertEquals(1, release.assets.size)
        val asset = release.assets.first()
        assertEquals("BossTerm-1.3.0.dmg", asset.name)
        assertEquals("https://cdn/bossterm/1.3.0/BossTerm-1.3.0.dmg", asset.browser_download_url)
        assertEquals(123456L, asset.size)
        assertEquals("cafebabe", asset.sha256)
    }

    private fun release(tag: String) = GitHubRelease(
        tag_name = tag, name = tag, body = "", published_at = "2026-01-01T00:00:00Z"
    )

    private class FakeSource(
        override val name: String,
        private val releases: () -> List<GitHubRelease>
    ) : UpdateSource {
        var listCalls = 0
        override suspend fun listReleases(): List<GitHubRelease> { listCalls++; return releases() }
    }

    @Test
    fun `uses primary when it returns releases and does not call backup`() = runTest {
        val primary = FakeSource("supabase") { listOf(release("v2.0.0")) }
        val backup = FakeSource("github") { error("backup must not be called") }

        val result = FallbackUpdateSource(primary, backup).listReleases()

        assertEquals(listOf("v2.0.0"), result.map { it.tag_name })
        assertEquals(0, backup.listCalls)
    }

    @Test
    fun `falls back to backup when primary throws`() = runTest {
        val primary = FakeSource("supabase") { throw UpdateSourceException("down") }
        val backup = FakeSource("github") { listOf(release("v1.0.0")) }

        val result = FallbackUpdateSource(primary, backup).listReleases()

        assertEquals(listOf("v1.0.0"), result.map { it.tag_name })
        assertEquals(1, backup.listCalls)
    }

    @Test
    fun `falls back to backup when primary returns empty`() = runTest {
        val primary = FakeSource("supabase") { emptyList() }
        val backup = FakeSource("github") { listOf(release("v1.0.0")) }

        val result = FallbackUpdateSource(primary, backup).listReleases()

        assertEquals(listOf("v1.0.0"), result.map { it.tag_name })
    }

    @Test
    fun `returns empty when both sources fail`() = runTest {
        val primary = FakeSource("supabase") { throw UpdateSourceException("down") }
        val backup = FakeSource("github") { throw UpdateSourceException("also down") }

        val result = FallbackUpdateSource(primary, backup).listReleases()

        assertTrue(result.isEmpty())
    }
}
