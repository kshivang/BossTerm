package ai.rever.bossterm.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EmbeddableTerminalPersistenceTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `manual state keeps the same interactive pty across observer remount`() {
        if (ShellCustomizationUtils.isWindows()) return

        val state = EmbeddableTerminalState()
        val output = StringBuilder()
        var mounted by mutableStateOf(true)
        rule.setContent {
            if (mounted) {
                Box(Modifier.size(480.dp, 240.dp)) {
                    EmbeddableTerminal(
                        state = state,
                        command = "/bin/cat",
                        onOutput = { chunk -> synchronized(output) { output.append(chunk) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        try {
            rule.waitUntil(10_000) { state.isConnected }
            val originalSession = state.session
            val before = "BOSSTERM_BEFORE_REMOUNT_7321"
            state.write("$before\r")
            rule.waitUntil(10_000) { synchronized(output) { before in output } }

            rule.runOnIdle { mounted = false }
            rule.waitForIdle()
            assertTrue(state.isConnected, "manual terminal state must keep its PTY while no observer is composed")
            assertSame(originalSession, state.session, "unmounting an observer must not replace the setup PTY")

            rule.runOnIdle { mounted = true }
            rule.waitForIdle()
            assertSame(originalSession, state.session, "remounting must render the original setup PTY")
            val after = "BOSSTERM_AFTER_REMOUNT_9476"
            state.write("$after\r")
            rule.waitUntil(10_000) { synchronized(output) { after in output } }
        } finally {
            state.dispose()
        }
    }
}
