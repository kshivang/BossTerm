package ai.rever.bossterm.compose.share

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Facts about the viewer ASSETS themselves — the served HTML's policy and its font cache tokens.
 *
 * Viewer *behavior* is covered by running the script: [ShareViewerScriptTest] (viewer.js under a
 * fake browser) and [ViewerLogicTest] (viewer-logic.js under Node). Assertions about viewer.js
 * source text belong in neither place — they break on a rename while proving nothing about
 * execution — so they are not added back here.
 */
class ShareViewerAssetTest {
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing resource: $path" }
            .bufferedReader()
            .use { it.readText() }

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing resource: $path" }
            .use { it.readBytes() }

    @Test
    fun `viewer content security policy allows only required local capabilities`() {
        val html = resource("share-viewer/index.html")

        assertTrue(html.contains("default-src 'self'"))
        assertTrue(html.contains("base-uri 'none'"))
        assertTrue(html.contains("form-action 'none'"))
        assertTrue(html.contains("img-src 'self' data:"))
        assertTrue(html.contains("script-src 'self'"))
        // The WebSocket origin is filled in per request (see installShareViewerIndexRoute), because
        // 'self' covering ws:// is CSP3-only. It must stay an origin placeholder, never a scheme
        // wildcard that would permit connecting to any host.
        // Boss Calling's SDP exchange is the ONLY remote origin the viewer may reach, named exactly
        // (no wildcard) and unconditionally — a policy that depended on the key or the toggle would
        // go stale against a page already loaded, producing a button that can never connect.
        assertTrue(html.contains("connect-src 'self' $WS_ORIGIN_PLACEHOLDER https://api.openai.com;"))
        assertFalse(html.contains("ws: wss:"))
        assertTrue(html.contains("MesloLGSNF-Regular.ttf?v=d97946186e97"))
    }

    @Test
    fun `font cache tokens match the bundled bytes`() {
        val html = resource("share-viewer/index.html")
        val fonts = listOf(
            "MesloLGSNF-Regular.ttf",
            "NotoSansSymbols2-Regular.ttf",
        )
        for (font in fonts) {
            val token = Regex("""${Regex.escape(font)}\?v=([0-9a-f]+)""")
                .find(html)?.groupValues?.get(1)
            assertTrue(!token.isNullOrEmpty(), "missing content token for $font")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(resourceBytes("fonts/$font"))
                .joinToString("") { "%02x".format(it) }
            assertEquals(digest.take(token.length), token, "stale immutable cache token for $font")
        }
    }

    @Test
    fun `shared bottom strip wraps mobile controls and centers voice-only state`() {
        val html = resource("share-viewer/index.html")
        val rule = checkNotNull(Regex("""#bottomstrip \{([^}]*)}""").find(html)) {
            "missing #bottomstrip CSS rule"
        }.groupValues[1]

        assertTrue(rule.contains("flex-wrap: wrap"), "voice controls must not push every key off-screen")
        assertTrue(rule.contains("justify-content: center"), "a voice-only pill stays centered")
        assertTrue(
            html.contains("#bottomstrip.has-keys { justify-content: flex-start; }"),
            "keystrokes keep a stable left edge when present",
        )
    }
}
