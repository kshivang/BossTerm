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
    fun `keeps a literal plus in the token literal (not a form-encoded space)`() {
        // A URI query component is RFC-3986, not form-encoded — '+' is literal. A standard-base64
        // token_hash can contain '+'; turning it into a space would silently corrupt the token.
        val l = parseAuthDeepLink("bossterm://auth/verify?token_hash=aB+cd/ef=&type=magiclink")
        assertEquals("aB+cd/ef=", l?.tokenHash)
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

    // ---- lenient paste parsing (manual "paste the sign-in link" fallback) ----

    @Test
    fun `parseAuthInput accepts the clean bossterm deep link`() {
        val l = parseAuthInput("  bossterm://auth/verify?token_hash=abc&type=signup  ")
        assertEquals(AuthDeepLink("abc", "signup"), l)
    }

    @Test
    fun `parseAuthInput extracts token + type from a confirmation URL`() {
        val l = parseAuthInput("https://api.risaboss.com/auth/v1/verify?token=HASH123&type=magiclink&redirect_to=bossterm://auth/verify")
        assertEquals("HASH123", l?.tokenHash)
        assertEquals("magiclink", l?.type)
    }

    @Test
    fun `parseAuthInput handles the percent-encoded redirect URL (decode fallback)`() {
        // The email button href: /redirect?url=<encoded ConfirmationURL with token%3D…%26type%3Dsignup>
        val l = parseAuthInput("https://api.risaboss.com/functions/v1/redirect?url=https%3A%2F%2Fp.co%2Fverify%3Ftoken%3DTOK9%26type%3Dsignup")
        assertEquals("TOK9", l?.tokenHash)
        assertEquals("signup", l?.type)
    }

    @Test
    fun `parseAuthInput preserves a literal plus in a pasted token`() {
        // The footgun: URLDecoder's form semantics turn '+' into a space. A pasted confirmation URL
        // whose token contains a literal '+' (standard base64) must come through intact.
        val l = parseAuthInput("https://api.risaboss.com/auth/v1/verify?token=aB+cd/ef=&type=magiclink")
        assertEquals("aB+cd/ef=", l?.tokenHash)
    }

    @Test
    fun `parseAuthInput prefers token_hash and defaults type to magiclink`() {
        assertEquals("xyz", parseAuthInput("foo?token_hash=xyz&token=other")?.tokenHash)
        assertEquals("magiclink", parseAuthInput("anything?token=t")?.type)
    }

    @Test
    fun `parseAuthInput returns null for junk with no token`() {
        assertNull(parseAuthInput("https://example.com/nothing-here"))
        assertNull(parseAuthInput("   "))
        assertNull(parseAuthInput("token="))
    }

    @Test
    fun `parseAuthInput does not mistake access_token or refresh_token for the hash`() {
        // An implicit-flow callback fragment carries access_token=/refresh_token= before token=.
        // A bare indexOf("token=") would latch onto the FIRST '*token=' (access_token) and POST the
        // wrong value to /verify; the match must be anchored to a real param boundary.
        val l = parseAuthInput("https://app/cb#access_token=AAA&refresh_token=RRR&token=TTT&type=signup")
        assertEquals("TTT", l?.tokenHash)
        assertEquals("signup", l?.type)
    }

    @Test
    fun `parseAuthInput finds token even when only refresh_token precedes it`() {
        // No clean 'token=' decoy other than the substring inside refresh_token=.
        assertEquals("HH", parseAuthInput("x?refresh_token=RR&token=HH")?.tokenHash)
    }

    @Test
    fun `parseAuthInput stops the value at a nested question mark`() {
        // The value scan must treat '?' as a delimiter, else 'token=abc?foo=bar' captures the trailing junk.
        assertEquals("abc", parseAuthInput("https://app/cb?token=abc?foo=bar")?.tokenHash)
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

    // ---- cold-start sign-in toast ----

    @Test
    fun `cold-start sign-in is stashed and drained exactly once`() {
        // A deep link that verifies before any window subscribes must still toast on the first
        // window — but exactly once, so later windows don't re-toast a stale email.
        BossAccountManager.consumePendingSignInToast() // clear any residue from earlier tests
        BossAccountManager.emitSignIn("cold@start.co") // no collectors → stashed as pending
        assertEquals("cold@start.co", BossAccountManager.consumePendingSignInToast())
        assertNull(BossAccountManager.consumePendingSignInToast())
    }

    @Test
    fun `blank cold-start sign-in is not stashed`() {
        BossAccountManager.consumePendingSignInToast()
        BossAccountManager.emitSignIn("")
        assertNull(BossAccountManager.consumePendingSignInToast())
    }

    @Test
    fun `staging temp file is owner-only before any token bytes are written`() {
        // The window the final-file test can't see: File.createTempFile honors umask (commonly
        // 0644), so the temp must be chmod 600 while still EMPTY — else the token is briefly
        // group/world-readable between write and restrict.
        val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        if (!posix) return // Windows ACLs protect the user profile; perms aren't applicable.
        val dir = java.nio.file.Files.createTempDirectory("auth-tmp-perms").toFile()
        val tmp = AuthStorage.newOwnerOnlyTempFile(dir)
        try {
            assertEquals(0L, tmp.length(), "staging temp must be empty when restricted")
            assertEquals(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                ),
                java.nio.file.Files.getPosixFilePermissions(tmp.toPath()),
                "staging temp must be chmod 600 before the token is written"
            )
        } finally {
            tmp.delete(); dir.delete()
        }
    }
}
