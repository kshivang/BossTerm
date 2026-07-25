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
        // Boss Calling's SDP exchange is the ONLY remote origin the viewer may reach, it is named
        // exactly (no wildcard), and it is a per-request placeholder so a host with no key serves a
        // policy with no remote origin at all.
        assertTrue(html.contains("connect-src 'self' $WS_ORIGIN_PLACEHOLDER $VOICE_ORIGIN_PLACEHOLDER;"))
        assertFalse(html.contains("ws: wss:"))
        // Scoped to the policy itself: the surrounding HTML comment names the origin while
        // explaining the placeholder, which is not the same as baking it into the directive.
        val csp = Regex("""content="(default-src[^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        assertTrue(csp.isNotEmpty(), "could not locate the CSP content attribute")
        assertFalse(csp.contains("api.openai.com"), "the origin is templated, not baked into the policy")
        assertEquals("https://api.openai.com", voiceCspSource(keyConfigured = true))
        assertEquals("", voiceCspSource(keyConfigured = false), "no key → no remote origin")
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
}
