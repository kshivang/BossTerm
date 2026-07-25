package ai.rever.bossterm.compose.share

import org.junit.Assume.assumeTrue
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared plumbing for the web-viewer regression harnesses, which run the SHIPPED viewer JavaScript
 * under Node rather than asserting on its source text.
 *
 * Node is required in CI (a silently skipped browser suite is worse than no suite) and merely
 * assumed locally, so a dev without Node still gets a green build.
 */
internal object NodeHarness {

    fun requireOrSkipNode() {
        val available = nodeAvailable()
        if (System.getenv("CI").equals("true", ignoreCase = true)) {
            assertTrue(available, "Node.js is required for the web-viewer regression harness in CI")
        } else {
            assumeTrue("Node.js is not available on PATH", available)
        }
    }

    /** Run [script] with [args], failing with the harness's own output when it exits non-zero. */
    fun run(script: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("node", script.toString()) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    fun readResource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing resource: $path" }
            .use { it.readBytes().decodeToString() }

    private fun nodeAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("node", "--version")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0
    }.getOrDefault(false)
}
