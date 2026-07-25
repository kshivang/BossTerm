package ai.rever.bossterm.compose.share

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewerLogicTest {
    @Test
    fun `node harness exercises reconnect budget and captured row offset`() {
        val logic = checkNotNull(javaClass.classLoader.getResourceAsStream("share-viewer/viewer-logic.js"))
            .use { it.readBytes() }
        val module = Files.createTempFile("bossterm-viewer-logic-", ".js")
        try {
            Files.write(module, logic)
            val harness = """
                const assert = require("assert");
                const logic = require(process.argv[1]);

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
            """.trimIndent()
            val process = ProcessBuilder("node", "-e", harness, module.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
        } finally {
            module.deleteIfExists()
        }
    }
}
