package ai.rever.bossterm.compose.share

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The viewer shell's `connect-src` names the request's own WebSocket origin, because `'self'`
 * covering `ws://` is CSP Level 3 behavior and older mobile WebKit blocked the socket outright.
 * The origin comes from the client-supplied Host header, so it is only ever interpolated when it
 * is a bare authority — otherwise it could close the directive and append another.
 */
class ShareViewerCspTest {

    @Test
    fun `a bare authority becomes the ws and wss sources for its own origin`() {
        assertEquals(
            "ws://192.168.1.20:8770 wss://192.168.1.20:8770",
            webSocketCspSources("192.168.1.20:8770"),
        )
        assertEquals("ws://share.example wss://share.example", webSocketCspSources("share.example"))
        assertEquals("ws://[::1]:8770 wss://[::1]:8770", webSocketCspSources("[::1]:8770"))
        assertEquals(
            "ws://a-tunnel.trycloudflare.com wss://a-tunnel.trycloudflare.com",
            webSocketCspSources(" a-tunnel.trycloudflare.com "),
            "surrounding whitespace is trimmed, not treated as an injection",
        )
    }

    @Test
    fun `anything that could inject a directive contributes no source at all`() {
        val hostile = listOf(
            "evil.example; script-src *",
            "evil.example ws://attacker.example",
            "evil.example/path",
            "evil.example\nscript-src *",
            "'unsafe-inline'",
            "*",
            "",
            null,
        )
        for (header in hostile) {
            assertEquals(
                "",
                webSocketCspSources(header),
                "must not interpolate a non-authority Host header: ${header?.let { "'$it'" } ?: "null"}",
            )
        }
    }
}
