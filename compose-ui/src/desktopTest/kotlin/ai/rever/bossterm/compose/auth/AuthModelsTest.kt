package ai.rever.bossterm.compose.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic guards for the sign-in flow: the `bossterm://auth/verify` deep-link
 * contract (incl. the `token` → `token_hash` param compatibility), GoTrue error
 * mapping, and the on-disk session round-trip. The network half is exercised
 * manually against the live backend (see risa-labs-inc/BossConsole#787).
 */
class AuthModelsTest {

    // ---- deep link parsing ----

    @Test
    fun `parses token_hash and type`() {
        val l = parseAuthDeepLink("bossterm://auth/verify?token_hash=abc123&type=magiclink")
        assertEquals(AuthDeepLink("abc123", "magiclink"), l)
    }

    @Test
    fun `passes signup type through verbatim`() {
        // New users' first magic link verifies as `signup` — hardcoding magiclink breaks them.
        val l = parseAuthDeepLink("bossterm://auth/verify?token_hash=h&type=signup")
        assertEquals("signup", l?.type)
    }

    @Test
    fun `accepts legacy token param as the hash`() {
        // BossConsole's current redirect function emits token=, the contract says token_hash=.
        val l = parseAuthDeepLink("bossterm://auth/verify?token=tok&type=magiclink")
        assertEquals("tok", l?.tokenHash)
    }

    @Test
    fun `type defaults to magiclink`() {
        assertEquals("magiclink", parseAuthDeepLink("bossterm://auth/verify?token_hash=h")?.type)
    }

    @Test
    fun `url-decodes parameter values`() {
        val l = parseAuthDeepLink("bossterm://auth/verify?token_hash=a%2Bb%3D&type=magiclink")
        assertEquals("a+b=", l?.tokenHash)
    }

    @Test
    fun `rejects non-auth links and malformed input`() {
        // URI("bossterm://auth/verify") parses host="auth", path="/verify" — wrong host/path/scheme must all fail.
        assertNull(parseAuthDeepLink("bossterm://workspace/open?id=1"))
        assertNull(parseAuthDeepLink("boss://auth/verify?token_hash=h"))
        assertNull(parseAuthDeepLink("bossterm://auth/other?token_hash=h"))
        assertNull(parseAuthDeepLink("bossterm://auth/verify"))            // no query
        assertNull(parseAuthDeepLink("bossterm://auth/verify?type=magiclink")) // no token
        assertNull(parseAuthDeepLink("bossterm://auth/verify?token_hash="))    // blank token
        assertNull(parseAuthDeepLink("::not a uri::"))
        assertNull(parseAuthDeepLink(""))
    }

    // ---- error mapping ----

    @Test
    fun `rate limit maps to cooldown message`() {
        assertTrue(mapAuthError(429, "over_email_send_rate_limit", AuthPhase.SEND).contains("wait a minute"))
    }

    @Test
    fun `expired verify token maps to terminal message`() {
        // Tokens are single-use: 401/403 on verify must read as expired/used, never retried.
        for (status in listOf(401, 403)) {
            assertTrue(mapAuthError(status, "otp_expired", AuthPhase.VERIFY).contains("expired"))
        }
    }

    @Test
    fun `server text wins for other errors, fallbacks otherwise`() {
        assertEquals("Signups not allowed", mapAuthError(400, "Signups not allowed", AuthPhase.SEND))
        assertTrue(mapAuthError(0, null, AuthPhase.SEND).contains("connection"))
        assertTrue(mapAuthError(500, "", AuthPhase.VERIFY).contains("Request a new one"))
    }

    // ---- stored session round-trip ----

    @Test
    fun `stored auth round-trips through disk`() {
        val file = File.createTempFile("auth-test", ".json").also { it.delete() }
        try {
            val auth = StoredAuth("at", "rt", 1_900_000_000L, "uid", "a@b.co")
            AuthStorage.save(auth, file)
            assertEquals(auth, AuthStorage.load(file))
            AuthStorage.clear(file)
            assertNull(AuthStorage.load(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `corrupted auth file loads as signed out`() {
        val file = File.createTempFile("auth-test", ".json")
        try {
            file.writeText("{ not json !!")
            assertNull(AuthStorage.load(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `saved auth file is owner-only on posix`() {
        val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        if (!posix) return // Windows ACLs protect the user profile; perms aren't applicable.
        val dir = java.nio.file.Files.createTempDirectory("auth-perms").toFile()
        val file = File(dir, "auth.json")
        try {
            AuthStorage.save(StoredAuth("at", "rt", 1_900_000_000L, "uid", "a@b.co"), file)
            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            // Tokens at rest must not be group/world readable.
            assertEquals(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                ),
                perms,
                "auth.json must be chmod 600, was $perms"
            )
        } finally {
            file.delete(); dir.delete()
        }
    }
}
