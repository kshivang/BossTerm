package ai.rever.bossterm.compose.osc

import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkType
import java.io.File
import java.util.Base64
import kotlin.test.*

/**
 * Unit tests for OpenTargetOSCListener and classifyOpenTarget: the routing of
 * CLI-originated open requests (OSC 1341;OpenTarget from the open/xdg-open
 * shim) into HyperlinkInfo dispatched via the link-open handler.
 */
class OpenTargetOSCListenerTest {

    private fun encode(target: String): String =
        Base64.getEncoder().encodeToString(target.toByteArray(Charsets.UTF_8))

    // ---- classifyOpenTarget ----

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
    fun `existing plain file path classifies as FILE with absolute url`() {
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

    @Test
    fun `mailto classifies as EMAIL`() {
        val info = classifyOpenTarget("mailto:someone@example.com")
        assertEquals(HyperlinkType.EMAIL, info?.type)
        assertEquals("mailto", info?.scheme)
    }

    @Test
    fun `non-existing path classifies as CUSTOM`() {
        val info = classifyOpenTarget("/definitely/not/a/real/path-12345")
        assertEquals(HyperlinkType.CUSTOM, info?.type)
    }

    @Test
    fun `blank target returns null`() {
        assertNull(classifyOpenTarget("   "))
    }

    // ---- listener dispatch ----

    @Test
    fun `dispatches decoded target to handler`() {
        var received: HyperlinkInfo? = null
        val listener = OpenTargetOSCListener {
            { info -> received = info; true }
        }
        listener.process(mutableListOf("OpenTarget", encode("https://example.com")))
        assertEquals("https://example.com", received?.url)
        assertEquals(HyperlinkType.HTTP, received?.type)
    }

    @Test
    fun `ignores other custom commands and malformed payloads`() {
        var called = false
        val listener = OpenTargetOSCListener {
            { _ -> called = true; true }
        }
        listener.process(mutableListOf("BossTermCmd", encode("https://example.com")))
        listener.process(mutableListOf("OpenTarget"))
        listener.process(mutableListOf("OpenTarget", "!!! not base64 !!!"))
        listener.process(mutableListOf("OpenTarget", null))
        assertFalse(called)
    }
}
