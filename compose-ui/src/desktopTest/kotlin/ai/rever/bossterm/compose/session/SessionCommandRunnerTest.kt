package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.terminal.model.BossTerminal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCommandRunnerTest {
    @Test
    fun `fast OSC completion submits one CR and captures first B through D without next prompt`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.run("echo one\n\n") {
            fixture.mark('B')
            fixture.line("$ echo one")
            fixture.line("one")
            fixture.mark('B') // later statement must not move the start mark
            fixture.line("two")
            fixture.mark('D', "7")
            fixture.line("$ next prompt") // D-time row excludes the later prompt
        }
        assertEquals(listOf("echo one\r"), fixture.writes)
        assertEquals(7, result.exitCode)
        assertEquals("one\ntwo", result.output)
        assertFalse(result.truncated)
        assertNull(result.error)
        assertEquals(0, fixture.listenerCount())
    }

    @Test
    fun `fresh sessions wait for prompt and ignore prior command completion`() = runBlocking {
        val fixture = Fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.run("echo ready", fresh = true) {
                fixture.mark('B')
                fixture.line("ready")
                fixture.mark('D', "0")
            }
        }
        fixture.mark('B')
        fixture.mark('D', "9")
        assertTrue(fixture.writes.isEmpty())
        assertFalse(pending.isCompleted)
        fixture.mark('A')
        val result = withTimeout(1000) { pending.await() }
        assertEquals("ready", result.output)
        assertEquals(0, result.exitCode)
        assertEquals(listOf("echo ready\r"), fixture.writes)
    }

    @Test
    fun `output cap retains part of an oversized line`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.run("long-command", maxOutputChars = 4) {
            fixture.mark('B')
            fixture.line("123456789")
            fixture.mark('D', "0")
        }
        assertEquals("1234", result.output)
        assertTrue(result.truncated)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `timeout captures partial output and removes the command listener`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.run("sleep", timeoutMs = 20) {
            fixture.mark('B')
            fixture.line("partial")
        }
        assertEquals("partial", result.output)
        assertTrue(result.truncated)
        assertNull(result.exitCode)
        assertTrue(result.error.orEmpty().startsWith("Timed out after 20ms"))
        assertEquals(0, fixture.listenerCount())
    }

    @Test
    fun `TUI detection returns control without submitting an interrupt`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.run("vim") { fixture.terminal.useAlternateBuffer(true) }
        assertTrue(result.error.orEmpty().startsWith("TUI detected"))
        assertTrue(fixture.buffer.isUsingAlternateBuffer)
        assertEquals(listOf("vim\r"), fixture.writes)
        assertEquals(0, fixture.listenerCount())
    }

    @Test
    fun `cancelling a command wait removes its listener`() = runBlocking {
        val fixture = Fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { fixture.run("waiting") {} }
        assertEquals(1, fixture.listenerCount())
        pending.cancelAndJoin()
        assertEquals(0, fixture.listenerCount())
        assertEquals(listOf("waiting\r"), fixture.writes)
    }

    @Test
    fun `exiting during fresh prompt wait prevents submission`() = runBlocking {
        val fixture = Fixture()
        val alive = AtomicBoolean(true)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.run("must-not-run", fresh = true, isAlive = alive::get) {}
        }
        alive.set(false)
        val result = withTimeout(1000) { pending.await() }
        assertTrue(fixture.writes.isEmpty())
        assertTrue(result.error.orEmpty().contains("exited"))
        assertEquals(0, fixture.listenerCount())
    }

    private class Fixture {
        private val stack = TerminalSessionStack.create(TerminalSettings.DEFAULT, HeadlessTerminalDisplay())
        val terminal = stack.terminal
        val buffer = stack.textBuffer
        val writes = mutableListOf<String>()
        fun mark(type: Char, vararg arguments: String) = terminal.processShellIntegration(type, arguments.toList())
        fun line(text: String) { terminal.writeCharacters(text); terminal.carriageReturn(); terminal.newLine() }
        fun listenerCount(): Int {
            val field = BossTerminal::class.java.getDeclaredField("myCommandStateListeners")
            field.isAccessible = true
            return (field.get(terminal) as Collection<*>).size
        }
        suspend fun run(
            script: String,
            fresh: Boolean = false,
            timeoutMs: Int = 2000,
            maxOutputChars: Int = 10000,
            isAlive: (() -> Boolean)? = null,
            response: () -> Unit,
        ) = SessionCommandRunner.run(
            terminal = terminal, textBuffer = buffer,
            writeInput = { writes.add(it); response() }, script = script,
            timeoutMs = timeoutMs, freshlyCreated = fresh, shellReadyTimeoutMs = 2000,
            maxOutputChars = maxOutputChars, isAlive = isAlive,
        )
    }
}
