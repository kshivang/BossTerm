package ai.rever.bossterm.compose.share

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drift guard for `cloudflared-pin.properties` — the single source of truth shared by the runtime
 * ([CloudflaredExposer]) and the app build (bossterm-app bundles the binary from it). If the file
 * goes missing, loses its version, or drops a hash for any platform/arch we install on, that
 * platform's auto-install / bundle silently breaks — so assert the contract here at build time.
 */
class CloudflaredPinTest {

    private val pin: Properties = Properties().apply {
        val res = CloudflaredPinTest::class.java.classLoader
            ?.getResourceAsStream("cloudflared-pin.properties")
        assertNotNull(res, "cloudflared-pin.properties must be on the classpath (compose-ui resources)")
        res.use { load(it) }
    }

    // Every asset CloudflaredExposer can download / the build can bundle. cloudflared ships no
    // arm64 Windows build, so it is intentionally absent.
    private val requiredAssets = listOf(
        "cloudflared-linux-amd64",
        "cloudflared-linux-arm64",
        "cloudflared-linux-arm",
        "cloudflared-linux-386",
        "cloudflared-darwin-amd64.tgz",
        "cloudflared-darwin-arm64.tgz",
        "cloudflared-windows-amd64.exe",
        "cloudflared-windows-386.exe",
    )

    @Test
    fun `pin has a non-blank version`() {
        val version = pin.getProperty("version")
        assertNotNull(version, "missing 'version'")
        assertTrue(version.isNotBlank(), "'version' must not be blank")
    }

    @Test
    fun `pin has a valid sha256 for every supported asset`() {
        val hex = Regex("[0-9a-fA-F]{64}")
        for (asset in requiredAssets) {
            val sha = pin.getProperty("sha256.$asset")
            assertNotNull(sha, "missing sha256.$asset")
            assertTrue(hex.matches(sha.trim()), "sha256.$asset must be 64 hex chars, got '$sha'")
        }
    }
}
