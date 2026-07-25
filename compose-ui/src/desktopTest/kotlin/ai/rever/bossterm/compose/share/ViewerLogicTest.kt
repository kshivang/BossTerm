package ai.rever.bossterm.compose.share

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewerLogicTest {
    @Test
    fun `node harness exercises reconnect budget and captured row offset`() {
        val hasNode = nodeAvailable()
        if (System.getenv("CI").equals("true", ignoreCase = true)) {
            assertTrue(hasNode, "Node.js is required for the web-viewer regression harness in CI")
        } else {
            assumeTrue("Node.js is not available on PATH", hasNode)
        }
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
                assert.strictEqual(logic.isTerminalWebSocketClose(1000), true);
                assert.strictEqual(logic.isTerminalWebSocketClose(1003), true);
                assert.strictEqual(logic.isTerminalWebSocketClose(1008), true);
                assert.strictEqual(logic.isTerminalWebSocketClose(1006), false);

                // Capture correction once. Five later scrolls move the image five rows upward;
                // recomputing from live baseY would incorrectly keep this at zero.
                const offset = logic.captureRowOffset(95, 100);
                assert.strictEqual(logic.visibleImageRow(100, offset, 95), 0);
                assert.strictEqual(logic.visibleImageRow(100, offset, 100), -5);
                assert.deepStrictEqual(logic.visibleHostRowRange(100, offset, 24), {
                  first: 105,
                  last: 128
                });

                // A paneRepaint preserves the reader's distance from the bottom and clamps after
                // history trims instead of relying on an obsolete RIS prefix.
                const distance = logic.scrollLinesFromBottom(120, 87);
                assert.strictEqual(distance, 33);
                assert.strictEqual(logic.scrollLineForDistance(140, distance), 107);
                assert.strictEqual(logic.scrollLineForDistance(20, distance), 0);

                // Queue the probe and repaint synchronously. A later output frame must stay behind
                // both, while the probe still measures after all earlier writes have drained.
                const writes = [];
                let activeBuffer = { baseY: 120, viewportY: 90 };
                let restoredLine = null, repaintCompleted = false;
                function queuedWrite(data, callback) { writes.push({ data, callback }); }
                logic.queuePaneRepaint(
                  queuedWrite,
                  () => activeBuffer,
                  line => { restoredLine = line; },
                  "repaint",
                  () => { repaintCompleted = true; }
                );
                queuedWrite("later-output", () => {});
                assert.deepStrictEqual(writes.map(entry => entry.data), ["", "repaint", "later-output"]);
                writes.shift().callback();
                activeBuffer = { baseY: 125, viewportY: 95 };
                writes.shift().callback();
                assert.strictEqual(restoredLine, 95);
                assert.strictEqual(repaintCompleted, true);

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
                assert.strictEqual(
                  logic.graphicsMemoryFits(0, 0, 72 * MiB, 0, 96 * MiB, 192 * MiB),
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
