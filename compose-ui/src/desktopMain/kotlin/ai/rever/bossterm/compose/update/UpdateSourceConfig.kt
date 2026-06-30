package ai.rever.bossterm.compose.update

import java.io.File
import java.util.Properties

/**
 * Configuration for BossTerm's update source (Supabase primary, GitHub backup).
 *
 * Values resolve through env var → system property → local.properties → default,
 * mirroring [GitHubConfig].
 *
 * NOTE: BossTerm is a public repository, so no Supabase anon key is embedded here.
 * The Supabase path is enabled only when SUPABASE_ANON_KEY is provided (CI/release
 * builds inject it; local devs can set it in local.properties). When absent, update
 * checks fall back to the GitHub Releases API automatically.
 *
 * Keys:
 * - SUPABASE_URL                  (default https://api.risaboss.com)
 * - SUPABASE_ANON_KEY             (default empty → Supabase disabled, GitHub-only)
 * - BOSSTERM_UPDATE_BUCKET        (default "app-releases")
 * - BOSSTERM_UPDATE_APP_ID        (default "bossterm")
 * - BOSSTERM_UPDATE_PRIMARY_SOURCE: "supabase" (default), "github", "supabase-only"
 */
object UpdateSourceConfig {

    private fun config(key: String, default: String): String =
        resolve(key)?.takeIf { it.isNotBlank() } ?: default

    private fun resolve(key: String): String? {
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it }
        System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
        try {
            val localProps = File("local.properties")
            if (localProps.exists()) {
                val props = Properties()
                localProps.inputStream().use { props.load(it) }
                props.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {
            // Ignore errors reading local.properties
        }
        return null
    }

    val supabaseUrl: String by lazy { config("SUPABASE_URL", "https://api.risaboss.com") }
    val anonKey: String by lazy { config("SUPABASE_ANON_KEY", "") }
    val bucket: String by lazy { config("BOSSTERM_UPDATE_BUCKET", "app-releases") }
    val appId: String by lazy { config("BOSSTERM_UPDATE_APP_ID", "bossterm") }
    val primarySource: String by lazy { config("BOSSTERM_UPDATE_PRIMARY_SOURCE", "supabase").lowercase() }

    /** True when Supabase can serve as a source (key present and not forced to GitHub). */
    val supabaseEnabled: Boolean
        get() = anonKey.isNotBlank() && primarySource != "github"

    /** PostgREST base, e.g. https://api.risaboss.com/rest/v1 */
    val restBaseUrl: String
        get() = "${supabaseUrl.trimEnd('/')}/rest/v1"
}
