package ai.rever.bossterm.compose.share

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareViewerAssetTest {
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing resource: $path" }
            .bufferedReader()
            .use { it.readText() }

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
        assertTrue(js.contains("RECONNECT_STABLE_MS"))
        assertFalse(
            js.substringAfter("function handleConnectionLost").substringBefore("function deviceName")
                .contains("reconnectAttempt = 0")
        )
    }

    @Test
    fun `graphics canvas and transparency stay lazy for text-only panes`() {
        val js = resource("share-viewer/viewer.js")
        val draw = js.substringAfter("function drawPaneGraphics").substringBefore("function requestGraphicsResync")

        assertTrue(draw.indexOf("if (!g.cells.length") < draw.indexOf("ensureGraphicsCanvas(p)"))
        assertTrue(js.contains("allowTransparency: false"))
        assertTrue(js.contains("setPaneGraphicsMode(p, true)"))
    }

    @Test
    fun `viewer content security policy allows only required local capabilities`() {
        val html = resource("share-viewer/index.html")

        assertTrue(html.contains("default-src 'self'"))
        assertTrue(html.contains("img-src 'self' data:"))
        assertTrue(html.contains("connect-src 'self' ws: wss:"))
    }
}
