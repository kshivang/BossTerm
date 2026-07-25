package ai.rever.bossterm.compose.share

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewerLogicTest {
    @Test
    fun `node harness exercises reconnect budget and captured row offset`() {
        assertTrue(nodeAvailable(), "Node.js is required to execute the web-viewer regression harness")
        val logic = checkNotNull(javaClass.classLoader.getResourceAsStream("share-viewer/viewer-logic.js"))
            .use { it.readBytes().decodeToString() }
        val harness = logic + "\n" + """
                const assert = require("assert");
                const logic = module.exports;

                // Fake-WebSocket close harness: initial connection + exactly three automatic
                // reconnects, then one manual prompt.
                let attempt = 0, connections = 1, prompts = 0;
                function closeSocket() {
                  const decision = logic.nextReconnectAttempt(attempt, 3);
                  if (decision.retry) {
                    attempt = decision.attempt;
                    connections += 1;
                  } else {
                    prompts += 1;
                  }
                }
                closeSocket(); closeSocket(); closeSocket(); closeSocket();
                assert.strictEqual(connections, 4);
                assert.strictEqual(attempt, 3);
                assert.strictEqual(prompts, 1);

                // Capture correction once. Five later scrolls move the image five rows upward;
                // recomputing from live baseY would incorrectly keep this at zero.
                const offset = logic.captureRowOffset(95, 100);
                assert.strictEqual(logic.visibleImageRow(100, offset, 95), 0);
                assert.strictEqual(logic.visibleImageRow(100, offset, 100), -5);

                // A paneRepaint preserves the reader's distance from the bottom and clamps after
                // history trims instead of relying on an obsolete RIS prefix.
                const distance = logic.scrollLinesFromBottom(120, 87);
                assert.strictEqual(distance, 33);
                assert.strictEqual(logic.scrollLineForDistance(140, distance), 107);
                assert.strictEqual(logic.scrollLineForDistance(20, distance), 0);

                // Host-side throttle denials are acknowledgements and never consume the bounded
                // timeout budget, even when repeated.
                let graphicsAttempts = 1;
                graphicsAttempts = logic.graphicsAttemptAfterDenied(graphicsAttempts, true);
                assert.strictEqual(graphicsAttempts, 0);
                assert.strictEqual(logic.graphicsAttemptAfterDenied(graphicsAttempts, true), 0);

                // A raster whose encoded+decoded peak cannot fit the per-pane budget remains
                // rejected even after unrelated images are freed.
                const MiB = 1024 * 1024;
                assert.strictEqual(
                  logic.graphicsMemoryFits(0, 0, 17 * MiB, 0, 16 * MiB, 64 * MiB),
                  false
                );
                assert.strictEqual(
                  logic.graphicsMemoryFits(8 * MiB, 20 * MiB, 6 * MiB, 4 * MiB, 16 * MiB, 64 * MiB),
                  true
                );
            """.trimIndent()
        val script = Files.createTempFile("bossterm-viewer-logic-", ".cjs")
        try {
            Files.writeString(script, harness)
            val process = ProcessBuilder("node", script.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
        } finally {
            script.deleteIfExists()
        }
    }

    private fun nodeAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("node", "--version")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0
    }.getOrDefault(false)
}
