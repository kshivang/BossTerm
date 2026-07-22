package ai.rever.bossterm.terminal.model.image

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
