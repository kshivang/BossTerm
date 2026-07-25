package ai.rever.bossterm.compose.share

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Runs the shipped `share-viewer/viewer.js` under a fake browser (DOM, xterm Terminal, WebSocket,
 * clock and rAF queue) and drives it with the frames a real session sends.
 *
 * This exists because viewer.js is where the web viewer's behavior actually lives, and it cannot be
 * covered by asserting on its source text: a grep proves nothing about execution and cannot catch an
 * undefined identifier on a live code path — the harness caught exactly that (`disarmConnectionHealth`
 * was called on every terminal close and never defined). Scenarios and their assertions live in
 * `share-viewer-harness/viewer-harness.cjs`; this test only supplies Node and the viewer assets.
 */
class ShareViewerScriptTest {

    @Test
    fun `node harness drives the shipped web viewer`() {
        NodeHarness.requireOrSkipNode()
        val dir = Files.createTempDirectory("bossterm-share-viewer-")
        try {
            for (asset in listOf("viewer-logic.js", "viewer.js")) {
                dir.resolve(asset).writeText(NodeHarness.readResource("share-viewer/$asset"))
            }
            val harness = dir.resolve("viewer-harness.cjs")
            harness.writeText(NodeHarness.readResource("share-viewer-harness/viewer-harness.cjs"))
            NodeHarness.run(harness, dir.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
