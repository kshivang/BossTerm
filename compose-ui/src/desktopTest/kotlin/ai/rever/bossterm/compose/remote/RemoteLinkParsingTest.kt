package ai.rever.bossterm.compose.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Pure-function coverage for the remote-client link parsing: `tokenOf` (share link → access
 * token) and `RemoteSessionConnection.toWsUrl` (share link → WebSocket URL). No socket needed.
 */
class RemoteLinkParsingTest {

    // toWsUrl is an instance method but pure over its argument; a throwaway connection is fine
    // (the constructor's HttpClient is harmless and never dials in these tests).
    private fun conn() = RemoteSessionConnection(
        link = "https://h/?t=seed",
        clientId = "c",
        deviceName = "d",
        keyProvider = { null },
        onGrant = { _, _ -> },
        onServerMessage = {},
    )

    @Test
    fun `tokenOf extracts and url-decodes the t param`() {
        assertEquals("abc123", tokenOf("https://host.trycloudflare.com/?t=abc123"))
        assertEquals("a b/c", tokenOf("https://host/?t=a%20b%2Fc")) // %20 → space, %2F → /
        assertEquals("tok", tokenOf("https://host/?foo=1&t=tok&bar=2")) // not the first param
    }

    @Test
    fun `tokenOf is null when absent or malformed`() {
        assertNull(tokenOf("https://host/"))        // no query
        assertNull(tokenOf("https://host/?x=1"))     // no t=
        assertNull(tokenOf("https://host/?t="))      // blank token
        assertNull(tokenOf("::::not a uri::::"))     // unparseable
    }

    @Test
    fun `toWsUrl maps https to wss and http to ws`() {
        val c = conn()
        assertEquals("wss://host/ws/tok", c.toWsUrl("https://host/?t=tok"))
        assertEquals("ws://host/ws/tok", c.toWsUrl("http://host/?t=tok"))
    }

    @Test
    fun `toWsUrl preserves an explicit port and decodes the token`() {
        val c = conn()
        assertEquals("ws://10.0.0.2:8080/ws/tok", c.toWsUrl("http://10.0.0.2:8080/?t=tok"))
        assertEquals("wss://host/ws/a/b", c.toWsUrl("https://host/?t=a%2Fb"))
    }

    @Test
    fun `toWsUrl rejects a hostless link instead of producing wss to null`() {
        assertFailsWith<IllegalArgumentException> { conn().toWsUrl("file:///foo?t=tok") }
    }

    @Test
    fun `secretOf extracts the e2e key from the fragment`() {
        val secret = ai.rever.bossterm.compose.share.SessionCrypto.newSessionSecret()
        val k = ai.rever.bossterm.compose.share.SessionCrypto.encodeSecretB64Url(secret)
        val c = conn()
        assertTrue(secret.contentEquals(c.secretOf("https://h/?t=tok#k=$k")))        // sole fragment param
        assertTrue(secret.contentEquals(c.secretOf("https://h/?t=tok#x=1&k=$k")))    // mixed with others
    }

    @Test
    fun `secretOf is null when the fragment is absent or has no k`() {
        val c = conn()
        assertNull(c.secretOf("https://h/?t=tok"))      // no fragment (plaintext / LAN link)
        assertNull(c.secretOf("https://h/?t=tok#x=1"))   // fragment without k=
        assertNull(c.secretOf("https://h/?t=tok#k="))    // blank key
    }
}
