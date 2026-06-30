package ai.rever.bossterm.compose.update.source

import ai.rever.bossterm.compose.update.GitHubRelease
import kotlinx.coroutines.CancellationException

/**
 * Tries [primary] first; on a thrown error OR an empty result, falls back to
 * [backup]. If the backup also fails, returns an empty list (treated as "up to
 * date"). Cancellation propagates. Used as Supabase-primary / GitHub-backup.
 */
class FallbackUpdateSource(
    private val primary: UpdateSource,
    private val backup: UpdateSource
) : UpdateSource {

    override val name: String = "${primary.name}->${backup.name}"

    override suspend fun listReleases(): List<GitHubRelease> {
        try {
            val releases = primary.listReleases()
            if (releases.isNotEmpty()) {
                println("[update] releases served by ${primary.name} (${releases.size})")
                return releases
            }
            println("[update] ${primary.name} returned no releases; falling back to ${backup.name}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[update] ${primary.name} failed (${e.message}); falling back to ${backup.name}")
        }

        return try {
            backup.listReleases()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[update] both update sources failed (${backup.name}: ${e.message})")
            emptyList()
        }
    }
}
