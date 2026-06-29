package ai.rever.bossterm.compose.auth

/**
 * Supabase (GoTrue) endpoint for BossTerm sign-in. This is **BossConsole's backend** —
 * both apps share one user pool at api.risaboss.com; BossTerm talks to it over plain
 * GoTrue REST (no Supabase SDK dependency).
 *
 * Resolution order mirrors BossConsole's SupabaseClientConfig: environment variable →
 * system property → production fallback. The anon key is public by design (it ships in
 * every client; Row Level Security is the real gate) — the override order keeps it
 * rotatable without a code change.
 */
object SupabaseAuthConfig {

    private fun resolve(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }

    val url: String by lazy {
        resolve("BOSSTERM_SUPABASE_URL") ?: "https://api.risaboss.com"
    }

    val anonKey: String by lazy {
        resolve("BOSSTERM_SUPABASE_ANON_KEY")
            ?: ("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBjbndxYW1xZG5zYWRyYW51Zmp2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTkxMDUwMzMsImV4cCI6MjA3NDY4MTAzM30." +
                "WZ6jSKuqM2EMyZLgoGJnI8Bn_Sdwk6plW0PkVNLIYVY")
    }

    /**
     * The deep link GoTrue is asked to redirect to (`redirect_to`). Until the backend
     * allow-lists it (risa-labs-inc/BossConsole#787) GoTrue silently falls back to its
     * site_url — harmless to send now, required for per-app email branding later.
     */
    const val REDIRECT_URI = "bossterm://auth/verify"
}
