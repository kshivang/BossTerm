package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.session.ParsedOutputPublisher

import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.PerformanceMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParsedOutputPublisherTest {
    @Test
    fun `queued reader output is published after the atomic snapshot exactly once`() {
        val stream = BlockingTerminalDataStream(PerformanceMode.LATENCY)
        val publisher = ParsedOutputPublisher(stream)
        val frames = mutableListOf<String>()
        var screen = ""
        stream.append("before")
        publisher.process(stream.char) { first -> screen += first + publisher.readNonControlCharacters(80).orEmpty() }
        stream.append("after") // queued, not yet applied to the model
        publisher.attach({ frames.add("output:$it") }) { frames.add("snapshot:$screen") }
        publisher.process(stream.char) { first -> screen += first + publisher.readNonControlCharacters(80).orEmpty() }
        assertEquals(listOf("snapshot:before", "output:after"), frames)
        assertEquals("beforeafter", screen)
        stream.close()
    }

    @Test
    fun `lookahead is excluded from the baseline publication until processed`() {
        val stream = BlockingTerminalDataStream(PerformanceMode.LATENCY)
        val publisher = ParsedOutputPublisher(stream)
        val frames = mutableListOf<String>()
        publisher.add { frames.add(it) }
        stream.append("ab")
        publisher.process(stream.char) {
            val next = publisher.char
            publisher.pushChar(next)
        }
        publisher.process(stream.char) {}
        assertEquals(listOf("a", "b"), frames)
        stream.close()
    }

    @Test
    fun `snapshot subscription waits for a complete emulator operation`() {
        val stream = BlockingTerminalDataStream(PerformanceMode.LATENCY)
        val publisher = ParsedOutputPublisher(stream)
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val captured = CountDownLatch(1)
        var screen = "initial"
        stream.append("x")
        val emulator = thread {
            publisher.process(stream.char) {
                entered.countDown()
                assertTrue(complete.await(5, TimeUnit.SECONDS))
                screen = "complete"
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val attach = thread {
            publisher.attach({}) {
                assertEquals("complete", screen)
                captured.countDown()
            }
        }
        assertTrue(!captured.await(50, TimeUnit.MILLISECONDS), "a half-processed operation must not be snapshotted")
        complete.countDown()
        emulator.join(5000)
        attach.join(5000)
        assertTrue(captured.await(1, TimeUnit.SECONDS))
        stream.close()
    }
}
