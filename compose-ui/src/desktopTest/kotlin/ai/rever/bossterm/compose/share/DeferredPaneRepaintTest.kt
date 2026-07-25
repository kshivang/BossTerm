package ai.rever.bossterm.compose.share

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredPaneRepaintTest {
    @Test
    fun `throttled repaints coalesce and send the latest screen after the delay`() = runTest {
        val sent = mutableListOf<String>()
        var admission = 0
        val sender = DeferredPaneRepaint(
            scope = this,
            admit = { if (admission++ == 0) 100 else null },
            send = sent::add,
        )

        sender.offer("older screen")
        runCurrent()
        sender.offer("latest screen")
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf("latest screen"), sent)
    }

    @Test
    fun `cancel clears a deferred repaint`() = runTest {
        val sent = mutableListOf<String>()
        val sender = DeferredPaneRepaint(
            scope = this,
            admit = { 100 },
            send = sent::add,
        )

        sender.offer("screen")
        runCurrent()
        sender.cancel()
        advanceTimeBy(200)
        runCurrent()

        assertEquals(emptyList(), sent)
    }
}
