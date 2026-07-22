package ai.rever.bossterm.terminal.model.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.concurrent.thread

class ImageDataCacheTest {

    @Test
    fun replacingAnImageIdDoesNotDoubleCountStorage() {
        val cache = ImageDataCache()
        val original = TerminalImage(id = 42, data = ByteArray(10))
        val replacement = TerminalImage(id = 42, data = ByteArray(25))

        cache.storeImage(original)
        cache.storeImage(replacement)

        assertEquals(1, cache.imageCount)
        assertEquals(25, cache.totalMemoryUsed)
    }

    @Test
    fun concurrentReplacementsKeepByteAccountingConsistent() {
        val cache = ImageDataCache()
        val workers = List(8) {
            thread(start = true) {
                repeat(1_000) {
                    cache.storeImage(TerminalImage(id = 42, data = ByteArray(7)))
                }
            }
        }

        workers.forEach(Thread::join)

        assertEquals(1, cache.imageCount)
        assertEquals(7, cache.totalMemoryUsed)
    }
}
