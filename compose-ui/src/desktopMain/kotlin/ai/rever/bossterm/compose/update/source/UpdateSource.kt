package ai.rever.bossterm.compose.update.source

import ai.rever.bossterm.compose.update.GitHubRelease

/**
 * Abstraction over "where release metadata comes from", normalizing each backend
 * into the existing [GitHubRelease] shape so the update service's version-selection
 * and platform-asset-matching logic is source-agnostic.
 *
 * Contract: transport/availability failures throw (so [FallbackUpdateSource] can try
 * the backup); a successful-but-empty result is an empty list.
 */
interface UpdateSource {
    /** Short identifier for logging, e.g. "supabase" / "github". */
    val name: String

    /** All known releases (unfiltered; callers apply prerelease/version filtering). */
    suspend fun listReleases(): List<GitHubRelease>
}

/** Thrown by an [UpdateSource] when its backend is unreachable or returns an error. */
class UpdateSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
