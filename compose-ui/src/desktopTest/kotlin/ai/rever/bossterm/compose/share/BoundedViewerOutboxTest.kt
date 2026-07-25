package ai.rever.bossterm.compose.share

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedViewerOutboxTest {
    @Test
    fun `queue evicts oldest frames at its character bound`() = runBlocking {
        val outbox = BoundedViewerOutbox(capacityChars = 10, capacityFrames = 10)
        assertTrue(outbox.trySend("123456"))
        assertTrue(outbox.trySend("abcdef"))
        outbox.close()

        val drained = mutableListOf<String>()
        outbox.drainTo { drained.add(it) }

        assertEquals(listOf("abcdef"), drained)
    }

    @Test
    fun `single frame larger than the bound is rejected`() {
        val outbox = BoundedViewerOutbox(capacityChars = 5, capacityFrames = 10)

        assertFalse(outbox.trySend("123456"))
    }
}
