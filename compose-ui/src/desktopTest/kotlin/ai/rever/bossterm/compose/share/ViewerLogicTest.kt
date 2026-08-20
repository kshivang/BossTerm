package ai.rever.bossterm.compose.share

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test

class ViewerLogicTest {
    @Test
    fun `node harness exercises reconnect budget and captured row offset`() {
        NodeHarness.requireOrSkipNode()
        val logic = NodeHarness.readResource("share-viewer/viewer-logic.js")
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

                // The contrast floor crosses the wire and xterm.js throws on some invalid
                // option values, so anything unusable must resolve to "off" (1) rather than
                // reaching the terminal. Mirrors xterm.js's own clamp: [1, 21], one decimal.
                assert.strictEqual(logic.validMinimumContrastRatio(4.5), 4.5);
                assert.strictEqual(logic.validMinimumContrastRatio(1), 1);
                assert.strictEqual(logic.validMinimumContrastRatio(999), 21);
                assert.strictEqual(logic.validMinimumContrastRatio(-5), 1);
                assert.strictEqual(logic.validMinimumContrastRatio(0), 1);
                assert.strictEqual(logic.validMinimumContrastRatio(4.567), 4.6);
                assert.strictEqual(logic.validMinimumContrastRatio("nope"), 1);
                assert.strictEqual(logic.validMinimumContrastRatio(undefined), 1);
                assert.strictEqual(logic.validMinimumContrastRatio(null), 1);
                assert.strictEqual(logic.validMinimumContrastRatio(NaN), 1);
                assert.strictEqual(logic.validMinimumContrastRatio(Infinity), 1);
                assert.strictEqual(logic.validMinimumContrastRatio({}), 1);
            """.trimIndent()
        val script = Files.createTempFile("bossterm-viewer-logic-", ".cjs")
        try {
            Files.writeString(script, harness)
            NodeHarness.run(script)
        } finally {
            script.deleteIfExists()
        }
    }
}
