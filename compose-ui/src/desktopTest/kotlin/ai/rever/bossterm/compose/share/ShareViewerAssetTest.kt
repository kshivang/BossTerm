package ai.rever.bossterm.compose.share

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareViewerAssetTest {
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing resource: $path" }
            .bufferedReader()
            .use { it.readText() }

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing resource: $path" }
            .use { it.readBytes() }

    @Test
    fun `image decode failure is rejected without requesting another full payload`() {
        val js = resource("share-viewer/viewer.js")
        val onError = js.substringAfter("image.onerror = function () {").substringBefore("image.src =")

        assertTrue(onError.contains("reason: \"decode\""))
        assertFalse(onError.contains("requestGraphicsResync"))
    }

    @Test
    fun `graphics resync and reconnect retries are bounded`() {
        val js = resource("share-viewer/viewer.js")

        assertTrue(js.contains("g.resyncAttempts >= MAX_GRAPHICS_RESYNCS"))
        assertTrue(js.contains("GRAPHICS_RESYNC_TIMEOUT_MS"))
        assertTrue(js.contains("graphicsAttemptAfterDenied"))
        assertTrue(js.contains("RECONNECT_STABLE_MS"))
        assertTrue(js.contains("viewerLogic.nextReconnectAttempt"))
        assertFalse(
            js.substringAfter("function handleConnectionLost").substringBefore("function deviceName")
                .contains("reconnectAttempt = 0")
        )
    }

    @Test
    fun `pane snapshots configure xterm with the host scrollback cap`() {
        val js = resource("share-viewer/viewer.js")
        val paneSnapshot = js.substringAfter("case \"paneSnapshot\": {")
            .substringBefore("case \"paneOutput\":")

        assertTrue(paneSnapshot.contains("m.scrollbackLines"))
        assertTrue(paneSnapshot.contains("MAX_WEB_VIEWER_SCROLLBACK_LINES"))
        assertTrue(js.contains("DEFAULT_WEB_VIEWER_SCROLLBACK_LINES = 10000"))
        assertTrue(js.contains("MAX_WEB_VIEWER_SCROLLBACK_LINES = 20000"))
        assertFalse(js.contains("WEB_VIEWER_SCROLLBACK_LINES = 5000"))
    }

    @Test
    fun `graphics text reanchors preserve the viewer scroll position`() {
        val js = resource("share-viewer/viewer.js")
        val paneOutput = js.substringAfter("case \"paneOutput\":")
            .substringBefore("case \"paneRepaint\":")
        val paneRepaint = js.substringAfter("case \"paneRepaint\":")
            .substringBefore("case \"paneGraphics\":")

        assertFalse(paneOutput.contains("scrollToLine"))
        assertTrue(paneRepaint.contains("scrollLinesFromBottom"))
        assertTrue(paneRepaint.contains("scrollToLine"))
    }

    @Test
    fun `graphics canvas and transparency stay lazy for text-only panes`() {
        val js = resource("share-viewer/viewer.js")
        val draw = js.substringAfter("function drawPaneGraphics").substringBefore("function requestGraphicsResync")

        assertTrue(draw.indexOf("if (!g.cells.length") < draw.indexOf("ensureGraphicsCanvas(p)"))
        assertFalse(draw.contains("for (var i = 0; i < run.length"))
        assertTrue(draw.contains("Object.keys(g.pending).length"))
        assertTrue(js.contains("allowTransparency: false"))
        assertTrue(js.contains("setPaneGraphicsMode(p, true)"))
    }

    @Test
    fun `viewer content security policy allows only required local capabilities`() {
        val html = resource("share-viewer/index.html")

        assertTrue(html.contains("default-src 'self'"))
        assertTrue(html.contains("base-uri 'none'"))
        assertTrue(html.contains("form-action 'none'"))
        assertTrue(html.contains("img-src 'self' data:"))
        assertTrue(html.contains("connect-src 'self'"))
        assertFalse(html.contains("connect-src 'self' ws: wss:"))
        assertTrue(html.contains("MesloLGSNF-Regular.ttf?v=d97946186e97"))
        assertFalse(resource("share-viewer/viewer.js").contains("document.fonts.load("))
    }

    @Test
    fun `viewer validates host colors and impossible raster retries`() {
        val js = resource("share-viewer/viewer.js")

        assertTrue(js.contains("safeCssColor"))
        assertTrue(js.contains("validatedTheme(m)"))
        assertTrue(js.contains("rejected.requiredBytes"))
        assertTrue(js.contains("graphicsMemoryFits(g, rejected.requiredBytes"))
        assertTrue(js.contains("if (m.resyncRequired)"))
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
    fun `viewer allowlists raster mime types and aligns rows to xterm base`() {
        val js = resource("share-viewer/viewer.js")

        assertTrue(js.contains("ALLOWED_GRAPHICS_MIME_TYPES[wire.mimeType]"))
        assertTrue(js.contains("viewerLogic.captureRowOffset"))
        assertTrue(js.contains("viewerLogic.visibleImageRow"))
        assertTrue(js.contains("g.historyLines = m.historyLines"))
    }
}
