package ai.rever.bossterm.compose.osc

import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Base64
import kotlin.test.*

/**
 * Unit tests for OpenTargetOSCListener and classifyOpenTarget: the routing of
 * CLI-originated open requests (OSC 1341;OpenTarget from the open/xdg-open
 * shim) into HyperlinkInfo dispatched via the link-open handler. Covers the
 * token authentication and the allow-list refusing non-web schemes.
 */
class OpenTargetOSCListenerTest {

    private fun encode(target: String): String =
        Base64.getEncoder().encodeToString(target.toByteArray(Charsets.UTF_8))

    private val token = OpenTargetToken.value

    /** Listener under test with synchronous dispatch and a recorded fallback. */
    private class Harness(handler: ((HyperlinkInfo) -> Boolean)?) {
        var received: HyperlinkInfo? = null
        val fallbacks = mutableListOf<String>()
        val listener = OpenTargetOSCListener(
            handlerProvider = {
                handler?.let { h ->
                    { info: HyperlinkInfo -> received = info; h(info) }
                }
            },
            fallbackOpener = { fallbacks.add(it) },
            dispatchScope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    // ---- classifyOpenTarget: allowed targets ----

    @Test
    fun `http and https URLs classify as HTTP`() {
        val http = classifyOpenTarget("http://example.com/a?b=c")
        assertEquals(HyperlinkType.HTTP, http?.type)
        assertEquals("http://example.com/a?b=c", http?.url)
        assertEquals("http", http?.scheme)

        val https = classifyOpenTarget("https://example.com")
        assertEquals(HyperlinkType.HTTP, https?.type)
        assertEquals("https", https?.scheme)
        assertFalse(https!!.isFile)
        assertFalse(https.isFolder)
    }

    @Test
    fun `existing absolute file path classifies as FILE`() {
        val tmp = File.createTempFile("open-target", ".txt")
        try {
            val info = classifyOpenTarget(tmp.absolutePath)
            assertEquals(HyperlinkType.FILE, info?.type)
            assertEquals(tmp.absolutePath, info?.url)
            assertTrue(info!!.isFile)
            assertFalse(info.isFolder)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `existing file url classifies as FILE and resolves to path`() {
        val tmp = File.createTempFile("open-target", ".txt")
        try {
            val info = classifyOpenTarget(tmp.toURI().toString())
            assertEquals(HyperlinkType.FILE, info?.type)
            assertEquals(tmp.absolutePath, info?.url)
            assertEquals("file", info?.scheme)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `directory classifies as FOLDER`() {
        val dir = System.getProperty("java.io.tmpdir")
        val info = classifyOpenTarget(dir)
        assertEquals(HyperlinkType.FOLDER, info?.type)
        assertTrue(info!!.isFolder)
    }

    // ---- classifyOpenTarget: refused targets (no click gates this path) ----

    @Test
    fun `non-web schemes are refused`() {
        assertNull(classifyOpenTarget("ssh://attacker-host"))
        assertNull(classifyOpenTarget("mailto:someone@example.com"))
        assertNull(classifyOpenTarget("ftp://example.com/file"))
        assertNull(classifyOpenTarget("javascript:alert(1)"))
        assertNull(classifyOpenTarget("myapp://deep-link"))
    }

    @Test
    fun `relative and non-existing paths are refused`() {
        assertNull(classifyOpenTarget("relative/path.txt"))
        assertNull(classifyOpenTarget("."))
        assertNull(classifyOpenTarget("/definitely/not/a/real/path-12345"))
        assertNull(classifyOpenTarget("   "))
    }

    // ---- listener dispatch ----

    @Test
    fun `dispatches decoded target to handler with valid token`() {
        val h = Harness { true }
        h.listener.process(mutableListOf("OpenTarget", token, encode("https://example.com")))
        assertEquals("https://example.com", h.received?.url)
        assertEquals(HyperlinkType.HTTP, h.received?.type)
        assertTrue(h.fallbacks.isEmpty(), "handled request must not hit the fallback")
    }

    @Test
    fun `unhandled request falls back to system opener`() {
        val declining = Harness { false }
        declining.listener.process(mutableListOf("OpenTarget", token, encode("https://example.com")))
        assertEquals(listOf("https://example.com"), declining.fallbacks)

        val unwired = Harness(null)
        unwired.listener.process(mutableListOf("OpenTarget", token, encode("https://example.com")))
        assertEquals(listOf("https://example.com"), unwired.fallbacks)
    }

    @Test
    fun `wrong or missing token is ignored`() {
        val h = Harness { true }
        h.listener.process(mutableListOf("OpenTarget", "not-the-token", encode("https://example.com")))
        h.listener.process(mutableListOf("OpenTarget", encode("https://example.com")))
        h.listener.process(mutableListOf("OpenTarget", null, encode("https://example.com")))
        assertNull(h.received)
        assertTrue(h.fallbacks.isEmpty())
    }

    @Test
    fun `refused targets are not dispatched and do not fall back`() {
        val h = Harness { true }
        h.listener.process(mutableListOf("OpenTarget", token, encode("ssh://attacker-host")))
        h.listener.process(mutableListOf("OpenTarget", token, encode("relative/path.txt")))
        assertNull(h.received)
        assertTrue(h.fallbacks.isEmpty())
    }

    @Test
    fun `ignores other custom commands and malformed payloads`() {
        val h = Harness { true }
        h.listener.process(mutableListOf("BossTermCmd", token, encode("https://example.com")))
        h.listener.process(mutableListOf("OpenTarget"))
        h.listener.process(mutableListOf("OpenTarget", token, "!!! not base64 !!!"))
        h.listener.process(mutableListOf("OpenTarget", token, null))
        assertNull(h.received)
        assertTrue(h.fallbacks.isEmpty())
    }
}
