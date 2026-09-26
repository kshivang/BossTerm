package ai.rever.bossterm.compose.terminal

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TerminalCheckpointTest {
    private class Terminal : AutoCloseable {
        val display = ComposeTerminalDisplay()
        val style = StyleState()
        val buffer = TerminalTextBuffer(80, 24, style)
        val terminal = BossTerminal(display, buffer, style)
        val stream = BlockingTerminalDataStream()
        val executor = Executors.newSingleThreadExecutor()
        val drain = executor.submit { drainTerminalEmulator(BossEmulator(stream, terminal), stream, terminal, { true }) }
        fun screen() = buffer.getScreenLines()
        override fun close() {
            stream.close()
            try { drain.get(3, TimeUnit.SECONDS) } finally { executor.shutdownNow(); display.dispose() }
        }
    }

    @Test fun `queued checkpoint follows earlier text and precedes later text`() = runBlocking {
        Terminal().use { t ->
            t.stream.append("first")
            val boundary = async(start = CoroutineStart.UNDISPATCHED) { t.stream.atQueuedCheckpoint { t.screen() } }
            t.stream.append("second")
            val screen = withTimeout(2000) { boundary.await() }
            assertTrue(screen.contains("first"))
            assertFalse(screen.contains("second"))
            assertTrue(t.stream.atQueuedCheckpoint { t.screen() }.contains("firstsecond"))
        }
    }

    @Test fun `queued action waits until fragmented CSI completes`() = runBlocking {
        Terminal().use { t ->
            t.stream.append("\u001b[")
            val boundary = async(start = CoroutineStart.UNDISPATCHED) { t.stream.atQueuedCheckpoint { t.screen() } }
            delay(50)
            assertFalse(boundary.isCompleted)
            t.stream.append("31mred")
            withTimeout(2000) { boundary.await() }
            assertTrue(t.stream.atQueuedCheckpoint { t.screen() }.contains("red"))
        }
    }

    @Test fun `quiet terminal wakes for a snapshot without receiving a terminal character`() = runBlocking {
        Terminal().use { t ->
            val before = t.screen()
            assertEquals(before, withTimeout(1000) { t.stream.atCheckpoint { t.screen() } })
            assertEquals(before, t.screen())
        }
    }

    @Test fun `snapshot plus applied output contains each character once`() = runBlocking {
        Terminal().use { t ->
            t.stream.append("before")
            withTimeout(2000) { while (!t.screen().contains("before")) delay(5) }
            val output = CopyOnWriteArrayList<String>()
            val observedScreens = CopyOnWriteArrayList<String>()
            val subscription = t.stream.observeProcessedOutput({ chunk ->
                output += chunk; observedScreens += t.screen()
            }) { t.screen() }
            assertTrue(subscription.snapshot.contains("before"))
            t.stream.append("after")
            withTimeout(2000) { while (!output.joinToString("").contains("after")) delay(5) }
            assertEquals("after", output.joinToString(""))
            assertTrue(observedScreens.last().contains("beforeafter"), "output must already be applied")
            subscription.close()
        }
    }

    @Test fun `snapshot waits for a fragmented escape instruction to complete`() = runBlocking {
        Terminal().use { t ->
            val output = CopyOnWriteArrayList<String>()
            val subscription = t.stream.observeProcessedOutput({ output += it }) { t.screen() }
            val partial = java.util.concurrent.CountDownLatch(1)
            t.stream.onChunkEnd = { partial.countDown() }
            t.stream.append("\u001b[")
            assertTrue(partial.await(2, TimeUnit.SECONDS))
            // This queued request wakes the parser but must not snapshot inside CSI parsing.
            val snapshot = async { t.stream.atCheckpoint { t.screen() } }
            delay(75)
            assertFalse(snapshot.isCompleted)
            assertTrue(output.isEmpty())
            t.stream.append("31mred")
            withTimeout(2000) { snapshot.await() }
            withTimeout(2000) { while (!output.joinToString("").contains("red")) delay(5) }
            assertEquals("\u001b[31mred", output.joinToString(""))
            assertTrue(t.screen().contains("red"))
            subscription.close()
        }
    }

    @Test fun `a failing observer cannot break local emulation`() = runBlocking {
        Terminal().use { t ->
            val failure = CompletableDeferred<Unit>()
            t.stream.observeProcessedOutput({ error("observer failed") }, { failure.complete(Unit) }) { t.screen() }
            t.stream.append("first")
            withTimeout(2000) { failure.await() }
            t.stream.append("second")
            withTimeout(2000) { while (!t.screen().contains("firstsecond")) delay(5) }
            assertTrue(t.stream.atCheckpoint { t.screen() }.contains("firstsecond"))
        }
    }

    @Test fun `oversized image instructions disconnect only the observer and preserve local and legacy output`() = runBlocking {
        Terminal().use { t ->
            val failure = CompletableDeferred<Exception>()
            val observed = CopyOnWriteArrayList<String>()
            t.stream.observeProcessedOutput({ observed += it }, { failure.complete(it) }) { Unit }
            // APC is consumed as one instruction. A large image payload must not turn the
            // observer's bounded capture into an unbounded allocation or stop the PTY parser.
            val imageInstruction = "\u001b_G" + "x".repeat(1024 * 1024) + "\u001b\\"
            t.stream.append(imageInstruction + "after-image")
            withTimeout(5000) { failure.await() }
            withTimeout(5000) { while (!t.screen().contains("after-image")) delay(5) }
            assertTrue(observed.isEmpty(), "partial image instructions must never be published")
            t.stream.append("-still-running")
            withTimeout(2000) { while (!t.screen().contains("after-image-still-running")) delay(5) }
            assertTrue(t.stream.atCheckpoint { t.screen() }.contains("after-image-still-running"))
        }
    }

    @Test fun `filtered observers stream large image instructions without retaining their raster bytes`() = runBlocking {
        Terminal().use { t ->
            val failures = CopyOnWriteArrayList<Exception>()
            val observed = CopyOnWriteArrayList<String>()
            val observer = t.stream.observeProcessedOutput({ if (it.isNotEmpty()) observed += it }, { failures += it }, graphicsFiltered = true) { Unit }
            t.stream.append("before\u001b_G" + "x".repeat(2 * 1024 * 1024) + "\u001b\\after")
            withTimeout(5000) { while (!observed.joinToString("").contains("beforeafter")) delay(5) }
            assertEquals("beforeafter", observed.joinToString(""))
            assertTrue(failures.isEmpty())
            assertTrue(t.screen().contains("beforeafter"))
            observer.close()
        }
    }
}
