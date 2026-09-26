package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.NodeHarness
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test

class RelayBrowserTest {
    @Test fun `shipped browser relay verifies encryption ordering replay and visibility`() {
        NodeHarness.requireOrSkipNode()
        val dir = Files.createTempDirectory("bossterm-relay-browser-")
        try {
            val assets = Files.createDirectories(dir.resolve("desktopMain/resources/share-viewer"))
            val tests = Files.createDirectories(dir.resolve("desktopTest/resources/relay"))
            for (asset in listOf("relay-crypto.mjs", "relay-transport.mjs", "viewer.js")) {
                assets.resolve(asset).writeText(NodeHarness.readResource("share-viewer/$asset"))
            }
            for (asset in listOf("crypto.test.mjs", "transport.test.mjs", "preferences.test.mjs", "output-vector.json")) {
                tests.resolve(asset).writeText(NodeHarness.readResource("relay/$asset"))
            }
            NodeHarness.run(tests.resolve("crypto.test.mjs"))
            NodeHarness.run(tests.resolve("transport.test.mjs"))
            NodeHarness.run(tests.resolve("preferences.test.mjs"))
        } finally { dir.toFile().deleteRecursively() }
    }
}
